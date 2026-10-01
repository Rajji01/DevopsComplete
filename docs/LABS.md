# Hands-on Labs: break it, debug it, fix it

You learn production by breaking things in a safe place. Every lab follows the same shape: **Goal → Do → Observe → Why → Challenge**. Interview questions on these topics are in [`interview-notes/`](interview-notes/).

Setup used by the labs (run everything from `shopflow/`):

| Labs | Environment |
|---|---|
| 1–4, 12–13 | Docker Compose: `docker compose up --build -d` |
| 5–11, 15 | minikube: see "Kubernetes" in [`../shopflow/README.md`](../shopflow/README.md) |
| 14 | GitHub: a branch + pull request |

Handy aliases:
```sh
alias k=kubectl
alias kn='kubectl -n shopflow'
order() { curl -s -XPOST localhost:8081/api/v1/orders -H 'Content-Type: application/json' "$@"; echo; }
```

---

## Lab 1: Explore the system

**Goal:** know the API and the data before breaking anything.

**Do**
```sh
docker compose ps                                  # all healthy?
curl -s localhost:8082/api/v1/products | jq
order -d '{"sku":"IPHONE-15","quantity":1}' | jq
curl -s 'localhost:8081/api/v1/orders?size=5' | jq
open http://localhost:8081/swagger-ui.html          # or paste in browser
docker compose exec postgres psql -U postgres -d orders -c 'select id, sku, status from orders;'
docker compose exec postgres psql -U postgres -d inventory -c 'select * from reservation;'
docker compose exec postgres psql -U postgres -d orders -c 'select version, description from flyway_schema_history;'
```

**Observe:** every order has an `orderRef`, and the same value appears in `inventory.reservation.order_ref`. That's how the two services correlate without a shared database.

**Why:** database-per-service. Each service owns its data, and they only talk via APIs.

**Challenge:** try to read the `orders` table while connected as the `inventory` user. Why does it fail, and why is that good?

---

## Lab 2: Prove there's no overselling

**Goal:** 50 people buy the last 3 PS5s at the same moment.

**Do**
```sh
docker compose down -v && docker compose up -d     # fresh DB: PS5-SLIM = 3
# wait until healthy, then fire 50 parallel orders
seq 1 50 | xargs -P 50 -I{} curl -s -XPOST localhost:8081/api/v1/orders \
  -H 'Content-Type: application/json' -d '{"sku":"PS5-SLIM","quantity":1}' \
  | grep -o '"status":"[A-Z]*"' | sort | uniq -c
curl -s localhost:8082/api/v1/products/PS5-SLIM
```

**Observe:** exactly `3 CONFIRMED`, `47 REJECTED`, and the stock is `0`. It never goes negative.

**Why:** `ProductRepository.decrementStock` does the check and the update in **one SQL statement** (`... WHERE quantity >= :qty`). The row lock serialises concurrent updates. If the code did "read stock in Java, then write", two threads would both read `1` and both sell it.

**Challenge:** temporarily rewrite `ReservationService.reserve` as read-then-save (`findBySku`, check, `setQuantity`, `save`) and run `./mvnw -pl inventory-service test`. Watch `concurrentReservationsNeverOversell` fail. Then revert, and explain optimistic vs pessimistic locking as alternatives.

---

## Lab 3: Idempotency (the double-click problem)

**Goal:** the user clicks "Pay" twice, or the mobile network retries the request.

**Do**
```sh
order -H 'Idempotency-Key: cart-777' -d '{"sku":"AIRPODS-PRO","quantity":1}' -w '%{http_code}\n'
order -H 'Idempotency-Key: cart-777' -d '{"sku":"AIRPODS-PRO","quantity":1}' -w '%{http_code}\n'
```

**Observe:** the first call returns `201`, the second returns `200` with the **same order id**, and stock dropped only once.

**Why:** there are two layers of idempotency. The client → order-service call uses the `Idempotency-Key` (unique column). The order-service → inventory call uses `orderRef` (unique column on reservation). The second layer is what makes it safe for order-service to *retry* a timed-out call.

**Challenge:** send the same key with a *different* body. What should happen in a real payment API? (Hint: Stripe returns an error. Try to implement that.)

---

## Lab 4: Kill a dependency (retry + circuit breaker)

**Goal:** see resilience patterns work for real.

**Do**
```sh
docker compose stop inventory-service
for i in $(seq 1 6); do order -d '{"sku":"PIXEL-9","quantity":1}' -o /dev/null -w '%{http_code} %{time_total}s\n'; done
curl -s localhost:8081/actuator/prometheus | grep 'circuitbreaker_state{.*} 1.0'
curl -s localhost:8081/actuator/health/readiness
docker compose start inventory-service
sleep 15; order -d '{"sku":"PIXEL-9","quantity":1}'
```

**Observe**
- The first calls take ~0.5 s: 3 attempts with exponential backoff.
- After about 5 failed calls the breaker is `open` and calls return `503` in ~10 ms. Inventory isn't even called.
- Readiness stays `UP`, so order-service keeps serving `GET` requests.
- After `wait-duration-in-open-state` (10 s), the breaker goes `half_open`, lets test calls through, and closes again.

**Why:** without timeouts and a breaker, every request thread waits on a dead service. Thread pools fill up and order-service dies too (a cascading failure). Readiness doesn't include inventory, so one outage doesn't become two.

**Challenge:** in Prometheus (http://localhost:9090), graph `resilience4j_circuitbreaker_state{state="open"}` while you repeat this. Then change `failure-rate-threshold` and observe the difference.

---

## Lab 5: Readiness vs liveness (K8s)

**Goal:** see why there are two probes.

**Do**
```sh
kn get pods -w &                                   # keep watching
kn scale statefulset postgres --replicas=0         # database goes away
kn get endpoints order-service inventory-service
kn describe pod -l app.kubernetes.io/name=order-service | grep -A3 Readiness
kn scale statefulset postgres --replicas=1
```

**Observe:** pods become `0/1 READY` but **do not restart** (RESTARTS stays the same), and the Service endpoints become empty. When Postgres comes back, the pods are ready again without a restart.

**Why:** readiness = "should I get traffic?" (it includes the service's own DB). Liveness = "is the process stuck?" (it doesn't check the DB). If liveness checked the DB, a DB blip would restart every pod, which is a restart storm.

**Challenge:** add `db` to the liveness group in `application.yml`, redeploy, repeat the lab, and count the restarts.

---

## Lab 6: OOMKilled

**Goal:** recognise and fix memory problems.

**Do**
```sh
kn set resources deployment/order-service --limits=memory=200Mi --requests=memory=200Mi
kn get pods -w
kn describe pod -l app.kubernetes.io/name=order-service | grep -iA5 'last state'
kn get events --sort-by=.lastTimestamp | tail
```

**Observe:** the pod ends up in `OOMKilled` (exit code 137), or in `CrashLoopBackOff` if the JVM exits early because `-XX:+ExitOnOutOfMemoryError` fired.

**Why:** the container limit is enforced by the kernel (cgroups). The JVM sizes its heap from the limit (`MaxRAMPercentage=75`), but metaspace, thread stacks and direct buffers live outside the heap. Spring Boot needs roughly 300–400 Mi in total.

**Fix:** `kubectl apply -k k8s/overlays/dev` (back to 384Mi request / 512Mi limit).

**Challenge:** explain the QoS classes (Guaranteed / Burstable / BestEffort). Which one do the ShopFlow pods get, and why?

---

## Lab 7: CrashLoopBackOff from bad config

**Goal:** debug a pod that starts and dies.

**Do:** in `k8s/overlays/dev/kustomization.yaml`, change `orders-db-password=orders` to `orders-db-password=wrong`, then:
```sh
kubectl apply -k k8s/overlays/dev
kn get pods
kn logs deploy/order-service --previous | grep -i 'password\|FATAL' | head
```

**Observe:** `password authentication failed for user "orders"` appears, and the pod restarts with growing back-off.

**Why:** `secretGenerator` adds a content hash to the Secret name, so changing a secret rolls the pods automatically. Postgres only runs `init-db.sh` on an empty volume, so the DB still has the old password.

**Fix:** revert the change and apply again. Notice that `maxUnavailable: 0` kept the old healthy pod serving the whole time.

**Challenge:** what are `CreateContainerConfigError` and `RunContainerError`? Cause each one on purpose (hint: reference a secret key that doesn't exist).

---

## Lab 8: ImagePullBackOff

**Do**
```sh
kn set image deployment/inventory-service inventory-service=shopflow/inventory-service:does-not-exist
kn get pods
kn describe pod <new-pod> | tail -15
kn rollout undo deployment/inventory-service
```

**Observe:** `ErrImagePull`, then `ImagePullBackOff`, while the old pods keep serving (thanks to the rolling update + `maxUnavailable: 0`).

**Challenge:** list 4 causes of ImagePullBackOff (wrong tag, private registry without `imagePullSecrets`, rate limit, architecture mismatch) and how you'd tell them apart.

---

## Lab 9: Zero-downtime deploy + rollback

**Goal:** prove there is zero downtime.

**Do**
```sh
# terminal 1: continuous traffic, print only failures
while true; do curl -s -o /dev/null -w '%{http_code}\n' http://shopflow.local/api/v1/products; sleep 0.1; done | grep -v 200

# terminal 2: build a new version and roll it out
docker build --build-arg SERVICE=inventory-service -t shopflow/inventory-service:v2 .
minikube image load shopflow/inventory-service:v2
kn set image deployment/inventory-service inventory-service=shopflow/inventory-service:v2
kn annotate deployment/inventory-service kubernetes.io/change-cause="v2: demo"
kn rollout status deployment/inventory-service
kn rollout history deployment/inventory-service
kn rollout undo deployment/inventory-service
```

**Observe:** terminal 1 prints nothing, so there were no failed requests.

**Why:** zero downtime needs all of these together: `maxUnavailable: 0`, a readiness probe, a `preStop` sleep (so endpoints are removed before shutdown), and `server.shutdown: graceful` (in-flight requests finish).

**Challenge:** remove the `preStop` hook, repeat a few times, and look for 502/503s. Then explain why that race happens.

---

## Lab 10: Autoscaling (HPA)

**Do**
```sh
kn get hpa -w &
# generate load from your machine (install 'hey', or use a bash loop with xargs -P)
hey -z 3m -c 50 http://shopflow.local/api/v1/products
kn top pods
```

**Observe:** CPU % (of the *request*) rises above 70%, the replicas go up to `maxReplicas`, and after the load stops they scale down only after the 5-minute stabilization window.

**Challenge:** why is HPA on CPU a weak signal for an IO-bound service? Read about scaling on custom metrics (requests per second via Prometheus Adapter, or KEDA).

---

## Lab 11: NetworkPolicy (zero trust)

Needs `minikube start --cni=calico`.

**Do**
```sh
kn run hacker --rm -it --image=busybox --restart=Never -- \
  wget -qO- -T 3 http://inventory-service:8082/api/v1/products     # should time out
kn exec deploy/order-service -- wget -qO- -T 3 http://inventory-service:8082/api/v1/products  # works
```

**Observe:** a random pod can't reach inventory or Postgres, but order-service can.

**Why:** `default-deny-ingress` + explicit allow rules (`k8s/base/network-policies.yaml`). If one pod is compromised, the attacker can't move sideways to everything else.

**Challenge:** write an egress policy so order-service can talk only to inventory-service, Postgres and DNS (port 53). Don't forget DNS!

---

## Lab 12: Safe database migration (expand → contract)

**Goal:** add a column without downtime.

**Do**
1. Add `order-service/src/main/resources/db/migration/V2__add_customer_email.sql`:
   `alter table orders add column customer_email varchar(255);` (nullable, which is the **expand** step)
2. Add the field to `Order.java`, run `./mvnw -pl order-service test`, and rebuild.
3. Now try a mistake: add the field to `Order.java` **without** a migration and run the tests.

**Observe:** step 3 fails at startup with `Schema-validation: missing column`. That's `ddl-auto: validate` protecting you.

**Why:** during a rolling update, old and new pods run at the same time against the same DB. So migrations must be backward compatible: add the nullable column → deploy code that writes it → backfill → only then make it `NOT NULL` or drop old columns (the **contract** step).

**Challenge:** how would you *rename* a column with zero downtime? (Answer: in several releases.)

---

## Lab 13: Write an alert and fire it

**Do**
1. In `monitoring/alert-rules.yml`, add an alert: rejected orders above 50% over 5 minutes:
   ```yaml
   - alert: HighOrderRejectionRate
     expr: sum(rate(orders_total{status="REJECTED"}[5m])) / sum(rate(orders_total[5m])) > 0.5
     for: 1m
   ```
2. `docker compose restart prometheus`, then send many out-of-stock orders (`quantity: 99`).
3. Watch http://localhost:9090/alerts: *inactive → pending → firing*.

**Why:** alert on symptoms that matter to the business. `for:` avoids paging on short blips.

**Challenge:** write a PromQL query for the p95 latency of `POST /api/v1/orders` only (hint: `uri` and `method` labels on `http_server_requests_seconds_bucket`).

---

## Lab 14: Break the pipeline

**Do:** on a new branch, make three separate commits and push after each. Watch the **shopflow** workflow on GitHub.
1. Change an assertion in `OrderServiceApplicationTests` so it fails → the `test` job goes red, and the image jobs never run (`needs:`).
2. Delete the `selector:` from `k8s/base/order-service/service.yaml` and make a typo in a Deployment field (e.g. `replicaz: 2`) → `validate-manifests` goes red (kubeconform `-strict`).
3. Change the runtime base image in the `Dockerfile` to an old one (e.g. `eclipse-temurin:21.0.1_12-jre-alpine`) → the Trivy scan may fail on known CVEs.

**Why:** fail fast, and fail before anything reaches a registry or a cluster. This is "shift left".

**Challenge:** add a job that runs only on `main` and opens a PR bumping the image tag in `k8s/overlays/prod`. That's GitOps: Argo CD then deploys the merged PR.

---

## Lab 15: Graceful shutdown under load

**Do:** run the traffic loop from Lab 9 against `/api/v1/orders` (POST), then delete pods repeatedly:
```sh
for i in 1 2 3; do kn delete pod -l app.kubernetes.io/name=order-service --wait=false; sleep 20; done
```

**Observe:** no failed requests, as long as at least one other replica is ready (the dev overlay runs 1 replica, so scale to 2 first: `kn scale deploy/order-service --replicas=2`).

**Why:** the pod shutdown sequence is: removed from endpoints + `preStop` sleep 5 s → SIGTERM → Spring stops accepting requests and finishes the in-flight ones (up to 20 s) → exit. This must fit in `terminationGracePeriodSeconds: 45`.

**Challenge:** what happens if the app ignores SIGTERM? (Hint: SIGKILL after the grace period. Shell-form `ENTRYPOINT` is a classic cause, because PID 1 is `sh`, which doesn't forward the signal.)
