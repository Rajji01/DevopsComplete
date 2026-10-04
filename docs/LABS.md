# Hands-on Labs: break it, debug it, fix it

You learn production by breaking things in a safe place. Every lab follows the same shape: **Goal → Do → Observe → Why → Challenge**. Interview questions on these topics are in [`interview-notes/`](interview-notes/).

Setup used by the labs (run everything from `shopflow/`):

| Labs | Environment |
|---|---|
| 1–4, 12–13, 16–22, 24–25 | Docker Compose: `docker compose up --build -d` |
| 5–11, 15 | minikube: see "Kubernetes" in [`../shopflow/README.md`](../shopflow/README.md) |
| 14 | GitHub: a branch + pull request |
| 23 | kubectl + Terraform (plan needs an AWS account) |

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

**Challenge:** egress is locked down too (`default-deny-egress-allow-dns`, `order-service-egress`, `inventory-service-egress`). Delete the DNS rule from the default-deny policy, redeploy, and watch every order fail with `UnknownHostException` even though nothing else changed. Then put it back. That is the most common NetworkPolicy mistake in real clusters.

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

---

# Part 2: events, security, cache, tracing, AWS

Setup: `docker compose up --build -d` from `shopflow/`, plus a token helper:
```sh
token() { curl -s -X POST http://localhost:8180/realms/shopflow/protocol/openid-connect/token \
  -d grant_type=password -d client_id=shopflow-web -d username=$1 -d password=$1 | jq -r .access_token; }
TOKEN=$(token alice)                      # alice = customer, bob = support
auth() { curl -s -H "Authorization: Bearer $TOKEN" "$@"; }
```

## Lab 16: JWT — who is allowed to do what

**Do**
```sh
curl -s -o /dev/null -w '%{http_code}\n' -XPOST localhost:8081/api/v1/orders -H 'Content-Type: application/json' -d '{"sku":"PIXEL-9","quantity":1}'      # no token
auth -o /dev/null -w '%{http_code}\n' -XPOST localhost:8081/api/v1/orders -H 'Content-Type: application/json' -d '{"sku":"PIXEL-9","quantity":1}'  # alice
TOKEN=$(token bob); auth -o /dev/null -w '%{http_code}\n' -XPOST localhost:8081/api/v1/orders -H 'Content-Type: application/json' -d '{"sku":"PIXEL-9","quantity":1}'
auth 'localhost:8081/api/v1/orders?size=2' | jq '.content[].status'                                       # bob may read
echo $TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | jq '{iss, exp, realm_access}'                          # look inside the token
```

**Observe:** `401` without a token, `201` for alice, `403` for bob on POST but `200` on GET. The token's `iss` must equal `JWT_ISSUER_URI`, and `realm_access.roles` is where the roles come from.

**Why:** order-service never sees a password. It downloads Keycloak's public keys (JWKS) once and verifies the signature locally, so authentication costs nothing per request and Keycloak can be down without taking orders down (already-issued tokens still verify).

**Challenge:** wait 15 minutes (token lifetime) and call again. What do you get, and how would a frontend handle it? (Refresh tokens.) Then swap `JWT_ISSUER_URI` to the Cognito issuer format from `infra/terraform/aws/outputs.tf` and explain what else must change (nothing in the code: the converter also reads `cognito:groups`).

## Lab 17: The outbox — kill Kafka and nothing is lost

**Do**
```sh
docker compose stop kafka
for i in 1 2 3; do auth -XPOST localhost:8081/api/v1/orders -H 'Content-Type: application/json' -d '{"sku":"AIRPODS-PRO","quantity":1}' | jq -r .status; done
curl -s localhost:8081/actuator/prometheus | grep '^outbox_unpublished'
docker compose exec postgres psql -U postgres -d orders -c 'select event_type, published_at from outbox_event order by created_at desc limit 5;'
docker compose start kafka; sleep 20
curl -s localhost:8081/actuator/prometheus | grep '^outbox_unpublished'
docker compose logs notification-service | grep EMAIL | tail -3
```

**Observe:** orders are still `CONFIRMED` while Kafka is down (the customer is never blocked by the notification path). `outbox_unpublished` is 3, the rows have `published_at = null`. After Kafka returns the relay drains the backlog and the three emails appear.

**Why:** the event is written in the same transaction as the order, so it exists exactly when the order exists. Compare with "save order, then `kafkaTemplate.send()`": a crash between the two loses the event, and a Kafka outage makes order placement fail.

**Challenge:** with Kafka stopped, place 101 orders (a loop) and watch `OutboxBacklogGrowing` go *pending* → *firing* in Prometheus → Alerts after 5 minutes.

## Lab 18: At-least-once — deliver the same event twice

**Do**
```sh
EVENT_ID=$(uuidgen); REF=lab18-$RANDOM
MSG="{\"eventId\":\"$EVENT_ID\",\"type\":\"OrderConfirmed\",\"orderRef\":\"$REF\",\"sku\":\"PS5-SLIM\",\"quantity\":1,\"status\":\"CONFIRMED\",\"occurredAt\":\"2026-10-04T10:00:00Z\"}"
for i in 1 2 3; do echo "$REF:$MSG" | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 --topic orders.events --property parse.key=true --property key.separator=:; done
docker compose logs notification-service | grep -E "EMAIL.*$REF|duplicate event" | tail -4
curl -s localhost:8083/actuator/prometheus | grep -E '^notifications_(sent|duplicates)_total'
```

**Observe:** one `EMAIL` line, two `duplicate event ... skipped` lines, `notifications_duplicates_total` went up by 2.

**Why:** Kafka guarantees at-least-once, not exactly-once, between independent systems. The consumer makes the *effect* exactly-once by recording `eventId` in the same transaction as the side effect. This is the "idempotent consumer" pattern; it is why every event carries an `eventId`.

**Challenge:** remove the `existsById` check, restart, repeat. Then put it back. Now explain why "exactly-once" in Kafka's own docs does not cover sending an email.

## Lab 19: Poison pill → dead-letter topic

**Do**
```sh
echo 'bad:this is not json' | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic orders.events --property parse.key=true --property key.separator=:
auth -XPOST localhost:8081/api/v1/orders -H 'Content-Type: application/json' -d '{"sku":"PIXEL-9","quantity":1}' | jq -r .orderRef   # a good event right behind it
docker compose logs -f notification-service | grep -E "EMAIL|Backoff|DLT"      # ctrl-c after ~15s
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic orders.events.DLT --from-beginning --max-messages 1 --property print.headers=true
```

**Observe:** the bad record is retried with growing backoff for ~10 s, then lands on `orders.events.DLT` with headers describing the exception; the good order's email still arrives.

**Why:** without an error handler a single bad message blocks its partition forever (the consumer keeps re-reading the same offset). A DLT turns an outage into a ticket: someone inspects the message, fixes the bug, and replays it.

**Challenge:** write a tiny replay: consume from the DLT and produce back to `orders.events`. When is replaying dangerous? (Non-idempotent consumers, ordering.)

## Lab 20: Cache-aside — see Redis work, and see why eviction matters

**Do**
```sh
docker compose exec redis redis-cli FLUSHALL >/dev/null
curl -s -o /dev/null -w 'cold: %{time_total}s\n' localhost:8082/api/v1/products/PIXEL-9
curl -s -o /dev/null -w 'warm: %{time_total}s\n' localhost:8082/api/v1/products/PIXEL-9
docker compose exec redis redis-cli KEYS 'inventory:*'
docker compose exec redis redis-cli TTL 'inventory:products::PIXEL-9'
auth -XPOST localhost:8081/api/v1/orders -H 'Content-Type: application/json' -d '{"sku":"PIXEL-9","quantity":1}' >/dev/null
docker compose exec redis redis-cli KEYS 'inventory:*'          # PIXEL-9 is gone
curl -s localhost:8082/api/v1/products/PIXEL-9 | jq .quantity    # fresh value
```

**Observe:** the second read is faster and comes from Redis; the key has a 60 s TTL; a reservation evicts it so the next read is correct.

**Why:** reads outnumber writes by orders of magnitude, but a wrong stock number in a flash sale is worse than a slow one. Evicting on write (instead of updating the cache) avoids a race where two writers update the cache in the wrong order. The TTL is only a safety net.

**Challenge:** stop Redis (`docker compose stop redis`). What happens to product reads and to readiness? (Reads fail: Spring Cache does not fall back. How would you make the cache *optional*? Hint: `CacheErrorHandler`.) Also: why is the DTO cached and not the JPA entity?

## Lab 21: Rate limiting — 429 before the database melts

**Do**
```sh
# 200 requests as fast as possible with 20 workers (install 'hey', or use the xargs loop from Lab 2)
hey -n 200 -c 20 -m POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"sku":"AIRPODS-PRO","quantity":1}' http://localhost:8081/api/v1/orders | grep -A6 'Status code'
curl -s localhost:8081/actuator/prometheus | grep 'resilience4j_ratelimiter_available_permissions'
```

**Observe:** about 50 × `201` per second, the rest `429` with a problem+json body. Latency of the accepted ones stays flat.

**Why:** the HPA needs minutes to add pods; the rate limiter protects Postgres and inventory in the first seconds of a burst. Per-pod limits are simple but add up with replicas; a shared limit needs Redis or the gateway.

**Challenge:** why is a limiter in the service weaker than one at the ingress/API gateway? (The request already consumed a thread and a TLS handshake.) Sketch where you would put a per-user limit.

## Lab 22: Follow one order across three services (tracing)

**Do**
1. Place an order (Lab 16) and copy its `orderRef`.
2. Grafana → Explore → **Loki** → query `{service="order-service"} |= "<orderRef>"`. Click the `trace.id` value in the log line.
3. You land in **Tempo**: spans for `POST /api/v1/orders` → `POST /api/v1/reservations` (inventory) → the Kafka send → `orders.events process` in notification-service.
4. In the trace, click "Logs for this span" to jump back to Loki filtered by that trace id.

**Observe:** one `trace.id` appears in the logs of all three services; the Kafka hop carries it in message headers (`traceparent`).

**Why:** metrics tell you *that* p99 is bad, traces tell you *where* (which span is slow), logs tell you *why*. Linking them by trace id is what turns a 40-minute investigation into 4 minutes.

**Challenge:** set `TRACING_SAMPLE=0.1` on order-service and explain the trade-off. Then add a custom span around `decrementStock` with `@Observed` and find it in Tempo.

## Lab 23: Render prod — the AWS overlay

**Do**
```sh
kubectl kustomize k8s/overlays/prod > /tmp/prod.yaml
grep -E '^kind:' /tmp/prod.yaml | sort | uniq -c           # no Postgres/Kafka/Redis/Keycloak
grep -E 'image: ghcr|rds-endpoint|cognito|ExternalSecret|alb.ingress' /tmp/prod.yaml
cd ../infra/terraform/aws && terraform init -backend=false && terraform validate && terraform plan   # needs AWS creds for plan
```

**Observe:** the same base renders to in-cluster dependencies for dev and to managed-service endpoints for prod; secrets come from an `ExternalSecret`, not from git; the Ingress becomes an ALB with an ACM certificate.

**Why:** databases, brokers and identity are *undifferentiated heavy lifting*; a managed service gives you backups, failover and patching for money, which is cheaper than an on-call engineer learning Kafka operations at 3 AM.

**Challenge:** read `infra/terraform/aws/README.md`'s cost section and produce a "learning budget" variant (`terraform.tfvars`) that costs under $150/month. Which pillar of the Well-Architected Framework did you trade away, and is that acceptable for a dev environment?

## Lab 24: IDOR — read someone else's order (and fail)

**Do**
```sh
TOKEN=$(token alice); ID=$(auth -XPOST localhost:8081/api/v1/orders -H 'Content-Type: application/json' -d '{"sku":"PIXEL-9","quantity":1}' | jq -r .id)
auth -o /dev/null -w 'alice GET: %{http_code}\n' localhost:8081/api/v1/orders/$ID
# carol is also a customer (create her in Keycloak admin UI, role customer), then:
TOKEN=$(token carol); auth -o /dev/null -w 'carol GET: %{http_code}\n' localhost:8081/api/v1/orders/$ID
auth -o /dev/null -w 'carol DELETE: %{http_code}\n' -XDELETE localhost:8081/api/v1/orders/$ID
TOKEN=$(token bob); auth -o /dev/null -w 'support GET: %{http_code}\n' localhost:8081/api/v1/orders/$ID
```

**Observe:** owner 200, other customer **404** (not 403) on GET and DELETE, support 200.

**Why:** "has role customer" is authentication-ish; "owns this order" is authorization. Without the second check any logged-in user can walk through ids `1, 2, 3...`: OWASP API Top-10 #1. 404 instead of 403 so an attacker can't even learn which ids exist.

**Challenge:** check out the commit before this fix (`git log --grep IDOR`), run the same curl, and watch carol cancel alice's order. Then explain why the fix lives in the service layer and not only in the controller.

## Lab 25: Limbo — what happens to FAILED orders

**Do**
```sh
docker compose stop inventory-service
TOKEN=$(token alice); auth -XPOST localhost:8081/api/v1/orders -H 'Content-Type: application/json' -d '{"sku":"PIXEL-9","quantity":1}' | jq '{status, orderId}'
docker compose start inventory-service
# reconciler runs every 60s with a 2-minute grace period; watch it settle the order
docker compose logs -f order-service | grep -E "reconciled|stays FAILED"
auth 'localhost:8081/api/v1/orders?size=3' | jq '.content[] | {id, status}'
```

**Observe:** the order is `FAILED` (503 with `orderId`), and ~2–3 minutes later `reconciled FAILED order ... -> CANCELLED` appears; an `OrderCancelled` event reaches notification-service.

**Why:** `FAILED` means *outcome unknown*: maybe inventory reserved the stock and the response was lost. Leaving it is a leak of stock and a confused customer. Because release is idempotent, the reconciler can always call it safely and give the order a definite end state.

**Challenge:** make the reconciler smarter: call `GET /api/v1/reservations/{orderRef}` (you'll need to add it) and *confirm* the order if the reservation exists instead of cancelling. Which is better for the business, and what new failure mode does it add?
