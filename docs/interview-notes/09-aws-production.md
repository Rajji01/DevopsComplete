# 09 — ShopFlow on AWS (EKS, RDS, MSK, ElastiCache, Cognito, Terraform)

> Source of truth: `infra/terraform/aws/*.tf`, `shopflow/k8s/overlays/prod/`, `.github/workflows/shopflow.yml`, `shopflow/argocd/application-prod.yaml`.
> **Honesty box (say this in the interview if asked "is it live?"):** the Terraform was written and `terraform fmt`-checked; CI runs `terraform init -backend=false && terraform validate`. In the authoring environment the provider registry was blocked, so `init`/`validate` could not be run locally, and **nothing has been applied to a real AWS account**. The same goes for the client side: the MSK IAM configuration now exists in code (`application-aws.yml` + `aws-msk-iam-auth` in order- and notification-service, `SPRING_PROFILES_ACTIVE=aws` in the prod overlay) but has **never been tested against a real MSK cluster** — only the dev PLAINTEXT path is exercised. The design is production-shaped and every claim below maps to a file, but the correct phrasing is *"this is how I provisioned it in Terraform and how I would run it"*, not *"this handled X requests in prod"*.

---

## 0. Quick facts (memorise)

| Item | Value (file) |
|---|---|
| Region | `ap-south-1` (Mumbai), 3 AZs (`variables.tf`, `vpc.tf`) |
| VPC | `10.0.0.0/16`; 3 private `/20`, 3 public `/24`, 3 database `/24`; NAT per AZ (`vpc.tf`) |
| Kubernetes | EKS **1.33**, managed node group `general`: t3.large, ON_DEMAND, min 2 / desired 3 / max 6 (`eks.tf`) |
| Add-ons | coredns, kube-proxy, vpc-cni, eks-pod-identity-agent, aws-ebs-csi-driver, metrics-server |
| Pod → AWS identity | IRSA (OIDC) roles: external-secrets, kafka-clients, aws-load-balancer-controller (`irsa.tf`) |
| Registry | 3 ECR repos, IMMUTABLE tags, scan on push, keep last 30 (`ecr.tf`). CI pushes to GHCR **and mirrors the same SHA-tagged image to ECR via OIDC** when the repo variable `AWS_ROLE_ARN` is set (`aws-actions/configure-aws-credentials@v6` + `amazon-ecr-login@v2`); the prod overlay still names the GHCR images |
| Database | RDS PostgreSQL 16, `db.t4g.medium` (Graviton), Multi-AZ, gp3 50→200 GB autoscaling, encrypted, 7-day backups, Performance Insights, Enhanced Monitoring 60s (`rds.tf`) |
| DB params | `max_connections=300` (pending-reboot), `log_min_duration_statement=500` ms |
| Cache | ElastiCache Redis 7.1 replication group, 2 nodes (primary + replica), Multi-AZ auto-failover, TLS + at-rest encryption (`elasticache.tf`) |
| Messaging | MSK Kafka 3.6.0, 3 brokers `kafka.t3.small`, 100 GB each, IAM auth (SASL/IAM, port 9098), TLS, `default.replication.factor=3`, `min.insync.replicas=2`, `num.partitions=6`, `auto.create.topics.enable=false`, 7-day retention, JMX + node exporter (`msk.tf`) |
| Identity | Cognito user pool (email login, MFA optional, 12-char passwords), public app client with PKCE, groups `customer` / `support`, access/ID token 15 min, refresh 30 days (`cognito.tf`) |
| Secrets | Secrets Manager `shopflow/prod/db` (JSON: master, orders, inventory, notifications, host, port) → External Secrets Operator → K8s Secret `shopflow-db`, refresh 1h |
| CI → AWS | GitHub OIDC provider + role `shopflow-github-actions`, trust pinned to `repo:Rajji01/DevopsComplete:ref:refs/heads/main`, ECR push only (`ci-oidc.tf`) |
| State | S3 backend, `use_lockfile = true` (S3 native locking, TF ≥ 1.11), encrypted, versioned bucket (`versions.tf`) |
| Providers | `hashicorp/aws ~> 6.0`, `random ~> 3.6`; modules `terraform-aws-modules/vpc ~> 6.0`, `eks ~> 21.0`, `iam ~> 5.0` |
| Ingress | AWS Load Balancer Controller, `ingressClassName: alb`, internet-facing, `target-type: ip`, HTTPS 443 with ACM cert, health check `/actuator/health/readiness` (prod overlay); NetworkPolicy ingress from the VPC CIDR `10.0.0.0/16` on 8081/8082 so the ALB ENIs can reach the pods |
| DNS / TLS | `dns.tf`: `aws_acm_certificate` (DNS validation) + `aws_route53_record` validation records + `aws_acm_certificate_validation`, created only when `hosted_zone_id` is set; output `acm_certificate_arn` goes into the Ingress annotation (overlay placeholder is `REPLACE-ME`) |
| Pod identity in the overlay | `k8s/overlays/prod/service-accounts.yaml`: ServiceAccounts `order-service` / `notification-service` annotated with `eks.amazonaws.com/role-arn` (kafka-clients role) + `serviceAccountName` patches on the two Deployments; `external-secrets-sa` in `external-secret.yaml` |
| Cost | ~**$400–600/month** at these defaults (README) |

---

## 1. Architecture walkthrough (say it in 90 seconds)

```
                        users / SPA (PKCE login at Cognito hosted UI)
                                   │ HTTPS  shop.example.com
                        Route 53 ──┼── ACM certificate
                                   ▼
┌─ VPC 10.0.0.0/16 (ap-south-1a/b/c) ───────────────────────────────────────────────────────────┐
│ PUBLIC  10.0.100-102.0/24   [ALB]  [NAT-a] [NAT-b] [NAT-c]      IGW                           │
│ ────────────────────────────┼──────────────────────────────────────────────────────────────── │
│ PRIVATE 10.0.0/16/32.0/20   ▼  EKS managed nodes (t3.large x3)                                │
│    ┌──────────────┐  REST  ┌──────────────────┐        ┌──────────────────────┐              │
│    │ order-service│──────▶ │ inventory-service│        │ notification-service │              │
│    │ :8081        │        │ :8082            │        │ :8083                │              │
│    └──┬─────┬─────┘        └──────┬─────┬─────┘        └─────┬──────┬─────────┘              │
│       │5432 │9098 (IAM)           │5432 │6379 TLS            │5432  │9098                    │
│       │     └────────────┐        │     │                    │      │                        │
│       │   [MSK b-1,b-2,b-3 in private subnets]◀──────────────┼──────┘                        │
│       │                           │     └─▶ [ElastiCache primary + replica]                   │
│ ──────┼───────────────────────────┼────────────────────────── ┼ ───────────────────────────── │
│ DATABASE 10.0.200-202.0/24        ▼                           ▼                               │
│       └─────────────▶ [RDS PostgreSQL 16, Multi-AZ: primary ◀─sync─▶ standby]                 │
└──────────────────────────────────────────────────────────────────────────────────────────────┘
 outside the VPC (regional services, reached via NAT or endpoints):
 Cognito (JWT issuer, JWKS)   Secrets Manager (db passwords)   ECR (images)   STS (IRSA)   CloudWatch
```

Narrative: *"Only the three stateless Spring Boot services run on EKS; everything stateful is a managed service. The ALB terminates TLS with an ACM cert and routes `/api/v1/orders` to order-service and `/api/v1/products` to inventory-service. order-service validates Cognito JWTs, calls inventory over HTTP, writes orders plus an outbox row to RDS, and a relay publishes to MSK with IAM auth. notification-service consumes from MSK. inventory-service caches in ElastiCache. No passwords live in Git: External Secrets pulls them from Secrets Manager using an IRSA role. GitHub Actions gets AWS credentials via OIDC, no access keys."*

### 1.1 File → resource map

| File | What it creates | One sentence of "why" |
|---|---|---|
| `versions.tf` | TF ≥ 1.11, AWS provider 6.x, S3 backend with lockfile, `default_tags` | Tags on every resource → cost allocation by `Project`/`Environment` |
| `variables.tf` | region, env, sizes, `db_multi_az`, `github_repository`, `domain_name` | One place to turn prod → cheap dev (`db_multi_az=false`, smaller types) |
| `vpc.tf` | VPC module: 9 subnets, NAT/AZ, DB subnet group, LB-controller subnet tags | Subnet tags `kubernetes.io/role/elb` / `internal-elb` are how the controller finds subnets |
| `eks.tf` | EKS module v21: cluster, IRSA OIDC provider, add-ons, managed node group | `enable_cluster_creator_admin_permissions` = the applier becomes admin via access entries |
| `ecr.tf` | 3 repos, immutable, scan-on-push, lifecycle keep 30 | What the overlay names is exactly what runs; rollbacks to any of the last 30 SHAs |
| `rds.tf` | SG (5432 from node SG only), parameter group, instance, Enhanced Monitoring role, Secrets Manager secret | DB is reachable only from the cluster's node security group |
| `elasticache.tf` | SG, subnet group, replication group | Cache loss = cache misses, so small + 1 replica is enough |
| `msk.tf` | SG (9092–9098 from nodes), MSK configuration, log group, cluster | RF 3 / ISR 2 survives one broker or AZ loss without losing acknowledged writes |
| `cognito.tf` | user pool, app client, hosted domain, groups | Replaces Keycloak; `JwtRolesConverter` already reads `cognito:groups` |
| `irsa.tf` | 3 IRSA roles + 2 policies | Pods get scoped IAM roles via ServiceAccount, no keys |
| `ci-oidc.tf` | GitHub OIDC provider, role, ECR push policy | CI pushes images without a stored secret |
| `dns.tf` | ACM certificate for `domain_name`, Route 53 DNS-validation records, `aws_acm_certificate_validation` (all `count`-gated on `hosted_zone_id != ""`) | Validation waits until ACM has issued the cert, so the output ARN is usable; the ALB's own DNS name only exists after the Ingress, hence external-dns or a manual alias record |
| `outputs.tf` | endpoints, ARNs, `configure_kubectl` | Paste into `k8s/overlays/prod/kustomization.yaml` + `external-secret.yaml` |

> Tip: Interviewer "architecture batao" bole toh pehle diagram ke 3 layers (public / private / database) bolo, phir "stateless on EKS, stateful managed" line. Yeh ek line hi 80% impression banati hai.

---

## 2. VPC design

### 2.1 Three subnet tiers

```hcl
private_subnets  = [for i in range(3) : cidrsubnet(var.vpc_cidr, 4, i)]       # /20 = 4096 IPs each
public_subnets   = [for i in range(3) : cidrsubnet(var.vpc_cidr, 8, 100 + i)] # /24 = 256 IPs
database_subnets = [for i in range(3) : cidrsubnet(var.vpc_cidr, 8, 200 + i)] # /24
```

| Tier | Holds | Route table default route | Why large/small |
|---|---|---|---|
| Public | ALB, NAT gateways | `0.0.0.0/0 → IGW` | Few IPs needed |
| Private | EKS nodes **and pods** (VPC CNI gives every pod a VPC IP), MSK brokers, ElastiCache | `0.0.0.0/0 → NAT in same AZ` | `/20` because VPC CNI consumes IPs per pod; IP exhaustion is the #1 EKS networking outage |
| Database | RDS primary + standby | no internet route at all | Smallest blast radius: nothing in here initiates outbound traffic |

Why are RDS / MSK in private subnets? Defence in depth: even if a security group is misconfigured, there is no route from the internet. `publicly_accessible = false` on RDS is the second lock. The DB tier has no NAT route, so a compromised DB engine (hypothetically) cannot exfiltrate.

### 2.2 NAT gateway: one per AZ vs single

```hcl
single_nat_gateway     = false
one_nat_gateway_per_az = true
```

| | Single NAT | NAT per AZ (ShopFlow prod) |
|---|---|---|
| Cost | 1 × ~$32/mo + data | 3 × ~$32/mo + data (~$100/mo fixed) |
| AZ failure | NAT's AZ dies → **all** private subnets lose egress (image pulls, Cognito JWKS, STS, Secrets Manager) | Only that AZ loses egress; others keep working |
| Cross-AZ data charge | Yes (nodes in b/c route through NAT in a) | No |
| Verdict | dev/learning (`single_nat_gateway=true`) | prod |

What actually goes through NAT in ShopFlow? ECR image pulls, STS (`AssumeRoleWithWebIdentity` for IRSA), Secrets Manager, Cognito JWKS fetch, CloudWatch. **Reduce the bill with VPC interface endpoints** (ECR api + dkr, STS, Secrets Manager, CloudWatch Logs) and the S3 gateway endpoint (free; ECR layers are stored in S3). That is the first optimisation I'd add.

### 2.3 Security Group vs NACL (as used here)

| | Security Group | NACL |
|---|---|---|
| Attaches to | ENI (instance, pod with SG-for-pods, RDS, ALB) | Subnet |
| Stateful? | **Yes** – return traffic auto-allowed | **No** – need rules both ways incl. ephemeral ports 1024–65535 |
| Rules | Allow only | Allow + Deny, evaluated by number |
| ShopFlow | `aws_security_group.rds` ingress 5432 **from `module.eks.node_security_group_id`** (SG reference, not CIDR); same pattern for Redis 6379 and MSK 9092–9098 | Default NACL (allow all); I'd add a deny for a known bad CIDR only in an incident |

SG referencing another SG is the idiom: "anything that is an EKS node may talk to Postgres". With `target-type: ip` and VPC CNI, pods share the node's SG, so this works for pods too. (Finer: Security Groups for Pods via `ENIConfig`/`SecurityGroupPolicy`, out of scope.)

NetworkPolicy still applies **inside** the cluster: the prod overlay adds egress to `10.0.0.0/16` on 5432/9092/9098/6379 and to `0.0.0.0/0:443` (Cognito JWKS), and ingress from `10.0.0.0/16` on 8081/8082 (ALB ENIs). Two layers: NetworkPolicy (pod → where) + SG (what may reach the managed service).

---

## 3. EKS

### 3.1 Control plane vs data plane

```
AWS-managed (you pay $73/mo, never see the VMs)          Your account / VPC
┌─────────────────────────────────────┐                 ┌─────────────────────────────┐
│ kube-apiserver (HA, multi-AZ)       │ ◀─ ENIs in ──▶  │ worker nodes (EC2, t3.large) │
│ etcd (encrypted, backed up by AWS)  │   your subnets  │   kubelet, kube-proxy        │
│ controller-manager, scheduler       │                 │   aws-node (VPC CNI)         │
│ OIDC issuer for IRSA                │                 │   coredns, metrics-server... │
└─────────────────────────────────────┘                 └─────────────────────────────┘
```

- `endpoint_public_access = true` + `endpoint_private_access = true`: kubectl from CI/laptops over the internet (restrict with `endpoint_public_access_cidrs`), nodes talk to the API privately. A stricter org: private only + bastion/VPN/SSM.
- Control-plane logs (api, audit, authenticator) → CloudWatch; enable audit logs in a regulated environment.

### 3.2 Compute options

| Option | How | Pros | Cons | ShopFlow |
|---|---|---|---|---|
| **Managed node group** | ASG managed by EKS, AMI updates via rolling replace | Simple, Spot support, daemonsets OK, cheap per vCPU | You size instance types, bin-packing waste | **Yes** (`general`, t3.large, ON_DEMAND) |
| Fargate | One micro-VM per pod | No nodes to patch, per-pod isolation | No DaemonSets, slower start (~60s), no EBS PVs, pricier at steady load, no privileged | No (the JVM start + DaemonSet-based monitoring argue against it) |
| Karpenter | Controller that launches right-sized EC2 directly from pending pods | Fast scale-up (~40s), consolidation, mixes Spot/OD/Graviton | Another controller to run | Not yet; subnets and cluster already carry the `karpenter.sh/discovery = shopflow` tag so it can be added |
| Auto Mode | AWS runs Karpenter + core add-ons for you | Least ops | Extra per-vCPU fee | Alternative to the above for small teams |

Why `t3.large` × 3? 2 vCPU / 8 GB each. ShopFlow pods request 250m CPU / 384Mi; HPA max 10 × 3 services = 30 pods ≈ 7.5 vCPU / 11 GB → fits in 6 nodes (max_size). Graviton (`m7g.large`) would be ~20% cheaper; the image is multi-arch-capable (Temurin alpine has arm64) but CI builds amd64 only today, so it is a two-line change (`platforms: linux/amd64,linux/arm64` in buildx) before switching.

### 3.3 Add-ons (`addons = {...}` in `eks.tf`)

| Add-on | Why ShopFlow needs it |
|---|---|
| `vpc-cni` | Pod IPs from the VPC; enables SG referencing and `target-type: ip` ALBs. Watch IP exhaustion (prefix delegation fixes it) |
| `coredns` | Service DNS (`inventory-service:8082`). `default-deny-egress-allow-dns` NetworkPolicy allows 53 to `kube-dns` |
| `kube-proxy` | ClusterIP → pod IP via iptables (see [12 — C2](12-hard-interview-questions.md)) |
| `eks-pod-identity-agent` | Enables **EKS Pod Identity** (the newer alternative to IRSA) |
| `aws-ebs-csi-driver` | PersistentVolumes on EBS — only needed for in-cluster stateful things (Prometheus, Loki); the app DBs are RDS |
| `metrics-server` | HPAs (`averageUtilization: 70` of CPU request) need it |
| Installed via Helm, not add-ons | AWS Load Balancer Controller, External Secrets Operator, Argo CD, kube-prometheus-stack |

### 3.4 IRSA vs EKS Pod Identity (both are in the Terraform)

```
IRSA                                          Pod Identity
pod ─ projected SA token (aud sts) ─▶ STS     pod ─ SA token ─▶ eks-pod-identity-agent (node, link-local 169.254.170.23)
     AssumeRoleWithWebIdentity                      ─▶ EKS Auth API ─▶ creds
trust policy: cluster OIDC provider +         association: aws_eks_pod_identity_association
  Condition sub = system:serviceaccount:ns:sa   (cluster, namespace, SA, role) — no OIDC in trust policy
role is per-cluster (OIDC URL in trust)       role reusable across clusters; trust = pods.eks.amazonaws.com
needs SA annotation eks.amazonaws.com/role-arn no annotation; agent DaemonSet needed
works with Fargate, self-managed, outside EKS  EKS EC2 nodes only (no Fargate at launch)
```

ShopFlow uses **IRSA** (`enable_irsa = true`, `irsa.tf` modules) because External Secrets and the LB controller docs assume it; the `eks-pod-identity-agent` add-on is installed so the Kafka-clients role could be moved to Pod Identity later. How a pod gets creds (full answer in Q&A): the EKS pod-identity webhook sees the annotated SA, injects env vars `AWS_ROLE_ARN` + `AWS_WEB_IDENTITY_TOKEN_FILE` and a projected token volume; the AWS SDK's default credential chain exchanges the token at STS.

**How the overlay wires it:** the base Deployments set `automountServiceAccountToken: false` and no `serviceAccountName`. The prod overlay adds `service-accounts.yaml` (ServiceAccounts `order-service` and `notification-service`, annotated `eks.amazonaws.com/role-arn: .../shopflow-kafka-clients`) and patches `serviceAccountName` onto the two Deployments; the role's trust policy lists exactly `shopflow:order-service` and `shopflow:notification-service` (`irsa.tf`). IRSA still works with `automountServiceAccountToken: false` because the webhook mounts its own projected-token volume. The same patch also sets `SPRING_PROFILES_ACTIVE=aws`, which activates the services' MSK IAM client config (see §7.2).

### 3.5 Access: who can `kubectl`?

- EKS module v21 uses **access entries** (API), not the legacy `aws-auth` ConfigMap. `enable_cluster_creator_admin_permissions = true` → the IAM identity running `terraform apply` gets `AmazonEKSClusterAdminPolicy`.
- Add a teammate: `access_entries = { dev = { principal_arn = "...", policy_associations = { view = { policy_arn = ".../AmazonEKSViewPolicy", access_scope = { type = "namespace", namespaces = ["shopflow"] } } } } }`.
- CI never gets kubectl: Argo CD pulls from Git (`application-prod.yaml`), so the GitHub role needs **no** EKS permission. That is a security argument for GitOps.

### 3.6 Cluster upgrades (EKS supports N-3 minor versions, ~14 months each, then extended support at extra cost)

1. Read the release notes; run `kubectl` deprecation checks (`pluto`, `kubent`) — e.g. removed APIs.
2. Bump `kubernetes_version` → control plane upgrades in place (~20–40 min, API server briefly unavailable, workloads unaffected).
3. Upgrade add-ons to versions compatible with the new control plane (`vpc-cni` first).
4. Node group: EKS performs a rolling replacement (new launch template AMI) honouring **PDBs** — ShopFlow `minAvailable: 1` + `maxUnavailable: 0` rolling strategy keep one pod up; `topologySpreadConstraints` on hostname keep replicas on different nodes. With `max_unavailable_percentage` on the node group you control speed.
5. Order: control plane → add-ons → nodes; never skip a minor version for nodes vs control plane (kubelet may be ≤ 3 minors behind apiserver, never ahead).
6. Blue/green alternative: create node group `general-v2`, `cordon` + `drain` old, delete old group. Safer, costs double for an hour.

---

## 4. ECR

```hcl
image_tag_mutability = "IMMUTABLE"     # a SHA tag can never be re-pushed
image_scanning_configuration { scan_on_push = true }
lifecycle policy: imageCountMoreThan 30 → expire
```

- **Immutable tags** make `kustomization.yaml` `newTag: <sha>` a true pointer: the bytes behind the tag cannot change after review. Also kills `:latest` (which the CI only publishes to GHCR as a convenience).
- **Scan on push** = basic scanning (free, OS-package CVEs); Enhanced scanning (Inspector) adds continuous re-scan and language packages (Maven jars!) — relevant because Trivy in CI already fails on fixable HIGH/CRITICAL (`.github/workflows/shopflow.yml`), so ECR scanning is the second gate and catches new CVEs in images that are *already* deployed.
- **Lifecycle**: 30 images × 3 repos ≈ 90 × ~150 MB = ~14 GB ≈ $1.4/mo. Keeps ~30 deployments of rollback depth.
- Pull from EKS: node role has `AmazonEC2ContainerRegistryReadOnly` (module default); no `imagePullSecrets` needed, unlike GHCR private images.
- Cross-region replication for DR; pull-through cache for Docker Hub rate limits.

ECR in CI today (`.github/workflows/shopflow.yml`, `image` job): after the GHCR push on `main`, if the repository variable `AWS_ROLE_ARN` is set, `aws-actions/configure-aws-credentials@v6` assumes it with the job's OIDC token (`permissions: id-token: write`), `aws-actions/amazon-ecr-login@v2` logs in, and the SHA-tagged image is re-tagged and pushed to `<acct>.dkr.ecr.ap-south-1.amazonaws.com/shopflow/<service>:<sha>`. Remaining step: point the prod overlay `images[].newName` (and the `kustomize edit set image` in `deploy-manifests`) at the ECR URLs so EKS pulls from ECR with the node role instead of GHCR.

---

## 5. RDS PostgreSQL

### 5.1 Multi-AZ vs read replica

```
Multi-AZ (HA)                                   Read replica (scale/DR)
primary (AZ-a) ══ synchronous block replication ══▶ standby (AZ-b)     primary ─ async WAL ─▶ replica(s), same or other region
standby is NOT readable; one DNS name            readable; separate endpoint; seconds of lag
automatic failover 60–120 s (DNS flip)           manual/promotion → becomes standalone
cost 2×                                          cost +1× each
```
ShopFlow: `multi_az = var.db_multi_az` (true in prod). No read replica yet — order/inventory are write-heavy small tables; a replica would help only for reporting. Multi-AZ **DB cluster** (2 readable standbys) is the newer option.

### 5.2 Parameter group (`aws_db_parameter_group.postgres`, family `postgres16`)

| Parameter | Value | Why |
|---|---|---|
| `max_connections` | 300, `apply_method = pending-reboot` (static param) | HikariCP pools: order 10 pods × 5 + inventory 10 × 5 + notification 10 × 5 = 150, plus relay/admin/Flyway headroom. Postgres needs ~5–10 MB RAM per connection → 300 × ~8 MB ≈ 2.4 GB of the 4 GB on t4g.medium: this is the ceiling, hence RDS Proxy discussion below |
| `log_min_duration_statement` | 500 ms (dynamic) | Slow query log → CloudWatch Logs → "why is p99 1s?" is answerable |

Formula you can quote (HikariCP wiki): `pool_size ≈ cores × 2 + effective_spindle_count`; a `db.t4g.medium` has 2 vCPU → the DB is happiest with ~10–20 *active* connections total. 150 idle-capable connections is fine, 150 *busy* is not — that is why `DB_POOL_SIZE=5` per pod and virtual threads (`spring.threads.virtual.enabled`) rather than one thread per connection.

### 5.3 Backups, PITR, snapshots

- `backup_retention_period = 7`, window 20:00–21:00 UTC (01:30 IST, low traffic). Automated daily snapshot + **transaction logs every 5 minutes → PITR to any second in the last 7 days** (RPO ≈ 5 min).
- `deletion_protection = true` and `skip_final_snapshot = false` in prod: `terraform destroy` fails until you flip the flag, and a final snapshot `shopflow-final` is taken.
- Restore = **new instance** from snapshot/PITR, then repoint the `rds-endpoint` key in the ConfigMap (or swap DNS via a Route 53 CNAME in front of RDS — recommended so the overlay never changes).
- Storage: gp3 50 GB → autoscale to 200 GB (`max_allocated_storage`); never let RDS hit storage-full (it goes read-only).

### 5.4 Performance Insights + Enhanced Monitoring

- **Performance Insights** (free 7-day tier): DB load as "average active sessions" split by wait event / SQL / user. Answers "is it CPU, lock, or IO?" and shows top SQL by load — pair with `log_min_duration_statement`.
- **Enhanced Monitoring** (`monitoring_interval = 60`, IAM role `rds_monitoring`): OS-level metrics (per-process CPU, swap) from an agent, unlike CloudWatch basic metrics which come from the hypervisor.
- Key CloudWatch alarms: `DatabaseConnections` > 80% of max, `FreeStorageSpace`, `CPUUtilization`, `ReplicaLag` (if replica), `FreeableMemory`, `WriteLatency`.

### 5.5 RDS Proxy vs PgBouncer

| | RDS Proxy | PgBouncer (in cluster) |
|---|---|---|
| Managed? | Yes, ~$0.015 per vCPU-hour of the DB instance, minimum 2 vCPU (~$22/mo for a `db.t4g.medium`) | You run a Deployment |
| Pooling mode | Session or transaction-ish (pinning on prepared statements / session state) | transaction mode, tiny footprint |
| Failover | **Hides Multi-AZ failover**: proxy holds client connections, re-connects to the new primary (3–10 s instead of 60–120 s) | Reconnects too, but you configure it |
| IAM auth | Yes (pods could use IRSA instead of a password → no secret at all) | No |
| Gotcha | Hikari + Proxy session pinning if you use `SET` statements or prepared statement caching | Named prepared statements break transaction mode unless `max_prepared_statements` (1.21+) |

ShopFlow today: no proxy; Hikari connects directly. I'd add RDS Proxy before the first Black-Friday-type event.

### 5.6 What the app sees during Multi-AZ failover

```
t0   primary AZ fails / "reboot with failover"
t0+  RDS flips the DNS CNAME shopflow.xxxx.ap-south-1.rds.amazonaws.com → standby IP (TTL 5 s)
t0..t0+60–120 s  existing TCP connections die → Hikari sees SQLException / connection reset
     ├─ in-flight transactions roll back (standby has all committed data: sync replication)
     ├─ Hikari evicts broken connections (validation on borrow, connectionTimeout 30 s default)
     └─ order-service: readiness group includes `db` → pods go NotReady → ALB stops routing → 503s for ~1–2 min
t0+120 s  new connections resolve to the new primary, pools refill, readiness UP
```
Mitigations you can state: JVM DNS cache (`networkaddress.cache.ttl` is 30 s by default without a security manager; set it to ~5 s so pods follow the RDS CNAME flip), Hikari `maxLifetime` < any proxy idle timeout, RDS Proxy, retries on the client side with idempotency keys (`Idempotency-Key` header in `OrderController`), outbox rows are committed in the same transaction so no event is lost.

---

## 6. ElastiCache Redis

```hcl
num_cache_clusters = 2            # primary + 1 replica, cluster mode disabled
automatic_failover_enabled = true
multi_az_enabled = true
transit_encryption_enabled = true # rediss:// — Spring: spring.data.redis.ssl.enabled=true
at_rest_encryption_enabled = true
parameter_group_name = "default.redis7"
snapshot_retention_limit = 1
```

- **Replication group** (cluster mode disabled) = one shard, one writer, N readers; `primary_endpoint_address` always points at the current primary, so the app needs no change on failover (reconnect only, Lettuce does this). Cluster mode enabled = multiple shards, use `configuration_endpoint_address`, client must speak cluster protocol.
- Failover: primary dies → a replica is promoted in ~15–30 s (Multi-AZ); writes fail during the window; inventory-service treats the cache as optional (`CACHE_TYPE=redis`, `REDIS_HEALTH` is a flag, readiness excludes Redis by default, and `CacheConfig`'s `LoggingCacheErrorHandler` turns a failed cache call into a logged miss) → requests fall through to RDS instead of failing. That is the right design for a *cache*.
- **TLS**: ShopFlow compose uses plain `redis://`; prod needs `spring.data.redis.ssl.enabled=true` and the host from the `redis-host` ConfigMap key. Auth token / RBAC users (`aws_elasticache_user`) add AUTH on top.
- **Eviction policy** (`maxmemory-policy`): default `volatile-lru` (evict only keys with TTL). ShopFlow sets `time-to-live: 60s` on every entry, so `volatile-lru` works; a pure cache with un-TTL'd keys needs `allkeys-lru` (compose uses it). Others: `allkeys-lfu` (hot-set skew), `noeviction` (queue/lock use cases, errors instead of silent loss), `volatile-ttl`.
- Metrics: `CacheHitRate` (ShopFlow business goal > 90% on product reads), `Evictions` (> 0 sustained = memory too small), `CurrConnections`, `ReplicationLag`, `EngineCPUUtilization` (Redis is single-threaded: 90% on one core = saturated even if host CPU is 25%).
- Serverless ElastiCache: pay per GB-hour + ECPU, no node sizing; good for spiky, small caches like this one.

---

## 7. Amazon MSK

### 7.1 Topology

```
AZ-a          AZ-b          AZ-c
b-1           b-2           b-3           number_of_broker_nodes = 3 (must be a multiple of AZ count)
 └── orders.events: 6 partitions, RF 3 → every partition has a replica in every AZ
     min.insync.replicas = 2 + producer acks=all (order-service application.yml)
     ⇒ a write is acked when leader + 1 follower have it; losing any one broker/AZ loses nothing
```

| Setting | Where | Effect |
|---|---|---|
| `default.replication.factor=3`, `min.insync.replicas=2` | `aws_msk_configuration` | Durability: tolerate 1 broker down for writes; with 2 down → producers get `NotEnoughReplicas` (availability sacrificed for consistency) |
| `acks=all`, `enable.idempotence=true` | order-service | Relay marks outbox row published only after a full ISR ack; broker dedups producer retries |
| `auto.create.topics.enable=false` | MSK config | Topics are created deliberately (Terraform `aws_msk_*` has no topic resource; use a Job with `kafka-topics.sh` or the Mongey/kafka provider). Prevents typo-topics with RF 1 |
| `num.partitions=6` | MSK config | notification-service can scale to 6 consuming pods (HPA max 10 → 4 idle), ordering by key `orderRef` |
| `log.retention.hours=168` | MSK config | 7 days to replay the DLT or re-consume after a bug |
| 100 GB EBS/broker | `storage_info` | Watch `KafkaDataLogsDiskUsed`; enable storage autoscaling or tiered storage |

### 7.2 Auth and encryption

- `client_authentication.sasl.iam = true`: clients connect to port **9098** (attribute `bootstrap_brokers_sasl_iam`, exported as output `kafka_bootstrap_servers_iam`) with `SASL_SSL` + `AWS_MSK_IAM` and sign with their IAM identity (IRSA role `shopflow-kafka-clients`). Policy in `irsa.tf`: `kafka-cluster:Connect` on the cluster, `WriteData/ReadData/*Topic*` on `topic/shopflow/*/orders.events*` (covers `.DLT`), `AlterGroup/DescribeGroup` on `group/shopflow/*/notification-service`. **Least privilege at topic level.**
- `encryption_in_transit.client_broker = "TLS"`, `in_cluster = true`. No PLAINTEXT listener → the prod overlay ConfigMap placeholder already uses the `:9098` IAM listener (`kafka-bootstrap-servers=b-1...:9098,b-2...:9098`); replace it with the `kafka_bootstrap_servers_iam` output. The egress NetworkPolicy allows both 9092 and 9098 to the VPC CIDR.
- Client side (implemented): order- and notification-service declare `software.amazon.msk:aws-msk-iam-auth:2.3.9` (`runtime` scope, version in the parent pom) and ship `src/main/resources/application-aws.yml` with the four properties under `spring.kafka.properties`: `security.protocol: SASL_SSL`, `sasl.mechanism: AWS_MSK_IAM`, `sasl.jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;`, `sasl.client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler`. The prod overlay (`overlays/prod/kustomization.yaml`) adds `SPRING_PROFILES_ACTIVE=aws` to both Deployments next to the `serviceAccountName` patch, so the profile and the IRSA identity arrive together; dev/compose keep the PLAINTEXT `application.yml`. The library picks up the IRSA web-identity credentials from the AWS default chain — no username/password anywhere. **Not yet verified against a real MSK cluster** (see the honesty box); first thing to check on a real run: `kafka-bootstrap-servers` points at the `:9098` IAM listener and the pod's `AWS_WEB_IDENTITY_TOKEN_FILE` is present.

### 7.3 Provisioned vs Serverless

| | Provisioned (ShopFlow) | MSK Serverless |
|---|---|---|
| Pricing | per broker-hour + storage (~$0.05/h for t3.small ≈ $110/mo for 3 + EBS) | per cluster-hour (~$0.75/h ≈ $550/mo!) + partition-hour + data; cheaper only at tiny *and* bursty scale... check numbers for your region |
| Config | full `server.properties`, JMX, tiered storage, Connect | IAM only, fixed limits (e.g., 120 partitions default), no custom configs |
| Ops | you pick versions, broker size, storage | zero sizing |
| `kafka.t3.small` caveat | burstable CPU credits; fine for a few hundred msg/s, **not** for prod load — move to `kafka.m7g.large` |

### 7.4 Monitoring consumer lag

- `open_monitoring.prometheus.jmx_exporter` + `node_exporter` → in-cluster Prometheus scrapes brokers on 11001/11002 (allow in MSK SG from the monitoring namespace's nodes).
- Broker-side lag: CloudWatch per-consumer-group metrics `EstimatedMaxTimeLag` / `SumOffsetLag` (enable the `PER_TOPIC_PER_PARTITION` monitoring level), or `kafka_consumergroup_lag` from a kafka_exporter scraping the brokers.
- Client-side: `kafka_consumer_fetch_manager_records_lag_max` (Micrometer's Kafka consumer binder) — ShopFlow's alert `KafkaConsumerLagHigh > 1000 for 10m` (`monitoring/alert-rules.yml`); the notification test asserts that gauge exists so the rule cannot silently point at a non-existent series. Pair with `OutboxBacklogGrowing` to tell "producer stuck" from "consumer slow".
- `kafka-consumer-groups.sh --describe --group notification-service --bootstrap-server ... --command-config iam.properties` for the manual check.

---

## 8. Cognito

### 8.1 Pieces

| Resource | ShopFlow value | Interview point |
|---|---|---|
| User pool | email username, auto-verify email, MFA `OPTIONAL`, min 12 chars, recovery via verified email | User directory + OIDC issuer: `https://cognito-idp.ap-south-1.amazonaws.com/<poolId>` (output `jwt_issuer_uri`) |
| App client | `generate_secret = false`, flow `code`, scopes openid/email/profile, callback `https://shop.example.com/callback` + localhost | **Public client → PKCE**: SPA cannot hide a secret, so the code exchange is bound to a `code_verifier` hash sent at `/authorize` |
| Hosted domain | `shopflow-<hex>.auth.ap-south-1.amazoncognito.com` | Hosted login page, `/oauth2/authorize`, `/oauth2/token`, `/.well-known/jwks.json` under the issuer |
| Groups | `customer`, `support` | Appear as `"cognito:groups": ["customer"]` claim → `JwtRolesConverter` → `ROLE_customer` → `SecurityConfig` `hasRole("customer")` for POST/DELETE, `hasAnyRole("customer","support")` for GET |
| Tokens | access 15 min, id 15 min, refresh 30 d | Short access tokens limit stolen-token damage; refresh rotation + revocation (`aws cognito-idp admin-user-global-sign-out`) |
| Auth flows | `ALLOW_USER_SRP_AUTH`, `ALLOW_REFRESH_TOKEN_AUTH` | SRP: password never leaves the client in clear; `USER_PASSWORD_AUTH` deliberately **not** allowed |

### 8.2 Flow

```
SPA ─(1) GET /oauth2/authorize?response_type=code&client_id&redirect_uri&code_challenge=S256(verifier)─▶ Cognito hosted UI
    ◀(2) 302 redirect_uri?code=...
    ─(3) POST /oauth2/token  grant_type=authorization_code&code&code_verifier ─▶  Cognito
    ◀(4) { access_token (15m), id_token, refresh_token (30d) }
    ─(5) GET /api/v1/orders  Authorization: Bearer <access_token> ─▶ ALB ─▶ order-service
           order-service: fetch JWKS once from issuer (cached), verify RS256 signature, exp, iss
           groups claim → ROLE_*; no DB call, no session (STATELESS, csrf disabled)
```

Which token to send? The **access token** (has `cognito:groups`, `scope`, `client_id`; `aud` is absent — Spring's default issuer validation does not require `aud`; add a custom `JwtClaimValidator` for `client_id` if you want audience pinning). The ID token is for the client to know *who* is logged in.

### 8.3 Cognito vs Keycloak

| | Cognito | Keycloak (compose / minikube) |
|---|---|---|
| Ops | none; first 10k MAU free on the Lite/Essentials tiers (it was 50k before the Nov 2024 pricing change), then per-MAU | you run + upgrade + back up its DB |
| Customisation | limited UI, Lambda triggers for logic | full: themes, flows, protocol mappers, realms |
| Roles | groups claim only (`cognito:groups`), no composite/client roles | realm/client roles, `realm_access.roles` |
| Federation | SAML/OIDC IdPs, social | same + user federation (LDAP/AD) |
| Portability | AWS-only | anywhere |
| ShopFlow | prod (`jwt-issuer-uri` ConfigMap key) | dev; same image works because `JwtRolesConverter` supports both claims |

---

## 9. Secrets Manager + External Secrets Operator

```
Terraform: random_password x4 ─▶ aws_secretsmanager_secret "shopflow/prod/db"
                                   { master, orders, inventory, notifications, host, port }
                                              │ GetSecretValue (IRSA role shopflow-external-secrets, policy = that ARN only)
                                              ▼
K8s: SecretStore(aws, SecretsManager, ap-south-1, jwt auth via SA external-secrets-sa)
     ExternalSecret shopflow-db (refreshInterval 1h) ─▶ Secret shopflow-db
        keys: orders-db-password / inventory-db-password / notifications-db-password
                                              │ secretKeyRef (unchanged from base Deployments)
                                              ▼
     DB_PASSWORD env in each pod
```

- Why ESO and not the **Secrets Store CSI driver**? ESO creates a normal `Secret` the existing manifests already reference (zero change to Deployments); CSI mounts files and needs the app to read files. Both avoid committing secrets; the CSI driver avoids *storing* them in etcd at all (stricter).
- Why Secrets Manager and not **SSM Parameter Store** (SecureString)? Parameter Store is free and fine for config, but Secrets Manager has **native rotation** (Lambda rotation functions for RDS that update the DB user *and* the secret), cross-account/replica secrets, and a 30-day recovery window (`recovery_window_in_days = 30` in prod). Cost ~$0.40/secret/month — one JSON secret with four keys costs $0.40.
- Rotation without downtime: enable rotation on the secret (Postgres "alternating users" strategy or single-user), ESO refreshes within 1 h (or trigger via annotation), and pods must *reload*: Spring reads env at start, so either (a) the Reloader controller restarts Deployments on Secret change (rolling, zero-downtime thanks to `maxUnavailable: 0`), or (b) mount as file + Spring Cloud Kubernetes reload. With Hikari, new connections use the new password; existing ones stay valid until `maxLifetime` recycles them — so use alternating users so both old and new passwords work during the overlap.
- Terraform state contains the passwords in clear → the S3 bucket is encrypted, versioned, access-logged, and only the apply role may read it. This is standard, and a reason some teams generate the secret value outside Terraform (`aws secretsmanager put-secret-value` once) and reference by name only.

---

## 10. IAM

### 10.1 Principles used

| Principle | Where in ShopFlow |
|---|---|
| **Roles, not users**: no IAM users, no access keys anywhere | Pods (IRSA), CI (OIDC), RDS monitoring (service role), nodes (instance role) |
| Least privilege, resource-scoped | `read_db_secret` → one secret ARN; `kafka_clients` → one topic prefix + one group; `github_actions_ecr` → three repo ARNs (`ecr:GetAuthorizationToken` must be `*`, it is account-level) |
| Separate roles per workload | External Secrets cannot touch Kafka; LB controller cannot read secrets |
| Temporary credentials | STS tokens 1 h (IRSA) / job lifetime (GitHub) |
| Managed policy where AWS maintains it | `AmazonRDSEnhancedMonitoringRole`, `attach_load_balancer_controller_policy` (the module ships the ~200-line controller policy) |

### 10.2 `ci-oidc.tf` trust policy, explained line by line

```hcl
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"   # GitHub's OIDC issuer
  client_id_list = ["sts.amazonaws.com"]                           # the audience the token must carry
  thumbprint_list = ["6938fd4d..."]                                 # legacy; AWS now trusts GitHub's CA chain directly
}
Condition = {
  StringEquals = {
    "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
    "token.actions.githubusercontent.com:sub" = "repo:Rajji01/DevopsComplete:ref:refs/heads/main"
  }
}
```

Flow: the workflow has `permissions: id-token: write` → the runner asks GitHub for a JWT (`iss` github, `aud sts.amazonaws.com`, `sub repo:owner/repo:ref:refs/heads/main`, plus `job_workflow_ref`, `actor`, `environment`) → `aws-actions/configure-aws-credentials` calls `sts:AssumeRoleWithWebIdentity` with it → STS verifies the signature against GitHub's JWKS (via the provider), checks `aud` and the `sub` condition → returns 1-hour credentials.

Why the `sub` pin matters: without it, **any** GitHub repository could assume your role (the provider is per-issuer, not per-repo). Pinning to `refs/heads/main` means a PR from a fork cannot push images; a stricter variant pins `environment:prod` so the role is assumable only from a job that passed a manual approval environment. `StringLike` with `repo:Rajji01/DevopsComplete:*` would allow any branch/tag.

### 10.3 IAM policy evaluation order (classic question)

```
explicit Deny anywhere?  ──yes──▶ DENY
   │ no
SCP (org) allows?  ──no──▶ DENY
   │ yes
resource-based policy allows this principal?  ──yes──▶ ALLOW (same account: enough)
   │
identity-based policy allows?  ──no──▶ DENY (implicit)
   │ yes
permissions boundary / session policy restrict?  ──yes──▶ DENY
   │ no
ALLOW
```
Cross-account requires *both* sides to allow. For KMS, the key policy is authoritative.

---

## 11. Ingress: ALB via AWS Load Balancer Controller

Prod overlay patch on the Ingress:

```yaml
spec.ingressClassName: alb
alb.ingress.kubernetes.io/scheme: internet-facing            # public subnets (tag kubernetes.io/role/elb=1)
alb.ingress.kubernetes.io/target-type: ip                    # ALB → pod IP directly (VPC CNI), skips NodePort hop
alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS":443}]'
alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:...   # TLS terminated at ALB
alb.ingress.kubernetes.io/healthcheck-path: /actuator/health/readiness
host: shop.example.com ; Keycloak rule removed
```

- Controller runs in `kube-system` with SA `aws-load-balancer-controller` (IRSA role `irsa_lb_controller`). It watches Ingress/Service objects and creates ALB + target groups + listeners; pods are registered as IP targets and **deregistered when they become NotReady / terminating** (the `preStop sleep 5` + readiness gates matter here — add `elbv2.k8s.aws/pod-readiness-gate-inject: enabled` on the namespace to avoid 502s during rollouts).
- **ALB vs NLB**: ALB = L7 (host/path routing, WAF, OIDC auth at the edge, HTTP/2, gRPC, slow-start), per-LCU pricing; NLB = L4 (TCP/UDP/TLS), static IPs / EIPs, millions of RPS, source IP preserved, lower latency, PrivateLink. ShopFlow needs path routing → ALB. One ALB for both services via a single Ingress (or `group.name` annotation to share across Ingresses). An NLB would front something like Kafka or a TCP service.
- **ACM**: free public cert, DNS validation via Route 53 — `dns.tf` has `aws_acm_certificate` (`validation_method = "DNS"`, `create_before_destroy`), the `aws_route53_record` validation CNAMEs built from `domain_validation_options`, and `aws_acm_certificate_validation`, all `count`-gated on `hosted_zone_id`; output `acm_certificate_arn` replaces the overlay's `REPLACE-ME`. Auto-renews. Private certs need ACM Private CA.
- **Route 53**: `A` **alias** record `shop.example.com → ALB DNS name` (alias = free queries, follows ALB IP changes; a CNAME at the zone apex is not allowed). Health checks + failover routing policy for DR; latency-based routing for multi-region.
- Hardening: WAFv2 web ACL (rate-based rule 2000 req/5 min per IP, AWS managed rule groups) attached via `alb.ingress.kubernetes.io/wafv2-acl-arn`; `ssl-policy: ELBSecurityPolicy-TLS13-1-2-2021-06`; access logs to S3; `shield-advanced-protection` if money allows.
- NetworkPolicy note: the base `*-ingress` policies allow from namespace `ingress-nginx`; with ALB `target-type: ip`, traffic arrives from the **ALB's ENI IPs** in the public subnets, so the prod overlay patches `order-service-ingress` / `inventory-service-ingress` with `ipBlock: 10.0.0.0/16` on 8081/8082 — without it the ALB health check is denied and every target is unhealthy. Tighter: only the public subnet CIDRs (`10.0.100.0/24`–`10.0.102.0/24`).

---

## 12. Observability on AWS

| Layer | ShopFlow today (compose/minikube) | AWS option | Trade-off |
|---|---|---|---|
| Metrics | Prometheus + Grafana, `alert-rules.yml` | **Amazon Managed Prometheus (AMP)** + **Amazon Managed Grafana (AMG)**; or kube-prometheus-stack on EKS | AMP removes Prometheus storage ops, remote_write from an in-cluster agent/ADOT; AMG = SSO, no Grafana upgrades; same PromQL and rules (AMP rules via `alertmanager definition`) |
| Node/pod metrics | metrics-server | **CloudWatch Container Insights** (CloudWatch agent DaemonSet or the `amazon-cloudwatch-observability` add-on) | Easiest dashboards per cluster/namespace/pod; pricey per metric at scale |
| Traces | Tempo via OTLP `otlp-endpoint` | **ADOT collector** (AWS Distro for OpenTelemetry) exporting to **X-Ray** or to Tempo/AMP | The prod ConfigMap already points at `otel-collector.monitoring:4318`; the app is unchanged (Micrometer Tracing OTLP). X-Ray: integrated service map, IAM; Tempo: TraceQL, cheaper at high volume, exemplars with Prometheus |
| Logs | Loki + Alloy, ECS JSON (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`) | **CloudWatch Logs** via Fluent Bit (Container Insights) or keep Loki on EBS/S3 | CloudWatch Logs Insights queries structured JSON well; cost $0.50/GB ingested — set retention; Loki on S3 is cheaper for big volumes |
| AWS-side signals | — | CloudWatch metrics from RDS/MSK/ElastiCache/ALB (e.g. `HTTPCode_ELB_5XX_Count`, `TargetResponseTime`, `DatabaseConnections`, `EstimatedMaxTimeLag`) | Scrape into Prometheus with **YACE** (yet-another-cloudwatch-exporter) so alerts live in one place |

Sampling: `TRACING_SAMPLE` env → set 0.1 in prod (comment in `application.yml`), head-based; ADOT tail sampling can keep all error traces.

---

## 13. Cost: the ~$400–600/month estimate

Rough `ap-south-1` on-demand numbers (verify in the calculator; prices drift):

| Component | Config | ~$/month |
|---|---|---|
| EKS control plane | 1 cluster | 73 |
| EC2 nodes | 3 × t3.large ($0.0928/h... Mumbai ≈ $0.0944) | ~200 |
| EBS for nodes | 3 × 20 GB gp3 | ~5 |
| NAT gateways | 3 × $0.045/h + $0.045/GB | ~100 + data |
| RDS | db.t4g.medium Multi-AZ (~$0.082/h × 2) + 50 GB gp3 ×2 | ~130 |
| ElastiCache | 2 × cache.t4g.small (~$0.036/h) | ~55 |
| MSK | 3 × kafka.t3.small (~$0.05/h) + 300 GB storage ($0.10/GB) | ~140 |
| ALB | 1 ALB ($0.0225/h) + LCU | ~20 |
| Secrets Manager, ECR, CloudWatch logs, Cognito (<10k MAU free) | | ~10 |
| **Total** | | **~$730 at list** → the README's $400–600 assumes `kafka.t3.small`, light data transfer and partially free tiers; treat it as "$500 ± $200" |

### How to cut it (ordered by effort/impact)

1. **`terraform destroy` the same day** for learning. Or `db_multi_az=false`, `single_nat_gateway=true`, `node_group_size={min=1,desired=1,max=2}` → ~$250.
2. **Spot** for the node group (`capacity_type = "SPOT"`, several instance types): −60–70% on EC2. Stateless pods + PDB + `maxUnavailable: 0` + `topologySpreadConstraints` make interruptions a non-event; add the AWS Node Termination Handler or use Karpenter.
3. **Graviton** everywhere: already `db.t4g`, `cache.t4g`; move nodes to `m7g/t4g`, MSK to `kafka.m7g.large` when load grows: −20% for the same work.
4. **Single NAT + VPC endpoints**: S3 gateway endpoint (free) + interface endpoints for ECR/STS/Secrets Manager (~$7/mo each) often cost less than the NAT data they replace.
5. **MSK Serverless or Express brokers** if traffic is tiny/spiky; or run Strimzi Kafka on the nodes for dev.
6. **Savings Plans / Reserved Instances**: 1-year compute SP −30–40% on EC2/Fargate; RDS/ElastiCache RIs −35%. Only after 2–3 months of stable usage.
7. **Right-size** with Container Insights / VPA recommendations; 384Mi requests × 30 pods may be over-reserved.
8. **Log retention** (CloudWatch 14 days for MSK is set; do the same for app logs), Performance Insights free tier, 10% trace sampling.
9. Budgets: `aws_budgets_budget` with alerts at 50/80/100%, Cost Anomaly Detection, `default_tags` for allocation.

> Tip: Cost question pe hamesha "measure → tag → right-size → commit" order bolo; Savings Plan sabse last. Pehle din RI kharidna beginner mistake hai.

---

## 14. Well-Architected pillars → this design

| Pillar | Evidence in ShopFlow | Honest gap |
|---|---|---|
| Operational excellence | Everything in Terraform + Kustomize + Argo CD (`selfHeal`, `prune`); `terraform validate` in CI; runbook below; alerts on symptoms | No `terraform plan` in PRs yet; no automated DB-init Job |
| Security | Private subnets, SG-to-SG, no IAM users, IRSA/OIDC, encryption at rest (RDS, ElastiCache, MSK, ECR, S3 state) and in transit (TLS to RDS `sslmode=require`, Redis TLS, MSK TLS, ALB HTTPS), Cognito PKCE + short tokens, non-root read-only containers, NetworkPolicies, Trivy + ECR scan | Public EKS endpoint open to `0.0.0.0/0`; no WAF; no KMS CMKs (AWS-managed keys) |
| Reliability | 3 AZs, NAT/AZ, RDS Multi-AZ, Redis auto-failover, Kafka RF3/ISR2, HPA 3–10, PDB, outbox + idempotent consumer + DLT, circuit breaker | Single region; no RDS Proxy; no chaos testing |
| Performance efficiency | Graviton DB/cache, gp3, Redis cache, virtual threads, HPA on CPU, `log_min_duration_statement` | HPA only on CPU (add RPS/latency via KEDA/custom metrics) |
| Cost optimisation | `default_tags`, lifecycle policy, log retention, dev flags, right-sized burstables | On-demand only; no budgets resource yet |
| Sustainability | Graviton, autoscaling to min 2 nodes, managed services (higher utilisation) | — |

---

## 15. Disaster recovery

| Term | ShopFlow number | How |
|---|---|---|
| **RPO** (data loss tolerated) | RDS: ≤ 5 min (PITR logs); MSK: 0 for acked writes within region (RF3); Redis: cache, RPO irrelevant | Automated backups 7 d; cross-region snapshot copy would make RPO ≈ 24 h for region loss |
| **RTO** (time to recover) | AZ loss: minutes, automatic (Multi-AZ, 3-AZ EKS/MSK, NAT/AZ). Region loss: hours (re-apply Terraform in another region from the same Git + restore snapshot) | `terraform apply -var region=ap-southeast-1` works because nothing is hard-coded except the state key, the `region` in the backend block and the overlay's endpoint/ACM values |

DR strategy ladder (AWS terminology): **Backup & restore** (hours, cheapest — ShopFlow today) → **Pilot light** (DB replica in region 2, no compute) → **Warm standby** (scaled-down full stack) → **Multi-site active/active** (Route 53 latency routing, Aurora Global Database / DynamoDB global tables, MSK replicated with MirrorMaker 2 or MSK Replicator). Each step costs more and complicates data consistency (see [12 — A5](12-hard-interview-questions.md)).

Minimum DR hygiene to actually do: enable **AWS Backup** plan with cross-region copy for RDS, copy ECR images to a second region (replication rules), keep the Terraform state bucket replicated, test a restore quarterly (a backup you never restored is a hope, not a backup).

---

## 16. Deploy-day runbook

```
[0] Prereqs: AWS account, admin role, aws CLI, terraform ≥ 1.11, kubectl, helm, argocd CLI; domain in Route 53.
[1] State bucket (once):  aws s3 mb s3://shopflow-terraform-state-<uniq> --region ap-south-1
                          aws s3api put-bucket-versioning ... Status=Enabled ; set bucket in versions.tf
[2] terraform init && terraform plan -out=tfplan        # ~60 resources, read it: nothing destroyed, MSK ~25 min, RDS Multi-AZ ~15 min, EKS ~12 min
    terraform apply tfplan                              # total 30–40 min
    terraform output                                    # keep this terminal open
[3] $(terraform output -raw configure_kubectl)          # kubeconfig
    kubectl get nodes                                   # 3 Ready nodes
[4] helm install aws-load-balancer-controller eks/aws-load-balancer-controller -n kube-system \
      --set clusterName=shopflow --set serviceAccount.annotations."eks\.amazonaws\.com/role-arn"=$(terraform output -raw lb_controller_role_arn)
    helm install external-secrets external-secrets/external-secrets -n external-secrets --create-namespace
    helm install argocd argo/argo-cd -n argocd --create-namespace
    (optional) kube-prometheus-stack / ADOT collector in `monitoring`
[5] DB init (once): run a psql pod in the cluster (RDS is private):
    kubectl run psql --rm -it --image=postgres:16-alpine -- psql "host=<rds host> user=shopflow_admin password=$(aws secretsmanager get-secret-value --secret-id shopflow/prod/db --query SecretString --output text | jq -r .master) dbname=postgres"
      → run the CREATE USER/DATABASE statements of k8s/base/postgres/init-db.sh with the orders/inventory/notifications passwords from the same secret
    Kafka topics (once): kafka-topics.sh --create --topic orders.events --partitions 6 --replication-factor 3 (and orders.events.DLT) using the IAM command-config
[6] Fill the overlay from outputs: rds-endpoint, redis-host, kafka-bootstrap-servers (kafka_bootstrap_servers_iam, :9098), jwt-issuer-uri,
    acm_certificate_arn (needs hosted_zone_id set), external_secrets_role_arn in external-secret.yaml, kafka_clients_role_arn in
    service-accounts.yaml, images[].newName → ECR URLs (set repo variable AWS_ROLE_ARN so CI mirrors to ECR). Commit to main (PR + review).
[7] kubectl apply -n argocd -f shopflow/argocd/application-prod.yaml ; argocd app get shopflow-prod   # automated sync, prune, selfHeal, SSA
    watch: kubectl -n shopflow get externalsecret,secret shopflow-db ; kubectl -n shopflow get pods -w
[8] DNS: Route 53 alias shop.example.com → $(kubectl -n shopflow get ingress shopflow -o jsonpath='{.status.loadBalancer.ingress[0].hostname}')
[9] Smoke test:
    curl -s https://shop.example.com/api/v1/products | jq length
    TOKEN=$(... Cognito hosted UI / aws cognito-idp initiate-auth with SRP ...)
    curl -s -X POST https://shop.example.com/api/v1/orders -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: $(uuidgen)" -d '{"sku":"PS5","quantity":1}'
    → check notification-service logs for "EMAIL -> customer", Grafana for orders_total{status="CONFIRMED"}, outbox_unpublished == 0
[10] Rollback paths:
    app:    git revert the "deploy(prod): pin images" commit → Argo syncs the previous SHA (ECR immutable tags guarantee it is the same image); or `argocd app rollback shopflow-prod <id>` (then fix Git, or selfHeal reverts you)
    infra:  `terraform plan` after `git revert` of the .tf change; for RDS engine changes → restore snapshot into a new instance, repoint endpoint
    emergency: `argocd app set --sync-policy none` to stop selfHeal, `kubectl rollout undo deploy/order-service -n shopflow`, then reconcile Git
```

---

## 17. Interview Q&A

**Q1. Security Group vs NACL?**
SG: stateful, ENI-level, allow-only, can reference other SGs (ShopFlow: RDS allows 5432 from the EKS node SG). NACL: stateless, subnet-level, allow+deny, ordered rules, needs ephemeral-port return rules. Use SGs for everything; NACLs for coarse subnet isolation or blocking an IP range.

**Q2. How does IAM evaluate a request?**
Explicit deny wins → SCP must allow → resource policy (can grant alone, same account) → identity policy → permissions boundary / session policy. Default is implicit deny. Cross-account needs both sides.

**Q3. IAM role vs user vs group vs policy?**
User = long-lived identity with password/keys (avoid). Group = policy attachment for users. Role = assumable identity with temporary creds (pods via IRSA, CI via OIDC, EC2 via instance profile). Policy = JSON document attached to identities or resources.

**Q4. How does a pod get AWS credentials on EKS?**
ServiceAccount annotated with a role ARN → EKS mutating webhook injects a projected SA token (audience `sts.amazonaws.com`, 24 h, rotated) + `AWS_ROLE_ARN`/`AWS_WEB_IDENTITY_TOKEN_FILE` → SDK calls `AssumeRoleWithWebIdentity` → STS validates against the cluster's OIDC provider and the trust condition `system:serviceaccount:<ns>:<sa>` → 1-hour creds. Alternatively EKS Pod Identity via the node agent. Never node-role creds for app pods (set IMDS hop limit 1 / block IMDS).

**Q5. S3 consistency model?**
Strong read-after-write consistency for all operations since Dec 2020 (PUT new, overwrite PUT, DELETE, LIST). Earlier it was eventual for overwrites/deletes. So the Terraform state's `use_lockfile` works on plain S3 without DynamoDB.

**Q6. EBS vs EFS vs S3?**
EBS: block, one AZ, one instance (multi-attach only for io1/io2), `ReadWriteOnce` PVs (EBS CSI add-on). EFS: NFS, multi-AZ, many pods `ReadWriteMany`, pay per GB, slower. S3: object storage via API, 11 nines, lifecycle tiers; not a filesystem (Mountpoint for S3 CSI exists). ShopFlow: state in S3, Prometheus on EBS, no EFS.

**Q7. ALB vs NLB vs CLB?**
ALB L7 (path/host, WAF, OIDC, HTTP/2, gRPC, slow start); NLB L4 (static IP, millions RPS, TLS passthrough, PrivateLink); CLB legacy. ShopFlow: ALB with `target-type: ip` via the LB controller.

**Q8. EKS vs ECS vs Lambda for ShopFlow?**
EKS: Kubernetes portability (same manifests on minikube and AWS), ecosystem (Argo CD, ESO, Prometheus), $73/mo. ECS (Fargate): simpler, AWS-native, no control plane cost, but Kustomize/NetworkPolicy/HPA knowledge does not transfer. Lambda: event-driven; a Spring Boot JVM is cold-start-heavy (SnapStart helps); the Kafka consumer could be a Lambda with the MSK event source. Chose EKS because the project's goal is Kubernetes skills and the manifests already exist.

**Q9. VPC peering vs Transit Gateway vs PrivateLink?**
Peering: 1:1, non-transitive, free (data charge), no overlapping CIDRs. TGW: hub-and-spoke, transitive, route tables, ~$0.05/attachment-hour + data; for > ~5 VPCs. PrivateLink: expose one service (NLB) into another VPC as an interface endpoint without routing; overlapping CIDRs OK.

**Q10. Why are NAT gateways expensive and how to reduce it?**
$0.045/h ($32/mo) each + $0.045 per GB processed, both directions. ShopFlow has three. Reduce: single NAT in dev, VPC endpoints (S3 gateway free; interface endpoints for ECR, STS, Secrets Manager, CloudWatch), keep traffic AZ-local, ECR pull-through cache instead of Docker Hub, don't send metrics/logs through NAT.

**Q11. Public vs private subnet, technically?**
Only the route table differs: a subnet is "public" if its route table has `0.0.0.0/0 → igw-...` and instances get public IPs. Private: `0.0.0.0/0 → nat-...`. Database subnets here have no default route at all.

**Q12. Why RDS Multi-AZ rather than a read replica for HA?**
Multi-AZ = synchronous standby + automatic DNS failover (RPO 0, RTO 1–2 min). Read replica = async, manual promotion, data lag → it is for read scaling / DR to another region, not HA.

**Q13. How does RDS failover look from Spring Boot?**
Connection resets for 60–120 s, Hikari drops dead connections, readiness (`db` indicator) flips pods NotReady → ALB stops routing → 503 for ~1–2 min, then recovers. RDS Proxy shrinks it to seconds. Clients retry with `Idempotency-Key`.

**Q14. Why `max_connections=300` and `pending-reboot`?**
Static parameter. 3 services × 10 pods × pool 5 = 150 + headroom. Each connection ≈ 5–10 MB on the DB server; the real fix for high pod counts is a pooler (RDS Proxy / PgBouncer), not a bigger number.

**Q15. ElastiCache cluster mode enabled vs disabled?**
Disabled = one shard, up to 5 replicas, one primary endpoint, simple client. Enabled = sharded across up to 500 nodes, configuration endpoint, cluster-aware client, no multi-key ops across slots. ShopFlow: disabled (tiny dataset, needs HA only).

**Q16. Kafka `min.insync.replicas=2` with RF 3 and `acks=all` — what does it buy?**
A write is acked only when ≥ 2 replicas have it, so one broker/AZ loss loses nothing acked. Two brokers down → writes rejected (CP choice); consumers still read. With `acks=1` you could lose acked data on leader crash.

**Q17. MSK IAM auth vs SASL/SCRAM vs mTLS?**
IAM: no secrets, policy at topic/group level, works with IRSA; needs the `aws-msk-iam-auth` jar (ShopFlow: version 2.3.9 at `runtime` scope plus the `aws` profile in `application-aws.yml`); slightly more CPU per connection. SCRAM: username/password stored in Secrets Manager (KMS CMK required). mTLS: ACM Private CA certs, strongest but cert lifecycle pain. ShopFlow: IAM.

**Q18. How would you monitor consumer lag on MSK?**
CloudWatch `EstimatedMaxTimeLag` / `SumOffsetLag` per group (needs `PER_TOPIC_PER_PARTITION` level), Prometheus JMX exporter on brokers, client `kafka_consumer_fetch_manager_records_lag_max` (alert `KafkaConsumerLagHigh`), and `kafka-consumer-groups.sh --describe`. Lag growing + outbox backlog flat = consumer problem.

**Q19. Cognito: why PKCE for the web client?**
A SPA can't keep a `client_secret`. PKCE binds the authorization code to a one-time `code_verifier` so an intercepted code is useless. `generate_secret = false` + `allowed_oauth_flows = ["code"]` (never implicit).

**Q20. Why are access tokens 15 minutes and refresh tokens 30 days?**
Short access = small window if leaked, no server-side revocation list needed. Refresh token lets the client silently re-auth; it is revocable (`GlobalSignOut`, token revocation endpoint). Trade-off: more token endpoint calls.

**Q21. Secrets Manager vs Parameter Store?**
SM: $0.40/secret, built-in rotation (RDS Lambda), replication, recovery window, larger payload; PS SecureString: free (standard), no rotation, hierarchical paths, great for config. Rule: credentials → SM, config → PS.

**Q22. Terraform state: why S3, why versioning, what is `use_lockfile`?**
Shared, durable, encrypted state; versioning = undo a corrupted state; `use_lockfile = true` (TF 1.10+) writes a `.tflock` object with conditional writes, replacing the DynamoDB table. Never commit `terraform.tfstate` (it contains the DB passwords).

**Q23. How does GitHub Actions authenticate to AWS here?**
OIDC: `id-token: write` → GitHub JWT → `aws-actions/configure-aws-credentials@v6` calls `AssumeRoleWithWebIdentity` on `shopflow-github-actions` (role ARN from the repo variable `AWS_ROLE_ARN`), whose trust requires `aud=sts.amazonaws.com` and `sub=repo:Rajji01/DevopsComplete:ref:refs/heads/main`; then `amazon-ecr-login@v2` and a `docker push` of the SHA tag to ECR. Permissions: ECR push to three repos only. No access keys in repo secrets.

**Q24. Immutable ECR tags: what breaks?**
Re-pushing `:latest` or re-tagging a fixed SHA fails. CI must tag with unique values (git SHA — done via `docker/metadata-action type=sha,format=long`). Benefit: the overlay's tag is cryptographically pinned to content.

**Q25. What is Container Insights vs Managed Prometheus?**
Container Insights: CloudWatch agent collects cluster/node/pod/container metrics and logs with ready dashboards; billed per metric/log GB. AMP: a Prometheus-compatible TSDB you remote_write to; PromQL, alert rules, pay per sample ingested/stored; pairs with AMG. ShopFlow's existing rules port to AMP unchanged.

**Q26. Why Argo CD instead of `kubectl apply` from CI?**
Pull model: cluster creds never leave the cluster; CI needs zero EKS permissions; `selfHeal` reverts drift; `prune` removes deleted objects; audit = Git history; `ignoreDifferences` on `/spec/replicas` because the HPA owns it.

**Q27. Which Terraform resources are "dangerous" to change?**
RDS `identifier`, `engine_version` major, storage type → replacement or long reboot; MSK `number_of_broker_nodes` can only go up; ElastiCache `node_type` → rolling; EKS `name`/`vpc` → replacement; subnet CIDRs → replacement of everything inside. Always read `plan`: a `-/+` on `aws_db_instance` is a stop-the-line event. `deletion_protection` and `prevent_destroy` are the seatbelts.

**Q28. EKS control plane upgrade — downtime?**
No workload downtime; API server may be briefly unavailable (Argo CD/HPA pause). Nodes: rolling, PDB-aware. Risk is removed APIs and incompatible add-ons → test on a dev cluster from the same Terraform with a different `kubernetes_version`.

**Q29. What is a VPC endpoint and which would you add first?**
Private connectivity to AWS services without NAT/IGW. Gateway endpoints (S3, DynamoDB) are free; interface endpoints (PrivateLink ENIs) ~$7/mo + data. First: S3 gateway (ECR layers), then ECR api/dkr and STS (every pod start), then Secrets Manager and CloudWatch Logs.

**Q30. How do you restrict who can reach the EKS API?**
`endpoint_public_access_cidrs` to office/VPN IPs or private-only with a bastion/SSM Session Manager; IAM via access entries; RBAC inside the cluster; CloudTrail + control-plane audit logs.

---

## 18. Production scenarios

**S1. RDS failover at peak (Multi-AZ).** Symptoms: 503 spike on order/inventory, `HighErrorRate` fires, readiness probes failing (`db` DOWN), RDS event "Multi-AZ failover started". Timeline 60–120 s. Actions: confirm in RDS events; do nothing destructive (it is automatic); watch Hikari reconnect; verify `outbox_unpublished` drains; check for stuck pods (`kubectl rollout restart` only if Hikari didn't recover, e.g. DNS cached). Follow-up: RDS Proxy, `networkaddress.cache.ttl`, client retries, run a "reboot with failover" game-day monthly.

**S2. NAT gateway bill spike.** Cost Explorer shows `NatGateway-Bytes` up 10×. Find the talker: VPC Flow Logs (Athena) grouped by source ENI → e.g. notification-service pulling a 300 MB image every restart (CrashLoop), Prometheus remote_write to an external SaaS, or Trivy DB downloads. Fixes: S3 gateway + ECR endpoints, fix the crash loop, keep traffic in-VPC, alarm on `BytesOutToDestination`.

**S3. Pod can't reach RDS.** Checklist in order: (1) `kubectl exec` → `nc -zv <rds host> 5432` (timeout = network, refused = auth/DB). (2) NetworkPolicy: prod overlay egress needs `10.0.0.0/16:5432` — did the patch apply? `kubectl describe netpol order-service-egress`. (3) RDS SG ingress must reference `module.eks.node_security_group_id` — with Karpenter nodes using a different SG this breaks. (4) Subnet routing: DB subnets have no NAT but that's irrelevant for inbound; check NACLs if someone added them. (5) DNS: `nslookup` the RDS endpoint (CoreDNS forwards to VPC resolver). (6) Credentials: ExternalSecret `SecretSynced`? `kubectl get externalsecret shopflow-db` → if `SecretSyncedError`, IRSA issue (S4). (7) `sslmode=require` — RDS enforces TLS by default in PG16 param groups (`rds.force_ssl=1`).

**S4. IRSA not working ("could not load credentials").** Verify: SA annotation `eks.amazonaws.com/role-arn` exact ARN; pod has `AWS_WEB_IDENTITY_TOKEN_FILE` env (if missing → pod created before the SA was annotated or the webhook didn't run → restart pods); the role trust policy `sub` = `system:serviceaccount:shopflow:external-secrets-sa` (namespace typo is the classic); OIDC provider exists for *this* cluster (`module.eks.oidc_provider_arn`); `aws sts get-caller-identity` from inside the pod using the token; `aud` must be `sts.amazonaws.com`; policy attached; region env set; SDK version supports web identity (old Java SDK v1 < 1.11.704 doesn't). Pod Identity equivalent: association exists, agent DaemonSet running.

**S5. MSK broker down.** With RF3/ISR2, producers keep working (ISR shrinks to 2), consumers rebalance partitions whose leader was on the dead broker (seconds). MSK auto-replaces the broker; `UnderReplicatedPartitions > 0` until it catches up. If a second broker fails → `NotEnoughReplicasException` in `OutboxRelay` → rows stay unpublished → `OutboxBacklogGrowing` pages, but **no order data is lost** (outbox is in RDS). Actions: confirm in MSK console/CloudWatch (`ActiveControllerCount`, `OfflinePartitionsCount`), check client bootstrap has all three brokers, do not lower ISR under pressure, backlog drains automatically.

**S6. EKS upgrade 1.33 → 1.34.** Plan: `pluto detect-all-in-cluster`; check add-on compatibility matrix; upgrade dev cluster first (same Terraform, different tfvars); bump `kubernetes_version`, apply (control plane 30 min); apply add-on versions; node group rolling update with `update_config.max_unavailable_percentage = 33`; watch PDB (`minAvailable: 1`) — if HPA is at minReplicas 3 and PDB allows 2 disruptions, drains are quick; Argo CD `selfHeal` keeps manifests in place; verify ALB targets healthy after each node replaces. Rollback: control plane can't downgrade → that's why dev-first matters; node groups can pin the previous AMI release version.

**S7. Leaked AWS access key (e.g. committed to GitHub).** Minute 0–5: deactivate the key (`aws iam update-access-key --status Inactive`), don't delete yet (forensics). 5–30: CloudTrail `lookup-events --lookup-attributes AttributeKey=AccessKeyId` → what did it do? Check for new users/keys/roles, S3 listing, EC2 (crypto mining), SES. Rotate anything it could read (Secrets Manager secret → new DB passwords, ESO refresh, Reloader). Delete created resources. Open an AWS support case if abused (billing). Post-incident: this stack has **no IAM users** — the leak must have been a personal/admin key; enforce OIDC/SSO (IAM Identity Center), `git-secrets`/gitleaks pre-commit + GitHub secret scanning push protection, SCP denying `iam:CreateAccessKey`.

**S8. ALB returns 502s.** 502 = ALB got a bad/no response from the target. Causes in ShopFlow: (1) pod terminated while still registered → add pod readiness gates (`elbv2.k8s.aws/pod-readiness-gate-inject`) and keep `preStop sleep 5` + `terminationGracePeriodSeconds: 45`; (2) target group health check hits `/actuator/health/readiness` but the pod's NetworkPolicy only allows `ingress-nginx` → check the prod overlay's `ipBlock 10.0.0.0/16` ingress patch actually rendered (`kubectl describe netpol order-service-ingress`); (3) app idle timeout < ALB idle timeout (60 s) → set Tomcat keep-alive timeout > 60 s or ALB `idle_timeout.timeout_seconds` lower; (4) response headers too large / HTTP/1.0; (5) JVM start: `startupProbe` 60 s but target registered early → readiness gate again. Debug: ALB access logs (`elb_status_code` vs `target_status_code`), `TargetConnectionErrorCount`, `kubectl get targetgroupbinding`, pod events.

---

## 19. Common mistakes to avoid saying

- "I deployed this to AWS and it handled X TPS" — you didn't apply it. Say "provisioned in Terraform, validated in CI, applied-ready".
- "Security groups are stateless" / "NACLs are stateful" — reversed.
- "Multi-AZ gives read scaling" — the standby is not readable (except Multi-AZ DB *cluster*).
- "IRSA uses the node role" — IRSA exists precisely to avoid that.
- "`:latest` in prod with immutable tags" — impossible by design, and bad anyway.
- "Kafka with RF3 can lose two brokers and still accept writes" — not with `min.insync.replicas=2`.
- "Terraform state has no secrets" — it has the RDS passwords; protect the bucket.
- Forgetting that NetworkPolicies written for `ingress-nginx` must change for ALB `target-type: ip` (the prod overlay adds the VPC-CIDR `ipBlock` for exactly this reason).
