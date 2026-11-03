# 16 · Virtual threads (Java 21) — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `*/application.yml` (`spring.threads.virtual.enabled: true`), `OrderService`/`InventoryClient` (blocking IO), HikariCP pool.

## Kya (one line)
Java 21 ke **virtual threads** JVM-managed halke threads hain jo blocking IO pe carrier (OS) thread se unmount ho jaate — to thread-per-request ka simple blocking code rehta hai par OS-thread ceiling hat jaata; bottleneck DB pool / downstream pe shift hota, mitta nahi.

## Easy way (Hinglish analogy)
Restaurant socho. **Platform threads** = mehenge waiters, sirf 200. Har customer ko ek waiter chipak jaata jab tak khana aaye — 200 customers ke baad baaki line mein, jabki waiter kuch kar nahi rahe (sirf kitchen ka wait). **Virtual threads** = waiter ko bola "jab customer ka order kitchen mein hai (IO wait), tu doosri table sambhal". Waiter free ho ke doosre serve karta; khana ready hote hi wapas. Ab 200 waiter 2000 customers ka wait sambhal lete. **Par kitchen (DB pool) abhi bhi utna hi bada hai** — waiter saste ho gaye, kitchen nahi.

## Kyu (ye problem solve karta hai)
IO-bound server (order-service: inventory HTTP + JDBC + Kafka) mein threads zyadatar **wait** karte hain, CPU idle rehta. Platform-thread pool (~200) blocked-on-IO threads se bhar jaata → baaki requests queue → latency badhti jabki CPU khali. Virtual threads is waste ko khatam karte: blocked thread unmount, carrier free. **Simple blocking code** (reactive ki complexity ke bina) par IO-concurrency scale.
Catch: virtual threads resource limit nahi badhate — real bottleneck (DB connections) wahin rehta; aur `synchronized`+IO se pinning benefit kha jaati.

## Kaise (step by step)
1. **Config:** `spring.threads.virtual.enabled: true` (Java 21). Spring har request ko virtual thread pe chalata.
2. **Unmount/mount:** virtual thread blocking IO (JDBC, `restClient.reserve`) pe JVM se **unmount**, carrier platform thread free → doosra virtual thread mount. IO complete → **remount**, resume.
3. **Pinning:** `synchronized` block / native call ke andar unmount **nahi** hota → carrier pinned. IO inside `synchronized` = benefit gaya → `ReentrantLock` use karo.
4. **Real limit:** DB kaam ke liye HikariCP connection (pool 10) chahiye. Million virtual threads → 10 connection pe wait. Pool chhota jaanbujh ke (`10/pod × ~10 pods < max_connections=200`).
5. **IO vs CPU:** IO-bound → virtual threads help (waiting sasta). CPU-bound → nahi (core chahiye, thread count se parallelism nahi badhta).

## Flow diagram (platform vs virtual)
```
 PLATFORM (thread-per-request, pool ~200):
   req1..req200 → 200 OS threads, sab IO pe BLOCKED (CPU idle)
   req201.. → QUEUE (no thread free)         ← ceiling = pool size

 VIRTUAL (Java 21, enabled):
   req1..req1000 → 1000 virtual threads
        each hits IO → UNMOUNT from carrier → park
        few carriers (≈cores) keep mounting runnable VTs
   → all 1000 "in flight", OS threads ≈ cores, no queue
        │
   real limit now: HikariCP pool (10) / inventory capacity
        (VTs wait cheaply for the pool; pool stays small)
```

## Decision diagram
```
 workload?                     lock needed?                  scaling goal?
   IO-bound  → virtual threads   synchronized + IO → PINNING   waiting concurrency → virtual threads
   CPU-bound → bounded pool=cores  → use ReentrantLock         computing parallelism → cores, not threads
```

## Alternate approaches (kab kaunsa)
| Approach | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Platform thread pool** | thread-per-request, fixed pool | simple, default pre-21 | IO-wait pe ceiling, CPU idle | low concurrency |
| **Virtual threads** *(ShopFlow)* | `threads.virtual.enabled` | simple blocking code + IO scale | pinning trap, bottleneck→DB pool | IO-bound request/response |
| **Reactive (WebFlux)** | Mono/Flux non-blocking | backpressure, fully non-blocking | callback complexity, hard debug | streaming/high-fanout, backpressure |
| **Bigger platform pool** | raise Tomcat threads | easy | OS-thread cost, still caps | band-aid |

## Understanding check
1. Platform model mein 800 requests queue par CPU idle — kyu, aur virtual threads kaise fix karte?
2. Virtual threads millions, par DB concurrency unlimited kyu nahi?
3. `synchronized` + IO virtual threads ke saath kyu bura, fix?
4. HikariCP 2000 kar do — kyu galat, asli bound kya?
5. CPU-bound endpoint virtual threads se scale hoga? Kyu/kyu nahi?

## Interview mein ye bolo
*"ShopFlow Java 21 pe `spring.threads.virtual.enabled:true` chalata — har request virtual thread pe, blocking IO (inventory HTTP, JDBC) pe JVM se unmount, carrier free, millions cheap. Thread-per-request ka simple blocking code rehta par OS-thread ceiling hat jaata. Do catch: `synchronized`+IO = pinning (ReentrantLock use karo), aur real limit ab HikariCP pool (10) / downstream — virtual threads waiting sasta karte, resource bada nahi. CPU-bound kaam inse scale nahi, sirf IO-bound. Reactive se farak: simple blocking code vs callbacks/backpressure."*

> Related: [`../09-resilience-breaker-retry/`](../09-resilience-breaker-retry/) (blocking inventory call), [`../03-idempotency-three-ways/`](../03-idempotency-three-ways/). Debug: [`../../debugging/D03-latency-spike-no-errors.md`](../../debugging/D03-latency-spike-no-errors.md) (pool saturation).
</content>
