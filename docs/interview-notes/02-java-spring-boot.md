# 02 — Core Java + Spring Boot Interview Q&A

> Level: 0–4 years backend. Each section = short explanation + Q&A. "In ShopFlow" lines point to real files under `shopflow/` so you can say *"I used this in my project"*.

Contents
1. Java 8 → 21 features
2. Collections & HashMap internals
3. Concurrency
4. JVM memory, GC, containers
5. Spring IoC / DI, bean scopes & lifecycle
6. Auto-configuration & starters
7. Profiles, externalised config, `@ConfigurationProperties`
8. `@Transactional`
9. JPA / Hibernate
10. REST API best practices
11. Validation & exception handling
12. Testing pyramid
13. Actuator & observability
14. Scenario questions

---

## 1. Java 8 → 21 features

| Version | Feature you must know |
|---|---|
| 8 | Lambdas, functional interfaces, Streams, `Optional`, default methods, `java.time` |
| 9 | Modules (JPMS), `List.of/Set.of/Map.of` (immutable), private interface methods |
| 10 | `var` (local type inference) |
| 11 (LTS) | `HttpClient`, `String.isBlank/strip/lines/repeat`, run single file `java Foo.java` |
| 14 | Switch expressions (`->`, `yield`) |
| 15 | Text blocks `"""` |
| 16 | Records, pattern matching for `instanceof` |
| 17 (LTS) | Sealed classes |
| 21 (LTS) | **Virtual threads**, pattern matching for `switch`, record patterns, sequenced collections (`getFirst()/getLast()/reversed()`) |

In ShopFlow (Java 21):
- **Records** for DTOs and config: `OrderController.CreateOrderRequest`, `OrderResponse`, `ProductResponse`, `ReservationController.ReserveRequest`, `OrderService.PlacedOrder`, `InventoryProperties`.
- **Text blocks + `formatted()`** in tests: `"""{"sku":"%s","quantity":%d}""".formatted(sku, quantity)`.
- **`var`** in `OrderService` / `InventoryClient`.
- **Streams + `toList()`** (Java 16): `productRepository.findAll().stream().map(ProductResponse::from).toList()`.
- **`Optional`**: `findBySku(sku).map(...).orElseThrow(() -> new ProductNotFoundException(sku))`.
- **`ExecutorService` in try-with-resources** (Java 19+, `ExecutorService` is `AutoCloseable`) in `concurrentReservationsNeverOversell`.

### Code snippets

```java
// Record: final fields, canonical constructor, accessors sku(), equals/hashCode/toString
public record Money(BigDecimal amount, String currency) {
    public Money {                       // compact constructor for validation
        if (amount.signum() < 0) throw new IllegalArgumentException("negative");
    }
}

// Sealed + pattern matching switch (exhaustive, no default needed)
sealed interface PaymentResult permits Paid, Declined, Pending {}
record Paid(String txId) implements PaymentResult {}
record Declined(String reason) implements PaymentResult {}
record Pending() implements PaymentResult {}

String describe(PaymentResult r) {
    return switch (r) {
        case Paid p -> "paid " + p.txId();
        case Declined(String reason) -> "declined: " + reason;   // record pattern
        case Pending p -> "pending";
    };
}

// Virtual threads
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    IntStream.range(0, 10_000).forEach(i -> executor.submit(() -> callSlowApi(i)));
}
```

### Q&A

**Q: Record vs Lombok `@Data` class?**
Record is immutable (final fields, no setters), language-level, gives `equals/hashCode/toString`. Good for DTOs, events, config. Not good for JPA entities (Hibernate needs a no-arg constructor and mutable/proxyable class) — that's why `Order` and `Product` are normal classes with a `protected` no-arg constructor.

**Q: `map` vs `flatMap` in streams?**
`map` is 1→1. `flatMap` is 1→many then flattens: `orders.stream().flatMap(o -> o.lines().stream())`.

**Q: Intermediate vs terminal operations? Are streams lazy?**
Intermediate (`filter`, `map`, `sorted`) return a stream and are lazy. Nothing runs until a terminal op (`collect`, `toList`, `forEach`, `count`, `findFirst`). Short-circuiting ops (`findFirst`, `anyMatch`, `limit`) can stop early.

**Q: `Stream.toList()` vs `Collectors.toList()`?**
`toList()` (16+) returns an **unmodifiable** list; `Collectors.toList()` returns a mutable `ArrayList` (not guaranteed by spec).

**Q: Common `Optional` mistakes?**
Calling `get()` without checking; using `Optional` as a field or method parameter; `orElse(expensiveCall())` (always evaluated — use `orElseGet`). Use it as a **return type** for "might be absent", like Spring Data's `findBySku`.

**Q: What are virtual threads and when do they help?**
Lightweight threads managed by the JVM, mounted on a small pool of carrier (platform) threads. When a virtual thread blocks on I/O it unmounts, so you can have millions. Great for **I/O-bound, thread-per-request** code (REST calls, JDBC). No help for CPU-bound work. In Spring Boot 3.2+: `spring.threads.virtual.enabled=true` (not enabled in ShopFlow). Watch out for **pinning** (blocking inside `synchronized` on Java 21; fixed in 24) and for downstream limits — the DB pool is still 10 connections, virtual threads don't create DB capacity.

**Q: Sealed classes — why?**
Restrict which classes can extend a type. The compiler then knows all subtypes, so `switch` can be exhaustive without `default`. Good for domain results / state types.

**Q: Functional interfaces you know?**
`Function<T,R>`, `Supplier<T>`, `Consumer<T>`, `Predicate<T>`, `BiFunction`, `UnaryOperator`, `Runnable`, `Callable`. Any interface with one abstract method can be a lambda target (`@FunctionalInterface` is optional).

**Q: `String` immutability — why?**
Security (class loading, file paths), thread safety, string pool reuse, cached `hashCode` (good HashMap keys).

---

## 2. Collections & HashMap internals

| Need | Use |
|---|---|
| ordered, index access | `ArrayList` (amortised O(1) add, O(1) get) |
| frequent insert/remove at ends | `ArrayDeque` (prefer over `LinkedList` and `Stack`) |
| unique, no order | `HashSet` |
| unique, insertion order | `LinkedHashSet` |
| sorted | `TreeSet` / `TreeMap` (red-black tree, O(log n)) |
| key→value | `HashMap`; insertion order `LinkedHashMap` (also LRU via `accessOrder=true` + `removeEldestEntry`) |
| thread-safe map | `ConcurrentHashMap` |
| priority | `PriorityQueue` (binary heap) |

### HashMap internals (Java 8+)

- Array of buckets (`Node<K,V>[] table`), default capacity **16**, load factor **0.75** → resize (double) when size > 12.
- `index = (n - 1) & hash`, where `hash = h ^ (h >>> 16)` (spreads high bits).
- Collision → linked list in the bucket; when a bucket grows **beyond 8** entries (`TREEIFY_THRESHOLD = 8`) **and** table size is **≥ 64**, it becomes a **red-black tree** (O(log n) worst case); if the table is smaller than 64 it resizes instead. A tree bin shrinks back to a list at ≤ 6 (on resize split).
- Resize rehashes: each entry either stays at index `i` or moves to `i + oldCap`.
- Allows one `null` key (bucket 0) and null values. Not thread-safe (concurrent resize could corrupt; in Java 7 it could even loop forever).

### Q&A

**Q: equals/hashCode contract?**
Equal objects must have equal hash codes. Unequal objects may share a hash code. If you override `equals` you must override `hashCode`, else `HashMap`/`HashSet` lookups fail. Records do this for you.

**Q: What if a key is mutated after insertion?**
Its hash changes, so `get` looks in the wrong bucket — entry is "lost". Use immutable keys (String, records).

**Q: `HashMap` vs `Hashtable` vs `ConcurrentHashMap`?**
`Hashtable` — legacy, every method `synchronized`, no nulls. `ConcurrentHashMap` — fine-grained (CAS + per-bin locking in Java 8), no null keys/values, iterators are weakly consistent (no `ConcurrentModificationException`). `HashMap` — not thread-safe.

**Q: Fail-fast vs fail-safe iterators?**
Fail-fast (`ArrayList`, `HashMap`) throw `ConcurrentModificationException` if the collection is structurally modified during iteration (via `modCount`). Fail-safe/weakly consistent (`ConcurrentHashMap`, `CopyOnWriteArrayList`) don't.

**Q: `ArrayList` vs `LinkedList`?**
`ArrayList` almost always: contiguous memory, cache-friendly, O(1) random access. `LinkedList` O(n) get, more memory per node; rarely faster in practice.

**Q: How to remove items while iterating?**
`list.removeIf(predicate)` or `Iterator.remove()`. Not `list.remove()` inside a for-each.

**Q: `Comparable` vs `Comparator`?**
`Comparable.compareTo` = natural order inside the class. `Comparator` = external, composable: `Comparator.comparing(Order::getCreatedAt).reversed()`. (ShopFlow sorts in the DB instead: `Sort.by(DESC, "createdAt")`.)

---

## 3. Concurrency

Key tools:

| Tool | Use |
|---|---|
| `synchronized` | simple mutual exclusion + visibility; reentrant; auto-release |
| `ReentrantLock` | `tryLock(timeout)`, fair mode, interruptible, multiple `Condition`s; must `unlock()` in `finally` |
| `ReadWriteLock` / `StampedLock` | many readers, few writers |
| `volatile` | visibility (and ordering) only — **not** atomicity (`count++` still unsafe) |
| `AtomicInteger`, `LongAdder` | lock-free counters (`LongAdder` better under high contention) |
| `ExecutorService` | thread pools — never `new Thread()` per task in servers |
| `CompletableFuture` | async pipelines, combine results |
| `CountDownLatch`, `Semaphore`, `CyclicBarrier` | coordination, limiting concurrency |
| `ConcurrentHashMap`, `BlockingQueue` | thread-safe data structures |

In ShopFlow: `InventoryServiceApplicationTests.concurrentReservationsNeverOversell` uses `Executors.newFixedThreadPool(20)`, `Future.get()` to wait and propagate errors, and `AtomicInteger` counters. **But the real correctness comes from the database** (atomic UPDATE), not Java locks — Java locks only work inside one JVM, and we run multiple pods.

```java
// CompletableFuture: call two services in parallel, combine, with timeout
CompletableFuture<Product> p = CompletableFuture.supplyAsync(() -> productClient.get(sku), ioPool);
CompletableFuture<Price>   q = CompletableFuture.supplyAsync(() -> priceClient.get(sku), ioPool);
ProductView view = p.thenCombine(q, ProductView::new)
        .orTimeout(2, TimeUnit.SECONDS)
        .exceptionally(ex -> ProductView.fallback(sku))
        .join();
```

### Q&A

**Q: `synchronized` vs `ReentrantLock`?**
Both mutual exclusion + reentrant. Lock adds `tryLock` with timeout, interruptible acquisition, fairness, multiple conditions — but you must unlock in `finally`. Use `synchronized` by default; Lock when you need those features.

**Q: Why should you not use `synchronized` to prevent overselling in a web app?**
It only protects one JVM. With 3 pods, three different locks. Use the DB (atomic update / row lock / optimistic version) or a distributed lock. That's exactly why ShopFlow uses `UPDATE ... WHERE quantity >= :qty`.

**Q: `Runnable` vs `Callable`; `submit` vs `execute`?**
`Callable` returns a value and can throw checked exceptions. `submit` returns a `Future` (exceptions are captured inside it — you must call `get()` to see them); `execute` returns nothing and exceptions go to the thread's uncaught handler.

**Q: Thread pool sizing?**
CPU-bound ≈ number of cores. I/O-bound ≈ cores × (1 + wait/compute). Always bounded queue + rejection policy in servers. Or use virtual threads for I/O-bound work.

**Q: `thenApply` vs `thenCompose` vs `thenCombine`?**
`thenApply` = map (sync transform). `thenCompose` = flatMap (next step itself returns a CompletableFuture). `thenCombine` = join two independent futures.

**Q: Default pool for `CompletableFuture.supplyAsync`?**
`ForkJoinPool.commonPool()` — shared and sized for CPU. Never run blocking I/O there; pass your own executor.

**Q: What is a deadlock and how to avoid?**
Two threads each hold a lock the other needs. Avoid with consistent lock ordering, `tryLock` with timeout, smaller lock scopes. Same idea in DBs: update rows in a consistent order (e.g., sort SKUs before reserving multiple products).

**Q: What does `volatile` guarantee?**
Visibility of writes across threads and no reordering around it (happens-before). Not atomic compound operations.

**Q: How does `ConcurrentHashMap` achieve thread safety in Java 8?**
CAS for empty bins, `synchronized` on the first node of a bin for updates, volatile reads for `get` (no locking). Size via `CounterCell`s like `LongAdder`. Use `compute/merge/putIfAbsent` for atomic read-modify-write.

---

## 4. JVM memory, GC, and containers

```
Heap        : objects (young gen: eden + survivors; old gen)       -Xmx / MaxRAMPercentage
Metaspace   : class metadata (native memory)                        -XX:MaxMetaspaceSize
Thread stacks: one per platform thread (~512KB–1MB)                 -Xss
Code cache, direct buffers (NIO/Netty), GC structures              native
```

GCs: **G1** (default since 9; region-based, pause-time goal `-XX:MaxGCPauseMillis=200`), **ZGC** (sub-ms pauses, large heaps; generational mode opt-in in 21 via `-XX:+ZGenerational`, the default from 23), **Parallel** (throughput, batch), **Serial** (tiny heaps — JVM picks it automatically when it sees < 2 CPUs or < ~1.8GB memory, common in small containers!).

### Containers

- Java 10+ is container-aware (reads cgroup limits). Default max heap = **25%** of container memory — usually too small.
- Use `-XX:MaxRAMPercentage=75.0` (not a hard `-Xmx`) so the heap follows the pod's `resources.limits.memory`. Leave ~25% for metaspace, thread stacks, direct memory.
- If total process memory > limit → kernel **OOMKilled** (exit code **137**) — no Java stack trace. Different from `java.lang.OutOfMemoryError` (heap) which you see in logs.
- Useful flags: `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps`, `-XX:+ExitOnOutOfMemoryError` (let K8s restart a broken pod).

In ShopFlow (`shopflow/Dockerfile`, runtime stage, trimmed):
```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
USER 10001
COPY --from=extract /extract/out/dependencies/ ./          # rarely changes -> cached layer
COPY --from=extract /extract/out/spring-boot-loader/ ./
COPY --from=extract /extract/out/snapshot-dependencies/ ./
COPY --from=extract /extract/out/application/ ./           # our code -> small layer
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
```
And the K8s Deployment sets `requests.memory: 384Mi`, `limits.memory: 512Mi` → max heap ≈ 384 MiB, ~128 MiB left for metaspace, thread stacks, code cache. No CPU limit (CPU throttling hurts JVM startup and GC threads); a startupProbe gives the JVM up to 60s.

### Q&A

**Q: Stack vs heap?**
Stack: per thread, method frames, local primitives and references, auto-freed. Heap: shared, all objects, garbage collected. `StackOverflowError` = deep recursion; `OutOfMemoryError: Java heap space` = heap full.

**Q: How does GC decide what's garbage?**
Reachability from GC roots (thread stacks, static fields, JNI refs). Unreachable = collectable. Generational hypothesis: most objects die young → frequent cheap young GCs, rare old GCs.

**Q: Memory leak in Java — how is it possible?**
Objects still referenced but unused: static maps/caches without eviction, listeners not removed, `ThreadLocal` not cleared in thread pools, unclosed resources. Diagnose with heap dump (`jcmd <pid> GC.heap_dump`) + Eclipse MAT, look at dominator tree.

**Q: Pod restarts with exit 137 but no OOM in logs?**
Container memory limit exceeded (heap + non-heap) → OOMKilled. Lower `MaxRAMPercentage`, check thread count / direct buffers, or raise limit. Check `kubectl describe pod` → `Last State: OOMKilled`.

**Q: `-Xms` = `-Xmx`?**
Common in servers to avoid heap resizing; in containers prefer `InitialRAMPercentage`/`MaxRAMPercentage`.

**Q: String pool / `intern`?**
Literal strings are interned in the heap's string table; `new String("a")` creates a new object. Compare strings with `equals`, never `==`.

---

## 5. Spring IoC / DI, bean scopes & lifecycle

- **IoC**: the container creates and wires objects (beans); your code declares dependencies.
- **DI types**: constructor (recommended), setter, field (`@Autowired` on field — avoid in production code; fine in tests).
- ShopFlow uses **constructor injection everywhere, no `@Autowired`** (single constructor = autowired automatically): e.g. `OrderService(OrderRepository, InventoryClient, MeterRegistry)`. Tests use field `@Autowired` for `MockMvc`.

Why constructor injection: dependencies are `final` (immutable), object can't exist half-initialised, easy to unit-test with `new`, circular dependencies fail fast.

### Scopes

| Scope | Instances |
|---|---|
| `singleton` (default) | one per ApplicationContext |
| `prototype` | new each time it's requested |
| `request` / `session` | per HTTP request / session (web) |
| `application` | per ServletContext |

Singletons must be **stateless** (or thread-safe) — every request thread shares them. `OrderService` holds only final references to other beans → safe.

### Lifecycle

```
instantiate → populate properties (DI) → Aware callbacks (BeanNameAware, ApplicationContextAware)
→ BeanPostProcessor.postProcessBeforeInitialization
→ @PostConstruct → InitializingBean.afterPropertiesSet → custom init-method
→ BeanPostProcessor.postProcessAfterInitialization   ← AOP proxies created HERE
→ bean in use
→ @PreDestroy → DisposableBean.destroy → destroy-method  (on context close / graceful shutdown)
```

### Q&A

**Q: `@Component` vs `@Service` vs `@Repository` vs `@Controller`?**
All are `@Component` stereotypes found by component scanning. `@Repository` adds persistence exception translation (to `DataAccessException`). `@RestController` = `@Controller` + `@ResponseBody`. `@Service` is semantic. (ShopFlow repositories are Spring Data interfaces — they get proxies automatically.)

**Q: `@Bean` vs `@Component`?**
`@Component` on your own class (scanned). `@Bean` method in a `@Configuration` class for third-party objects or when construction needs logic.

**Q: Two beans of the same type — how to choose?**
`@Primary`, `@Qualifier("name")`, or inject `List<T>`/`Map<String,T>` to get all.

**Q: Injecting a prototype into a singleton?**
It's injected once, so you get one instance forever. Use `ObjectProvider<T>`, `@Lookup`, or a scoped proxy.

**Q: How do circular dependencies behave in Boot 2.6+?**
Disallowed by default (startup fails). Fix the design (extract a third bean, use events); `spring.main.allow-circular-references=true` is only a stopgap. With pure constructor injection they can't be resolved at all (unless one side is `@Lazy`).

**Q: What is a Spring AOP proxy?**
A wrapper object (Spring Framework: JDK dynamic proxy for interfaces, CGLIB subclass otherwise; **Spring Boot defaults to CGLIB** via `spring.aop.proxy-target-class=true`) that intercepts external method calls to add behaviour: `@Transactional`, `@Cacheable`, `@Async`, Resilience4j `@Retry`/`@CircuitBreaker` (why ShopFlow's order-service needs `spring-boot-starter-aop`). Only calls **through the proxy** are intercepted.

**Q: `BeanFactory` vs `ApplicationContext`?**
`ApplicationContext` extends `BeanFactory` and adds eager singleton init, events, i18n, environment/profiles, AOP integration. You always use `ApplicationContext` in Boot.

---

## 6. Auto-configuration & starters

- `@SpringBootApplication` = `@Configuration` + `@EnableAutoConfiguration` + `@ComponentScan` (from the class's package down — that's why `OrderServiceApplication` sits in `com.shopflow.order`).
- Auto-config classes are listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` and are guarded by conditions: `@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnProperty`, `@ConditionalOnWebApplication`.
- **Starters** = curated dependency bundles (`spring-boot-starter-web` → Spring MVC + Tomcat + Jackson). Versions come from the parent BOM (`spring-boot-starter-parent 3.5.x` in `shopflow/pom.xml`).

Examples in ShopFlow:
- `postgresql` driver + `spring-boot-starter-data-jpa` on classpath → DataSource (Hikari) + EntityManagerFactory + JPA repositories auto-configured from `spring.datasource.*`.
- `flyway-core` on classpath → Flyway runs `db/migration` on startup, **before** JPA validation.
- `micrometer-registry-prometheus` → `/actuator/prometheus` endpoint.
- `resilience4j-spring-boot3` → registries built from `resilience4j.*` properties.
- `RestClient.Builder` bean auto-configured (with message converters and observation) → injected into `InventoryClient`.

### Q&A

**Q: How do you override an auto-configured bean?**
Define your own bean of that type — most auto-configs use `@ConditionalOnMissingBean` and back off.

**Q: How to see which auto-configs applied?**
Run with `--debug` (conditions evaluation report) or the actuator `conditions` endpoint (not exposed in ShopFlow).

**Q: How to exclude one?**
`@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)` or `spring.autoconfigure.exclude`.

**Q: What happens in `SpringApplication.run`?**
Create environment (load properties/profiles) → create ApplicationContext → run auto-config + component scan → instantiate singletons → start embedded Tomcat → `ApplicationStartedEvent` (liveness CORRECT) → run `CommandLineRunner`/`ApplicationRunner` beans → `ApplicationReadyEvent` (readiness becomes ACCEPTING_TRAFFIC).

**Q: How would you write a custom starter?**
An autoconfigure module with a `@AutoConfiguration` class + conditions + `@ConfigurationProperties`, registered in the `.imports` file, plus a starter POM that pulls it in.

---

## 7. Profiles, externalised config, `@ConfigurationProperties`

Property precedence (high → low, simplified): command-line args → `SPRING_APPLICATION_JSON` → OS env vars → `application-{profile}.yml` → `application.yml` → defaults. Relaxed binding: `INVENTORY_BASE_URL` env var ↔ `inventory.base-url` ↔ `baseUrl` field.

In ShopFlow:

```yaml
# order-service/src/main/resources/application.yml
spring.datasource.url: ${DB_URL:jdbc:postgresql://localhost:5432/orders}   # env var with local default
spring.datasource.hikari.maximum-pool-size: ${DB_POOL_SIZE:10}
inventory:
  base-url: ${INVENTORY_URL:http://localhost:8082}
  connect-timeout: 1s
  read-timeout: 2s
```

```java
// InventoryProperties.java — immutable typed config, Duration parsed from "1s"/"2s"
@ConfigurationProperties(prefix = "inventory")
public record InventoryProperties(String baseUrl, Duration connectTimeout, Duration readTimeout) {}
// enabled by @ConfigurationPropertiesScan on OrderServiceApplication
```

- Profile `test`: `@ActiveProfiles("test")` loads `src/test/resources/application-test.yml` (H2 URL, `read-timeout: 500ms`, retry `wait-duration: 10ms`).
- Tests override a single property at runtime with `@DynamicPropertySource` (`inventory.base-url` → WireMock URL).

### Q&A

**Q: `@Value` vs `@ConfigurationProperties`?**
`@Value("${x}")` for one-off values. `@ConfigurationProperties` for groups: type-safe, relaxed binding, `Duration`/`DataSize` conversion, can be validated with `@Validated` + constraints, IDE metadata. Prefer it.

**Q: How do you handle secrets?**
Never in Git or `application.yml`. Inject as env vars from K8s Secrets / Vault / AWS Secrets Manager. ShopFlow reads `DB_PASSWORD` from Secret `shopflow-db` (`secretKeyRef` in the Deployment; Kustomize `secretGenerator` in dev, External Secrets Operator in prod). The local default `orders` is only for laptop dev.

**Q: How do you activate a profile?**
`SPRING_PROFILES_ACTIVE=prod`, `--spring.profiles.active=prod`, or `@ActiveProfiles` in tests. Avoid lots of env-specific profiles — prefer one config + env vars (12-factor).

**Q: Can config change without restart?**
Not by default. Options: Spring Cloud Config + `@RefreshScope` + `/actuator/refresh`, or K8s ConfigMap change + rolling restart (simplest, most common).

**Q: Validate config at startup?**
Add `@Validated` and constraints (`@NotNull URI baseUrl`) on the properties record — app fails fast on bad config. (Not done in ShopFlow — easy improvement.)

---

## 8. `@Transactional`

- Implemented with an AOP proxy: begin tx before method, commit after, rollback on exception.
- **Default rollback**: on `RuntimeException` and `Error` only; **checked exceptions commit** unless `rollbackFor = Exception.class`.
- Works only on methods called **from outside** the bean (through the proxy) — public methods, or since Spring 6.0 also protected/package-private ones on class-based (CGLIB) proxies; never private.

In ShopFlow:
- `ReservationService.reserve` and `.release` are `@Transactional` — decrement + insert must commit or roll back together.
- `ProductController` has class-level `@Transactional(readOnly = true)`.
- `OrderService` has **no** `@Transactional` on `placeOrder` on purpose: never hold a DB transaction across a remote HTTP call. Its private `saveWithEvent` uses a **`TransactionTemplate`** to commit the order row and the `outbox_event` row together (transactional outbox) — programmatic, because an annotation on a self-invoked private method would be ignored.
- `OutboxRelay.publishPending` is `@Transactional` so the `FOR UPDATE SKIP LOCKED` rows stay locked until the batch is marked published; `OrderEventListener.onOrderEvent` (notification-service) is `@KafkaListener` + `@Transactional` so the `processed_event` insert and the side effect commit together.

### Propagation

| Propagation | Behaviour |
|---|---|
| `REQUIRED` (default) | join existing tx or start new |
| `REQUIRES_NEW` | suspend current, start independent tx (e.g. audit log that must persist even if outer rolls back) |
| `SUPPORTS` | join if exists, else non-transactional |
| `MANDATORY` | must already be in a tx, else exception |
| `NOT_SUPPORTED` | suspend tx, run without |
| `NEVER` | exception if a tx exists |
| `NESTED` | savepoint inside current tx (needs savepoint support — e.g. `DataSourceTransactionManager`/JDBC) |

### Isolation

| Level | Dirty read | Non-repeatable read | Phantom |
|---|---|---|---|
| READ_UNCOMMITTED | possible | possible | possible |
| READ_COMMITTED (Postgres default) | no | possible | possible |
| REPEATABLE_READ (MySQL InnoDB default) | no | no | possible (std) / no in PG |
| SERIALIZABLE | no | no | no |

### Self-invocation pitfall

```java
@Service
class OrderService {
    public void placeAll(List<Req> reqs) {
        reqs.forEach(this::placeOne);        // "this" = raw object, NOT the proxy
    }
    @Transactional
    public void placeOne(Req r) { ... }      // => runs WITHOUT a transaction!
}
```
Fixes: move `placeOne` to another bean; inject self via `ObjectProvider`; or use `TransactionTemplate` programmatically — **exactly what `OrderService.saveWithEvent` does** (its Javadoc: "calling an annotated method from inside the same class bypasses the proxy, so it would silently run without a transaction"). Same pitfall applies to `@Retry`, `@CircuitBreaker`, `@RateLimiter`, `@Cacheable`, `@CacheEvict`, `@Async` — that's why ShopFlow puts `@Retry/@CircuitBreaker` on `InventoryClient` (separate bean) and calls it from `OrderService`, and why `@Cacheable` on `ProductController.get` works (Spring MVC calls the controller through its proxy).

### Q&A

**Q: Does `@Transactional` work on private methods?**
No (proxy can't intercept). Spring 6 supports protected/package-private with CGLIB, but keep it on public service methods.

**Q: Exception caught inside the method — rollback?**
No — the proxy never sees it. Rethrow, or call `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`.

**Q: "Transaction silently rolled back because it has been marked as rollback-only"?**
An inner `REQUIRED` method threw (marked the shared tx rollback-only), the outer caught it and tried to commit. Use `REQUIRES_NEW` for the inner part or don't swallow.

**Q: What does `readOnly = true` do?**
Spring sets Hibernate's FlushMode to MANUAL and the session to read-only (no dirty-checking snapshots, no flush) and calls `Connection.setReadOnly(true)`; a routing DataSource can use it to send reads to a replica. Mainly an optimisation — whether writes are actually rejected depends on the driver/DB (PostgreSQL's JDBC driver runs the tx as `READ ONLY`, so writes fail there).

**Q: Why not wrap a REST call in a transaction?**
Holds a DB connection during network latency → pool exhaustion; can't roll back the remote side effect anyway. Exactly the `OrderService` Javadoc.

**Q: `@Transactional` on a test method?**
Spring test rolls it back after the test by default — handy for isolation, but it hides real commit behaviour and doesn't work for multi-thread tests (ShopFlow's concurrency test is not transactional; it uses unique `orderRef`s and seeded data instead).

---

## 9. JPA / Hibernate

Key ideas:
- **Persistence context** (first-level cache) = managed entities in the current tx/session; **dirty checking** writes changes at flush/commit without calling `save`.
- **Entity states**: transient → managed (`persist`) → detached (session closed) → removed.
- `save()` in Spring Data: `persist` if new, else `merge` (returns a managed copy — always use the returned object: `order = orderRepository.save(order)` as in `OrderService`).

In ShopFlow:
- `ddl-auto: validate` + Flyway migrations (`V1__create_orders.sql`, `V1__create_tables.sql`, `V2__seed_products.sql`).
- `open-in-view: false`.
- `@Enumerated(EnumType.STRING)` for `OrderStatus` / `Reservation.Status` (ORDINAL breaks if you reorder enum constants).
- `@Version long version` on `Order` → optimistic locking.
- `@PrePersist`/`@PreUpdate` callbacks set `createdAt`/`updatedAt` on `Order`.
- `@Modifying @Query` bulk updates in `ProductRepository` with `clearAutomatically`/`flushAutomatically`.
- `GenerationType.IDENTITY` with `bigint generated by default as identity` columns.
- Derived queries: `findBySku`, `existsBySku`, `findByOrderRef`, `findByIdempotencyKey`.

### N+1 problem

```java
List<Order> orders = repo.findAll();          // 1 query
orders.forEach(o -> o.getLines().size());     // + N queries (one per order) if lines are LAZY
```
Fixes: `JOIN FETCH` in JPQL, `@EntityGraph(attributePaths = "lines")`, `@BatchSize` / `hibernate.default_batch_fetch_size`, or DTO projections. Detect with `spring.jpa.show-sql`/Hibernate statistics, or count queries in tests. (ShopFlow entities have no associations, so no N+1 risk today.)

### Locking

| | Optimistic | Pessimistic |
|---|---|---|
| How | `@Version` column; `UPDATE ... WHERE id=? AND version=?` | `SELECT ... FOR UPDATE` (`@Lock(LockModeType.PESSIMISTIC_WRITE)`) |
| Conflict | `OptimisticLockException` at flush/commit (Spring: `ObjectOptimisticLockingFailureException`) → retry | other tx **waits** (or times out) |
| Best for | low contention, long user "think time" | high contention, short tx |
| Risk | retry storms on hot rows | deadlocks, blocked threads |

ShopFlow uses **both ideas differently**: `@Version` for `Order` (rare concurrent updates), and a **conditional atomic UPDATE** for stock (hot rows) — better than either for a single counter.

### Q&A

**Q: LAZY vs EAGER — defaults?**
`@ManyToOne`/`@OneToOne` default EAGER, `@OneToMany`/`@ManyToMany` default LAZY. Best practice: make everything LAZY and fetch explicitly per use case.

**Q: `LazyInitializationException`?**
Accessing a lazy association after the session closed (e.g. in the controller with open-in-view off). Fix by fetching what you need inside the transaction (fetch join / entity graph) and mapping to DTOs there. Don't "fix" it by enabling open-in-view or EAGER.

**Q: What is open-in-view and why disable it?**
OSIV keeps the Hibernate session (and a DB connection, once used) open for the whole HTTP request including JSON serialisation, enabling lazy loads in the view. Problems: connections held longer, hidden N+1 queries in serialisation. Boot warns about it at startup. ShopFlow sets `spring.jpa.open-in-view: false`.

**Q: `ddl-auto` values?**
`none`, `validate`, `update`, `create`, `create-drop`. Production: `validate` or `none` with Flyway/Liquibase.

**Q: `persist` vs `merge`?**
`persist` makes a new instance managed (same object). `merge` copies the state of a detached object onto a managed one and returns the managed copy.

**Q: `@Modifying` query and the persistence context?**
Bulk JPQL updates bypass the first-level cache → loaded entities become stale. `clearAutomatically = true` clears after; `flushAutomatically = true` flushes pending changes before. ShopFlow's `release()` depends on the flush so the `RELEASED` status isn't lost.

**Q: How do you see the SQL Hibernate runs?**
`spring.jpa.show-sql=true` (stdout) or better `logging.level.org.hibernate.SQL=debug` and `org.hibernate.orm.jdbc.bind=trace`; in prod use datasource-proxy / p6spy or DB slow-query logs.

**Q: First-level vs second-level cache?**
First-level: per persistence context, always on. Second-level: shared across sessions (Ehcache/Hazelcast), opt-in per entity; careful with consistency across pods.

---

## 10. REST API best practices

| Method | Safe | Idempotent | ShopFlow example |
|---|---|---|---|
| GET | yes | yes | `GET /api/v1/orders/{id}` |
| POST | no | **no** (made idempotent with `Idempotency-Key`) | `POST /api/v1/orders` |
| PUT | no | yes | — |
| PATCH | no | not necessarily | — |
| DELETE | no | yes | `DELETE /api/v1/reservations/{orderRef}` (204 every time). Idempotent = same server *state*, not same response: `DELETE /api/v1/orders/{id}` returns 409 the 2nd time, but nothing changes |

Status codes used in ShopFlow:

| Code | When |
|---|---|
| 200 | GET, cancel, idempotent POST replay |
| 201 | new order with `Location` (`ResponseEntity.created(...)`); new reservation via `@ResponseStatus(CREATED)` (no `Location` header) |
| 204 | release reservation |
| 400 | validation failure (body or params) |
| 404 | order / product not found |
| 409 | insufficient stock, not cancellable, concurrent duplicate request |
| 503 | inventory unavailable / circuit open |

Other practices:
- **Versioning**: URI `/api/v1/...` (ShopFlow). Alternatives: header (`Accept: application/vnd.shopflow.v2+json`), query param. Breaking change → new version; additive changes don't need one.
- **Pagination**: `page`/`size` with max size (ShopFlow caps at 100), stable sort (`createdAt DESC`). For big/infinite feeds use **keyset/cursor pagination** (`WHERE created_at < :cursor`) — offset gets slow for deep pages.
- **Idempotency** for POST via `Idempotency-Key`.
- **ProblemDetail (RFC 7807, superseded by RFC 9457 — same format)** errors: `type`, `title`, `status`, `detail`, `instance` + custom properties (`orderId`).
- Nouns not verbs, plural resources, DTOs not entities, consistent naming, OpenAPI docs (`/swagger-ui.html`).

### Q&A

**Q: PUT vs PATCH?**
PUT replaces the whole resource (idempotent). PATCH partial update (JSON Merge Patch / JSON Patch).

**Q: 401 vs 403?**
401 = not authenticated (no/invalid token). 403 = authenticated but not allowed.

**Q: 400 vs 422?**
400 malformed/invalid request; 422 well-formed but semantically invalid. Many APIs (and Spring defaults) use 400 for validation.

**Q: How do you make POST idempotent?**
Client sends a unique `Idempotency-Key`; server stores it with a unique constraint alongside the result; replays return the stored result. ShopFlow: `orders.idempotency_key` unique + `findByIdempotencyKey`.

**Q: Why return `Location` on 201?**
Tells the client the URI of the new resource (`/api/v1/orders/{id}`); REST convention.

**Q: Offset vs cursor pagination?**
Offset is simple but `OFFSET 100000` scans and skips rows, and results shift when new rows insert. Cursor uses an indexed column value as the bookmark — fast and stable.

---

## 11. Validation & exception handling

```java
// OrderController
public record CreateOrderRequest(@NotBlank @Size(max = 64) String sku,
                                 @Min(1) @Max(100) int quantity) {}

@PostMapping
public ResponseEntity<OrderResponse> create(
        @Valid @RequestBody CreateOrderRequest request,
        @RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 100) String idempotencyKey) { ... }

@GetMapping
public PagedModel<OrderResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) { ... }
```

- `@Valid` on a body → `MethodArgumentNotValidException`.
- Constraints directly on method parameters (`@Min` on `@RequestParam`) → Spring 6.1+ built-in **method validation** → `HandlerMethodValidationException`. Both become 400 via `ResponseEntityExceptionHandler`.
- Gotcha: once **any** parameter has a direct constraint, method validation also covers the `@Valid @RequestBody`, so body errors raise `HandlerMethodValidationException` instead of `MethodArgumentNotValidException`. That is what happens on ShopFlow's `POST /api/v1/orders` (the `Idempotency-Key` header has `@Size`), which is why both handlers are overridden. Inventory's `POST /api/v1/reservations` has no parameter constraints, so it uses `MethodArgumentNotValidException`.
- ShopFlow's `GlobalExceptionHandler` (both services) **overrides** `handleMethodArgumentNotValid` and `handleHandlerMethodValidationException` to add a sorted `errors` list so clients see *which* field failed:

```java
@Override
protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
        HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    ex.getBody().setProperty("errors", messages(ex.getAllErrors()));
    return super.handleMethodArgumentNotValid(ex, headers, status, request);
}

private static List<String> messages(List<? extends MessageSourceResolvable> errors) {
    return errors.stream()
            .map(e -> e instanceof FieldError fe ? fe.getField() + ": " + fe.getDefaultMessage() : e.getDefaultMessage())
            .sorted()
            .toList();
}
```
(Note the Java 16 pattern-matching `instanceof FieldError fe` — a nice "modern Java in my project" example.)
- `GlobalExceptionHandler extends ResponseEntityExceptionHandler` + `@RestControllerAdvice` → custom exceptions to `ProblemDetail`; `spring.mvc.problemdetails.enabled: true`.

```java
@ExceptionHandler(InventoryUnavailableException.class)
ProblemDetail handleInventoryUnavailable(InventoryUnavailableException ex) {
    ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "Inventory unavailable", ex.getMessage());
    problem.setProperty("orderId", ex.getOrderId());   // extension member
    return problem;
}
```

Sample response:
```json
{ "type": "about:blank", "title": "Inventory unavailable", "status": 503,
  "detail": "Inventory service unavailable, order 7 marked FAILED",
  "instance": "/api/v1/orders", "orderId": 7 }
```
Validation failure (`POST /api/v1/orders` with `{"sku":"","quantity":0}`, asserted in `validatesRequests`):
```json
{ "type": "about:blank", "title": "Bad Request", "status": 400,
  "detail": "Validation failure", "instance": "/api/v1/orders",
  "errors": ["quantity: must be greater than or equal to 1", "sku: must not be blank"] }
```

### Q&A

**Q: `@Valid` vs `@Validated`?**
`@Valid` (Jakarta) triggers cascaded validation of an object. `@Validated` (Spring) supports validation groups and enables method-level validation on a class.

**Q: How to write a custom constraint?**
Annotation with `@Constraint(validatedBy = SkuValidator.class)` + a `ConstraintValidator<Sku, String>` implementation.

**Q: `@ControllerAdvice` vs `@RestControllerAdvice`?**
The latter adds `@ResponseBody`. Both apply `@ExceptionHandler`s globally.

**Q: Why extend `ResponseEntityExceptionHandler`?**
It already maps Spring MVC's own exceptions (validation, 405, 415, missing params…) to proper ProblemDetail responses — you only add domain handlers, and override the protected `handleXxx` methods when you want to enrich the body (ShopFlow adds the `errors` list).

**Q: Should you expose exception messages to clients?**
Domain messages yes ("Insufficient stock for PS5-SLIM"); internal ones (SQL, stack traces) no. ShopFlow's downstream handler returns a generic "Try again later". The `DataIntegrityViolationException` handler also hides SQL details.

**Q: Checked vs unchecked exceptions in Spring apps?**
Prefer unchecked domain exceptions (`OrderNotFoundException extends RuntimeException`): they roll back transactions by default and don't pollute signatures.

---

## 12. Testing pyramid

```
           /  E2E (few)  \          real deployed services, slow, flaky
          / @SpringBootTest \       full context + MockMvc/RANDOM_PORT + WireMock/Testcontainers
         /  slices: @WebMvcTest, @DataJpaTest, @JsonTest \
        /        unit tests (many, fast, JUnit 5 + Mockito)        \
```

| Tool | Loads | Use for |
|---|---|---|
| JUnit 5 + Mockito | nothing | pure logic (e.g. `Order.isCancellable()`), `OrderService` with mocked repo/client |
| `@WebMvcTest(OrderController.class)` | MVC layer only; `@MockitoBean` the service | status codes, validation, JSON, advice (**ShopFlow: `OrderControllerWebTest`** — 409 optimistic lock, 429 rate limit; `@Import(SecurityConfig, JwtRolesConverter)` + `@MockitoBean JwtDecoder` so the security chain loads without an issuer) |
| `@DataJpaTest` | JPA + embedded DB (tx rolled back per test) | queries, constraints, mappings, `decrementStock` |
| `@SpringBootTest` + `@AutoConfigureMockMvc` | everything, mock servlet env | integration flows (**what ShopFlow uses**) |
| `@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`/`RestClient` | real server | true HTTP tests |
| WireMock | fake HTTP server | downstream services: errors, delays, scenarios (**ShopFlow**) |
| `@EmbeddedKafka` (`spring-kafka-test`) | in-process Kafka broker; `spring.kafka.bootstrap-servers: ${spring.embedded.kafka.brokers}` | outbox → topic (`publishesOrderEventThroughTheOutbox`), duplicate delivery and poison pill → DLT in notification-service (**ShopFlow**) |
| `spring-security-test` `jwt()` | `SecurityMockMvcRequestPostProcessors.jwt().authorities(new SimpleGrantedAuthority("ROLE_customer"))` | authenticated requests without a real IdP; 401/403 paths (**ShopFlow**) |
| Awaitility | `await().atMost(...).untilAsserted(...)` | async assertions: relay published, consumer processed (**ShopFlow**) |
| Testcontainers | real Postgres/Kafka/Redis in Docker | DB-specific behaviour (not in ShopFlow yet) |

ShopFlow WireMock wiring:
```java
static final WireMockServer inventory = new WireMockServer(wireMockConfig().dynamicPort());
static { inventory.start(); }

@DynamicPropertySource
static void inventoryUrl(DynamicPropertyRegistry registry) {
    registry.add("inventory.base-url", inventory::baseUrl);
}
```

Testcontainers version (how you'd add it):
```java
@Testcontainers
@SpringBootTest
class InventoryPgTest {
    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
}
```

### Q&A

**Q: `@Mock` vs `@MockitoBean` (formerly `@MockBean`)?**
`@Mock` is plain Mockito (no Spring). `@MockitoBean` (Spring Framework 6.2 / Boot 3.4+; Boot's `@MockBean` is deprecated since 3.4) replaces a bean in the Spring context with a mock.

**Q: Why WireMock instead of mocking `InventoryClient`?**
Mocking the client skips the real HTTP layer: serialisation, status mapping (`onStatus`), timeouts, and Resilience4j proxies. WireMock exercises all of it — that's how ShopFlow proves "retry 3 times on 500, 0 calls when breaker is open".

**Q: Why H2 and what's the risk?**
Fast, no Docker. Risk: different locking/SQL. ShopFlow mitigates with `MODE=PostgreSQL` and running the same Flyway migrations; Testcontainers is the proper fix.

**Q: How do you keep `@SpringBootTest` fast?**
Reuse the cached context (same config across classes; avoid `@DirtiesContext` and per-class `@MockitoBean` variations), use slices where enough, shorten timeouts in a test profile (ShopFlow: retry `wait-duration: 10ms`, `read-timeout: 500ms`).

**Q: How do you test concurrency?**
Executor + many threads hitting the real service + assert invariants (ShopFlow: 20 threads, 3 units → 3 successes, 17 rejections, stock 0). Use `Future.get()` so exceptions in threads fail the test.

**Q: Test isolation without `@Transactional` rollback?**
Unique data per test (`UUID.randomUUID()` orderRefs), reset stubs (`inventory.resetAll()`) and state (`circuitBreakerRegistry.circuitBreaker("inventory").reset()`) in `@BeforeEach`.

**Q: How do you test Kafka and security without Docker or an identity provider?**
`@EmbeddedKafka(partitions = 1, topics = OrderEvent.TOPIC)` starts a broker inside the test JVM; the order test consumes with a raw `KafkaConsumer` from `KafkaTestUtils.consumerProps(...)` and asserts the record key is the `orderRef`; notification tests publish with `KafkaTemplate` and wait with Awaitility on metrics (`notifications.duplicates`) and the `processed_event` table. For security, `jwt()` from `spring-security-test` places a `JwtAuthenticationToken` in the request so the `JwtDecoder` is never called; the test profile's `issuer-uri` is a dummy. In the `@WebMvcTest` slice the decoder is a `@MockitoBean` because the slice would otherwise try to fetch the issuer's JWKS at startup.

---

## 13. Actuator & observability

ShopFlow config (all three services; tracing and Kafka specifics are in [11](11-security-caching-performance.md) and [10](10-kafka-event-driven.md)):

```yaml
management:
  endpoints.web.exposure.include: health,info,prometheus
  endpoint.health.probes.enabled: true                 # /actuator/health/liveness, /readiness
  endpoint.health.group.readiness.include: readinessState,db
  metrics.tags.application: ${spring.application.name}
  metrics.distribution.percentiles-histogram.http.server.requests: true
```

Custom metrics:
```java
// ReservationService — Prometheus: inventory_reservations_rejected_total
Counter.builder("inventory.reservations.rejected").register(meterRegistry);
// OrderService — Prometheus: orders_total{status="CONFIRMED"}
meterRegistry.counter("orders", "status", order.getStatus().name()).increment();
```

### Q&A

**Q: Liveness vs readiness vs startup probe?**
Liveness: "am I broken beyond repair?" → failing = restart. Readiness: "can I take traffic now?" → failing = removed from Service endpoints, no restart. Startup: protects slow-starting apps from liveness kills. Never put external dependencies in liveness. ShopFlow Deployments: startupProbe on `/actuator/health/liveness` (2s × 30), livenessProbe every 10s, readinessProbe on `/actuator/health/readiness` every 5s.

**Q: Why is only `health,info,prometheus` exposed?**
Least privilege: `env`, `heapdump`, `beans`, `configprops` can leak secrets. Expose more only on a separate management port / internal network.

**Q: Counter vs Gauge vs Timer vs DistributionSummary?**
Counter only increases (orders placed). Gauge is a current value (queue size, pool active). Timer = count + total time + histogram (request latency). DistributionSummary = same for non-time values (payload size).

**Q: How do you get p99 latency?**
With `percentiles-histogram` on, Prometheus gets buckets: `histogram_quantile(0.99, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))`. Histograms aggregate across pods; client-side percentiles don't.

**Q: Watch out for in metric tags?**
High cardinality — never tag with userId/orderId. ShopFlow tags by `status` (5 values) only.

---

## 14. Scenario questions

**S1. An API is slow in production. How do you debug?**
1. Scope it: which endpoint, since when (deploy?), all pods or one? Check p95/p99 from `http_server_requests_seconds` by `uri`.
2. Split the time: app CPU vs DB vs downstream. Look at Hikari `hikaricp_connections_pending` / acquire time, downstream client metrics, GC pause metrics.
3. DB: slow-query log / `pg_stat_statements`, `EXPLAIN ANALYZE`; missing index (ShopFlow added `idx_orders_created_at` for the newest-first list), N+1 queries.
4. Threads: `jcmd <pid> Thread.print` — many threads `WAITING` on the pool or blocked on a socket read?
5. Downstream: timeouts missing? (ShopFlow sets 1s/2s and a breaker.)
6. Fix + add a dashboard/alert so you catch it earlier next time.

**S2. Pod OOM / `OutOfMemoryError`.**
Distinguish: Java `OutOfMemoryError` in logs (heap) vs exit 137 OOMKilled (container limit). Heap: take heap dump (`-XX:+HeapDumpOnOutOfMemoryError`), analyse in MAT — unbounded caches, huge `findAll()` without pagination (ShopFlow's `GET /products` is unpaginated — fine for 4 products, risky for 4 million), large result sets. Container: ShopFlow already uses `MaxRAMPercentage=75` with a 512Mi limit and `ExitOnOutOfMemoryError` (crash → K8s restarts a clean pod instead of limping); if still OOMKilled, check thread count and direct memory, lower the percentage or raise the limit. Many teams set memory request = limit for predictable scheduling.

**S3. "Connection is not available, request timed out after 30000ms" (Hikari pool exhausted).**
Causes: long transactions, remote calls inside `@Transactional` (exactly what `OrderService` avoids), OSIV holding connections (ShopFlow disables), connection leaks (unclosed manual JDBC), slow queries, pool too small for load. Diagnose: `hikaricp_connections_active/pending`, `leakDetectionThreshold`, thread dump. Fix root cause before increasing `DB_POOL_SIZE`; remember `pods × pool size ≤ DB max_connections`.

**S4. Customers report duplicate orders.**
Usually client/gateway retries on timeouts or double clicks. Fix: `Idempotency-Key` with a DB unique constraint (ShopFlow: `orders.idempotency_key unique`, replay returns 200 with the original order); disable the button client-side; make downstream calls idempotent (`orderRef`). Don't rely on "check then insert" without a unique constraint — it's racy.

**S5. Stock went negative / we oversold.**
Read-modify-write race (`find → set → save`) across threads or pods. Fix with an atomic conditional UPDATE (ShopFlow `decrementStock`), or pessimistic/optimistic locking, plus a `CHECK (quantity >= 0)` DB constraint. Add a concurrency test.

**S6. Downstream service is down and our service is also falling over.**
Threads stuck waiting with no timeout → all Tomcat threads busy → whole service unresponsive. Fix: timeouts (connect/read), circuit breaker (fail fast), bulkhead (limit concurrent calls), fallback/degraded response, and keep downstream out of readiness. ShopFlow does timeouts + breaker + readiness exclusion.

**S7. `@Transactional` isn't rolling back.**
Check: self-invocation, private method, exception caught inside, checked exception (needs `rollbackFor`), bean not a Spring bean (created with `new`), wrong `TransactionManager`, MySQL MyISAM tables.

**S8. App works locally, fails on startup in prod with schema validation error.**
`ddl-auto: validate` found an entity/column mismatch: a Flyway migration wasn't applied or failed, or an entity changed without a migration. Check `flyway_schema_history`; add a new `V{n}__` migration — never edit an applied one (checksum mismatch).

**S9. A deploy causes a burst of 502/connection-reset errors.**
Pods killed while serving, or new pods getting traffic too early. ShopFlow's answer, all in place: `server.shutdown: graceful` + `timeout-per-shutdown-phase: 20s`, `preStop: sleep 5` so the endpoint is removed from Service/Ingress first, `terminationGracePeriodSeconds: 45` (> 5 + 20), readiness probe so new pods only get traffic when ready, rolling update `maxUnavailable: 0, maxSurge: 1`, and a PDB `minAvailable: 1` for node drains.

**S10. High CPU on one pod.**
`top -H` / `jcmd Thread.print` a few times → hot threads; frequent GC (heap too small → GC thrashing); infinite loops; regex backtracking; heavy JSON serialisation. Use async-profiler / JFR (`jcmd <pid> JFR.start`).

**S11. Intermittent `ObjectOptimisticLockingFailureException`.**
Two requests updated the same `@Version` entity (e.g. double cancel of an `Order`). Expected behaviour — map it to 409 and let the client retry, or retry the operation server-side if it's safe. (ShopFlow's handler doesn't map it yet, so today it surfaces as a 500 — an honest gap.)

**S12. Logs show retries hammering a recovering service.**
Retry storm: many clients retry at the same fixed intervals. Add jitter (randomised backoff), cap attempts, rely on the circuit breaker, and respect `Retry-After`. ShopFlow has exponential backoff but no jitter yet.

---

> Tip: Har answer ke end me "In my project I…" add karo — 1 line, specific file ya config key ke saath. Ye generic answer ko experienced answer bana deta hai.
