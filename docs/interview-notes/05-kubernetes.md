# 05 — Kubernetes (Interview Notes)

> Source of truth in this repo:
> - **Production-style:** `shopflow/k8s/` — Kustomize `base/` + `overlays/dev`, `overlays/prod`
> - **Learning manifests:** `Devops/*.yaml`, `microservice01/*.yaml` (Pods, NodePort/LoadBalancer/ClusterIP, ConfigMaps, Secrets, emptyDir/hostPath/PV-PVC, Jobs, CronJob)
> - **Cheatsheet:** `notes.txt` (minikube + kubectl)
> - App side: `shopflow/*-service/src/main/resources/application.yml` (Actuator probes, graceful shutdown)

```
shopflow/k8s/
├── base/
│   ├── kustomization.yaml          # namespace: shopflow, label part-of=shopflow, lists all below
│   ├── namespace.yaml
│   ├── endpoints-configmap.yaml    # shopflow-endpoints: kafka-bootstrap-servers, redis-host, jwt-issuer-uri, otlp-endpoint
│   ├── ingress.yaml                # nginx, host shopflow.local, /api/v1/orders, /api/v1/products
│   ├── network-policies.yaml       # default-deny ingress + egress(+DNS), per-service allow rules (apps, postgres, kafka, redis, keycloak)
│   ├── order-service/      {deployment, service, hpa, pdb, kustomization}.yaml
│   ├── inventory-service/  {deployment, service, hpa, pdb, kustomization}.yaml
│   ├── notification-service/ {deployment, service, hpa, pdb, kustomization}.yaml   # Kafka consumer, port 8083
│   ├── payment-service/    {deployment, service, hpa, pdb, kustomization}.yaml   # Kafka consumer + producer, port 8084, DB payments
│   ├── postgres/           {statefulset, service (headless), kustomization (configMapGenerator), init-db.sh}  # 4 logical DBs
│   ├── kafka/              {statefulset (KRaft, 1 node, 2Gi PVC), service (headless 9092/9093)}
│   ├── redis/              {deployment (no volume, allkeys-lru), service}
│   └── keycloak/           {deployment (realm from ConfigMap, startupProbe /realms/shopflow), service, shopflow-realm.json}
└── overlays/
    ├── dev/kustomization.yaml      # tag :dev, secretGenerator (shopflow-db incl. notifications + payments pwd, keycloak-admin), 1 replica, HPA 1-2
    └── prod/kustomization.yaml     # GHCR images pinned by CI, HPA 3-10, TLS host shop.example.com, ExternalSecret;
                                    # $patch: delete postgres/kafka/redis/keycloak (+ their NetworkPolicies) -> RDS/MSK/ElastiCache/Cognito;
                                    # shopflow-endpoints replaced with managed endpoints; egress to VPC CIDR + 443 for Cognito JWKS;
                                    # service-accounts.yaml: SAs order/notification/payment-service with the kafka-clients IRSA role
shopflow/helm/
├── shopflow-service/               # generic chart: Chart.yaml, values.yaml, templates/{deployment,service,hpa,pdb,serviceaccount}.yaml, _helpers.tpl, NOTES.txt
├── values/{order-service,order-service-prod,payment-service}.yaml   # one small values file per service / env
└── README.md                       # Kustomize vs Helm comparison (see §18)
```

---

## 0. Cheat table

| Feature | ShopFlow value | File |
|---|---|---|
| Replicas | base 2, dev 1, prod 3 | `deployment.yaml`, overlays |
| Rolling update | `maxSurge: 1`, `maxUnavailable: 0`, `revisionHistoryLimit: 5` | `deployment.yaml` |
| Probes | startup (liveness path, 2s × 30 = 60s), liveness (10s × 3), readiness (5s × 3) | `deployment.yaml` |
| Resources | req `cpu 250m / mem 384Mi`, limit `mem 512Mi`, **no CPU limit** | `deployment.yaml` |
| Shutdown | `preStop: sleep 5`, `terminationGracePeriodSeconds: 45`, Spring `shutdown: graceful` + 20s phase | deployment + `application.yml` |
| Security | `runAsNonRoot`, UID/GID 10001, seccomp `RuntimeDefault`, no priv-esc, `readOnlyRootFilesystem`, drop ALL caps, `automountServiceAccountToken: false` | `deployment.yaml` |
| Spread | `topologySpreadConstraints` on `kubernetes.io/hostname`, `maxSkew: 1`, `ScheduleAnyway` | `deployment.yaml` |
| HPA | `autoscaling/v2`, CPU 70% of request, min 2 / max 6 (prod 3/10), scaleDown window 300s | `hpa.yaml` |
| PDB | `minAvailable: 1` | `pdb.yaml` |
| Services | ClusterIP 8081 / 8082 / 8083 / 8084; Postgres and Kafka headless (`clusterIP: None`; `kafka-0.kafka` for the controller quorum) | `service.yaml` |
| DB / broker | Postgres StatefulSet, `volumeClaimTemplates` 1Gi RWO, `fsGroup: 70`; Kafka StatefulSet (KRaft combined node) 2Gi PVC, `tcpSocket` readiness on 9092 | `postgres/statefulset.yaml`, `kafka/statefulset.yaml` |
| Cache / IdP | Redis Deployment (no PVC — "losing it only costs cache misses"), Keycloak Deployment with realm ConfigMap + `startupProbe` on `/realms/shopflow` | `redis/`, `keycloak/` |
| Endpoints | ConfigMap `shopflow-endpoints` consumed via `configMapKeyRef` (`KAFKA_BOOTSTRAP_SERVERS`, `REDIS_HOST`, `JWT_ISSUER_URI`, `OTLP_ENDPOINT`); prod overlay `configMapGenerator` with `behavior: replace` | `endpoints-configmap.yaml`, overlays |
| Secrets | dev: `secretGenerator` (`shopflow-db` with 4 DB passwords incl. `payments-db-password`, `keycloak-admin`); prod: External Secrets Operator (`external-secret.yaml`, 4 keys from one Secrets Manager JSON) | overlays |
| TLS | prod Ingress `tls` with `secretName: shopflow-tls` issued by cert-manager | `overlays/prod` |

---

## 1. Architecture

```
                        ┌──────────────────────── CONTROL PLANE ────────────────────────┐
 kubectl / CI / ArgoCD  │                                                                │
 ───────────────────────┼─▶ kube-apiserver ◀──────▶ etcd (key-value store, all state)    │
          HTTPS (REST)  │      ▲   ▲   ▲                                                 │
                        │      │   │   └── kube-scheduler   (picks a node for new Pods)  │
                        │      │   └────── kube-controller-manager (Deployment, RS,      │
                        │      │           Node, Job, EndpointSlice ... controllers)     │
                        │      │           cloud-controller-manager (LBs, nodes in cloud)│
                        └──────┼─────────────────────────────────────────────────────────┘
                               │ watch
          ┌────────────────────┼───────────────────────┐
          │ WORKER NODE        │                       │
          │   kubelet ─────────┘  (runs Pods via CRI)   │
          │     └─▶ containerd/CRI-O ─▶ containers      │
          │   kube-proxy (iptables/IPVS rules for Svcs) │
          │   CNI plugin (Calico/Cilium/aws-vpc-cni)    │
          └─────────────────────────────────────────────┘
```

| Component | Job | Interview one-liner |
|---|---|---|
| **kube-apiserver** | Front door; authn → authz (RBAC) → admission → validation → persist to etcd | "Only component that talks to etcd" |
| **etcd** | Consistent distributed KV store (Raft) | "Back it up — it *is* the cluster" |
| **kube-scheduler** | Assigns unscheduled Pods to nodes: filter (resources, taints, affinity) → score → bind | "Doesn't start pods, only sets `nodeName`" |
| **controller-manager** | Reconciliation loops: desired state vs actual state | "Deployment controller creates ReplicaSets, RS controller creates Pods" |
| **kubelet** | Agent on each node; watches Pods bound to it, starts containers via CRI, runs probes, reports status | "Probes are executed by kubelet" |
| **kube-proxy** | Programs iptables/IPVS so Service ClusterIP → Pod IPs | (Cilium can replace it with eBPF) |
| **CoreDNS** | Cluster DNS (`svc.cluster.local`) | Runs as a Deployment in `kube-system` |
| **CNI** | Pod networking, IPs, NetworkPolicy enforcement | ShopFlow comment: NetworkPolicy needs Calico/Cilium |

### What happens when you `kubectl apply -k k8s/overlays/prod`

```
1. kubectl builds YAML (kustomize), sends to apiserver (server-side or client-side apply)
2. apiserver: authenticate → RBAC authorize → mutating admission → schema validation
               → validating admission (e.g. Pod Security) → write Deployment to etcd
3. Deployment controller (watch) sees new/changed template → creates a new ReplicaSet
4. ReplicaSet controller → creates Pod objects (no nodeName yet)
5. Scheduler (watch) → filters nodes (requests 250m/384Mi fit? taints? spread?)
               → scores → binds Pod to node (writes nodeName)
6. kubelet on that node (watch) → mounts volumes (emptyDir /tmp, secrets)
               → creates pod sandbox via CRI (CNI assigns Pod IP)
               → pulls image via containerd → starts container
7. kubelet runs startupProbe → then liveness + readiness
8. Readiness OK → EndpointSlice controller adds Pod IP to Service "order-service"
               → kube-proxy updates iptables → traffic flows
9. Deployment controller scales old RS down step by step (maxSurge 1 / maxUnavailable 0)
```
> Tip: Ye flow ek baar ratt lo — "what happens when you run kubectl apply" bahut common question hai.

Everything is **declarative** and **level-triggered**: controllers continuously reconcile actual → desired. Delete a Pod of a Deployment and the RS recreates it.

---

## 2. Workload objects

| Object | Purpose | Repo example |
|---|---|---|
| **Pod** | Smallest unit; 1+ containers sharing network namespace (localhost) and volumes | `Devops/fist-pod.yaml` (nginx), `Devops/backend-pod.yaml` |
| **ReplicaSet** | Keeps N identical Pods running | Created by Deployments; you rarely write one |
| **Deployment** | Stateless apps; manages ReplicaSets → rolling updates & rollback | `shopflow/k8s/base/*-service/deployment.yaml`, `Devops/backenddeploy.yaml` |
| **StatefulSet** | Stable identity (`postgres-0`), ordered start/stop, per-replica PVC | `shopflow/k8s/base/postgres/statefulset.yaml` |
| **DaemonSet** | One Pod per node (log shippers, node-exporter, CNI) | Not in repo (Promtail/Fluent Bit would be one) |
| **Job** | Run to completion | `Devops/job.yaml`, `Devops/job-backofflimit.yaml` |
| **CronJob** | Jobs on a schedule | `Devops/cron-job.yaml` |

Never run bare Pods in prod — nothing recreates them if the node dies.

Multi-container Pod example: `Devops/empty-dir-volume.yaml` — containers `one` and `two` share the `cache` emptyDir (mounted at `/foo` and `/hello`). Patterns: sidecar (log shipper, proxy), init container (wait/migrate), ambassador.

### Deployment vs StatefulSet

| | Deployment | StatefulSet |
|---|---|---|
| Pod names | random `order-service-7d9f...-x2k` | ordinal `postgres-0`, `postgres-1` |
| Storage | shared PVC or none | `volumeClaimTemplates` → one PVC per Pod (`data-postgres-0`), kept on reschedule/delete |
| DNS | via Service only | per-Pod via headless Service: `postgres-0.postgres.shopflow.svc.cluster.local` |
| Order | parallel | ordered create (0→N-1), reverse-order scale-down/updates (`podManagementPolicy: OrderedReady`) |
| Use | APIs, workers | DBs, Kafka, ZooKeeper, Elasticsearch |

ShopFlow's StatefulSet comment says it honestly: *demo DB; in real production use a managed DB (RDS / Cloud SQL) or an operator (CloudNativePG)*.

### Jobs and CronJobs (`Devops/`)

`Devops/job-backofflimit.yaml`:
```yaml
spec:
  ttlSecondsAfterFinished: 10   # auto-delete Job + Pods 10s after finish
  activeDeadlineSeconds: 10     # whole Job fails if it runs longer than 10s
  completions: 2                # need 2 successful Pods
  parallelism: 2                # run up to 2 at a time
  backoffLimit: 2               # retries before marking Job failed (default 6)
  template:
    spec:
      restartPolicy: Never      # Jobs need Never or OnFailure (not Always)
```
(Note: the inline comment in `job.yaml` / `job-backofflimit.yaml` says completions run "one after the other" — with `parallelism: 2` they actually run at the same time; `parallelism: 1` would make them sequential.)

`Devops/cron-job.yaml`:
```yaml
schedule: "* * * * *"           # every minute (kube-controller-manager's time zone, usually UTC; set spec.timeZone to be explicit)
concurrencyPolicy: Forbid       # Allow | Forbid (skip if previous still running) | Replace
successfulJobsHistoryLimit: 0
failedJobsHistoryLimit: 0       # keep >0 in real life so you can debug failures!
# suspend: true
```

---

## 3. Services & DNS

A Service = stable virtual IP + DNS name in front of a changing set of Pods selected by **labels**. Endpoints/EndpointSlices hold the ready Pod IPs.

| Type | Reachable from | Repo example |
|---|---|---|
| **ClusterIP** (default) | Inside cluster only | `shopflow/k8s/base/order-service/service.yaml` (8081), `microservice01/helper-service.yaml` (port 8088 → targetPort 8081) |
| **NodePort** | `<anyNodeIP>:30000-32767` | `Devops/backend-service.yaml` (nodePort 30004), `microservice01/helper-nodeport.yaml` (32004) |
| **LoadBalancer** | External cloud LB → NodePort → Pod | `Devops/backend-service-loadbalancer.yaml`, `Devops/pv-pvc.yaml` (minikube: `minikube tunnel`) |
| **Headless** (`clusterIP: None`) | DNS returns Pod IPs directly, no VIP | `shopflow/k8s/base/postgres/service.yaml` |
| ExternalName | CNAME to external DNS | not in repo |

```
port       = Service port (what clients call)        e.g. 8088
targetPort = container port (can be a NAME, e.g. "http")  e.g. 8081
nodePort   = port opened on every node (NodePort/LB only)
```
ShopFlow uses **named ports** (`targetPort: http`) → container port can change without touching the Service.

### DNS
```
<service>.<namespace>.svc.cluster.local
order-service                         # same namespace
inventory-service.shopflow            # from another namespace  (notes.txt: "service.namespace")
inventory-service.shopflow.svc.cluster.local
postgres-0.postgres.shopflow.svc.cluster.local   # StatefulSet pod via headless svc
```
In ShopFlow: `INVENTORY_URL: http://inventory-service:8082` and `DB_URL: jdbc:postgresql://postgres:5432/...` — the same names as in docker-compose, so configs look identical.

Debug from `notes.txt`:
```bash
kubectl exec -it <pod> -- sh
nslookup helper-service
curl http://helper-service.default.svc.cluster.local:8088/api2/m2
kubectl get endpoints   # empty endpoints = selector mismatch or no ready pods
```

---

## 4. Ingress

L7 HTTP routing (host/path) into ClusterIP Services, implemented by an **Ingress controller** (ingress-nginx, AWS Load Balancer Controller, Traefik). The Ingress object alone does nothing.

`shopflow/k8s/base/ingress.yaml`:
```yaml
spec:
  ingressClassName: nginx
  rules:
    - host: shopflow.local
      http:
        paths:
          - path: /api/v1/orders     → order-service:http
          - path: /api/v1/products   → inventory-service:http
```
Prod overlay patches host to `shop.example.com` and adds:
```yaml
tls:
  - hosts: ["shop.example.com"]
    secretName: shopflow-tls     # issued by cert-manager
```
```
Internet ─▶ Cloud LB (Service type LoadBalancer of ingress-nginx) ─▶ nginx pods
        ─▶ (host+path match) ─▶ ClusterIP order-service ─▶ Pod :8081
```
Why Ingress vs many LoadBalancers: one LB (cost), TLS termination in one place, path/host routing. Newer: **Gateway API** (`HTTPRoute`) — successor to Ingress. Note: the community ingress-nginx controller was retired (best-effort maintenance ended March 2026, no further fixes) — mention Gateway API or another controller as the migration path.

---

## 5. ConfigMaps & Secrets

**ConfigMap** = non-secret config; **Secret** = sensitive data. Both consumed as env vars or files.

From `Devops/testing-config-map.yaml` (all three ways):
```yaml
env:
  - name: APP_VERSION
    valueFrom: { configMapKeyRef: { name: app-properties, key: app-version } }
volumes:
  - name: nginx-conf
    configMap: { name: nginx-conf }                 # each key → a file
  - name: config
    projected: { sources: [ {configMap: {name: nginx-conf}}, {configMap: {name: app-properties}} ] }
```
`Devops/secret.yaml`: secret mounted as files at `/etc/secrets` and one key as env `AMIGOSCODE_SECRET`.

### base64 ≠ encryption
```bash
kubectl get secret mysecret -o jsonpath='{.data.password}' | base64 -d
echo dGhpcyBpcyBzZWNyZXQ= | base64 -d        # from notes.txt → "this is secret"
```
Secrets are only base64-encoded. Anyone with `get secret` RBAC or etcd access reads them. Real protection:
1. **RBAC** — restrict `get/list` on secrets.
2. **Encryption at rest** in etcd (`EncryptionConfiguration` / KMS; EKS supports envelope encryption with KMS).
3. **Keep them out of git**: External Secrets Operator (sync from AWS Secrets Manager/Vault), Sealed Secrets (encrypted in git, decrypted in cluster), SOPS.
4. Prefer mounting as files over env (env leaks into crash dumps, `/proc`, child processes) — though env is common for Spring.

ShopFlow:
- **dev overlay**: `secretGenerator` with literal throwaway passwords → Secret `shopflow-db-<hash>`.
- **prod overlay**: comment — `Secret "shopflow-db" is NOT in git: it is synced from AWS Secrets Manager / Vault by External Secrets Operator (or created once with Sealed Secrets).`
- Deployments read `DB_PASSWORD` via `secretKeyRef` (`orders-db-password`, `inventory-db-password`).

### The `shopflow-endpoints` ConfigMap (one place for "where are my dependencies")
`k8s/base/endpoints-configmap.yaml` holds `kafka-bootstrap-servers: kafka:9092`, `redis-host: redis`, `jwt-issuer-uri: http://keycloak.shopflow.local/realms/shopflow`, `otlp-endpoint: http://tempo.monitoring:4318/v1/traces`; Deployments read them with `valueFrom.configMapKeyRef`. The prod overlay replaces it (`configMapGenerator` with `behavior: replace`) with the MSK bootstrap string, the ElastiCache host, the Cognito issuer, an OTel collector and `rds-endpoint`, copied from `terraform output`. Same image, same Deployment — only this ConfigMap and the ExternalSecret differ between dev and prod.

### Generators and the hash suffix
`configMapGenerator` (postgres `init-db.sh` → `postgres-init`, Keycloak `shopflow-realm.json` → `keycloak-realm`) and `secretGenerator` add a **content hash** to the name, and Kustomize rewrites all references. Change the content → new name → Pod template changes → **automatic rolling restart**. Plain ConfigMaps don't trigger restarts when edited (env vars never update; mounted files update eventually but the app may not reload).

Gotcha in the repo: `Devops/backend-cm.yaml` and `Devops/testing-config-map.yaml` both define ConfigMap `app-properties` — last applied wins (README mentions this).

---

## 6. Volumes, PV, PVC, StorageClass

| Volume | Lifetime | Repo |
|---|---|---|
| `emptyDir` | Pod lifetime (survives container restart, deleted with Pod) | ShopFlow `/tmp` for Tomcat under read-only rootfs; `Devops/empty-dir-volume.yaml` shared between 2 containers; `microservice01/helperdeployEmptyDirVolume.yaml` |
| `hostPath` | Node's filesystem | `Devops/hostpath-volume.yaml` mounts node `/var/log` read-only. Dangerous (node escape, pod tied to node) — only for system agents |
| `configMap` / `secret` / `projected` | Pod | `Devops/testing-config-map.yaml` |
| `persistentVolumeClaim` | Independent of Pod | `Devops/pv-pvc.yaml`, ShopFlow `volumeClaimTemplates` |

```
 Pod ──uses──▶ PVC (request: 1Gi, RWO, class X) ──bound──▶ PV (actual disk: EBS vol)
                         ▲                                   ▲
                         │ dynamic provisioning              │ created by CSI driver
                    StorageClass (provisioner: ebs.csi.aws.com, reclaimPolicy, volumeBindingMode)
```
- **PV** — a piece of storage (admin-created = static, or StorageClass-created = dynamic).
- **PVC** — a request for storage by a user/pod.
- **StorageClass** — template for dynamic provisioning (`gp3` on EKS, `standard` on minikube).
- `Devops/pv-pvc.yaml` = **static** provisioning: PV `mypv` hostPath `/mnt/data`, `storageClassName: manual`, `persistentVolumeReclaimPolicy: Retain` (comment: Recycle is deprecated), PVC `mypvc` in namespace `engineering`.
- ShopFlow Postgres = **dynamic**: `volumeClaimTemplates` `data`, `ReadWriteOnce`, `1Gi`, default StorageClass.

Access modes: **RWO** (one node), **ROX**, **RWX** (many nodes — EFS/NFS), **RWOP** (one pod). Reclaim: `Delete` (default for dynamic) vs `Retain`.

`fsGroup: 70` in the Postgres StatefulSet makes the mounted volume group-owned by GID 70 (postgres user in alpine) so the non-root DB process can write. `PGDATA=/var/lib/postgresql/data/pgdata` uses a subdir because some volumes have a `lost+found` at the root, which makes `initdb` refuse a non-empty dir.

---

## 7. Probes — startup vs liveness vs readiness

| Probe | Question | On failure | ShopFlow |
|---|---|---|---|
| **startupProbe** | "Has the app finished starting?" | Keeps checking; after threshold → restart. **Liveness/readiness are disabled until it succeeds** | `/actuator/health/liveness`, `periodSeconds: 2`, `failureThreshold: 30` → up to 60s |
| **livenessProbe** | "Is the process stuck/broken beyond repair?" | **Restart container** | `/actuator/health/liveness`, every 10s, 3 failures |
| **readinessProbe** | "Can it serve traffic right now?" | **Remove from Service endpoints** (no restart) | `/actuator/health/readiness`, every 5s, 3 failures |

Spring side (`application.yml`):
```yaml
management:
  endpoint:
    health:
      probes:
        enabled: true            # /actuator/health/liveness and /readiness
      group:
        readiness:
          include: readinessState,db
```
- **Liveness** = Spring's `LivenessState` only (app internal state). **Never include the DB** in liveness: DB down → all pods restart in a loop → doesn't fix the DB, adds load.
- **Readiness** = `readinessState` + **its own** DB.
- **Why readiness must NOT include downstream services**: order-service's comment says it — *"only own dependencies: if inventory is down, order pods must NOT be pulled from the load balancer (that would turn one outage into two)"*. If order-service readiness checked inventory, an inventory outage would make every order pod NotReady → the Ingress returns 503 for *all* order endpoints (even `GET /orders/{id}` which doesn't need inventory) → cascading failure. Instead order-service handles inventory failure with timeouts, retry and a circuit breaker and returns a proper error.
- Why startupProbe: JVM start can take 20–60s. Without it you'd set `initialDelaySeconds: 60` on liveness (like the old `Devops/backenddeploy.yaml` with `initialDelaySeconds: 30`) — which slows detection forever. Startup probe = fast checks only during start.
- Old manifests use `tcpSocket` probes — port open ≠ app healthy. HTTP actuator probes are better.
- Postgres readiness is an `exec` probe: `pg_isready -U postgres`.

During graceful shutdown Spring flips readiness to `REFUSING_TRAFFIC` automatically.

---

## 8. Resources, QoS, OOMKilled, CPU throttling

```yaml
resources:
  requests:
    cpu: 250m          # scheduler reserves this; HPA % is based on it
    memory: 384Mi
  limits:
    memory: 512Mi      # over this -> OOMKilled; heap = 75% of it (Dockerfile)
    # no CPU limit on purpose: CPU limits cause throttling and slow JVM start-up
```
- **requests** → scheduling + guaranteed share (CPU shares/weight). **limits** → hard cap.
- **Memory** is incompressible: exceed limit → kernel kills → `OOMKilled`, exit 137.
- **CPU** is compressible: exceed limit → **throttled** (CFS quota per 100ms period), not killed. JVM startup (JIT, class loading) and GC are bursty; with a 500m limit the JVM gets throttled → slow start → startup probe fails → restart loop. Without a CPU limit, the pod can burst into idle node CPU and requests still guarantee fairness under contention. Old manifests set `cpu: 500m` limits — contrast story.
- Counter-argument interviewers may raise: without CPU limits a noisy pod can use spare CPU and you lose predictability; some orgs require limits via LimitRange. Answer: requests protect neighbours under contention; set limits if you need strict multi-tenant isolation or chargeback.

### QoS classes

| Class | Rule | Eviction order under node pressure |
|---|---|---|
| **Guaranteed** | Every container: requests == limits for **both** CPU and memory | Last |
| **Burstable** | At least one request/limit set, not Guaranteed | Middle — **ShopFlow** (no CPU limit, mem req ≠ limit) |
| **BestEffort** | No requests/limits at all | First (e.g. `Devops/fist-pod.yaml`, CronJob busybox) |

Node-pressure eviction (node running out of memory) is different from container OOMKill (container exceeded its own limit).

Check:
```bash
kubectl get pod <p> -o jsonpath='{.status.qosClass}'
kubectl top pod -n shopflow ; kubectl top node        # needs metrics-server
kubectl describe node <n> | grep -A8 "Allocated resources"
```
CPU throttling ratio: `rate(container_cpu_cfs_throttled_periods_total[5m]) / rate(container_cpu_cfs_periods_total[5m])` (only non-zero when a CPU limit is set).

---

## 9. Autoscaling: HPA, VPA, Cluster Autoscaler

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  scaleTargetRef: { apiVersion: apps/v1, kind: Deployment, name: order-service }
  minReplicas: 2
  maxReplicas: 6
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: { type: Utilization, averageUtilization: 70 }   # % of the CPU *request*
  behavior:
    scaleDown:
      stabilizationWindowSeconds: 300    # wait 5 min before removing pods (anti-flapping)
```
Formula: `desired = ceil(currentReplicas × currentMetric / targetMetric)`, clamped to min/max; no action if the ratio is within the 10% tolerance. With 2 pods averaging 140% of the 250m request → `ceil(2 × 140/70) = 4`.

- Needs **metrics-server** (comment in `hpa.yaml`; `minikube addons enable metrics-server`).
- **No CPU request = HPA can't compute utilization** → `<unknown>` targets.
- Custom metrics (RPS, queue length) via Prometheus Adapter or **KEDA**.
- Dev overlay patches HPA to 1–2, prod to 3–10.

**HPA + `spec.replicas` gotcha**: ShopFlow's Deployments deliberately have **no `spec.replicas`**. If the manifest set it, every `kubectl apply` / GitOps sync would reset the count to that number and the HPA would scale again → a brief scale-down on each deploy. The HPA's `minReplicas` (1 in dev, 3 in prod) is the floor instead. Alternative when you can't drop the field: Argo CD `ignoreDifferences` on `/spec/replicas`.

| | HPA | VPA | Cluster Autoscaler / Karpenter |
|---|---|---|---|
| Scales | Number of Pods | Pod requests/limits | Number of **nodes** |
| Trigger | Metrics vs target | Historical usage | Pending Pods that don't fit / underused nodes |
| Note | Don't combine with VPA on the same CPU/memory metric | Recreates pods to apply (in-place resize is newer) | Karpenter (AWS) picks right-sized instance types |

Chain: traffic ↑ → HPA adds pods → pods Pending (no room) → Cluster Autoscaler adds a node → pods scheduled.

---

## 10. PodDisruptionBudget

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
spec:
  minAvailable: 1
  selector: { matchLabels: { app.kubernetes.io/name: order-service } }
```
- Protects against **voluntary** disruptions: `kubectl drain`, node upgrades, cluster-autoscaler scale-down. **Not** against node crashes (involuntary).
- The eviction API refuses evictions that would violate the budget. Deployment rolling updates and direct `kubectl delete pod` do **not** go through PDBs.
- **Gotcha**: with 1 replica, `minAvailable: 1` means `kubectl drain` keeps retrying that eviction until `--timeout`. That is why the dev overlay patches the PDB to `maxUnavailable: 1` (JSON patch: remove `minAvailable`, add `maxUnavailable`) while prod keeps `minAvailable: 1` with 3+ replicas. Good thing to mention proactively.

---

## 11. Rolling updates & rollback

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1          # at most replicas+1 pods during update
    maxUnavailable: 0    # never below desired ready count
revisionHistoryLimit: 5  # old ReplicaSets kept → how far 'rollout undo' can go
```
With 3 replicas (prod):
```
old RS: ●●●   new RS: -       → create 1 new (surge)        ●●● + ○
new pod Ready (readiness OK)  → scale old to 2              ●●  + ●
                               → create next new              ●● + ●○
... until old RS = 0                                          ●●●(new)
```
`maxUnavailable: 0` relies on **readiness** — a new pod that never becomes Ready blocks the rollout (safe! old pods keep serving). After `progressDeadlineSeconds` (default 600s) rollout is marked failed (`ProgressDeadlineExceeded`) — but **not** auto-rolled back.

Old manifests use `maxUnavailable: 1` with `replicas: 1` → can drop to zero pods during a deploy.

`Recreate` strategy: kill all, then start new — downtime, but needed when two versions can't run together (e.g., RWO volume, incompatible schema).

```bash
kubectl rollout status deploy/order-service -n shopflow
kubectl rollout history deploy/order-service -n shopflow
kubectl rollout undo deploy/order-service -n shopflow [--to-revision=3]
kubectl rollout restart deploy/order-service -n shopflow   # new pods, same spec
kubectl rollout pause deploy/order-service -n shopflow    # batch several changes...
kubectl rollout resume deploy/order-service -n shopflow   # ...into one rollout
kubectl annotate deploy/order-service -n shopflow kubernetes.io/change-cause="v1.2.3" --overwrite   # old manifests set this annotation
```
In GitOps, rollback = `git revert` of the image-tag commit (cluster follows git); `kubectl rollout undo` would be reverted by Argo CD self-heal.

---

## 12. Graceful shutdown sequence

```
t=0   Pod marked Terminating (kubectl delete / rollout / drain / scale down)
      ├── (parallel) EndpointSlice controller removes Pod IP → kube-proxy/ingress-nginx
      │               update their routing   (takes 1–few seconds, eventually consistent!)
      └── kubelet runs preStop hook:  sh -c "sleep 5"
              │  pod still serves requests that arrive via stale routes
t=5s  preStop done → kubelet sends SIGTERM to PID 1 (java, exec-form ENTRYPOINT)
      Spring (server.shutdown: graceful): readiness → REFUSING_TRAFFIC,
      Tomcat stops accepting new connections, waits for in-flight requests
      (spring.lifecycle.timeout-per-shutdown-phase: 20s), closes Hikari pool
t≤25s JVM exits (code 143 = 128+SIGTERM — normal for a signal-driven graceful stop)
t=45s if still alive → SIGKILL  (terminationGracePeriodSeconds: 45, counted from t=0, includes preStop)
```
Budget check: 5s preStop + 20s Spring phase = 25s < 45s grace ✓.

**Why preStop sleep?** Endpoint removal and SIGTERM happen **in parallel**. Without the sleep, Tomcat closes its listener while ingress-nginx/kube-proxy still send traffic → `502/connection refused` during every deploy. The sleep bridges the propagation delay. (K8s 1.30+ has a native `preStop: sleep: {seconds: 5}` action; ShopFlow uses `exec sh -c "sleep 5"`, which needs `sh` in the image — exists in Alpine, not in distroless.)

---

## 13. Scheduling: affinity, taints/tolerations, topology spread

| Tool | Who decides | Example |
|---|---|---|
| `nodeSelector` | Pod: "only nodes with label X" | `nodeSelector: {disktype: ssd}` |
| Node affinity | Pod, richer (required/preferred, In/NotIn) | Run on `topology.kubernetes.io/zone in (ap-south-1a,1b)` |
| Pod (anti-)affinity | Pod relative to other pods | Anti-affinity: don't co-locate replicas |
| **Taints / tolerations** | **Node repels** pods unless they tolerate | `kubectl taint nodes n1 gpu=true:NoSchedule`; control-plane taint |
| **topologySpreadConstraints** | Even spread across a topology key | ShopFlow |

ShopFlow:
```yaml
topologySpreadConstraints:
  - maxSkew: 1                               # pod counts per node differ by at most 1
    topologyKey: kubernetes.io/hostname      # spread across nodes (use topology.kubernetes.io/zone for AZs)
    whenUnsatisfiable: ScheduleAnyway        # soft: prefer, don't block scheduling
    labelSelector:
      matchLabels: { app.kubernetes.io/name: order-service }
```
Purpose: *one node failure does not take every pod*. `DoNotSchedule` would make it hard (pods Pending if constraint can't be met).

Taints vs affinity: taints **keep pods away** from nodes; affinity **attracts** pods to nodes. Dedicated nodes = taint + toleration + affinity together. Effects: `NoSchedule`, `PreferNoSchedule`, `NoExecute` (evicts running pods).

---

## 14. RBAC & ServiceAccounts

```
 Subject (User / Group / ServiceAccount)
      │  RoleBinding (namespace) / ClusterRoleBinding (cluster-wide)
      ▼
 Role / ClusterRole  ── rules: apiGroups, resources, verbs (get list watch create update patch delete)
```
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata: { name: config-reader, namespace: shopflow }
rules:
  - apiGroups: [""]
    resources: ["configmaps"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata: { name: order-service-config-reader, namespace: shopflow }
subjects:                                  # example only — ShopFlow pods use the default SA [{ kind: ServiceAccount, name: order-service, namespace: shopflow }]
roleRef: { kind: Role, name: config-reader, apiGroup: rbac.authorization.k8s.io }
```
- Every Pod runs as a ServiceAccount (`default` if unset); its token is mounted at `/var/run/secrets/kubernetes.io/serviceaccount/`.
- ShopFlow: `automountServiceAccountToken: false` — *"the app never talks to the K8s API"* → a compromised pod has no API token to abuse.
- Cloud access from pods: **IRSA** / **EKS Pod Identity** (AWS) — map a ServiceAccount to an IAM role, no static keys.
- Test: `kubectl auth can-i list secrets --as=system:serviceaccount:shopflow:default -n shopflow`.

---

## 15. NetworkPolicy

By default **all pods can talk to all pods** in all namespaces. NetworkPolicies are allow-lists, enforced by the CNI (Calico, Cilium). Comment in repo: *minikube: `--cni=calico`*; flannel (and older kindnet) ignore them silently.

`shopflow/k8s/base/network-policies.yaml`:
```
default-deny-ingress         podSelector: {}  → no ingress to any pod in shopflow
order-service-ingress        from ns ingress-nginx, ns monitoring                         → 8081
inventory-service-ingress    from pods order-service, ns ingress-nginx, ns monitoring     → 8082
notification-service-ingress from ns monitoring only (no public API, Prometheus scrapes it) → 8083
payment-service-ingress      from ns monitoring only (no public API either: it only talks Kafka)    → 8084
postgres-ingress             from pods order-service | inventory-service | notification-service | payment-service → 5432
kafka-ingress                from pods order-service | notification-service | payment-service → 9092 ; from kafka pods → 9093 (controller quorum)
redis-ingress                from pods inventory-service                    → 6379
keycloak-ingress             from ns ingress-nginx (login/token), pods order-service (JWKS) → 8180
```
```
ingress-nginx ──▶ order-service ──▶ inventory-service ──▶ redis
     │              │   │   └──▶ keycloak (JWKS)   │
     │              │   └──▶ kafka ◀── notification-service
     │              │        ▲  ◀── payment-service   (consumes orders.events, produces payments.events)
     └──────────────┼──▶ keycloak (token)          │
monitoring (Prometheus) scrapes all four; apps send traces to monitoring:4318
                    └──────▶ postgres ◀────────────┘   (only the four services can reach 5432)
```
- Multiple items in one `from` list = **OR**. A `namespaceSelector` and `podSelector` in the **same** item = **AND** (common bug).
- Selecting namespaces uses the automatic label `kubernetes.io/metadata.name`.
- Egress is locked down too: `default-deny-egress-allow-dns` selects every pod, denies all egress, and allows only UDP/TCP 53 to `k8s-app: kube-dns` in `kube-system`; then `order-service-egress` (→ inventory 8082, postgres 5432, kafka 9092, keycloak 8180, monitoring ns 4318 for traces), `notification-service-egress` (→ postgres, kafka, monitoring 4318), `payment-service-egress` (same three destinations — a payment pod has no business calling inventory or Keycloak) and `inventory-service-egress` (→ postgres, redis 6379, monitoring 4318). **Forgetting DNS** is the classic egress-policy mistake: every Service lookup fails and it looks like the app is broken.
- Prod overlay: the in-cluster postgres/kafka/redis/keycloak NetworkPolicies are deleted with the workloads, and the egress policies get extra `ipBlock` rules — `10.0.0.0/16` (VPC CIDR) on 5432/9092/**9098** (MSK IAM listener) and 6379, plus `0.0.0.0/0:443` for order-service to fetch Cognito's JWKS. Managed services live **outside** the cluster, so pod selectors cannot describe them.

---

## 16. securityContext & Pod Security Standards

Pod level:
```yaml
securityContext:
  runAsNonRoot: true          # kubelet refuses to start if image user is root/unverifiable
  runAsUser: 10001            # matches Dockerfile USER 10001
  runAsGroup: 10001
  seccompProfile: { type: RuntimeDefault }   # blocks dangerous syscalls
```
Container level:
```yaml
securityContext:
  allowPrivilegeEscalation: false    # no setuid/sudo gaining privileges
  readOnlyRootFilesystem: true       # attacker can't drop binaries; /tmp is an emptyDir
  capabilities: { drop: ["ALL"] }    # app on 8081 doesn't need NET_BIND_SERVICE etc.
```
**Pod Security Standards** (PSP was removed in 1.25; replacement is **Pod Security Admission**):

| Level | Meaning |
|---|---|
| privileged | No restrictions (system components) |
| baseline | Blocks known privilege escalations (hostNetwork, privileged...) |
| restricted | Hardened: non-root, drop ALL, seccomp, no priv-esc — **ShopFlow app pods comply** |

Enforce with namespace labels: `pod-security.kubernetes.io/enforce: restricted`. (Postgres pod would need its own securityContext tweaks for restricted.) Policy engines: Kyverno, OPA Gatekeeper.

---

## 17. Namespaces, ResourceQuota, LimitRange

- Namespace = scope for names, RBAC, quotas, NetworkPolicies. ShopFlow: `namespace: shopflow` in `base/kustomization.yaml` + `namespace.yaml`. Learning: `engineering` in `Devops/pv-pvc.yaml`.
- Not a security boundary on its own (nodes and network shared) — combine with RBAC + NetworkPolicy + PSA.
- Cluster-scoped resources (Nodes, PVs, StorageClasses, ClusterRoles) have no namespace.
```yaml
kind: ResourceQuota       # total cap per namespace
spec: { hard: { requests.cpu: "4", requests.memory: 8Gi, limits.memory: 12Gi, pods: "30" } }
---
kind: LimitRange          # per-container defaults/min/max if a pod doesn't specify
spec: { limits: [ { type: Container, defaultRequest: { cpu: 100m, memory: 128Mi }, default: { memory: 256Mi } } ] }
```
If a quota on `requests.cpu` exists, pods **without** CPU requests are rejected — unless a LimitRange injects defaults.

---

## 18. Kustomize vs Helm

| | Kustomize (ShopFlow) | Helm |
|---|---|---|
| Model | Plain YAML base + overlays + patches | Go-templated charts + `values.yaml` |
| Built into | `kubectl apply -k` | Separate CLI |
| Packaging/versioning | No (just directories) | Charts versioned, repositories, dependencies |
| Release mgmt | No (use Argo CD) | `helm upgrade --install`, `helm rollback`, release history |
| Best for | Your own apps, env differences | Third-party software (ingress-nginx, Prometheus, cert-manager) or shared internal chart |
| Downside | Verbose for big variations | Templates become unreadable; whitespace/indentation bugs |

ShopFlow Kustomize features used:
- `namespace:` transformer, common `labels` with `includeSelectors: false` (so selectors aren't changed — changing selectors on an existing Deployment is forbidden/immutable).
- `images:` to set `newName`/`newTag` per env (`kustomize edit set image ...` from CI).
- `configMapGenerator` / `secretGenerator` with hash suffix.
- JSON6902 `patches` with `target` (replicas, HPA min/max, `imagePullPolicy: IfNotPresent` in dev, Ingress host+TLS in prod).

```bash
kubectl kustomize shopflow/k8s/overlays/prod | less      # render
kubectl diff -k shopflow/k8s/overlays/prod                # what would change
kubectl apply -k shopflow/k8s/overlays/dev
```
`notes.txt` mentions the office using Helm: `helm install chartname chartfoldername -n namespace`. Know: `helm template`, `helm upgrade --install -f values-prod.yaml`, `helm rollback <release> <rev>`, `helm list -n ns`.

### The ShopFlow Helm chart (`shopflow/helm/shopflow-service`) — same workload, other packaging

One **generic chart** renders what `k8s/base/<service>/` contains (Deployment, Service, HPA, PDB) plus a ServiceAccount, driven by a values file per service:

```yaml
# helm/values/payment-service.yaml (complete file — this is the whole per-service difference)
name: payment-service
image:
  repository: shopflow/payment-service
port: 8084
db:
  name: payments
  secretKey: payments-db-password
env:
  - name: KAFKA_BOOTSTRAP_SERVERS
    valueFrom: { configMapKeyRef: { name: shopflow-endpoints, key: kafka-bootstrap-servers } }
  - name: OTLP_ENDPOINT
    valueFrom: { configMapKeyRef: { name: shopflow-endpoints, key: otlp-endpoint } }
```
`values.yaml` holds the defaults every service shares (`db.secretName: shopflow-db`, `poolSize: 5`, requests 250m/384Mi, memory limit 512Mi, HPA 2–6 at 70% CPU, PDB `minAvailable: 1`, `startupFailureThreshold: 30`), `helm/values/order-service-prod.yaml` layers the prod differences, and `--set image.tag=$(git rev-parse HEAD)` pins the build:

```sh
helm template order-service helm/shopflow-service -f helm/values/order-service.yaml | kubeconform -strict -   # CI-style validation
helm upgrade --install order-service helm/shopflow-service -n shopflow -f helm/values/order-service.yaml \
  -f helm/values/order-service-prod.yaml --set image.tag=$(git rev-parse HEAD)
helm rollback order-service 1
```

What the comparison looks like with both in hand (`helm/README.md`):

| | Kustomize (`k8s/`) | Helm (`helm/`) |
|---|---|---|
| Model | copy + patch (overlays) | template + values |
| Reuse across services | `k8s/base/<service>` is copied per service (4 copies now) | one chart, N values files (~15 lines each) |
| Logic in manifests | none — readable, but repetitive | `if`/`range`/helpers in `_helpers.tpl` — powerful, harder to read and to diff |
| Releases / rollback | git history + `kubectl rollout undo` (Argo CD does the rest) | `helm history` / `helm rollback` per release |
| Third-party software | awkward | the standard (ingress-nginx, External Secrets, Argo CD are Helm charts) |
| Typical team setup | **both**: Helm for vendor charts, Kustomize (or Helm values per env) for your own apps | |

Rule of thumb from the README: when you find yourself copying `k8s/base/<service>` for the fourth time, you want a chart — adding payment-service was exactly that fourth copy. Both outputs are validated the same way in CI terms (`kubeconform -strict`); Argo CD can sync either (it detects a `Chart.yaml` or a `kustomization.yaml`).

---

## 19. Troubleshooting playbook

General first steps:
```bash
kubectl get pods -n shopflow -o wide
kubectl describe pod <pod> -n shopflow          # Events at the bottom = 80% of answers
kubectl logs <pod> -n shopflow [-c container] [--previous]
kubectl get events -n shopflow --sort-by=.lastTimestamp
```

### CrashLoopBackOff
Container starts, exits, kubelet restarts with exponential backoff (10s → 5 min).
```bash
kubectl logs <pod> --previous                    # logs of the crashed instance
kubectl describe pod <pod>                        # Last State: Terminated, Reason, Exit Code
kubectl get pod <pod> -o jsonpath='{.status.containerStatuses[0].lastState}'
```
| Exit code / reason | Likely cause |
|---|---|
| 1 | App exception: bad config, DB unreachable on startup, Flyway migration failed, `ddl-auto: validate` mismatch |
| 3 | `ExitOnOutOfMemoryError` — heap OOM |
| 137 + `OOMKilled` | Memory limit exceeded |
| 137 without OOMKilled | SIGKILL after the grace period — app ignored SIGTERM or shut down too slowly (e.g. after a liveness/startup failure; check events "Liveness probe failed") |
| 143 | SIGTERM — graceful stop (also typical after a liveness kill for a JVM that handles SIGTERM) |
| 126/127 | Bad command/ENTRYPOINT |

Debugging tricks: `kubectl debug -it <pod> --image=busybox --target=<container>` (ephemeral container), or temporarily override `command: ["sleep","3600"]` and exec in.

### ImagePullBackOff / ErrImagePull
```bash
kubectl describe pod <pod> | grep -A5 Events     # "not found", "unauthorized", "manifest unknown"
```
Causes: typo in image/tag; tag not pushed (ShopFlow CI pushes only on `main`!); private registry without `imagePullSecrets` (GHCR packages are private by default); node can't reach registry; arch mismatch (arm64 Mac build on amd64 nodes → `exec format error` is actually a CrashLoop). Dev overlay: image `shopflow/order-service:dev` must be loaded with `minikube image load shopflow/order-service:dev`, and `imagePullPolicy: IfNotPresent` is set so kubelet doesn't try Docker Hub.

### Pending
```bash
kubectl describe pod <pod>     # "0/3 nodes are available: 3 Insufficient cpu" / "didn't match node affinity" /
                               # "had untolerated taint" / "pod has unbound immediate PersistentVolumeClaims"
kubectl get pvc -n shopflow ; kubectl get sc
kubectl describe node <n> | grep -A8 Allocated
```
Causes: requests too big for any node, taints, affinity/`DoNotSchedule` spread, PVC unbound (no default StorageClass, AZ mismatch with EBS), ResourceQuota exceeded (that shows on the ReplicaSet events, pod isn't even created: `kubectl describe rs`).

### OOMKilled
```bash
kubectl describe pod <pod> | grep -B2 -A5 "Last State"     # Reason: OOMKilled, Exit Code: 137
kubectl top pod <pod> --containers
```
Fix: raise memory limit or lower `MaxRAMPercentage`; check non-heap (threads, metaspace, direct buffers); heap dump for leaks (`-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp` — `/tmp` is writable emptyDir in ShopFlow).

### CreateContainerConfigError
Referenced ConfigMap/Secret or key doesn't exist.
```bash
kubectl describe pod <pod>     # 'secret "shopflow-db" not found' / 'couldn't find key orders-db-password'
kubectl get secret -n shopflow
```
ShopFlow-specific: in **prod** the `shopflow-db` Secret is not in git — if External Secrets Operator hasn't synced it yet (`kubectl get externalsecret -n shopflow`), every pod is stuck here. Also `runAsNonRoot` with non-numeric image user produces `CreateContainerConfigError`.

### Service not reachable
Work from the outside in, or inside out:
```bash
kubectl get svc,endpointslices -n shopflow                 # 1. endpoints empty?
kubectl get pods -n shopflow --show-labels                 # 2. labels match svc selector?
kubectl get pods -n shopflow                               # 3. pods READY 1/1? (readiness failing → no endpoints)
kubectl port-forward pod/<pod> 8081:8081 -n shopflow       # 4. app itself works? curl localhost:8081/actuator/health
kubectl port-forward svc/order-service 8081:8081 -n shopflow  # 5. service works?
kubectl run tmp --rm -it --image=busybox -n shopflow -- sh # 6. DNS + connectivity from inside
  nslookup order-service ; wget -qO- http://order-service:8081/actuator/health
kubectl get networkpolicy -n shopflow                      # 7. NetworkPolicy blocking? (tmp pod has no allowed label → BLOCKED by default-deny!)
kubectl get ingress -n shopflow ; kubectl logs -n ingress-nginx deploy/ingress-nginx-controller   # 8. ingress host/path/class
```
Common causes: selector/label mismatch, `targetPort` wrong (named port `http` must exist on container), app listening on `127.0.0.1` instead of `0.0.0.0`, readiness failing, NetworkPolicy, wrong namespace in DNS, Ingress host header (`curl -H 'Host: shopflow.local' http://<ingress-ip>/api/v1/products`).

### Others
- **Running but not Ready** → readiness failing; `kubectl describe` shows `Readiness probe failed: HTTP probe failed with statuscode: 503` → check `/actuator/health/readiness` (DB down?).
- **Terminating forever** → finalizers, or node unreachable. `kubectl get pod <p> -o jsonpath='{.metadata.finalizers}'`.
- **Evicted** → node pressure (disk/memory); `kubectl describe node` conditions.
- **Rollout stuck** → `kubectl rollout status`; new pods not Ready; PDB/quota.

---

## 20. kubectl cheatsheet

```bash
# context & namespace
kubectl config get-contexts ; kubectl config use-context minikube
kubectl config set-context --current --namespace=shopflow
alias k=kubectl                                         # notes.txt uses "k"

# get / inspect
k get po -A                                             # all namespaces
k get po -o wide --show-labels
k get po -l app.kubernetes.io/name=order-service        # label selector
k get po --selector="tier=backend,app=hello-world"      # from notes.txt
k get deploy,rs,po,svc,ep,ing,hpa,pdb,pvc -n shopflow
k get po <p> -o yaml | less ; k get po <p> -o jsonpath='{.status.podIP}'
k describe po <p> ; k get events --sort-by=.lastTimestamp
k explain deployment.spec.strategy.rollingUpdate        # built-in docs
k api-resources

# logs & exec
k logs <p> -f --tail=100 ; k logs <p> --previous ; k logs <p> -c <container> ; k logs <p> --all-containers
k logs -l app.kubernetes.io/name=order-service --prefix
k exec -it <p> -- sh ; k exec -it <p> -c <container> -- sh
k debug -it <p> --image=busybox --target=order-service
k cp shopflow/<p>:/tmp/heap.hprof ./heap.hprof

# networking
k port-forward svc/order-service 8081:8081 -n shopflow
k port-forward deploy/hello-world 8081:8080            # notes.txt
minikube service backend-service --url ; minikube tunnel

# apply / change
k apply -f file.yaml ; k apply -f . ; k apply -k shopflow/k8s/overlays/dev
k diff -k shopflow/k8s/overlays/prod
k delete -f file.yaml ; k delete po --all
k scale deploy/order-service --replicas=3
k set image deploy/order-service order-service=ghcr.io/x/shopflow-order-service:<sha>
k rollout status deploy/order-service    # also: history | undo | restart
k create secret generic mysecret --from-literal=password=changeme --dry-run=client -o yaml
k create ns testing

# resources & nodes
k top po -n shopflow ; k top no
k cordon <node> ; k drain <node> --ignore-daemonsets --delete-emptydir-data ; k uncordon <node>
k taint nodes <node> key=value:NoSchedule
k auth can-i get secrets -n shopflow --as=system:serviceaccount:shopflow:default
```

---

## 21. Interview Q&A

**Q1. Explain the Kubernetes architecture.**
Control plane: API server (the only entry point, persists to etcd), etcd (state), scheduler (assigns pods to nodes), controller-manager (reconciliation loops). Each worker runs kubelet (starts containers through containerd and runs probes), kube-proxy (Service routing) and a CNI plugin. Everything works by controllers watching the API and reconciling actual state to desired state.

**Q2. Walk me through what happens when you `kubectl apply` a Deployment.**
(Use the 9-step flow in §1.) Key points: apiserver auth/admission/etcd; Deployment controller → ReplicaSet → Pods; scheduler binds; kubelet runs; readiness adds to endpoints.

**Q3. Liveness vs readiness vs startup — how did you configure them?**
Startup gives the JVM up to 60s (2s × 30) and disables the others until it passes. Liveness restarts a stuck app and checks only internal state. Readiness controls traffic and includes `readinessState` plus the service's own DB. I deliberately don't include inventory-service in order-service's readiness, because a downstream outage would pull all order pods out of the load balancer and turn one outage into two.

**Q4. Why no CPU limit?**
CPU over limit is throttled by CFS, and Java's startup and GC are bursty — limits slow startup, can fail the startup probe and add latency. Requests (250m) still guarantee a fair share under contention and drive scheduling and HPA. Memory does have a limit (512Mi) because memory isn't compressible, and the heap is sized at 75% of it.

**Q5. How do you achieve zero-downtime deployments?**
`maxUnavailable: 0, maxSurge: 1` so capacity never drops; readiness probes so new pods get traffic only when ready; preStop sleep 5s to let endpoint removal propagate; Spring graceful shutdown with a 20s phase; grace period 45s; PDB `minAvailable: 1` for node drains; backward-compatible DB migrations.

**Q6. What's a headless Service and why does Postgres use one?**
`clusterIP: None` — no virtual IP; DNS returns pod IPs directly and gives each StatefulSet pod a stable name like `postgres-0.postgres`. StatefulSets need it for stable network identity (`serviceName: postgres`).

**Q7. Deployment vs StatefulSet?**
(See table §2.) StatefulSet = stable names, ordered rollout, a PVC per replica that survives rescheduling. For production I'd still use RDS or an operator.

**Q8. Are Kubernetes Secrets secure?**
Not by themselves — base64 only. Secure them with RBAC, etcd encryption at rest (KMS), and keep them out of git with External Secrets Operator or Sealed Secrets. In ShopFlow dev uses `secretGenerator` with throwaway values; prod's `shopflow-db` comes from AWS Secrets Manager/Vault via ESO.

**Q9. How does HPA work? Why does it need requests?**
Every 15s (default sync period) it reads metrics from metrics-server and computes `ceil(currentReplicas × current/target)`, ignoring changes within a 10% tolerance. Utilization is a percentage of the **request**, so without a request it can't compute. Mine targets 70% CPU, 2–6 pods (prod 3–10), with a 5-min scale-down stabilization window to avoid flapping.

**Q10. PDB — what does it protect against?**
Voluntary disruptions — drains, upgrades, autoscaler scale-down — via the eviction API. Not node crashes. `minAvailable: 1` means a drain can't evict the last pod. Watch out: with 1 replica, it blocks drains forever.

**Q11. What is a NetworkPolicy and how did you use it?**
Pod-level firewall enforced by the CNI. Default-deny all ingress (and egress except DNS) in the namespace, then allow ingress-nginx and monitoring to the apps, order-service to inventory, order/notification/payment to Kafka, inventory to Redis, order-service to Keycloak for the JWKS, and only the four services to Postgres on 5432.

**Q12. Pod is in CrashLoopBackOff — what do you do?**
`kubectl describe` for exit code/reason and events, `kubectl logs --previous`. 137+OOMKilled → memory; probe failures → probe timing / startup probe; exit 1 → app logs (bad env, DB, Flyway). Fix and redeploy; `kubectl debug` if the image has no shell.

**Q13. Kustomize or Helm — why Kustomize here?**
For my own four services with small env differences, plain YAML + overlays is readable, needs no templating and is built into kubectl — and `$patch: delete` in the prod overlay cleanly swaps in-cluster Postgres/Kafka/Redis/Keycloak for managed AWS services. The honest counter-argument is in the repo too: by the fourth service `k8s/base/<service>` is a fourth copy, so I also wrote a generic chart (`helm/shopflow-service`) where a service is a 15-line values file; `helm/README.md` compares both. I'd use Helm for third-party things like ingress-nginx, kube-prometheus-stack, cert-manager, and for my own apps once the copies outnumber the readability benefit.

**Q14. How do taints/tolerations differ from node affinity?**
Taints are on nodes and repel pods that don't tolerate them; affinity is on pods and attracts them to nodes. A toleration only *allows* scheduling on a tainted node — to *force* it there you also need affinity.

**Q15. What's QoS class of your pods and why does it matter?**
Burstable (memory request ≠ limit, no CPU limit). Under node memory pressure BestEffort pods are evicted first, then Burstable exceeding their requests, Guaranteed last. For critical pods you could make them Guaranteed.

**Q16. How do pods in different namespaces communicate?**
DNS `service.namespace` (or FQDN `service.namespace.svc.cluster.local`), if NetworkPolicies allow it.

---

## 22. Scenario questions

**S1. "During every deployment we see a few 502s."**
Likely endpoint propagation race: pod gets SIGTERM before ingress stops routing. Add `preStop` sleep (ShopFlow: 5s), `server.shutdown: graceful`, ensure exec-form ENTRYPOINT, `maxUnavailable: 0`, readiness probe so new pods aren't routed too early, grace period > preStop + shutdown.

**S2. "Pods restart every few minutes, no errors in logs."**
`describe`: liveness probe failing (timeouts under load or GC pauses) or OOMKilled. Check `timeoutSeconds` (default 1s), liveness must not depend on DB, look at CPU throttling, memory.

**S3. "New version deployed, all pods stuck 0/1 Ready, old pods still running."**
That's `maxUnavailable: 0` protecting you. Check readiness endpoint — e.g. new code's Flyway migration failed or DB unreachable. `kubectl rollout undo` (or git revert in GitOps).

**S4. "HPA shows `<unknown>/70%`."**
metrics-server missing or pods have no CPU request. `kubectl top pods`, `kubectl describe hpa`.

**S5. "Node drain during cluster upgrade never finishes."**
PDB blocking (e.g., 1 replica + `minAvailable: 1`), or pods with local storage. Scale up temporarily or adjust PDB; `--delete-emptydir-data`.

**S6. "Postgres pod moved to another node and is Pending."**
EBS volumes are AZ-bound; the PV has node affinity to zone A, so the pod can only schedule on a zone-A node. Fix: make sure there's capacity in that AZ (per-AZ node groups / Karpenter), snapshot-and-restore to move zones, or use a managed Multi-AZ DB. `WaitForFirstConsumer` only helps at *creation* time (volume created in the pod's zone), it can't move an existing volume.

**S7. "After enabling NetworkPolicies, Prometheus can't scrape and DNS fails."**
Ingress: allow monitoring namespace (ShopFlow does). If you add default-deny **egress**, you must explicitly allow DNS to kube-dns on 53 UDP/TCP.

**S8. "Developer wants to change one config value in prod quickly."**
Change it in git (overlay / configMapGenerator) → PR → Argo CD sync; the hash suffix triggers a rolling restart. Avoid `kubectl edit` (drift, overwritten by GitOps).

---

## 23. Common mistakes

- Liveness probe checking DB/downstream → restart storms.
- Readiness probe including downstream services → cascading outage.
- No startup probe, huge `initialDelaySeconds` instead.
- CPU limits on JVM apps causing throttling; memory limit with heap = 100% of it.
- No requests → BestEffort, HPA broken, bad scheduling.
- `:latest` tags + `imagePullPolicy: Always` surprises.
- `maxUnavailable: 1` with 1 replica → downtime.
- PDB `minAvailable` ≥ replicas → drains blocked.
- Selector/label mismatch → Service with no endpoints.
- Thinking Secrets are encrypted; committing them to git.
- NetworkPolicies on a CNI that doesn't enforce them (false sense of security).
- Running stateful DBs in K8s without backups/operator.
- Shell-form ENTRYPOINT → SIGTERM never reaches Java.
- `kubectl edit` in prod when using GitOps.
- Forgetting `-n` namespace and "losing" resources (`kubectl get po -A`).

> Tip: Troubleshooting questions mein hamesha sequence bolo: `get` → `describe` (events) → `logs --previous` → `exec/port-forward` → fix. Interviewer structured thinking dekhna chahta hai.
