# Practice Labs: khud banao, phir solution se milao

[`LABS.md`](LABS.md) mein cheezein **todni** thi aur dekhna tha kya hota hai. Yahan cheezein **banani** hain: chhote-chhote tasks, isi project (ShopFlow) ke andar, jaise asli kaam mein milte hain. Har lab ka pattern:

> **Task** (kya banana hai) → **Kyu** (real life mein ye kab aata hai) → **Diagram** → **Steps** (khud try karo) → **Solution** (poora code/config) → **Common galtiyan** → **Verify** (kaise pata chale sahi hua)

Pehle khud try karo (20–30 min), phir solution kholo. Solution copy karne se kuch nahi seekhoge; *apna* version solution se compare karne se seekhoge.

Setup: repo clone, JDK 21, `cd shopflow && ./mvnw verify` green. Docker wale labs ke liye `docker compose up -d`. Har lab ke end mein `./mvnw verify` phir se green hona chahiye.

Difficulty: 🟢 easy (30 min) · 🟡 medium (1 h) · 🔴 hard (2 h+)

---

## Level 1: Backend (Spring Boot)

### P1 🟢 Naya read endpoint: `GET /api/v1/products/{sku}/availability`

**Task:** inventory-service mein ek endpoint jo sirf bataye `{"sku":"PS5-SLIM","inStock":true}`.

**Kyu:** Frontend ko sirf "hai ya nahi" chahiye, poora product nahi. Chhota DTO = chhota response = sasta.

**Diagram**
```
GET /products/PS5-SLIM/availability
        │
        ▼
ProductController.availability()  ──▶ ProductRepository.findBySku()  ──▶ DB
        │                                     │
        └── 404 ProductNotFoundException ◀── empty
        ▼
{"sku":"PS5-SLIM","inStock":true}
```

**Steps**
1. `ProductController` mein naya `@GetMapping("/{sku}/availability")`.
2. Ek record `AvailabilityResponse(String sku, boolean inStock)`.
3. Repository se product lo; nahi mila to wahi exception jo `get()` use karta hai.
4. Test: 200 for `PS5-SLIM`, 404 for `NOPE`.

**Solution**
```java
// inventory-service/.../product/ProductController.java  (class ke andar)
public record AvailabilityResponse(String sku, boolean inStock) {}

@GetMapping("/{sku}/availability")
public AvailabilityResponse availability(@PathVariable String sku) {
    return productRepository.findBySku(sku)
            .map(p -> new AvailabilityResponse(p.getSku(), p.getQuantity() > 0))
            .orElseThrow(() -> new ProductNotFoundException(sku));
}
```
```java
// InventoryServiceApplicationTests.java mein
@Test
void availabilityEndpoint() throws Exception {
    mockMvc.perform(get("/api/v1/products/PS5-SLIM/availability"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.inStock").value(true));
    mockMvc.perform(get("/api/v1/products/NOPE/availability")).andExpect(status().isNotFound());
}
```

**Common galtiyan:** entity (`Product`) return kar dena (ArchUnit rule aur cache dono tootenge); `@Cacheable` lagana bhool jaana ya galat key (`"#sku"` hona chahiye); exception ko controller mein catch karke 200 dena.

**Verify:** `./mvnw -pl inventory-service test` green; `curl localhost:8082/api/v1/products/PS5-SLIM/availability`.

---

### P2 🟢 Naya field with migration: order mein `note`

**Task:** customer order ke saath ek optional `note` (max 200 chars) bhej sake; response mein wapas aaye.

**Kyu:** "Ek column add karna" sabse common ticket hai, aur sabse zyada galat hota hai (migration bhoolna, NOT NULL laga dena, validate fail).

**Diagram (expand → contract)**
```
Release 1 (ye lab):  ALTER TABLE orders ADD COLUMN note VARCHAR(200)   ← nullable (expand)
                      code: field + validation + DTO
Release 2 (kabhi):    agar NOT NULL chahiye → backfill → ALTER ... SET NOT NULL (contract)
```
Rolling update ke dauraan purana aur naya code **ek saath** chalte hain. Purana code `note` nahi jaanta, isliye column nullable hona chahiye.

**Steps**
1. `V6__order_note.sql`: `alter table orders add column note varchar(200);`
2. `Order` entity: `@Column(length = 200) private String note;` + getter; `pending(...)` mein set.
3. `CreateOrderRequest` mein `@Size(max = 200) String note` (optional).
4. `OrderResponse` mein `note` add; `OrderService.placeOrder` signature badlo.
5. Test: note round-trip; 201-char note → 400.

**Solution (mukhya hisse)**
```sql
-- order-service/src/main/resources/db/migration/V6__order_note.sql
alter table orders add column note varchar(200);
```
```java
// Order.java
@Column(length = 200)
private String note;
static Order pending(String sku, int quantity, String idempotencyKey, String customerId, String note) {
    ... order.note = note; ...
}
public String getNote() { return note; }

// OrderController.CreateOrderRequest
public record CreateOrderRequest(@NotBlank @Size(max = 64) String sku,
                                 @Min(1) @Max(100) int quantity,
                                 @Size(max = 200) String note) {}
// OrderResponse: ek aur field + from() mein o.getNote()
// OrderService.placeOrder(sku, quantity, idempotencyKey, caller, note) -> Order.pending(..., note)
```
```java
@Test
void noteIsStoredAndValidated() throws Exception {
    stubReserve(201);
    mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/orders").with(customer())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"sku":"PIXEL-9","quantity":1,"note":"gift wrap please"}"""))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.note").value("gift wrap please"));
    mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/orders").with(customer())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""{"sku":"PIXEL-9","quantity":1,"note":"%s"}""".formatted("x".repeat(201))))
        .andExpect(status().isBadRequest());
}
```

**Common galtiyan:** migration ke bina entity badalna (`ddl-auto: validate` startup pe fail karega: *achha hai*, yahi uska kaam hai); purani migration file edit karna (Flyway checksum mismatch: **kabhi nahi**, hamesha nayi `V7`); `NOT NULL` pehli release mein.

**Verify:** tests green; Lab 12 (LABS.md) ka reasoning dobara padho.

---

### P3 🟡 Naya Kafka event consumer: `OrderCancelled` pe inventory ko "audit" entry

**Task:** inventory-service `orders.events` sune aur har `OrderCancelled` ke liye ek row `cancellation_audit(order_ref, sku, quantity, received_at)` likhe. Idempotent.

**Kyu:** "Ek aur service ko ye event chahiye" roz ka kaam hai. Yahan seekhoge: consumer group, idempotency, aur ye ki inventory ko *event* se pata chalta hai, kisi ke call se nahi.

**Diagram**
```
order-service ──OrderCancelled──▶ orders.events ──┬──▶ notification-service (group: notification-service)
                                                  ├──▶ payment-service      (group: payment-service, ignores it)
                                                  └──▶ inventory-service    (group: inventory-service)  ← NAYA
                                                              │
                                                       processed? ──no──▶ insert cancellation_audit + processed_event
```
Har group ko **apni** copy milti hai. Naya group add karne se baaki pe koi asar nahi.

**Steps**
1. `inventory-service/pom.xml`: `spring-kafka` (+ `spring-kafka-test` test scope) add karo (notification-service ka pom dekho).
2. `V3__cancellation_audit.sql`: `cancellation_audit` + `processed_event` tables.
3. Entities + repositories (notification-service ke `ProcessedEvent` copy karo).
4. `OrderEventListener` (`@KafkaListener(topics="orders.events", groupId="inventory-service")`, `@Transactional`), sirf `type == "OrderCancelled"` pe act karo.
5. `application.yml`: kafka consumer config (notification-service se copy), test yml mein `spring.embedded.kafka.brokers`.
6. `KafkaErrorConfig` (DLT) copy.
7. Test: `@EmbeddedKafka`, event do baar bhejo → ek row.

**Solution (listener)**
```java
@Component
public class OrderEventListener {
    private final CancellationAuditRepository audits;
    private final ProcessedEventRepository processed;
    private final ObjectMapper objectMapper;
    // constructor ...

    @KafkaListener(topics = "orders.events", groupId = "inventory-service")
    @Transactional
    public void onOrderEvent(String payload) throws Exception {
        OrderEvent e = objectMapper.readValue(payload, OrderEvent.class);   // @JsonIgnoreProperties(ignoreUnknown = true)
        if (!"OrderCancelled".equals(e.type()) || processed.existsById(e.eventId())) return;
        audits.save(new CancellationAudit(e.orderRef(), e.sku(), e.quantity()));
        processed.save(new ProcessedEvent(e.eventId()));
    }
}
```
```sql
create table cancellation_audit (
    id          bigint generated by default as identity primary key,
    order_ref   varchar(64) not null,
    sku         varchar(64) not null,
    quantity    integer     not null,
    received_at timestamp   not null
);
create table processed_event (event_id uuid primary key, processed_at timestamp not null);
```
Test notification-service ke `processesEachEventExactlyOnceEvenWhenDeliveredTwice` jaisa, `type` = `OrderCancelled`.

**Common galtiyan:** `groupId` same rakhna jo doosri service ka hai (tab events *baant* jayenge, dono ko nahi milenge); `@Transactional` bhoolna (audit likha, processed nahi → duplicate); existing `InventoryServiceApplicationTests` mein `@EmbeddedKafka` add karna bhoolna (context Kafka dhundega, fail hoga).

**Verify:** `./mvnw -pl inventory-service test`; compose mein order cancel karo → `select * from cancellation_audit`.

---

### P4 🟡 Cache ek aur endpoint pe + eviction ka sahi jagah

**Task:** `GET /api/v1/products` (list) ko cache karo, key `"all"`, aur jab bhi stock badle to evict ho.

**Kyu:** List endpoint sabse zyada hit hota hai. Lekin galat eviction = stale list = flash sale mein galat stock dikhega.

**Diagram**
```
GET /products ──▶ cache "products::all" hit? ──yes──▶ return
                          │ no
                          ▼
                       DB findAll ──▶ put ──▶ return
reserve()/release() ──▶ @CacheEvict("products", allEntries=true)   ← list bhi, single bhi
```

**Steps**
1. `ProductController.list()` pe `@Cacheable(cacheNames="products", key="'all'")`.
2. `ReservationService.reserve` pe eviction ab `allEntries = true` honi chahiye (single-key eviction list ko nahi hataati).
3. Test: list do baar → cache mein `all` key; reserve → key gayi.

**Solution**
```java
@GetMapping
@Cacheable(cacheNames = "products", key = "'all'")
public List<ProductResponse> list() { ... }

// ReservationService.reserve: @CacheEvict(cacheNames = "products", allEntries = true)
```
```java
@Test
void listIsCachedAndEvictedOnReserve() throws Exception {
    mockMvc.perform(get("/api/v1/products")).andExpect(status().isOk());
    assertThat(cacheManager.getCache("products").get("all")).isNotNull();
    reservationService.reserve(UUID.randomUUID().toString(), "AIRPODS-PRO", 1);
    assertThat(cacheManager.getCache("products").get("all")).isNull();
}
```

**Common galtiyan:** `key="all"` (bina quotes: SpEL mein `all` ek variable hai, null banega); `List<Entity>` cache karna; `@Cacheable` private method pe (proxy nahi chalega, self-invocation).

**Trade-off sochne layak:** `allEntries=true` har reserve pe poora cache udaata hai. Flash sale mein 1000 reserve/s = cache bekaar. Behtar: list ko 5 s TTL do aur evict mat karo (thoda stale theek hai list mein, single product mein nahi). Dono approach notes mein likho.

---

### P5 🟡 Naya rate limit: per-customer, Redis pe (shared)

**Task:** `POST /orders` pe per-customer limit 5 req/s, saare pods milke (per-pod nahi).

**Kyu:** Abhi ka limiter per-pod hai: 10 pods × 50 = 500 req/s, aur ek customer sabko kha sakta hai. Real systems per-user limit Redis pe rakhte hain.

**Diagram**
```
request(JWT sub=alice) ──▶ filter ──▶ Redis INCR ratelimit:alice:<second>  (EXPIRE 2s)
                                           │
                                     count > 5 ? ──yes──▶ 429
                                           │ no
                                           ▼
                                      controller
```
Fixed window (simple). Sliding window / token bucket advanced hai (notes 11 dekho).

**Steps**
1. order-service: `spring-boot-starter-data-redis` dependency; compose/k8s mein `REDIS_HOST` (inventory ki tarah).
2. Ek `OncePerRequestFilter` jo sirf `POST /api/v1/orders` pe chale, JWT subject nikale (`SecurityContextHolder`), Redis `INCR` + `EXPIRE`.
3. Limit cross → 429 problem+json (same format jo `GlobalExceptionHandler` deta hai).
4. Test: Redis ke bina chalana mushkil → `StringRedisTemplate` ko `@MockitoBean` karke counter simulate karo, ya Testcontainers (Docker chahiye).

**Solution (filter ka core)**
```java
@Component
public class PerCustomerRateLimitFilter extends OncePerRequestFilter {
    private final StringRedisTemplate redis;
    private final int limitPerSecond;
    // constructor: @Value("${ratelimit.per-customer:5}")

    @Override
    protected boolean shouldNotFilter(HttpServletRequest r) {
        return !("POST".equals(r.getMethod()) && r.getRequestURI().startsWith("/api/v1/orders"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) { chain.doFilter(req, res); return; }            // 401 security khud dega
        String key = "ratelimit:" + auth.getName() + ":" + (System.currentTimeMillis() / 1000);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) redis.expire(key, Duration.ofSeconds(2));
        if (count != null && count > limitPerSecond) {
            res.setStatus(429);
            res.setContentType("application/problem+json");
            res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too many requests\",\"status\":429,\"detail\":\"5 orders per second per customer\"}");
            return;
        }
        chain.doFilter(req, res);
    }
}
```
Filter ko security filter chain ke **baad** chalana hai (auth chahiye): `SecurityConfig` mein `.addFilterAfter(filter, AuthorizationFilter.class)`.

**Common galtiyan:** filter security se pehle lagana (auth null); `EXPIRE` bhoolna (Redis keys hamesha ke liye); Redis down pe request fail karna (fail-open karo: exception → allow + log).

**Verify:** compose mein `hey -c 20 -n 100` as alice → ~5/s pass, baaki 429; `redis-cli keys 'ratelimit:*'`.

---

## Level 2: Kubernetes

### P6 🟢 Naye service ke liye poora manifest set (payment-service ki tarah)

**Task:** Maan lo ek `search-service` (port 8085, DB `search`) aaya. Uske liye `k8s/base/search-service/` banao: Deployment, Service, HPA, PDB, kustomization; base ke `kustomization.yaml` mein add; NetworkPolicy ingress (monitoring) + egress (postgres); dev overlay secret; prod overlay patches.

**Kyu:** "Naya service K8s pe lao" ek checklist hai. Checklist yaad ho gayi to kabhi probe/netpol/secret nahi bhoologe.

**Diagram (checklist)**
```
k8s/base/search-service/   deployment.yaml  service.yaml  hpa.yaml  pdb.yaml  kustomization.yaml
k8s/base/kustomization.yaml          + "- search-service"
k8s/base/network-policies.yaml       + ingress (from monitoring :8085) + egress (postgres 5432, monitoring 4318)
k8s/base/postgres/{init-db.sh,statefulset.yaml}   + search DB/user/password env
k8s/overlays/dev/kustomization.yaml  + image tag, + search-db-password literal
k8s/overlays/prod/kustomization.yaml + image newName/newTag, + RDS DB_URL patch, + SA/profile if Kafka
k8s/overlays/prod/external-secret.yaml + search-db-password key
monitoring/prometheus.yml            + target (compose)   |  k8s: scrape annotation already on the pod
```

**Steps:** payment-service ke 5 files copy karo, `sed` se naam/port/DB badlo, baaki checklist follow karo.

**Solution:** exactly jo Walk 5 mein payment-service ke liye kiya gaya (`git show 90e77ac --stat` aur `git show afa8f64 -- shopflow/k8s` dekho). Shortcut jo wahan use hua:
```sh
cd shopflow/k8s/base && mkdir search-service
for f in deployment service hpa pdb kustomization; do
  sed -e 's/payment-service/search-service/g' -e 's/8084/8085/g' -e 's#/payments#/search#' \
      -e 's/value: payments/value: search/' -e 's/payments-db-password/search-db-password/' \
      payment-service/$f.yaml > search-service/$f.yaml
done
```

**Verify:** `kustomize build k8s/overlays/dev | kubeconform -strict -ignore-missing-schemas -summary -` → +6 resources valid; `kyverno apply k8s/policies/kyverno/ --resource <(kustomize build k8s/overlays/prod)` → sab pass (agar probes/resources bhool gaye to **yahin pakda jayega**, yahi policy ka fayda).

---

### P7 🟢 Naya alert + runbook + unit test (ek saath, warna adhoora)

**Task:** alert `HikariPoolExhausted`: `hikaricp_connections_pending > 0` 2 minute tak → `page`. Runbook + promtool test ke saath.

**Kyu:** Pool exhaustion sabse common "sab slow hai" cause hai, aur alert tab hi acha hai jab runbook aur test ho.

**Diagram**
```
hikaricp_connections_pending{application="order-service"}  ──▶ rule (for: 2m) ──▶ Alertmanager (severity=page)
                                                                      │                    │
                                                                tests/alerts_test.yml   runbook annotation
```

**Solution**
```yaml
# monitoring/alert-rules.yml (shopflow group ke andar)
      - alert: HikariPoolExhausted
        expr: max by (application) (hikaricp_connections_pending) > 0
        for: 2m
        labels:
          severity: page
        annotations:
          summary: "{{ $labels.application }}: threads are waiting for a DB connection (pool exhausted)"
          runbook: docs/runbooks/HikariPoolExhausted.md
```
```yaml
# monitoring/tests/alerts_test.yml (tests: ke andar)
  - interval: 1m
    input_series:
      - series: 'hikaricp_connections_pending{application="order-service", pool="HikariPool-1"}'
        values: '3x5'
    alert_rule_test:
      - eval_time: 3m
        alertname: HikariPoolExhausted
        exp_alerts:
          - exp_labels: {severity: page, application: order-service}
            exp_annotations:
              summary: "order-service: threads are waiting for a DB connection (pool exhausted)"
              runbook: docs/runbooks/HikariPoolExhausted.md
```
Runbook (`docs/runbooks/HikariPoolExhausted.md`): check `hikaricp_connections_active` (pool full?) → slow queries (`pg_stat_activity`, Performance Insights) → long transactions / remote call inside `@Transactional`? → mitigate: pods **kam** karo (zyada pods = zyada connections), DB fix; pool badhana aakhri option (`pods × pool < max_connections`). `docs/runbooks/README.md` table mein row.

**Common galtiyan:** `sum` use karna (pending ko sum karne ka matlab nahi, `max` sahi); runbook annotation test mein add na karna (test fail; sahi hai); `for:` bina alert (ek blip pe page).

**Verify:** `promtool check rules` + `promtool test rules monitoring/tests/alerts_test.yml` SUCCESS; `make validate`.

---

### P8 🟡 Naya NetworkPolicy + use todne ka test

**Task:** payment-service ko inventory-service call karne ki permission **nahi** honi chahiye (woh sirf Kafka + Postgres use karta hai). Prove karo ki policy ye rokti hai.

**Kyu:** Zero trust: har allow rule ka reason hona chahiye. "Kya ye service us service se baat kar sakti hai?" interview ka favourite sawaal hai.

**Diagram**
```
payment-service egress:  postgres:5432 ✓   kafka:9092 ✓   monitoring:4318 ✓   inventory-service:8082 ✗ (default deny)
```

**Steps**
1. `network-policies.yaml` padho: `payment-service-egress` mein inventory nahi hai → already blocked (default-deny egress). To task hai *prove* karna.
2. minikube (calico) pe: `kn exec deploy/payment-service -- wget -qO- -T 3 http://inventory-service:8082/actuator/health` → timeout.
3. Ab allow rule add karo (temporarily) → kaam karega → hata do.
4. Bonus: offline check, kyunki NetworkPolicy ka offline simulator nahi hai: `kubectl kustomize ... | yq` se saare egress rules ki table nikalo aur review karo.

**Solution (bonus script)**
```sh
kubectl kustomize shopflow/k8s/overlays/dev | yq ea '
  select(.kind == "NetworkPolicy" and .spec.policyTypes[] == "Egress")
  | .metadata.name + " -> " + ([.spec.egress[].to[] | (.podSelector.matchLabels["app.kubernetes.io/name"] // .namespaceSelector.matchLabels["kubernetes.io/metadata.name"] // .ipBlock.cidr)] | join(", "))'
```
Output ek line per service: kaun kisse baat kar sakta hai. Ye table `docs/STATE.md` jaise doc mein rakho.

**Common galtiyan:** DNS allow bhoolna (sab tootega, Lab 11); `podSelector` + `namespaceSelector` ek hi item mein (AND) vs alag items (OR) confuse karna.

---

### P9 🟡 Helm: ek aur service chart se, aur values ka layering

**Task:** `helm/values/inventory-service.yaml` + `inventory-service-prod.yaml` banao; render karke Kustomize wale manifest se diff karo.

**Kyu:** Helm values layering (`-f base -f prod`) roz use hota hai. Diff se seekhoge ki dono approach same cheez banate hain.

**Solution**
```yaml
# helm/values/inventory-service.yaml
name: inventory-service
image: {repository: shopflow/inventory-service}
port: 8082
db: {name: inventory, secretKey: inventory-db-password}
env:
  - {name: CACHE_TYPE, value: redis}
  - name: REDIS_HOST
    valueFrom: {configMapKeyRef: {name: shopflow-endpoints, key: redis-host}}
  - name: OTLP_ENDPOINT
    valueFrom: {configMapKeyRef: {name: shopflow-endpoints, key: otlp-endpoint}}
```
```yaml
# helm/values/inventory-service-prod.yaml
image: {repository: ghcr.io/rajji01/shopflow-inventory-service, tag: set-by-ci}
db: {endpoint: shopflow.xxxxxx.ap-south-1.rds.amazonaws.com:5432}
hpa: {minReplicas: 3, maxReplicas: 10}
```
```sh
helm template inventory-service helm/shopflow-service -f helm/values/inventory-service.yaml > /tmp/helm.yaml
kubectl kustomize k8s/overlays/dev | yq 'select(.kind=="Deployment" and .metadata.name=="inventory-service")' > /tmp/kust.yaml
diff <(yq -P 'sort_keys(..)' /tmp/kust.yaml) <(yq 'select(.kind=="Deployment")' /tmp/helm.yaml | yq -P 'sort_keys(..)')
```
Diff mein sirf labels (`helm.sh/chart`, `app.kubernetes.io/managed-by`) aur comments ka farak dikhna chahiye. Kuch aur dikhe to chart ya base mein se ek galat hai: **sync rakho**.

**Common galtiyan:** values mein `env` ko map banana (list hai); `image.tag` quote na karna (`1.0` float ban jaata hai); prod values mein base values dobara likhna (layering ka matlab khatam).

---

### P10 🔴 Kyverno policy likho: "har Deployment pe `app.kubernetes.io/part-of: shopflow` label ho"

**Task:** Policy + bad resource test + prod overlay pass.

**Kyu:** Labels se hi NetworkPolicy, PDB, dashboards, cost allocation chalte hain. Ek bhool, aur woh pod "invisible" ho jaata hai.

**Solution**
```yaml
# k8s/policies/kyverno/require-part-of-label.yaml
apiVersion: kyverno.io/v1
kind: ClusterPolicy
metadata:
  name: require-part-of-label
spec:
  validationFailureAction: Enforce
  background: true
  rules:
    - name: part-of-shopflow
      match:
        any:
          - resources:
              kinds: ["Deployment", "StatefulSet"]
              namespaces: ["shopflow"]
      validate:
        message: "label app.kubernetes.io/part-of=shopflow is required (NetworkPolicies, dashboards and cost reports key on it)"
        pattern:
          metadata:
            labels:
              app.kubernetes.io/part-of: shopflow
```
```yaml
# k8s/policies/tests/bad-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata: {name: bad, namespace: shopflow, labels: {app: bad}}
spec:
  selector: {matchLabels: {app: bad}}
  template:
    metadata: {labels: {app: bad}}
    spec:
      containers: [{name: app, image: nginx:1.27}]
```
```sh
kyverno apply k8s/policies/kyverno/require-part-of-label.yaml --resource <(kubectl kustomize k8s/overlays/prod)   # pass
kyverno apply k8s/policies/kyverno/require-part-of-label.yaml --resource k8s/policies/tests/bad-deployment.yaml      # fail
```
(Kustomize base `labels:` block har resource pe ye label lagata hai, isliye prod pass karega.)

**Advanced version (sochne ke liye):** `validate` ki jagah `mutate` se label **add** kar do: policy fix karti hai, rokti nahi. Kab kaunsa? (Mutate tab jab default safe ho; validate tab jab insaan ko sochna chahiye.)

---

## Level 3: CI/CD, Infra, Ops

### P11 🟢 CI mein naya check: "koi `System.out` nahi" repo-wide

**Task:** ArchUnit sirf order-service mein hai. Ek sasta grep-based CI step jo poore `shopflow/` mein `System.out`/`printStackTrace` dhoonde aur fail kare.

**Kyu:** Sab checks ko Java test hona zaroori nahi; 1-line shell check bhi CI ka hissa hai (sasta, fail fast).

**Solution**
```yaml
# .github/workflows/shopflow.yml -> lint job ke steps mein
      - name: No System.out / printStackTrace in production code
        run: |
          if grep -rnE 'System\.(out|err)\.print|printStackTrace\(' shopflow/*/src/main/java; then
            echo "::error::use SLF4J logging instead"; exit 1; fi
```
Makefile `lint` target mein bhi wahi line. (Advanced: ArchUnit test ko `shopflow-test-support` module mein nikaal ke sab services mein chalao.)

**Verify:** jaan-boojh ke ek `System.out.println` daalo, `make lint` fail; hatao, pass.

---

### P12 🟡 Terraform: ek naya resource (S3 bucket for backups) + output + tflint

**Task:** `infra/terraform/aws/backups.tf`: encrypted, versioned, private S3 bucket `shopflow-db-backups-<random>` with 30-day lifecycle; output bucket name. **Apply mat karo**: `terraform fmt`, `tflint`, (CI mein `validate`).

**Kyu:** "Ek bucket chahiye" sabse chhota IaC ticket hai aur usme bhi 5 security defaults hain jo log bhoolte hain.

**Solution**
```hcl
# infra/terraform/aws/backups.tf
resource "random_id" "backups" {
  byte_length = 4
}

resource "aws_s3_bucket" "db_backups" {
  bucket        = "${var.name}-db-backups-${random_id.backups.hex}"
  force_destroy = var.environment != "prod"
}

resource "aws_s3_bucket_versioning" "db_backups" {
  bucket = aws_s3_bucket.db_backups.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "db_backups" {
  bucket = aws_s3_bucket.db_backups.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "db_backups" {
  bucket                  = aws_s3_bucket.db_backups.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "db_backups" {
  bucket = aws_s3_bucket.db_backups.id
  rule {
    id     = "expire-old-dumps"
    status = "Enabled"
    filter {}
    expiration {
      days = 30
    }
    noncurrent_version_expiration {
      noncurrent_days = 7
    }
  }
}

output "db_backups_bucket" {
  value = aws_s3_bucket.db_backups.bucket
}
```
Phir backup CronJob (P-advanced) ko `aws s3 cp` se bucket pe bhejna, IRSA role ke saath: `irsa.tf` mein ek aur module `irsa_backup` with `s3:PutObject` on that bucket only.

**Common galtiyan:** bucket naam hard-code (global unique hona chahiye); `acl = "private"` (deprecated, public access block use karo); lifecycle bina `filter {}` (provider v4+ mein required).

**Verify:** `terraform fmt -check`, `tflint`, CI `terraform validate`. **Apply nahi.**

---

### P13 🟡 Ansible: ek naya role `monitoring-agent` (node_exporter)

**Task:** VM pe `node_exporter` systemd service se chale (port 9100), playbook idempotent rahe.

**Kyu:** Ansible mein "ek binary download karo, user banao, systemd unit do" sabse common role hai; yahi pattern har agent (Alloy, Datadog, Fluent Bit) ke liye lagta hai.

**Solution**
```yaml
# infra/ansible/roles/monitoring_agent/tasks/main.yml
- name: Create node_exporter user
  ansible.builtin.user:
    name: node_exporter
    system: true
    shell: /usr/sbin/nologin
    create_home: false

- name: Download node_exporter
  ansible.builtin.unarchive:
    src: "https://github.com/prometheus/node_exporter/releases/download/v{{ node_exporter_version }}/node_exporter-{{ node_exporter_version }}.linux-amd64.tar.gz"
    dest: /opt
    remote_src: true
    creates: "/opt/node_exporter-{{ node_exporter_version }}.linux-amd64/node_exporter"

- name: Link the binary
  ansible.builtin.file:
    src: "/opt/node_exporter-{{ node_exporter_version }}.linux-amd64/node_exporter"
    dest: /usr/local/bin/node_exporter
    state: link

- name: Install systemd unit
  ansible.builtin.template:
    src: node_exporter.service.j2
    dest: /etc/systemd/system/node_exporter.service
    mode: "0644"
  notify: Restart node_exporter

- name: Enable node_exporter
  ansible.builtin.systemd:
    name: node_exporter
    daemon_reload: true
    enabled: true
    state: started
```
```ini
# templates/node_exporter.service.j2
[Unit]
Description=Prometheus node exporter
After=network-online.target
[Service]
User=node_exporter
ExecStart=/usr/local/bin/node_exporter --web.listen-address=:9100
Restart=on-failure
[Install]
WantedBy=multi-user.target
```
`group_vars/shopflow.yml`: `node_exporter_version: "1.9.1"`; handler `Restart node_exporter`; `site.yml` roles mein add.

**Common galtiyan:** `creates:` bhoolna (har run download = not idempotent, `changed` har baar); root se chalana; version hard-code task mein (variable mein rakho).

**Verify:** `ansible-lint` clean, `--syntax-check`, VM pe `curl :9100/metrics`; doosra run → `changed=0`.

---

### P14 🔴 Canary ko doosri service pe lagao + apna AnalysisTemplate (latency)

**Task:** `payment-service` ke liye canary Rollout, aur ek naya `AnalysisTemplate` `latency-p99` jo canary ka p99 < 500ms check kare.

**Kyu:** Error ratio ke alawa latency bhi regression hoti hai (naya code 2× slow). Dono gates lagane se canary sach mein "safe" hota hai.

**Diagram**
```
Rollout(payment-service) ── step 20% ── analysis [error-ratio, latency-p99] ── pass ──▶ 50% ── ... ──▶ 100%
                                                      │ fail (3×)
                                                      ▼
                                                   abort → canary pods removed
```

**Solution**
```yaml
# k8s/rollouts/analysis-latency.yaml
apiVersion: argoproj.io/v1alpha1
kind: AnalysisTemplate
metadata:
  name: latency-p99
spec:
  args:
    - name: service
  metrics:
    - name: p99
      interval: 1m
      count: 5
      successCondition: result[0] < 0.5
      failureLimit: 3
      provider:
        prometheus:
          address: http://prometheus.monitoring:9090
          query: |
            histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{application="{{args.service}}", uri!~"/actuator.*", rollouts_pod_template_hash!=""}[2m])))
```
```yaml
# k8s/rollouts/payment-service-canary.yaml
apiVersion: argoproj.io/v1alpha1
kind: Rollout
metadata: {name: payment-service}
spec:
  selector: {matchLabels: {app.kubernetes.io/name: payment-service}}
  workloadRef: {apiVersion: apps/v1, kind: Deployment, name: payment-service, scaleDown: onsuccess}
  strategy:
    canary:
      maxUnavailable: 0
      analysis:
        templates: [{templateName: error-ratio}, {templateName: latency-p99}]
        args: [{name: service, value: payment-service}]
        startingStep: 1
      steps:
        - setWeight: 20
        - pause: {duration: 5m}
        - setWeight: 50
        - pause: {duration: 5m}
        - setWeight: 100
```
`kustomization.yaml` mein dono files add. Validate: `kustomize build k8s/rollouts | kubeconform -strict -schema-location default -schema-location '<datreeio catalog>' -`.

**Sochne ke liye:** payment-service ka HTTP traffic sirf actuator hai (woh Kafka consumer hai!). To `http_server_requests` wala analysis **hamesha empty** aayega → `result[0]` undefined → analysis error. Sahi metric: `payments_total{status="FAILED"}` ratio, ya consumer lag. Yahi lab ka asli lesson: **canary ka gate service ke kaam ke hisaab se chuno, copy-paste nahi.**

---

### P15 🔴 Poora feature end-to-end: "Order refund"

> **Note (Walk 7):** ye feature ab repo mein **ban chuka hai** (`git show a3c3a93`). Isliye ise do tarah se use karo: (a) pehle khud likho bina solution dekhe, phir apna version actual commit se diff karo; ya (b) agla feature isi pattern pe banao — refund **gateway failure** (`RefundFailed` → order wapas `PAID` + alert), ya `RefundRequested` ko sirf `support` role allow karna. Neeche ka plan dono ke liye template hai.

**Task:** `PAID` order ko cancel karne pe refund: `DELETE /orders/{id}` on PAID → `RefundRequested` event → payment-service refund (fake gateway) → `PaymentRefunded` → order `REFUNDED`. Tests, migration, docs, alert (`RefundFailureRateHigh`), runbook row, STATE.md update: sab.

**Kyu:** Ye ek asli "feature ticket" hai jo backend + events + ops sab touch karta hai. Isko akele kar liya to tum junior nahi rahe.

**Diagram**
```
DELETE /orders/42 (PAID) ─▶ order: REFUND_PENDING + outbox RefundRequested ─▶ orders.events
                                                                                  │
                                                payment-service ◀─────────────────┘  (type == RefundRequested)
                                                     │ refund(amount) via gateway, idempotent per eventId
                                                     ▼ outbox PaymentRefunded / RefundFailed ─▶ payments.events
                                                                                  │
                                                order-service ◀───────────────────┘  REFUNDED / stays PAID + reason
```

**Steps (checklist; solution ke liye Walk 5 ka payment flow template hai)**
1. `OrderStatus`: `REFUND_PENDING`, `REFUNDED`. `Order.requestRefund()`, `markRefunded()`. `isCancellable()` ab PAID ko bhi allow kare → lekin cancel ki jagah refund path.
2. `OrderService.cancel`: agar PAID → `requestRefund()` → status `REFUND_PENDING`, event type `OrderRefundPending`; stock release **nahi** abhi (paisa pehle wapas aaye).
3. payment-service `OrderConfirmedListener` ka switch `OrderRefundPending` handle kare: `PaymentGateway.refund(orderRef, amount)` (interface mein naya method, fake: hamesha approve), `CAPTURED` payment ko `REFUNDED`, event `PaymentRefunded`.
4. order-service `PaymentEventListener`: `PaymentRefunded` → `onPaymentRefunded()` (stock release + `REFUNDED`).
5. Tests: order-service (PAID → DELETE → REFUND_PENDING; `PaymentRefunded` → REFUNDED), payment-service (refund event → `PaymentRefunded` on payments.events).
6. Alert `RefundFailureRateHigh` + runbook + promtool test; `docs/STATE.md` open-items se hatao; notes 01/10 update.

**Common galtiyan:** refund ko synchronous HTTP call bana dena (saga tod diya); `payment` table mein refund ke liye alag row na hona (amount reconcile nahi hoga); `REFUND_PENDING` ka reconciler na likhna (phir se limbo: Walk 4 ka lesson).

**Verify:** `./mvnw verify` green (naye ~5 tests), compose mein PAID order delete → 10 s mein `REFUNDED`, Grafana mein `payments_total{status="REFUNDED"}`.

---

## Kaise aage badhein

| Agar ye pasand aaya | To agla |
|---|---|
| P1–P5 (backend) | Phase 7 roadmap: Schema Registry, Testcontainers |
| P6–P10 (k8s) | Lab 30–32 live cluster pe; apna Kyverno `mutate` policy |
| P11–P14 (CI/infra/ops) | Argo CD se GitOps end to end (notes 06), chaos experiment during k6 |
| P15 | Tumhara apna feature: wishlist, coupons, inventory restock events; pattern wahi hai |

> Tip: Har practice lab ke baad ek commit message likho jaise PR hai: *kya*, *kyu*, *kaise test kiya*. Interview mein "tell me about a change you made end to end" ka jawab yahi commits hain.
