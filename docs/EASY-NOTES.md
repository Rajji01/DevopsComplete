# Easy Notes: har concept ka "kyu" aur "kaise"

Ye file interview notes se alag hai. Yahan har cheez simple Hinglish mein hai, ek hi pattern mein:

> **Problem** (prod mein kya toota) → **Kyu** (ye concept kyu bana) → **Kaise** (steps) → **ShopFlow mein kahan** → **System kaise behtar hua** → **Ek line mein yaad rakho**

Pehle ye padho, phir labs karo ([`LABS.md`](LABS.md)), phir interview notes ([`interview-notes/`](interview-notes/)). Concept samajh aa gaya to interview answer apne aap ban jaata hai.

---

## Part 0: Production system kaise grow karta hai (big picture)

Koi bhi real system ek din mein "production-grade" nahi banta. Woh **incidents se seekh kar** step by step improve hota hai. ShopFlow ka safar bhi waisa hi hai:

| Stage | System kaisa dikhta hai | Kya toot-ta hai | Kya add hota hai |
|---|---|---|---|
| **v0: Tutorial** (`Devops/`, `microservice01/`) | 2 services, hardcoded IP, no tests, 128Mi limit | Local pe start nahi hota, pod OOMKilled, galat port | Config defaults, tests, sahi resources, CI |
| **v1: Works correctly** (ShopFlow pehla version) | Order + Inventory, Postgres, atomic stock update, idempotency | Flash sale mein oversell nahi hota, lekin inventory down ho to order-service bhi latak jaata hai | Timeouts, retry, circuit breaker, probes |
| **v2: Survives failures** | Resilience4j, readiness/liveness, graceful shutdown, HPA, PDB, NetworkPolicy, alerts | Deploy pe scale-down, drain hang, alert kabhi fire nahi hota, DB connections khatam | Self-walk fixes (`SELF-WALK.md` Walk 2) |
| **v3: Real product** | Kafka outbox + notification-service, JWT, Redis cache, rate limit, tracing | "Email do baar gaya", "kaunsi service slow hai?", "ye API kaun call kar sakta hai?" | Idempotent consumer, DLT, OAuth2, cache-aside, OpenTelemetry |
| **v4: On AWS** | EKS + RDS + MSK + ElastiCache + Cognito, Terraform, GitOps | Kaun DB ka backup lega? Kaun Kafka patch karega 3 AM ko? | Managed services, IRSA, External Secrets, Argo CD |

**Pattern yaad rakho:** har stage pe ek **naya type ka failure** aata hai, aur uska ek **standard pattern** hota hai. Production engineer woh hai jise ye mapping yaad hai: *ye symptom → ye cause → ye pattern*.

> Tip: Interview mein "tell me about your project" ka best answer yahi journey hai: "pehle ye toota, isliye ye add kiya". Features ki list mat do, **kyu** batao.

### Improvement loop (har company mein yahi chalta hai)

```
   incident / slow API / bug
          │
          ▼
   observe (metrics, logs, traces)  ──▶  root cause  ──▶  pattern lagao  ──▶  test likho jo wapas hone se roke
          ▲                                                                          │
          └──────────────────────  alert + runbook + postmortem  ◀────────────────────┘
```

Agar tumhare paas observability nahi hai, to loop ka pehla step hi nahi hai. Isliye metrics/logs/traces "nice to have" nahi, **pehla kadam** hain.

---

## Part 1: Backend correctness (sahi jawab dena)

### 1. Race condition aur atomic update

**Problem:** PS5 ke 3 units hain, 50 log ek saath "Buy" dabaate hain. Naive code: `stock = read(); if (stock > 0) write(stock - 1)`. 50 threads ne ek saath `3` padha, sab ne `2` likha. 50 orders confirm, stock 2. **Oversell.**

**Kyu:** Read aur write ke beech doosra thread ghus sakta hai. Ise *check-then-act race* kehte hain. Java `synchronized` kaam nahi karega kyunki 10 pods hain, 10 JVMs.

**Kaise (steps):**
1. Check aur update ko **ek hi SQL statement** banao: `UPDATE product SET quantity = quantity - 1 WHERE sku = ? AND quantity >= 1`.
2. Database row lock lagata hai: ek time pe ek hi transaction row update karega. Doosre wait karte hain.
3. Jab doosre ki baari aati hai, `WHERE quantity >= 1` **dobara evaluate** hota hai, fresh value pe. Stock 0 hai to 0 rows update → reject.
4. Return value (`int` rows updated) check karo: 0 = fail, 1 = success.

**ShopFlow mein:** `ProductRepository.decrementStock` (`@Modifying @Query`), test `concurrentReservationsNeverOversell` (20 threads, exactly 3 jeette hain).

**System kaise behtar hua:** Oversell 100% khatam, bina kisi distributed lock ya Redis ke. DB jo already karta hai (row locking) usi ka use.

**Ek line mein:** *Check aur act ek hi atomic statement mein karo, DB ko lock sambhalne do.*

**Alternatives jo interview mein poochte hain:** Optimistic locking (`@Version`: conflict pe exception, retry karo; jab conflicts rare hon), Pessimistic (`SELECT ... FOR UPDATE`: pehle lock, phir padho; jab conflicts common hon). Atomic UPDATE in dono se simple hai jab logic ek statement mein aa jaye.

---

### 2. Idempotency (ek kaam do baar na ho)

**Problem:** User ne "Pay" dabaya, network slow tha, usne phir dabaya. Ya mobile app ne timeout pe retry kiya. Do orders ban gaye, do baar paisa kata.

**Kyu:** Network pe "request pahunchi ya nahi" kabhi 100% pata nahi hota. Isliye **retry zaroori hai**, aur retry safe tabhi hai jab same request do baar bhejne se **same result** aaye. Isi ko idempotency kehte hain.

**Kaise (steps):**
1. Client har request ke saath ek unique key bhejta hai (`Idempotency-Key: cart-777`), jo retry pe **same** rehti hai.
2. Server pehle check karta hai: ye key pehle dekhi? Haan → purana result return karo (200). Nahi → kaam karo, key save karo.
3. Race ke liye: key pe **unique constraint** DB mein. Do concurrent requests mein ek insert fail hoga → 409, client retry karega aur stored result milega.
4. Service-to-service bhi same: order-service → inventory ko `orderRef` bhejta hai, inventory `reservation.order_ref` unique rakhta hai. Isliye order-service bina darr ke retry kar sakta hai.

**ShopFlow mein:** `OrderService.placeOrder` (Idempotency-Key), `ReservationService.reserve` (orderRef), `ReservationController` (race mein haarne wala winner ka reservation return karta hai).

**System kaise behtar hua:** Retries free ho gaye. Ab timeout = retry, bina duplicate ke. Ye hi baat circuit breaker/retry ko possible banati hai (section 4).

**Ek line mein:** *Har write ke saath ek key, key pe unique constraint, duplicate pe purana result.*

---

### 3. Status codes jo ek hi matlab rakhte hain

**Problem:** Inventory 409 deta tha do cases mein: "stock nahi hai" aur "concurrent duplicate request". Order-service har 409 ko "stock nahi" samajhta tha → order REJECTED, jabki stock reserve ho chuka tha. Customer ko "out of stock" dikha, stock atka raha.

**Kyu:** Caller sirf status code dekhta hai. Ek code ke do matlab = caller galat decision lega.

**Kaise:** Har endpoint pe har status code ka **exactly ek matlab** fix karo. Agar do alag situations hain, ya to alag code do ya situation ko hi khatam karo (ShopFlow ne khatam kiya: race mein haarne wala ab winner ka result return karta hai, 201).

**ShopFlow mein:** `ReservationController.reserve` catch block, `SELF-WALK.md` Walk 2.

**Ek line mein:** *Status code ek contract hai; ek code, ek matlab.*

---

## Part 2: Resilience (dependency toote to bhi khade raho)

### 4. Timeout, retry, circuit breaker (teeno saath chalte hain)

**Problem:** Inventory-service down ho gaya. Order-service ka har request thread inventory ka wait karne laga. 200 threads bhar gaye, order-service bhi mar gaya. Ek service ki failure do ban gayi: **cascading failure**.

**Kyu:** Default HTTP client **infinite wait** karta hai. Bina timeout ke "slow dependency" = "dead service".

**Kaise (steps, isi order mein):**
1. **Timeout** lagao (connect 1s, read 2s). Ab worst case 3s mein fail hoga, thread free.
2. **Retry** sirf *transient* errors pe (timeout, 503), **kabhi** 404/409 jaise business errors pe nahi. Exponential backoff (200ms, 400ms, 800ms) taaki down service pe hamla na ho. Max 3 attempts.
3. **Circuit breaker**: pichhli 10 calls mein 50% fail → "open" ho jaata hai → 10s tak call hi nahi karta, turant fail (fast fail). Phir "half-open": 2 test calls, pass hui to "closed".
4. Caller ko saaf jawab do: 503 + order `FAILED` + orderId, taaki baad mein reconcile ho sake.
5. **Readiness probe mein downstream mat daalo** (section 7), warna healthy pods bhi traffic se hat jayenge.

**ShopFlow mein:** `InventoryClient` (`@Retry`, `@CircuitBreaker`), `application.yml` → `resilience4j`, test `circuitBreakerOpensAndFailsFast`, Lab 4.

**System kaise behtar hua:** Inventory down → order-service 15ms mein 503 deta hai, GET requests chalti rehti hain, readiness UP. Pehle: poora checkout down.

**Ek line mein:** *Timeout se thread bachao, retry se transient error jeeto, breaker se dead service pe hamla roko.*

---

### 5. Rate limiting (burst se DB bachao)

**Problem:** Flash sale 12:00 pe shuru. 0 se 5000 req/s ek second mein. HPA ko naye pods lane mein 2–3 minute lagte hain. Tab tak Postgres connections khatam, sab slow.

**Kyu:** Autoscaling **minutes** mein react karta hai, burst **seconds** mein aata hai. Beech ka gap kisi ko bharna hai.

**Kaise:** Token bucket: har second 50 tokens, har request ek token leta hai, token nahi to turant 429 ("slow down"). 429 sasta hai (ms mein), DB query mehenga.

**ShopFlow mein:** `@RateLimiter(name="orders")` on `OrderController.create`, 429 mapping `GlobalExceptionHandler`, Lab 21.

**System kaise behtar hua:** Accepted requests ki latency flat rehti hai; extra load shed hota hai, crash nahi.

**Ek line mein:** *Jo load sambhal nahi sakte use jaldi aur sasta reject karo.*

**Aage:** Per-pod limit pods ke saath multiply hota hai. Shared limit chahiye to Redis ya API gateway pe lagao.

---

### 6. Graceful shutdown aur zero-downtime deploy

**Problem:** Har deploy pe 2–3 second ke liye 502 errors. Kyun? Naya pod aaya, purana turant maara gaya jabki uske paas in-flight requests thi, aur load balancer ko abhi pata bhi nahi tha ki pod ja raha hai.

**Kyu:** Kubernetes mein "pod delete" aur "endpoint se hatao" **parallel** chalte hain, sequential nahi. Race hai.

**Kaise (steps):**
1. `maxUnavailable: 0, maxSurge: 1`: pehle naya pod aaye, ready ho, phir purana jaye.
2. Readiness probe: naya pod traffic tabhi le jab sach mein ready ho.
3. `preStop: sleep 5`: pod ko 5s zinda rakho taaki endpoints/ingress se hatne ka time mile.
4. SIGTERM pe Spring `server.shutdown: graceful`: naye requests band, chal rahe 20s tak poore karo.
5. `terminationGracePeriodSeconds: 45` > 5 + 20, warna SIGKILL beech mein aa jayega.

**ShopFlow mein:** `k8s/base/*/deployment.yaml`, `application.yml`, Lab 9 aur 15.

**System kaise behtar hua:** Deploy ke dauraan 0 failed requests (Lab 9 mein khud prove karo).

**Ek line mein:** *Pehle traffic hatao, phir process band karo; dono ke beech sleep.*

---

### 7. Probes: liveness vs readiness

**Problem:** Postgres 30 second ke liye blip hua. Liveness probe DB check karta tha → saare 10 pods ek saath restart → JVM start hone mein 40s → 70 second ka outage, 30 ki jagah. **Restart storm.**

**Kyu:** Do alag sawaal hain: "process zinda hai?" aur "traffic lene layak hai?". Ek probe se dono ka jawab galat aata hai.

**Kaise:**
- **Liveness** = sirf "process atka to nahi". DB mat check karo. Fail → restart.
- **Readiness** = "main abhi request serve kar sakta hoon?" Apna DB check karo, **downstream services nahi**. Fail → traffic hatao, restart nahi.
- **Startup** = slow JVM ko 60s do, tab tak liveness chup rahe.

**ShopFlow mein:** `management.endpoint.health.group.readiness.include: readinessState,db`, Lab 5.

**Ek line mein:** *Liveness = restart karo?, Readiness = traffic do?; downstream kabhi readiness mein nahi.*

---

## Part 3: Events (services ko loosely connect karna)

### 8. Dual-write problem aur transactional outbox

**Problem:** Code tha: `orderRepository.save(order); kafkaTemplate.send(event);`. Ek din Kafka 2 minute down tha. Orders save hue, events gaye nahi. Customers ko confirmation email nahi mili. Ulta case bhi: event gaya, DB commit fail → email aayi, order hai hi nahi.

**Kyu:** DB aur Kafka **do alag systems** hain, dono mein ek saath atomic commit nahi ho sakta (2PC Kafka support nahi karta, aur slow hai). Ise *dual-write problem* kehte hain.

**Kaise (steps):**
1. Event ko Kafka mein nahi, **apne hi DB ki `outbox_event` table** mein likho, **usi transaction** mein jisme order save hua. Ab ya dono commit, ya dono rollback.
2. Ek **relay** (scheduled job, har 1s) outbox se unpublished rows uthata hai, Kafka pe bhejta hai (`acks=all`, broker confirm kare), phir `published_at` set karta hai.
3. Multiple pods ke liye `SELECT ... FOR UPDATE SKIP LOCKED`: har pod alag rows uthaye.
4. Agar relay send ke baad, mark se pehle crash hua → row dobara bhejegi → **at-least-once**. Isliye consumer idempotent chahiye (section 9).
5. Monitor karo: `outbox_unpublished` gauge; badh raha hai = Kafka down ya relay stuck → alert.

**ShopFlow mein:** `order-service/.../outbox/*`, `OrderService.saveWithEvent` (TransactionTemplate), `V2__outbox.sql`, alert `OutboxBacklogGrowing`, Lab 17.

**System kaise behtar hua:** Kafka down → orders phir bhi confirm hote hain, events queue hote hain, Kafka wapas aate hi sab deliver. Customer ko Kafka ki outage dikhti hi nahi.

**Ek line mein:** *Event ko pehle apne DB mein likho (same transaction), Kafka pe baad mein bhejo.*

**Kyu TransactionTemplate, @Transactional nahi?** Same class ke andar `this.method()` call proxy se nahi guzarta → `@Transactional` **chup-chaap ignore** ho jaata hai. Outbox bina transaction ke bekaar hai. TransactionTemplate explicit hai, ye bug possible hi nahi.

---

### 9. Idempotent consumer aur dead-letter topic

**Problem:** Kafka ne ek event do baar deliver kiya (consumer crash hua offset commit se pehle). Customer ko do emails. Doosra din: ek corrupt message aaya, consumer usi pe baar-baar fail hota raha, poori partition **atak gayi**, kisi ko email nahi gayi.

**Kyu:** Kafka at-least-once deta hai; "exactly-once" sirf Kafka ke andar (topic to topic) hota hai, email bhejne pe nahi. Aur ek bad message partition ka head block karta hai.

**Kaise (steps):**
1. Har event mein `eventId` (UUID) ho.
2. Consumer: `processed_event` table mein eventId check karo. Hai → skip, counter badhao. Nahi → kaam karo + eventId insert karo, **ek transaction mein**.
3. Offset commit listener ke **baad** (`enable-auto-commit: false`); crash pe redelivery, redelivery pe skip.
4. **Error handler**: fail hua to backoff ke saath retry (0.5s, 1s, 2s... max 10s), phir message **DLT** (`orders.events.DLT`) pe bhejo aur aage badho.
5. DLT pe alert/ticket; bug fix karke replay karo.

**ShopFlow mein:** `notification-service` (`OrderEventListener`, `KafkaErrorConfig`), tests `processesEachEventExactlyOnce...`, `poisonPillGoesToDeadLetterTopic...`, Labs 18–19.

**System kaise behtar hua:** Duplicate delivery harmless; ek bad message baaki sabko nahi rokta.

**Ek line mein:** *eventId yaad rakho (same transaction mein), bad message ko DLT pe bhejo.*

---

### 10. Kafka basics jo rozana kaam aate hain

- **Topic** = log; **partition** = parallelism unit; ordering sirf **ek partition ke andar**. Isliye key = `orderRef`: ek order ke saare events ek partition mein, order mein.
- **Consumer group**: har partition ek hi consumer ko. 6 partitions, 3 pods → har pod 2 partitions. 10 pods → 4 idle. **Partitions = max parallelism.**
- **Replication factor 3, `min.insync.replicas=2`, `acks=all`**: ek broker mare to data safe, write tabhi confirm jab 2 copies ho.
- **Consumer lag** = produce hua minus consume hua. Badh raha hai = consumer slow/down → `KafkaConsumerLagHigh` alert.
- **Schema change**: consumer `@JsonIgnoreProperties(ignoreUnknown = true)` → producer field add kar sakta hai bina consumer tode. Field hatana/rename = breaking → Schema Registry (roadmap).

---

## Part 4: Security aur performance

### 11. JWT / OAuth2 resource server

**Problem:** `POST /api/v1/orders` koi bhi call kar sakta tha. Aur agar har request pe "user DB mein password check" karte to har service ko users ka DB chahiye hota.

**Kyu:** Authentication ek jagah ho (identity provider), baaki services sirf **token verify** karein. Stateless, scalable, passwords sirf ek jagah.

**Kaise (steps):**
1. User Keycloak/Cognito pe login karta hai, use **JWT** milta hai (header.payload.signature, base64).
2. Client har request mein `Authorization: Bearer <jwt>`.
3. order-service issuer ki **public keys (JWKS)** ek baar download karta hai, signature locally verify karta hai, `exp` aur `iss` check karta hai. **Koi network call nahi** per request.
4. Token ke roles (`realm_access.roles` Keycloak, `cognito:groups` Cognito) → Spring `ROLE_customer`. Rules: POST = customer, GET = customer ya support, actuator = open (NetworkPolicy se protected).
5. Stateless: koi session/cookie nahi → CSRF off, horizontally scale free.

**ShopFlow mein:** `order-service/.../security/*`, realm `k8s/base/keycloak/shopflow-realm.json`, tests `rejectsRequestsWithoutAValidToken`, Lab 16.

**System kaise behtar hua:** Auth ka cost ~0 per request, identity provider down ho to bhi pehle se issued tokens kaam karte hain, ek hi image Keycloak (local) aur Cognito (AWS) dono ke saath chalti hai.

**Ek line mein:** *Login ek jagah, token verify har jagah, public key se, bina DB ke.*

---

### 12. Cache-aside (reads sasta karo, galat data mat do)

**Problem:** `GET /products/{sku}` flash sale mein 10,000 req/s. Har request Postgres query. DB CPU 100%, writes bhi slow.

**Kyu:** Reads writes se 1000× zyada hain. Ek hi jawab baar-baar compute karna bewakoofi hai. Lekin **stale stock dikhana** flash sale mein aur bura hai.

**Kaise (steps):**
1. **Cache-aside**: read → cache mein hai? return. Nahi → DB se lo, cache mein daalo (TTL 60s), return.
2. **Har write pe evict** (reserve/release → us sku ki key delete). Update nahi, evict: do writers galat order mein cache update kar sakte hain, delete safe hai.
3. **DTO cache karo, entity nahi** (entity mein lazy proxies, session attached, serialization problems).
4. Redis (shared across pods) prod mein; local `simple` cache tests/dev mein. Same code, config alag.
5. TTL safety net hai: eviction kabhi miss ho to 60s mein theek.

**ShopFlow mein:** `@Cacheable` `ProductController.get`, `@CacheEvict` `ReservationService`, Lab 20.

**System kaise behtar hua:** DB pe read load ~95% kam, stock hamesha correct (write ke turant baad fresh).

**Ek line mein:** *Read cache se, write pe evict, DTO cache karo, TTL safety net.*

---

### 13. Observability: metrics, logs, traces (teeno chahiye)

**Problem:** "Checkout slow hai." Kaunsi service? order? inventory? DB? Kafka? 3 services ke logs alag-alag kholo, time match karo, 40 minute.

**Kyu:** Teeno alag sawaal ka jawab dete hain:
- **Metrics** (Prometheus): *kitna* bura hai? (p99 1.2s, error rate 3%). Sasta, aggregate, alerts yahan se.
- **Traces** (Tempo): *kahan* slow hai? Ek request ka safar, har service ka span aur time.
- **Logs** (Loki): *kyu*? Exception, exact values.

**Kaise (steps):**
1. Har service Micrometer se metrics expose kare (`/actuator/prometheus`), business counters bhi (`orders_total{status}`).
2. Tracing: har request ko `traceId` milta hai; HTTP headers (`traceparent`) aur **Kafka headers** mein aage jaata hai. Teeno services same traceId log karte hain.
3. Logs JSON (ECS format) mein, `trace.id` field ke saath. Alloy/Promtail Loki mein bhejta hai.
4. Grafana mein teeno jodo: trace → "logs for this span", log → trace id click → trace.
5. Alerts **symptoms** pe (error rate, p99), causes pe sirf early warning (breaker open, outbox backlog, consumer lag).

**ShopFlow mein:** parent `pom.xml` (otel bridge + otlp exporter), `monitoring/`, `alert-rules.yml`, Lab 22, 13.

**System kaise behtar hua:** "Slow checkout" → Tempo kholo → dikh gaya inventory ka DB span 900ms → index missing. 4 minute.

**Ek line mein:** *Metrics = kitna, traces = kahan, logs = kyu; ek trace id se teeno jude.*

---

## Part 5: Platform (Kubernetes, CI/CD, AWS)

### 14. Resources, HPA, PDB: scaling jo sach mein kaam kare

**Problems jo hue:**
- `spec.replicas: 2` + HPA → har deploy pe replicas 2 pe reset, phir HPA wapas 6 karta → har deploy pe chhota outage.
- Dev mein 1 replica + PDB `minAvailable: 1` → node drain kabhi khatam nahi hota.
- Pool 10 × 10 pods × 2 services = 200 connections > Postgres 100 → autoscale hote hi DB down.

**Kaise:**
1. HPA use kar rahe ho to Deployment se `replicas` hatao; `minReplicas` floor hai.
2. Requests set karo (scheduler + HPA % iske upar), memory limit set karo, **CPU limit mat lagao** (throttling, slow JVM start).
3. PDB: replicas ≥ 2 ho tab `minAvailable: 1`; single replica env mein `maxUnavailable: 1`.
4. **Connection math** hamesha karo: `pods_max × pool_size` har service ka sum < DB `max_connections`. Chhota pool (5) + bada max_connections (200/300).
5. Scale-down stabilization 5 min: flapping roko.

**ShopFlow mein:** `k8s/base/*/hpa.yaml`, `pdb.yaml`, `DB_POOL_SIZE=5`, `SELF-WALK.md` Walk 2, Labs 6, 10.

**Ek line mein:** *HPA ko replicas do, DB ko connection math do, drain ko PDB jo possible ho.*

---

### 15. NetworkPolicy: zero trust cluster ke andar

**Problem:** Ek pod mein vulnerability se attacker ghusa. By default K8s mein **har pod har pod se baat kar sakta hai** → seedha Postgres, Kafka, sab.

**Kaise:**
1. `default-deny` ingress **aur** egress.
2. Phir sirf zaroori allow: order → inventory:8082, order → postgres:5432, order → kafka:9092...
3. **DNS mat bhoolna** (UDP/TCP 53 to kube-dns), warna sab "broken" lagega.
4. Prod mein managed services cluster ke bahar hain → VPC CIDR pe egress allow.

**ShopFlow mein:** `k8s/base/network-policies.yaml`, Lab 11.

**Ek line mein:** *Sab band, sirf zaroori khula, DNS yaad rakho.*

---

### 16. CI/CD + GitOps: commit se prod tak

**Problem:** "Mere laptop pe chal raha tha." Kisi ne prod pe haath se `kubectl edit` kiya, ab git aur cluster alag hain. `:latest` deploy hua, rollback kispe karein pata nahi.

**Kaise (steps, ShopFlow pipeline):**
1. **Test** (unit + integration: H2, embedded Kafka, WireMock, fake JWT).
2. **Validate** manifests (`kustomize build | kubeconform`), alert rules (`promtool`), Terraform (`fmt`, `validate`).
3. **Build** image (multi-stage, layered, non-root), **scan** (Trivy; HIGH/CRITICAL fixable → fail).
4. **Push** sirf `main` pe, tag = **git SHA** (immutable, traceable).
5. **Pin**: CI prod overlay mein SHA likh ke commit karta hai (`deploy-manifests` job). CI **kabhi kubectl nahi chalata**.
6. **Argo CD** git dekhta hai, cluster ko git jaisa banata hai (`selfHeal`: haath ka edit revert).
7. Rollback = git revert → Argo sync. 1 minute.

**ShopFlow mein:** `.github/workflows/shopflow.yml`, `Jenkinsfile` (same stages, Jenkins syntax), `argocd/`, Lab 14.

**Ek line mein:** *Git hi sach hai; CI build karta hai, Argo deploy karta hai, insaan sirf PR merge karta hai.*

---

### 17. Secrets: git mein kabhi nahi

**Problem:** DB password `kustomization.yaml` mein literal → git history mein hamesha ke liye.

**Kaise:**
1. Dev: `secretGenerator` literals theek hai (minikube, throwaway).
2. Prod: password **AWS Secrets Manager** mein (Terraform `random_password` se bana), git mein sirf uska **naam**.
3. **External Secrets Operator** cluster mein: Secrets Manager se padh ke K8s Secret banata hai, 1h mein refresh.
4. Operator ko AWS permission **IRSA** se: ServiceAccount pe role annotation, pod ka token STS pe exchange → temporary creds. **Koi access key nahi.**
5. Rotation: Secrets Manager mein rotate → ESO sync → rolling restart.

**ShopFlow mein:** `k8s/overlays/prod/external-secret.yaml`, `infra/terraform/aws/rds.tf`, `irsa.tf`.

**Ek line mein:** *Secret manager mein value, git mein sirf reference, pod ko role se access.*

---

### 18. AWS pe prod: managed services kyu

**Problem (socho):** Cluster ke andar Postgres StatefulSet hai. Disk full hua raat 3 baje. Backup kab liya tha? Restore kaise? Failover? Kafka broker upgrade kaun karega?

**Kyu:** DB/Kafka/Redis/identity **undifferentiated heavy lifting** hain: tumhare product ko unique nahi banate, par tootne pe sab tod dete hain. Managed service = AWS ka on-call, tumhara nahi.

**Kaise (ShopFlow prod design):**
| Dev (cluster ke andar) | Prod (AWS managed) | Kya mila |
|---|---|---|
| Postgres StatefulSet | **RDS** Multi-AZ | automatic failover, backups/PITR, patching, Performance Insights |
| Kafka StatefulSet | **MSK** 3 brokers, IAM auth | broker replacement, metrics, no passwords |
| Redis Deployment | **ElastiCache** replica + failover | TLS, auto failover |
| Keycloak | **Cognito** | hosted login, MFA, no server to patch |
| secretGenerator | **Secrets Manager + ESO** | rotation, audit |
| nginx Ingress | **ALB** (LB Controller) + ACM | managed TLS certs, WAF option |

Steps: Terraform (`infra/terraform/aws/`) → `terraform output` → overlay ConfigMap mein endpoints → Argo sync. Code **same**, sirf env vars alag.

**Trade-off:** Paisa (~$400–600/mo full, <$150 learning config) aur vendor lock-in vs engineer ki neend aur reliability. Zyadatar companies ke liye managed jeet-ta hai.

**Ek line mein:** *Jo tumhara product nahi hai, use kharido; jo hai, use banao.*

---

### 19. Infrastructure as Code (Terraform)

**Problem:** Console mein click karke VPC banaya. 6 mahine baad: ye SG kyu hai? Kisne banaya? Dev environment same kaise banayein?

**Kaise:**
1. Har resource code mein (`.tf`), git mein, PR review ke saath.
2. **State** remote (S3, versioned, locked): Terraform ko pata rahe kya already bana hai.
3. `plan` pehle padho (kya banega/badlega/**destroy** hoga), phir `apply`.
4. Modules reuse karo (`terraform-aws-modules/vpc`, `eks`, `iam`).
5. Drift (console change) `plan` mein dikhta hai.

**ShopFlow mein:** `infra/terraform/aws/*.tf`, CI job `terraform fmt/validate`.

**Ek line mein:** *Infra bhi code hai: review, version, repeat.*

---

## Part 6: Prod system **kaise** improve karte hain (method)

Ye section sabse important hai. Tools badalte rehte hain, method nahi.

### Step 1: Measure karo, guess mat karo
- SLI define karo: "checkout p99 < 500ms", "error rate < 1%".
- Dashboard: RED (Rate, Errors, Duration) har service ka.
- Jab tak number nahi, "slow lag raha hai" opinion hai.

### Step 2: Sabse bada bottleneck dhundo
- Trace kholo: sabse lamba span kaun? Aksar DB query ya downstream call.
- `EXPLAIN ANALYZE`, missing index, N+1.
- Ek baar mein **ek** cheez badlo, phir measure.

### Step 3: Failure modes list karo aur ek-ek karke handle karo
Har dependency ke liye poochho: **slow ho to? down ho to? galat jawab de to?**

| Dependency | Slow | Down | Galat |
|---|---|---|---|
| inventory | timeout → 503 | breaker → fast 503 | status code contract |
| Postgres | pool wait → readiness fail → traffic hat-ta hai | pod not ready, no restart | migrations validate |
| Kafka | relay backlog, alert | outbox queue, orders chalte rahein | DLT |
| Keycloak | tokens cached, chalta rahe | purane tokens valid | signature verify |

### Step 4: Har incident ke baad teen cheezein
1. **Test** jo wahi bug wapas aane se roke (ShopFlow mein har fix ke saath test hai).
2. **Alert** jo agli baar insaan se pehle bataye.
3. **Runbook** line: "ye alert aaye to ye dekho".

### Step 5: Load test karke prove karo
- k6/hey: 1000 buyers, 3 PS5. Expected: 3 CONFIRMED, baaki REJECTED/429, p99 flat, stock 0.
- Jo claim test se prove nahi, woh claim nahi, umeed hai.

### Step 6: Self-walk regularly
- Mahine mein ek baar `SELF-WALK.md` wala checklist chalao. Code padho jaise reviewer. Jo mile, fix ya likho.

> Tip: Interview mein "how would you improve this system?" ka jawab yahi 6 steps hain, kisi bhi system ke liye. Pehle measure, phir bottleneck, phir failure modes, phir test+alert+runbook, phir load test, phir repeat.

---

## Quick map: symptom → cause → pattern

| Symptom (prod mein dikha) | Likely cause | Pattern / fix |
|---|---|---|
| Stock negative / oversell | check-then-act race | atomic UPDATE WHERE |
| Duplicate orders / emails | retries bina key ke | idempotency key / idempotent consumer |
| Ek service down, sab down | no timeout, no breaker | timeout + retry + circuit breaker |
| Deploy pe 502s | pod killed before endpoint removal | preStop + graceful + maxUnavailable 0 |
| Saare pods ek saath restart | liveness mein DB check | liveness sirf process, readiness mein DB |
| Events gum | dual write (DB + Kafka) | transactional outbox |
| Partition atki | poison message | error handler + DLT |
| DB connections khatam | pool × pods > max_connections | chhota pool, connection math |
| Alert kabhi fire nahi hota | gauge flapping + `for:` | counter rate pe alert |
| "Slow hai" par pata nahi kahan | sirf logs | tracing + correlation |
| Autoscale hua par DB mar gaya | burst > scaling speed | rate limit + cache |
| Rollback nahi ho pa raha | `:latest` | SHA tags + GitOps |
| Secret leak | git mein literal | Secrets Manager + ESO + IRSA |
