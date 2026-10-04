# ShopFlow on AWS (Terraform)

Everything stateful becomes a managed service; only the three stateless Spring Boot services run on EKS.

```
                         Internet
                            │
                  Route 53 (shop.example.com) → ACM cert
                            │
┌─ VPC 10.0.0.0/16 ─────────┼────────────────────────────────────────────────────────┐
│  public subnets (3 AZ)    ▼                                                        │
│      ALB (AWS Load Balancer Controller ← Ingress)        NAT gateways (1 per AZ)   │
│  ─────────────────────────┼────────────────────────────────────────────────────── │
│  private subnets (3 AZ)   ▼                                                        │
│      EKS managed nodes: order-service / inventory-service / notification-service   │
│          │ 5432          │ 9098 (IAM)        │ 6379            │ HTTPS             │
│  database subnets (3 AZ) ▼                   ▼                 ▼                   │
│      RDS PostgreSQL (Multi-AZ)     MSK (3 brokers)     ElastiCache Redis           │
└────────────────────────────────────────────────────────────────────────────────────┘
   Cognito (JWT issuer)   Secrets Manager (DB passwords → External Secrets)   ECR (images)
```

| File | Creates |
|---|---|
| `vpc.tf` | VPC, 3 public + 3 private + 3 database subnets, NAT per AZ, subnet tags for the LB controller |
| `eks.tf` | EKS cluster, managed node group, IRSA, add-ons (CoreDNS, VPC CNI, EBS CSI, metrics-server) |
| `ecr.tf` | 3 immutable, scan-on-push ECR repositories with a 30-image lifecycle policy |
| `rds.tf` | Multi-AZ PostgreSQL 16, encrypted, 7-day backups, Performance Insights, passwords in Secrets Manager |
| `elasticache.tf` | Redis replication group (primary + replica), TLS + at-rest encryption |
| `msk.tf` | 3-broker Kafka, IAM auth, TLS, `min.insync.replicas=2`, JMX metrics for Prometheus |
| `cognito.tf` | User pool, public app client (PKCE), groups `customer` / `support` |
| `irsa.tf` | IAM roles for External Secrets, Kafka clients and the AWS Load Balancer Controller |
| `ci-oidc.tf` | GitHub Actions → AWS without access keys (OIDC), ECR push only, `main` branch only |

## Apply

```sh
# once: state bucket (versioned, encrypted); then put its name in versions.tf
aws s3 mb s3://shopflow-terraform-state-<unique> --region ap-south-1
aws s3api put-bucket-versioning --bucket shopflow-terraform-state-<unique> --versioning-configuration Status=Enabled

terraform init
terraform plan -out=tfplan      # read it: ~60 resources, nothing destroyed
terraform apply tfplan
terraform output                 # values for k8s/overlays/prod
```

Then, in the cluster:
1. `aws eks update-kubeconfig ...` (see `terraform output configure_kubectl`)
2. Install the AWS Load Balancer Controller and External Secrets Operator (Helm), annotating their ServiceAccounts with the role ARNs from the outputs.
3. Create the databases and users on RDS once (same SQL as `k8s/base/postgres/init-db.sh`, passwords from the `shopflow/prod/db` secret).
4. Put the outputs into `k8s/overlays/prod/kustomization.yaml` and `external-secret.yaml`, point Argo CD at the overlay.

> Cost warning: this stack is roughly **$400–600/month** (EKS control plane $73, 3 × t3.large, Multi-AZ RDS, 3 MSK brokers, 3 NAT gateways). For learning: `db_multi_az=false`, `kafka_instance_type=kafka.t3.small`, `single_nat_gateway=true`, and **`terraform destroy` the same day**.

## What the services still need for MSK IAM auth

The compose/dev setup uses PLAINTEXT Kafka. For MSK with IAM the services need the `aws-msk-iam-auth` library and these properties (left as an exercise, see `docs/interview-notes/09-aws-production.md`):

```yaml
spring.kafka.properties:
  security.protocol: SASL_SSL
  sasl.mechanism: AWS_MSK_IAM
  sasl.jaas.config: software.amazon.msk.auth.iam.IAMLoginModule required;
  sasl.client.callback.handler.class: software.amazon.msk.auth.iam.IAMClientCallbackHandler
```
