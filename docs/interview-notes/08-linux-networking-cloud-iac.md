# 08 — Linux, Networking, AWS, Terraform, Ansible & Git (Interview Notes)

> Repo context: ShopFlow (`shopflow/`) runs on Docker Compose and Kubernetes (`shopflow/k8s/`), images are pushed to GHCR by `.github/workflows/shopflow.yml`.
> **There is no Terraform, Ansible or AWS code in this repo.** The cloud/IaC sections explain the concepts and show how ShopFlow *would* map onto AWS — say "this is how I would provision it", not "I did".

---

## PART A — LINUX ESSENTIALS

### A1. Processes

- Every process has a **PID**, parent (**PPID**), owner (UID), state, priority (nice).
- PID 1 in a container = your app (ShopFlow: `java` via exec-form ENTRYPOINT). PID 1 has special signal handling: the kernel doesn't apply default actions for signals it hasn't registered handlers for → a shell script as PID 1 may ignore SIGTERM. JVM registers handlers, so it's fine. Use `tini`/`--init` for shell-based entrypoints that spawn children (zombie reaping).
- States: `R` running, `S` sleeping (interruptible), `D` uninterruptible (usually I/O — high D count = disk/NFS trouble), `Z` zombie (exited, parent didn't `wait()`), `T` stopped.
- Foreground/background: `cmd &`, `jobs`, `fg`, `bg`, `nohup cmd &`, `disown`.

```bash
ps aux | grep java                    # all processes, user, %CPU, %MEM, RSS
ps -ef --forest                       # parent/child tree
pgrep -f JarLauncher ; pkill -f JarLauncher
top / htop                            # live; in top: P (cpu), M (mem), 1 (per-core), k (kill)
nice -n 10 cmd ; renice +5 -p <pid>
```

Reading `top` header: `load average: 2.10, 1.50, 0.90` = runnable + D-state processes averaged over 1/5/15 min. Compare to CPU core count (4 cores → 4.0 = fully busy). `%Cpu: us sy ni id wa hi si st` — high `wa` = waiting on I/O, high `st` = noisy neighbour on a VM (steal).

### A2. Signals

| Signal | No. | Meaning | Catchable? |
|---|---|---|---|
| SIGHUP | 1 | Terminal closed / often "reload config" (nginx) | Yes |
| SIGINT | 2 | Ctrl+C | Yes |
| SIGQUIT | 3 | Ctrl+\ ; **JVM prints thread dump** | Yes |
| SIGKILL | 9 | Kill immediately | **No** |
| SIGTERM | 15 | Polite stop (default of `kill`, `docker stop`, K8s) | Yes |
| SIGSTOP/SIGCONT | 19/18 | Pause/resume | STOP no |

Exit code after a signal = 128 + N → **137** (SIGKILL, e.g. OOMKilled), **143** (SIGTERM).
K8s flow: preStop → SIGTERM → wait `terminationGracePeriodSeconds` (ShopFlow 45s) → SIGKILL.
```bash
kill <pid>            # SIGTERM
kill -9 <pid>         # SIGKILL (last resort — no cleanup, no graceful shutdown)
kill -3 <pid>         # Java thread dump to stdout (or: jcmd <pid> Thread.print)
trap 'echo cleaning; exit' TERM INT    # handle signals in bash
```

### A3. systemd

```bash
systemctl status|start|stop|restart|reload nginx
systemctl enable --now docker         # start at boot + now
systemctl list-units --failed
systemctl daemon-reload               # after editing unit files
journalctl -u docker -f               # follow a unit's logs
journalctl -u kubelet --since "10 min ago" -p err
journalctl -b -1                      # previous boot
```
Running a Spring Boot jar as a service (on a VM, without containers):
```ini
# /etc/systemd/system/order-service.service
[Unit]
Description=ShopFlow order-service
After=network-online.target
[Service]
User=app
Environment=JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75
EnvironmentFile=/etc/shopflow/order.env
ExecStart=/usr/bin/java -jar /opt/shopflow/app.jar
Restart=on-failure
TimeoutStopSec=45
MemoryMax=512M
[Install]
WantedBy=multi-user.target
```
(`Restart=on-failure` ≈ Docker `restart: unless-stopped` ≈ K8s restartPolicy; `MemoryMax` is a cgroup limit like a container limit.)

### A4. File permissions & ownership

```
-rwxr-x---  1 app app  1234  init-db.sh
│└┬┘└┬┘└┬┘     owner group
│ u  g  o      r=4 w=2 x=1  → rwx r-x --- = 750
└ type: - file, d dir, l symlink
```
```bash
chmod 750 init-db.sh ; chmod +x mvnw ; chmod -R g+w dir
chown app:app file ; chown -R 10001:10001 /data
umask 022                          # default new file 644, dir 755
id ; whoami ; groups
sudo -u app cmd
```
- For directories: `r` list, `w` create/delete entries, `x` enter (cd).
- Special bits: setuid (`4xxx`, runs as file owner — dangerous), setgid (`2xxx`, new files inherit group), sticky (`1xxx`, `/tmp`: only owner deletes).
- Classic CI issue: `./mvnw: Permission denied` → `git update-index --chmod=+x mvnw` (Git tracks the exec bit).
- Containers: ShopFlow runs as UID 10001; K8s `fsGroup: 70` on Postgres makes the volume group-writable for the postgres user. "Permission denied" on a volume with non-root containers → fix with `fsGroup` / `chown` init, not by running as root.

### A5. Disk, memory, network, files — the troubleshooting toolkit

| Question | Command |
|---|---|
| Which disk is full? | `df -h` ; inodes: `df -i` |
| What is using space? | `du -sh /var/* \| sort -h` ; `du -xh / --max-depth=1` ; `ncdu` |
| Deleted file still holding space? | `lsof +L1` (or `lsof \| grep deleted`) → restart the process |
| Memory | `free -h` — look at **available**, not "free" (Linux uses spare RAM as page cache) |
| Memory per process | `ps aux --sort=-rss \| head` ; `pmap -x <pid>` |
| OOM killer events | `dmesg -T \| grep -i -E 'killed process\|oom'` ; `journalctl -k` |
| CPU / IO | `top`, `htop`, `vmstat 1`, `iostat -xz 1`, `mpstat -P ALL 1`, `pidstat 1` |
| Listening ports | `ss -tulpn` (modern) ; `netstat -tulpn` (old) |
| Who's connected | `ss -tan state established '( dport = :5432 )'` |
| Which process owns port 8081 | `ss -lptn 'sport = :8081'` ; `lsof -i :8081` ; `fuser 8081/tcp` |
| Files opened by a process | `lsof -p <pid>` ; `ls -l /proc/<pid>/fd \| wc -l` (fd leaks → "Too many open files"; `ulimit -n`) |
| HTTP test | `curl -v http://localhost:8081/actuator/health` ; `curl -o /dev/null -s -w '%{http_code} %{time_total}\n' URL` |
| DNS | `dig +short order-service.shopflow.svc.cluster.local` ; `nslookup` ; `cat /etc/resolv.conf` |
| Route / reachability | `ip a` ; `ip r` ; `ping` ; `traceroute`/`mtr` ; `nc -zv postgres 5432` ; `telnet host port` |
| Packets | `tcpdump -i any port 5432 -nn` |
| System info | `uname -a` ; `uptime` ; `lscpu` ; `cat /etc/os-release` ; `hostnamectl` |

`ss -tulpn` flags: **t**cp, **u**dp, **l**istening, **p**rocess, **n**umeric.

### A6. Text processing: grep, awk, sed, and friends

```bash
grep -rn "ERROR" logs/                 # recursive with line numbers
grep -i -v "health" access.log         # case-insensitive, invert
grep -E "5[0-9]{2}" access.log         # extended regex
grep -c "Exception" app.log            # count
grep -A3 -B2 "OutOfMemory" app.log     # context lines
zgrep "ERROR" app.log.gz

awk '{print $1}' access.log | sort | uniq -c | sort -rn | head    # top client IPs
awk '$9 >= 500 {print $7}' access.log | sort | uniq -c | sort -rn # URLs returning 5xx (nginx combined log: $9=status, $7=path)
awk -F, '{sum += $3} END {print sum}' data.csv                    # sum a CSV column
awk '/ERROR/ {c++} END {print c}' app.log

sed -n '100,120p' app.log              # print lines 100-120
sed -i 's/newTag: latest/newTag: 3f9c2ab/' kustomization.yaml     # in-place replace
sed '/^#/d' file                        # delete comment lines

cut -d: -f1 /etc/passwd ; sort -u ; uniq -c ; wc -l ; head -n 50 ; tail -f app.log ; tail -n 200
xargs: find . -name "*.log" -mtime +7 | xargs rm -f
find /var/log -name "*.gz" -mtime +30 -delete
jq '.["log.level"]' logs.json ; kubectl get pods -o json | jq -r '.items[].metadata.name'
```
ECS JSON logs (ShopFlow in K8s) are perfect for `jq`:
```bash
kubectl logs deploy/order-service -n shopflow | jq -r 'select(."log.level"=="ERROR") | .message'
```

### A7. cron

```
┌──── minute (0-59)
│ ┌──── hour (0-23)
│ │ ┌──── day of month (1-31)
│ │ │ ┌──── month (1-12)
│ │ │ │ ┌──── day of week (0-6, Sun=0)
* * * * *  command
```
| Expr | Meaning |
|---|---|
| `* * * * *` | every minute (ShopFlow learning `Devops/cron-job.yaml`) |
| `*/5 * * * *` | every 5 minutes |
| `0 2 * * *` | 02:00 daily |
| `30 9 * * 1-5` | 09:30 weekdays |
| `0 0 1 * *` | midnight on the 1st of each month |

```bash
crontab -e ; crontab -l
0 2 * * * /opt/scripts/pg_backup.sh >> /var/log/pg_backup.log 2>&1
```
Gotchas: cron has a minimal `PATH` and no profile → use absolute paths; time zone is the server's (K8s CronJob default UTC; `spec.timeZone` available); redirect output or you'll never see errors; overlapping runs → `flock` (K8s: `concurrencyPolicy: Forbid`).

### A8. Bash scripting basics

Key rules: `set -euo pipefail` (exit on error, unset vars are errors, fail pipelines), quote variables `"$var"`, use `[[ ]]` for tests, `$(cmd)` for substitution, check exit codes `$?`.

Example — a health-check/smoke-test script for the ShopFlow compose stack:
```bash
#!/usr/bin/env bash
# smoke.sh — wait until ShopFlow services are ready, then hit the APIs
set -euo pipefail

BASE_ORDER="${BASE_ORDER:-http://localhost:8081}"
BASE_INV="${BASE_INV:-http://localhost:8082}"
TIMEOUT="${TIMEOUT:-90}"

wait_ready() {
  local name="$1" url="$2" waited=0
  until curl -fsS "$url/actuator/health/readiness" > /dev/null; do
    if (( waited >= TIMEOUT )); then
      echo "ERROR: $name not ready after ${TIMEOUT}s" >&2
      return 1
    fi
    sleep 3; waited=$(( waited + 3 ))
  done
  echo "OK: $name ready in ${waited}s"
}

wait_ready inventory-service "$BASE_INV"
wait_ready order-service "$BASE_ORDER"

status=$(curl -s -o /dev/null -w '%{http_code}' "$BASE_INV/api/v1/products")
if [[ "$status" != "200" ]]; then
  echo "ERROR: GET /api/v1/products returned $status" >&2
  exit 1
fi
echo "Smoke test passed"
```
Other must-knows: `for f in *.yaml; do ...; done` (the CI loop `for overlay in k8s/overlays/*/; do ... done` in `shopflow.yml` is the same idea), `while read -r line; do ...; done < file`, `case`, functions, `$1 $@ $# $?`, `>`, `>>`, `2>&1`, `/dev/null`, here-docs (`init-db.sh` uses `<<-EOSQL`), `exit` codes, `shellcheck` for linting.

`init-db.sh` (ShopFlow) explained: `#!/bin/sh`, `set -e` (stop on first error), `psql -v ON_ERROR_STOP=1 ... <<-EOSQL` heredoc with `${ORDERS_DB_PASSWORD}` expanded from env vars injected from the K8s Secret.

---

## PART B — NETWORKING

### B1. OSI vs TCP/IP

| OSI | TCP/IP | Examples | DevOps relevance |
|---|---|---|---|
| 7 Application | Application | HTTP, DNS, gRPC, TLS* | Ingress (L7), ALB, API gateways |
| 6 Presentation | | TLS*, encoding | |
| 5 Session | | | |
| 4 Transport | Transport | TCP, UDP (ports) | NLB, kube-proxy, Security Groups |
| 3 Network | Internet | IP, ICMP, routing | VPC, subnets, route tables, NetworkPolicy (L3/L4) |
| 2 Data link | Link | Ethernet, ARP, MAC | |
| 1 Physical | | cables, Wi-Fi | |

### B2. TCP vs UDP; the handshake

| TCP | UDP |
|---|---|
| Connection-oriented, reliable, ordered, flow/congestion control | Connectionless, no guarantees, low overhead |
| HTTP/1.1, HTTP/2, Postgres (5432), SSH | DNS (mostly), video, QUIC/HTTP3 runs on UDP |

```
Client                        Server
  │ ── SYN (seq=x) ───────────▶ │
  │ ◀── SYN-ACK (seq=y,ack=x+1) │
  │ ── ACK (ack=y+1) ─────────▶ │      connection ESTABLISHED
  │  ... data ...               │
  │ ── FIN ───────────────────▶ │      4-way close: FIN, ACK, FIN, ACK
  │ ◀── ACK / FIN ───────────── │      closing side ends in TIME_WAIT (~60s)
```
Symptoms: connection **refused** (RST: nothing listening on port — app down or wrong port) vs connection **timeout** (no reply: firewall/SG/NetworkPolicy dropping, wrong IP, route). That distinction narrows down debugging fast. ShopFlow's `connect-timeout: 1s` / `read-timeout: 2s` for inventory are exactly these two phases.

### B3. DNS resolution

```
browser cache → OS cache (/etc/hosts, nsswitch) → recursive resolver (ISP/8.8.8.8/VPC resolver)
   → root (.) → TLD (.com) → authoritative NS for example.com → A/AAAA record → cached for TTL
```
Records: **A** (IPv4), **AAAA** (IPv6), **CNAME** (alias to name), **ALIAS/Route53 alias** (apex → ALB), **MX**, **TXT** (SPF, domain verification, ACME DNS-01), **NS**, **SRV**, **PTR** (reverse).
Inside Kubernetes: `/etc/resolv.conf` points to CoreDNS with search domains `shopflow.svc.cluster.local svc.cluster.local cluster.local` → that's why `http://inventory-service:8082` works. `ndots:5` means short external names are tried with each search suffix first (extra lookups) — FQDN with trailing dot avoids it.
Local dev: ShopFlow Ingress uses host `shopflow.local` → add `<minikube ip> shopflow.local` to `/etc/hosts`.

### B4. HTTP, HTTPS, TLS

- HTTP methods (GET/POST/PUT/PATCH/DELETE), idempotency (GET/PUT/DELETE idempotent; POST not — ShopFlow adds an `Idempotency-Key` header for POST /orders).
- Status codes: 2xx success, 3xx redirect, 4xx client error (400, 401 unauthenticated, 403 forbidden, 404, 409 conflict, 429 rate-limited), 5xx server (500 bug, **502** bad gateway — proxy got invalid/no response from upstream, **503** unavailable — no healthy backends/overloaded, **504** gateway timeout — upstream too slow).
- HTTP/1.1 keep-alive; HTTP/2 multiplexing over one TCP connection; HTTP/3 over QUIC/UDP.

TLS 1.3 handshake (simplified):
```
ClientHello (supported ciphers, key share, SNI=shop.example.com)
   ◀── ServerHello (chosen cipher, key share) + Certificate + Finished
Client verifies certificate chain → trusted CA, hostname matches SAN, not expired
   ──▶ Finished ; both derive symmetric session keys → encrypted application data
```
- Asymmetric crypto for key exchange/authentication, **symmetric** (AES-GCM/ChaCha20) for data.
- **SNI** lets one IP/LB serve many certs. **mTLS** = client also presents a cert (service mesh).
- **TLS termination**: ShopFlow prod terminates TLS at the Ingress (`tls: secretName: shopflow-tls`, cert from **cert-manager** via Let's Encrypt ACME); traffic inside the cluster is plain HTTP. On AWS, terminate at ALB with an ACM certificate.
- Debug: `openssl s_client -connect shop.example.com:443 -servername shop.example.com`, `curl -vI https://...`, check expiry: `| openssl x509 -noout -dates`.

### B5. Load balancers: L4 vs L7

| | L4 (TCP/UDP) | L7 (HTTP) |
|---|---|---|
| Sees | IPs + ports | URLs, headers, cookies, host |
| Can do | Forward connections, very fast, static IPs, any protocol | Path/host routing, TLS termination, header rewrites, WAF, sticky sessions, retries |
| AWS | **NLB** | **ALB** |
| K8s | Service (kube-proxy), Service `type: LoadBalancer` | Ingress (nginx), Gateway API |

Algorithms: round robin, least connections, weighted (canary), IP hash/consistent hashing. Health checks remove bad targets (= readiness in K8s).

### B6. IP addressing, CIDR, subnets

```
10.0.0.0/16  → 16 network bits, 2^(32-16) = 65,536 addresses   (VPC)
10.0.1.0/24  → 256 addresses                                    (subnet)
10.0.1.0/28  → 16 addresses
AWS reserves 5 IPs per subnet → /24 gives 251 usable
```
Private ranges (RFC1918): `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`. Kubernetes uses separate CIDRs for pods and services (e.g., `10.96.0.0/12` for services in kubeadm) — they must not overlap with the VPC/VPN ranges. On EKS with the AWS VPC CNI, **pods get real VPC IPs** → subnet sizing matters (use /19 or larger for node subnets, or prefix delegation).

### B7. NAT, proxies

- **NAT**: rewrites source IPs. **SNAT/masquerade** lets private hosts reach the internet with a shared public IP (AWS **NAT Gateway** for private subnets). **DNAT/port-forward**: `docker run -p 8080:80` is DNAT on the host via iptables.
- **Forward proxy**: client-side, controls outbound access (corporate proxy; this very environment uses an HTTPS proxy).
- **Reverse proxy**: server-side, in front of apps — nginx, ingress-nginx, ALB, API gateway: TLS termination, routing, rate limiting, caching.
- `X-Forwarded-For` / `X-Forwarded-Proto` carry the original client IP/scheme through proxies (Spring: `server.forward-headers-strategy=framework`).

### B8. "What happens when you type `https://shop.example.com/api/v1/products` in the browser?" (ShopFlow edition)

```
1. URL parsed; HSTS check → https
2. DNS: browser/OS cache → resolver → Route 53 → ALIAS → ALB/NLB IPs
3. TCP 3-way handshake to the LB on 443
4. TLS handshake (SNI shop.example.com) → cert (ACM at ALB, or cert-manager's shopflow-tls at nginx)
5. HTTP GET /api/v1/products, Host: shop.example.com
6. Load balancer → ingress-nginx pod (in cluster) → matches Ingress host + path prefix /api/v1/products
7. → Service inventory-service (ClusterIP) → kube-proxy/iptables picks a Ready pod IP:8082
   (NetworkPolicy inventory-service-ingress allows ingress-nginx namespace)
8. Tomcat thread → Spring DispatcherServlet → ProductController → JPA → Hikari connection
   → postgres:5432 (headless svc → postgres-0; NetworkPolicy allows only the two services)
9. JSON response ← back the same path; metrics recorded in http_server_requests_seconds{uri="/api/v1/products"}
10. Browser renders; connection kept alive for reuse
```
> Tip: Ye answer apne project ke saath do — generic answer sab dete hain, ShopFlow wala answer yaad rahega.

---

## PART C — AWS CORE FOR DEVOPS

### C1. Services cheat table

| Service | What | DevOps notes |
|---|---|---|
| **VPC** | Your private network (CIDR, e.g. 10.0.0.0/16) in a region | Span 2–3 AZs |
| **Subnets** | CIDR slice in one AZ. **Public** = route `0.0.0.0/0 → Internet Gateway`. **Private** = route `0.0.0.0/0 → NAT Gateway` (outbound only) | LBs in public, nodes/DBs in private |
| **IGW / NAT GW** | Internet access in/out / outbound-only for private subnets | NAT GW per AZ for HA (costly) |
| **Route tables** | Per subnet routing | |
| **Security Group** | Stateful firewall on ENIs/instances, **allow rules only**, can reference other SGs | "DB SG allows 5432 from app SG" |
| **NACL** | Stateless subnet-level firewall, allow + deny, numbered rules, need return-traffic (ephemeral ports) rules | Coarse blocking (deny an IP range) |
| **EC2** | VMs; AMI, instance types, EBS volumes, user data, ASG | EKS worker nodes are EC2 (or Fargate) |
| **ELB: ALB / NLB** | L7 / L4 load balancers | AWS Load Balancer Controller creates them from Ingress/Service |
| **EKS** | Managed Kubernetes control plane | Managed node groups, Karpenter, IRSA/Pod Identity, add-ons (VPC CNI, CoreDNS, EBS CSI) |
| **ECR** | Container registry | Image scanning, lifecycle policies, immutable tags option |
| **RDS / Aurora** | Managed Postgres/MySQL | Multi-AZ, automated backups, PITR, read replicas |
| **S3** | Object storage, 11 nines durability | Terraform state, artifacts, log archive, static sites |
| **IAM** | Users, groups, **roles**, policies | Prefer roles + temporary creds; least privilege |
| **Secrets Manager / SSM Parameter Store** | Secrets & config | Rotation; ESO syncs into K8s |
| **CloudWatch** | Metrics, logs, alarms, dashboards; Container Insights | AMP/AMG = managed Prometheus/Grafana |
| **Route 53** | DNS, health checks, routing policies (weighted/failover/latency) | Alias to ALB |
| **ACM** | Free TLS certs for ALB/CloudFront | Auto renewal |
| **KMS** | Encryption keys | EKS secret envelope encryption, RDS/S3/EBS encryption |
| **CloudTrail** | API audit log | "Who deleted that?" |

### C2. Security Group vs NACL

| | Security Group | NACL |
|---|---|---|
| Level | Instance / ENI | Subnet |
| State | **Stateful** (return traffic auto-allowed) | **Stateless** (must allow return, ephemeral 1024–65535) |
| Rules | Allow only | Allow + Deny, evaluated in number order |
| Reference | Other SGs as source | CIDRs only |
| Default | Deny all in, allow all out | Default NACL allows all |

### C3. IAM essentials
- **Policy** = JSON document: `Effect`, `Action`, `Resource`, `Condition`. Explicit **Deny** always wins; default is implicit deny.
- **Identity-based** (attached to user/role) vs **resource-based** (S3 bucket policy, ECR repo policy, KMS key policy).
- **Role** = identity with temporary credentials assumed via STS by EC2 (instance profile), Lambda, EKS pods (IRSA/Pod Identity), GitHub Actions (OIDC), other accounts.
- Least privilege example — CI may push only to ShopFlow repos:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": "ecr:GetAuthorizationToken", "Resource": "*" },
    { "Effect": "Allow",
      "Action": ["ecr:BatchCheckLayerAvailability","ecr:InitiateLayerUpload","ecr:UploadLayerPart",
                 "ecr:CompleteLayerUpload","ecr:PutImage","ecr:BatchGetImage"],
      "Resource": "arn:aws:ecr:ap-south-1:123456789012:repository/shopflow-*" }
  ]
}
```
- No long-lived access keys on servers or in CI; MFA for humans; SSO (IAM Identity Center).

### C4. How ShopFlow would map onto AWS

```
                       Route 53: shop.example.com (ALIAS)
                                   │
 ┌──────────────────────────── VPC 10.0.0.0/16  (ap-south-1, 3 AZs) ─────────────────────────────┐
 │  Public subnets (10.0.0.0/20 ×3)   ── IGW                                                     │
 │     ALB (ACM cert, WAF)  ◀── created by AWS Load Balancer Controller from the Ingress         │
 │     NAT Gateways                                                                              │
 │  ─────────────────────────────────────────────────────────────────────────────────────────── │
 │  Private app subnets (10.0.32.0/19 ×3)                                                        │
 │     EKS managed node group / Karpenter nodes                                                  │
 │       namespace shopflow: order-service, inventory-service (HPA, PDB, NetworkPolicies)        │
 │       External Secrets Operator ── IRSA ──▶ Secrets Manager (shopflow-db passwords)           │
 │       kube-prometheus-stack / Amazon Managed Prometheus, Fluent Bit → CloudWatch Logs         │
 │  ─────────────────────────────────────────────────────────────────────────────────────────── │
 │  Private DB subnets (10.0.128.0/24 ×3)                                                        │
 │     RDS PostgreSQL Multi-AZ  (databases "orders" + "inventory"; SG allows 5432 from node SG)  │
 └───────────────────────────────────────────────────────────────────────────────────────────────┘
 ECR: shopflow-order-service, shopflow-inventory-service  ◀── GitHub Actions via OIDC role
 S3 + DynamoDB: Terraform state      CloudWatch alarms / AMP alert rules = monitoring/alert-rules.yml
```
| ShopFlow piece today | AWS equivalent |
|---|---|
| GHCR images | ECR (or keep GHCR with pull secret) |
| Postgres StatefulSet (`postgres/statefulset.yaml`, "demo DB") | **RDS PostgreSQL Multi-AZ** — change `DB_URL` to the RDS endpoint, drop the StatefulSet from the prod overlay |
| ingress-nginx + cert-manager | AWS Load Balancer Controller + ALB + ACM (or keep nginx behind an NLB) |
| `shopflow-db` Secret via ESO (prod overlay comment) | Secrets Manager + ESO with IRSA |
| HPA | HPA + Cluster Autoscaler/Karpenter for nodes |
| `topologySpreadConstraints` on hostname | add `topology.kubernetes.io/zone` key for AZ spread |
| Prometheus/Grafana in compose | Amazon Managed Prometheus + Managed Grafana, or kube-prometheus-stack |
| ECS JSON logs on stdout | Fluent Bit DaemonSet → CloudWatch Logs / OpenSearch |
| NetworkPolicies (need Calico/Cilium) | VPC CNI network policy support or Calico; plus SGs (Security Groups for Pods) |

Alternative simpler target: **ECS Fargate** (no Kubernetes to manage) — task definitions per service, ALB, RDS.

---

## PART D — TERRAFORM (IaC)

### D1. Core concepts
- **Declarative** IaC: describe desired infra in HCL; Terraform computes a plan to reach it.
- **Providers** (aws, kubernetes, helm, github), **resources** (things to create), **data sources** (read existing things), **variables**, **outputs**, **locals**, **modules**.
- Workflow:
```bash
terraform init        # download providers/modules, configure backend
terraform fmt -check ; terraform validate
terraform plan -out=tfplan     # diff desired vs state vs real world
terraform apply tfplan
terraform destroy
terraform state list | show <addr> | mv | rm ; terraform import <addr> <id>
```

### D2. State
- `terraform.tfstate` maps your config to real resource IDs + attributes. Terraform uses it to know what it manages.
- Contains **secrets in plain text** (e.g., DB passwords) → never commit it; encrypt the backend.
- **Remote backend + locking** for teams: S3 bucket (versioned, encrypted) + locking. Two people running `apply` simultaneously would corrupt state; the lock prevents it.
```hcl
terraform {
  required_version = ">= 1.10"
  backend "s3" {
    bucket       = "shopflow-tfstate-123456789012"
    key          = "prod/ecr/terraform.tfstate"
    region       = "ap-south-1"
    encrypt      = true
    use_lockfile = true      # native S3 locking (TF 1.10+); older setups use dynamodb_table = "tf-locks"
  }
  required_providers {
    aws = { source = "hashicorp/aws", version = "~> 6.0" }
  }
}
```
- `terraform force-unlock <id>` only if a lock is stale (crashed run) — make sure nobody is applying.

### D3. Small example — ECR repositories for ShopFlow
```hcl
provider "aws" {
  region = var.region
  default_tags { tags = { project = "shopflow", managed_by = "terraform" } }
}

variable "region" {
  type    = string
  default = "ap-south-1"
}

variable "services" {
  type    = set(string)
  default = ["order-service", "inventory-service"]
}

resource "aws_ecr_repository" "svc" {
  for_each             = var.services
  name                 = "shopflow-${each.key}"          # mirrors ghcr.io/<owner>/shopflow-<service>
  image_tag_mutability = "IMMUTABLE"                      # a SHA tag can never be overwritten
  image_scanning_configuration { scan_on_push = true }
  encryption_configuration { encryption_type = "KMS" }
}

resource "aws_ecr_lifecycle_policy" "svc" {
  for_each   = aws_ecr_repository.svc
  repository = each.value.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "keep last 30 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 30 }
      action       = { type = "expire" }
    }]
  })
}

output "repository_urls" {
  value = { for k, r in aws_ecr_repository.svc : k => r.repository_url }
}
```
Note: `IMMUTABLE` tags conflict with pushing a moving `latest` tag (which `shopflow.yml` does on main) — you'd drop `latest` or use mutable repos. Good trade-off discussion.

### D4. Modules, workspaces, environments
- **Module** = reusable folder of `.tf` with inputs/outputs. Use registry modules (`terraform-aws-modules/vpc/aws`, `terraform-aws-modules/eks/aws`) or your own:
```hcl
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 6.0"
  name = "shopflow-prod"
  cidr = "10.0.0.0/16"
  azs             = ["ap-south-1a", "ap-south-1b", "ap-south-1c"]
  public_subnets  = ["10.0.0.0/20", "10.0.16.0/20", "10.0.240.0/20"]
  private_subnets = ["10.0.32.0/19", "10.0.64.0/19", "10.0.96.0/19"]
  enable_nat_gateway = true
}
```
  Always **pin module and provider versions**.
- **Workspaces**: multiple state files for the same config (`terraform workspace new staging`). Fine for identical envs; many teams prefer **separate directories per env** (`envs/dev`, `envs/prod`) with different backends — clearer, different blast radius, different credentials. (Same idea as ShopFlow's Kustomize `overlays/dev` vs `overlays/prod`.)
- **Drift**: real infra changed outside Terraform (someone edited an SG in the console). `terraform plan` shows it; `terraform plan -refresh-only` to see/accept drift. Prevent with no console write access, scheduled drift-detection plans in CI.
- `count` vs `for_each` (prefer `for_each` with maps/sets — removing an item doesn't shift indexes), `lifecycle { prevent_destroy = true, create_before_destroy = true, ignore_changes = [...] }`, `depends_on`.
- CI for Terraform: `fmt -check`, `validate`, `tflint`, `checkov`/`trivy config`, `plan` on PR (post as comment), `apply` on merge with approval — Atlantis or Terraform Cloud/HCP. Use OIDC for AWS creds.

Terraform vs others: CloudFormation (AWS-only, managed state), Pulumi (real languages), CDK; OpenTofu = open-source fork of Terraform.

---

## PART E — ANSIBLE (brief)

- **Configuration management** over SSH, **agentless**, push-based, YAML playbooks, idempotent modules.
- Inventory (hosts/groups) → playbook (plays → tasks → modules) → roles (reusable structure), variables, handlers (run on change, e.g. restart), Jinja2 templates, Ansible Vault for secrets.
```yaml
# site.yml — install Docker and run ShopFlow compose on a VM
- hosts: app_servers
  become: true
  tasks:
    - name: Install docker
      ansible.builtin.package: { name: docker.io, state: present }
    - name: Ensure docker running
      ansible.builtin.service: { name: docker, state: started, enabled: true }
    - name: Copy compose stack
      ansible.builtin.copy: { src: shopflow/, dest: /opt/shopflow/ }
      notify: restart shopflow
  handlers:
    - name: restart shopflow
      ansible.builtin.command: docker compose up -d --build
      args: { chdir: /opt/shopflow }
```
```bash
ansible all -i inventory.ini -m ping
ansible-playbook -i inventory.ini site.yml --check --diff
```
Terraform vs Ansible: Terraform **provisions** infrastructure (VPC, EKS, RDS) and tracks state; Ansible **configures** machines (packages, files, services). With Kubernetes + immutable images, Ansible is less needed for apps but common for VMs, on-prem, bootstrapping.

---

## PART F — GIT

| Command | What it does | Rewrites history? |
|---|---|---|
| `git merge feature` | Creates a merge commit joining histories (or fast-forward) | No |
| `git rebase main` | Replays your commits on top of main → linear history | **Yes** (new SHAs) |
| `git cherry-pick <sha>` | Copies one commit onto current branch (e.g., hotfix to release branch) | No (new commit) |
| `git revert <sha>` | New commit that undoes a commit | No — **safe for shared branches** (GitOps rollback!) |
| `git reset --soft <sha>` | Move HEAD, keep changes staged | Yes (local) |
| `git reset --mixed <sha>` (default) | Move HEAD, keep changes unstaged | Yes |
| `git reset --hard <sha>` | Move HEAD, discard changes | Yes — dangerous |
| `git stash` / `stash pop` | Shelve uncommitted work | — |
| `git reflog` | History of HEAD moves → recover "lost" commits after bad reset/rebase | — |

```
merge:                         rebase:
A─B─C─────M  (main)            A─B─C─D'─E'  (feature rebased on main)
     \   /
      D─E    (feature)
```
- **Golden rule**: don't rebase/force-push shared branches. On your own feature branch before a PR, `git rebase -i main` to squash/clean is fine (`git push --force-with-lease`, never plain `--force`).
- Squash merge = one commit per PR on main (clean trunk-based history).
- Revert vs reset: on `main` (shared), always **revert**; reset is for local, unpushed work.
- Others: `git bisect` (binary search for the commit that broke something), `git blame`, `git log --oneline --graph --decorate`, `git diff main...feature`, tags for releases (`git tag -a v1.0.0`), `.gitignore` (this repo ignores `target/` etc.), `git update-index --chmod=+x mvnw`.
- Merge conflicts: `git status` → edit markers `<<<<<<< ======= >>>>>>>` → `git add` → `git rebase --continue` / commit.

---

## PART G — INTERVIEW Q&A

**Q1. How do you find what's using port 8081 on a Linux box?**
`ss -lptn 'sport = :8081'` or `lsof -i :8081`; then `ps -fp <pid>`.

**Q2. Disk is 100% full on a server — steps?**
`df -h` to find the filesystem (and `df -i` for inodes), `du -xh --max-depth=1 / | sort -h` to drill down, usual suspects: logs, Docker images/volumes (`docker system df`), core dumps. If `du` doesn't add up, `lsof +L1` for deleted-but-open files and restart that process. Then fix the cause: log rotation, retention, alerts on disk usage.

**Q3. Difference between SIGTERM and SIGKILL? Why does it matter for Kubernetes?**
SIGTERM can be caught for graceful shutdown; SIGKILL can't. K8s sends SIGTERM (after preStop), waits the grace period, then SIGKILL. ShopFlow's exec-form ENTRYPOINT ensures Java receives SIGTERM and Spring finishes in-flight requests.

**Q4. Free memory looks very low — is the server in trouble?**
Probably not — Linux uses free RAM as page cache. Look at the `available` column in `free -h`, swap activity (`vmstat si/so`), and OOM killer messages in `dmesg`.

**Q5. Explain the TCP handshake and what "connection refused" vs "timeout" means.**
SYN, SYN-ACK, ACK. Refused = host reachable but nothing listening (RST) → app down or wrong port. Timeout = packets dropped → firewall/SG/NetworkPolicy/routing.

**Q6. What happens when you type a URL in the browser?**
(§B8 — DNS, TCP, TLS, HTTP, LB, ingress, service, pod, DB, response.)

**Q7. L4 vs L7 load balancer?**
L4 routes TCP/UDP by IP:port, fast, protocol-agnostic (NLB). L7 understands HTTP — host/path routing, TLS termination, headers, WAF (ALB, nginx Ingress). ShopFlow's Ingress is L7 path routing to two services.

**Q8. Security Group vs NACL?**
SGs are stateful, instance-level, allow-only and can reference other SGs; NACLs are stateless, subnet-level, ordered allow/deny rules. I use SGs for app-level rules (DB accepts 5432 only from the app SG), NACLs rarely, for broad denies.

**Q9. Public vs private subnet?**
A public subnet's route table sends `0.0.0.0/0` to an Internet Gateway; private sends it to a NAT Gateway (outbound only). Load balancers in public, EKS nodes and RDS in private.

**Q10. How would you deploy ShopFlow on AWS?**
Terraform for VPC (3 AZs), EKS, RDS Postgres Multi-AZ, ECR; GitHub Actions pushes images to ECR via OIDC; Argo CD applies the Kustomize prod overlay; ALB via AWS Load Balancer Controller with ACM; ESO with IRSA pulls DB passwords from Secrets Manager; managed Prometheus/Grafana and Fluent Bit to CloudWatch.

**Q11. What is Terraform state and why remote backend with locking?**
State maps config to real resource IDs; Terraform plans from it. Remote (S3, versioned + encrypted) lets a team share it and keeps secrets off laptops; locking prevents two concurrent applies corrupting state.

**Q12. What is drift and how do you handle it?**
Real infra differs from code due to manual changes. Detect with scheduled `terraform plan`; either codify the change or re-apply to revert; prevent with least-privilege console access.

**Q13. Workspaces or directories per environment?**
Workspaces share code and backend config with separate state — fine for identical envs. For prod vs dev I prefer separate directories/backends: different credentials, explicit differences, smaller blast radius.

**Q14. Terraform vs Ansible?**
Terraform provisions infrastructure declaratively with state; Ansible configures servers procedurally-but-idempotently over SSH without state. Often used together.

**Q15. merge vs rebase; revert vs reset?**
Merge preserves history with a merge commit; rebase rewrites my commits on top for linear history — never on shared branches. Revert adds an undo commit — safe on main and how GitOps rollbacks work; reset moves the branch pointer — local only.

**Q16. How do you recover a commit after a bad `reset --hard`?**
`git reflog` to find the SHA, then `git reset --hard <sha>` or `git branch rescue <sha>`.

**Q17. CIDR: how many IPs in a /20? Usable in AWS?**
2^12 = 4096; AWS reserves 5 per subnet → 4091.

---

## PART H — SCENARIO QUESTIONS

**S1. "App on an EC2 instance can't connect to RDS."**
Timeout or refused? Timeout → RDS SG must allow 5432 from the instance's SG; same VPC / peering / routes; NACLs (ephemeral ports); RDS in private subnet is fine for in-VPC access. Refused/auth error → endpoint/port/user/password; `nc -zv <endpoint> 5432`, `psql`. DNS → `dig <endpoint>`.

**S2. "Server load average is 20 on a 4-core box."**
`top`: is it CPU (`us/sy` high) or I/O (`wa` high, many `D` processes)? CPU → find process (`ps --sort=-%cpu`), thread dump for Java (`jcmd <pid> Thread.print`). I/O → `iostat -xz 1`, `iotop`; maybe swapping (`vmstat`), slow disk/EBS burst credits exhausted.

**S3. "Java process got killed, no exception in app logs."**
Kernel OOM killer: `dmesg -T | grep -i oom`. Inside containers it's the cgroup limit (exit 137, OOMKilled). Size heap with `MaxRAMPercentage`, leave headroom.

**S4. "`terraform apply` failed halfway."**
State records what was created. Fix the error and re-run `plan`/`apply` — Terraform continues. If the lock is stuck after a crash, `force-unlock` carefully. Never edit state by hand; use `state rm`/`import` if needed.

**S5. "Someone deleted the Terraform state file."**
Restore from S3 versioning (that's why it's enabled). Otherwise `terraform import` each resource. Lesson: remote, versioned, locked backend with restricted access.

**S6. "You need to give a pod access to an S3 bucket."**
No access keys in Secrets. Use IRSA/EKS Pod Identity: IAM role with least-privilege S3 policy, trust bound to the namespace/ServiceAccount, annotate the ServiceAccount, set `automountServiceAccountToken` appropriately (ShopFlow disables it because its pods don't need any token — you'd enable the projected token only for pods that need AWS access).

**S7. "A colleague force-pushed to main and commits disappeared."**
Find the old SHA from your local clone/reflog or the PR/CI history (`github.sha` in the run), restore via a new branch and PR or `push --force-with-lease`. Prevent with branch protection (no force push, required reviews/checks).

**S8. "HTTPS site shows certificate error after a weekend."**
Expired cert: `openssl s_client ... | openssl x509 -noout -dates`. Check cert-manager `Certificate`/`CertificateRequest`/`Order` status (`kubectl describe certificate shopflow-tls -n shopflow`), ACME challenge reachable (HTTP-01 needs port 80 through the Ingress). Add expiry alerts (e.g., blackbox exporter `probe_ssl_earliest_cert_expiry`).

---

## PART I — COMMON MISTAKES

- `kill -9` as the first move (no graceful shutdown, corrupt state).
- `chmod 777` to "fix" permission errors; running containers as root to avoid volume permission issues.
- Reading "free" instead of "available" memory.
- Cron jobs with relative paths and no output redirection.
- Bash scripts without `set -euo pipefail` and unquoted variables.
- Confusing connection refused with timeout when debugging.
- Overlapping CIDRs (VPC vs pod CIDR vs office VPN) — painful to fix later.
- SG open to `0.0.0.0/0` on 22/5432.
- Long-lived IAM access keys in CI or in K8s Secrets; wildcard `"Action": "*"` policies.
- Local Terraform state, committed `.tfstate`, no locking, unpinned providers/modules.
- Making changes in the AWS console for Terraform-managed resources (drift).
- `count` with lists that change order → resources destroyed/recreated.
- `git push --force` on shared branches; `reset --hard` on pushed commits instead of `revert`.

> Tip: Linux/networking questions mein command ke saath "why" bolo — e.g. "`ss -tulpn` because netstat is deprecated and ss reads directly from the kernel" — depth dikhti hai.
