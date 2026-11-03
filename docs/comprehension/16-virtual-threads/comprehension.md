# 16 · Virtual threads: a cheap thread per request — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `*/application.yml` (`spring.threads.virtual.enabled: true`), Java 21, `OrderService`/`InventoryClient` (blocking IO), HikariCP pool.

---

## Scenario (read cold)

> *Excerpt from ShopFlow's concurrency notes. Read once, then answer.*

Every checkout request to order-service does **blocking IO**: a JDBC call to Postgres, a synchronous HTTP call to inventory-service (`reserve`, with 1s/2s timeouts), and writes to the outbox. Classic Spring MVC handles each request on **one platform (OS) thread** for the request's whole lifetime — thread-per-request. Platform threads are **expensive** (~1MB stack each, scheduled by the OS), so the server pools a few hundred (Tomcat default ~200). Under a flash sale, if 1,000 requests each block for ~200ms waiting on inventory, only ~200 can be in flight; the other 800 **queue** for a thread, and latency climbs even though the CPU is idle (everyone's just *waiting* on IO).

ShopFlow runs on **Java 21** with `spring.threads.virtual.enabled: true`. Now each request runs on a **virtual thread** — a lightweight thread managed by the **JVM**, not the OS. Virtual threads are cheap (a few hundred bytes, grow on demand) and you can have **millions**. When a virtual thread hits blocking IO, the JVM **unmounts** it from its carrier (platform) thread and parks it; the carrier is freed to run another virtual thread; when the IO completes, the virtual thread is remounted and continues. So 1,000 requests all blocked on inventory no longer need 1,000 OS threads — a handful of carriers serve them all. The **thread-per-request** model stays (simple, debuggable, blocking code), but the **thread is nearly free**.

Two caveats the notes stress. First, **pinning**: inside a `synchronized` block (or a native/foreign call), a virtual thread **cannot unmount** — it stays pinned to its carrier for the duration, so if you block on IO *inside* `synchronized`, you lose the benefit (use `ReentrantLock` instead). Second, **virtual threads don't remove real limits** — they move the bottleneck. order-service's blocking work still needs a **database connection** (HikariCP pool size 10) and inventory still has finite capacity. A million virtual threads all wanting a DB connection just means a million threads **waiting on a pool of 10**. Virtual threads make *waiting* cheap; they don't make the *resource* bigger.

The design point: virtual threads let you keep **simple blocking code** (no reactive/async complexity) while scaling to many concurrent **IO-bound** requests — the shape of almost everything order-service does.

---

## Questions

### A — Literal
1. In the classic model, what is a request's relationship to an OS thread, and why does that cap concurrency under IO wait?
2. When a virtual thread hits blocking IO, what does the JVM do with it and its carrier thread?
3. Name the two caveats the notes stress about virtual threads.

### B — Inference
4. 800 requests are queued waiting for a platform thread while CPU sits near-idle. Why does that happen, and how do virtual threads fix *that specific* waste?
5. Virtual threads are "millions, nearly free". Why does order-service still not get unlimited effective concurrency for its **database** work?

### C — Trade-off
6. Virtual threads vs going fully **reactive** (WebFlux/Mono/Flux) for IO scaling: what does the virtual-thread approach keep that reactive gives up, and when would reactive still win?
7. Why is blocking on IO **inside a `synchronized` block** especially bad with virtual threads, and what's the one-line fix?

### D — Transfer / extension
8. A teammate says "we have virtual threads now, let's raise the HikariCP pool to 2,000 so every request gets a connection." Why is that the wrong conclusion, and what actually bounds safe DB concurrency?
9. order-service is mostly **IO-bound**; a hypothetical new endpoint does heavy **CPU** work (image resizing) per request. Do virtual threads help *that* endpoint scale? Why or why not?

### E — Spot-the-flaw
10. To make a hot-path cache thread-safe, a dev wraps a slow `restClient` call and a DB read inside `synchronized(this)`. Load-tested on a laptop it's fine; in production under the flash sale, throughput collapses to roughly the old ~200-thread ceiling despite virtual threads being on. What happened?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. One request is bound to **one platform (OS) thread for its entire lifetime** (thread-per-request). Because OS threads are expensive, the server pools only a few hundred; when more requests are **blocked on IO** than there are threads, the rest **queue**, so concurrency is capped by the thread pool size, not by CPU.
2. The JVM **unmounts** the virtual thread from its carrier (platform) thread and **parks** it; the carrier is freed to run another virtual thread. When the IO completes, the virtual thread is **remounted** onto a carrier and resumes.
3. **Pinning** (inside `synchronized`/native calls a virtual thread can't unmount) and that **virtual threads don't remove real resource limits** — they move the bottleneck to the DB pool / downstream capacity.
</details>

<details>
<summary>B — Inference</summary>

4. The 800 are queued because all ~200 platform threads are **held by requests that are merely waiting on IO** (inventory/DB), doing no CPU work — the threads are blocked, not busy, so CPU is idle while the pool is exhausted. Virtual threads fix exactly this: a virtual thread waiting on IO is **unmounted**, so it doesn't hold a carrier; the few carriers stay busy mounting *runnable* virtual threads, and all 1,000 requests can be "in flight" (parked on IO) at once. The waste was threads blocked on IO; virtual threads make blocked threads nearly free.
5. Because the DB work needs a **connection from HikariCP (pool of 10)**, and that pool is a hard, finite resource. A million virtual threads can all *try* to do DB work, but only 10 can hold a connection at a time; the rest **wait for the pool**. Virtual threads make the *waiting* cheap (no OS-thread cost), but they don't enlarge the pool — the DB connection count is still the real ceiling on concurrent DB work.
</details>

<details>
<summary>C — Trade-off</summary>

6. Virtual threads **keep simple, blocking, top-to-bottom code**: ordinary `try/catch`, readable stack traces, step-through debugging, `ThreadLocal`, straightforward blocking calls — while still scaling IO-bound concurrency. Reactive (WebFlux) gives that up for a callback/operator programming model (`Mono`/`Flux`), which is harder to write, read, and debug (stack traces are fragmented). **Reactive still wins** when you need fine-grained **backpressure** across streaming pipelines, or a fully non-blocking stack end to end (e.g. very high-fanout streaming, or a non-blocking driver ecosystem you're already in). For ShopFlow's request/response blocking IO, virtual threads get ~the same scaling with far less complexity.
7. Inside `synchronized`, a virtual thread **cannot unmount** — it **pins** its carrier platform thread for the whole block. If you then block on IO inside that block, the carrier is **stuck waiting** (not freed to run other virtual threads), so you've reintroduced the platform-thread ceiling for every thread that passes through that lock. One-line fix: use a **`ReentrantLock`** instead of `synchronized` (virtual threads can unmount while holding a `ReentrantLock`).
</details>

<details>
<summary>D — Transfer / extension</summary>

8. Raising HikariCP to 2,000 doesn't help and likely **hurts**: the real limit isn't the app's willingness to open connections, it's **Postgres's own `max_connections`** and its ability to run that many queries concurrently. 2,000 app connections would **overwhelm Postgres** (memory per connection, context switching) — you'd move the bottleneck from "waiting for a pool slot" to "the database falls over". Safe DB concurrency is bounded by what **Postgres** can handle (ShopFlow sizes it: ~10 per pod × up to ~10 pods = ~100 < `max_connections=200`). Virtual threads let many requests *wait* cheaply for a *correctly sized* pool; the pool stays small on purpose.
9. **No** (not for scaling). Virtual threads help **IO-bound** work — their trick is unmounting during *blocking waits*. CPU-bound work isn't waiting; it's **using** a core the whole time, so it still needs a real core to make progress. A million virtual threads doing image resizing just contend for the same handful of CPUs; throughput is bounded by cores, not thread count. For CPU-bound work you size a **bounded pool to the core count** (and virtual threads can even hurt by over-subscribing). Virtual threads scale *concurrency of waiting*, not *parallelism of computing*.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. The `synchronized(this)` block **pins** the virtual thread to its carrier, and it contains a **slow blocking `restClient` call + a DB read**. So every request passing through that lock holds a carrier platform thread for the whole slow IO (it can't unmount while pinned), and because it's `synchronized(this)` on a shared instance, requests also **serialize** on the monitor. Under load, carriers get stuck on pinned, IO-blocked virtual threads → you're back to a platform-thread ceiling (~the number of carriers, which defaults to the core count or a small pool), exactly the ~200-ish wall virtual threads were supposed to remove. It passed on a laptop because low concurrency never exhausted the carriers. Fixes: don't do blocking IO inside a lock at all; if you must lock, use `ReentrantLock` (no pinning); and don't serialize independent requests on a shared monitor.
</details>

---

## Tod ke dekho — Local (docker compose)

```bash
make up && TOKEN=$(make -s token)
# confirm virtual threads are on (config) and observe thread behaviour under load:
grep -rn "threads:" shopflow/order-service/src/main/resources/application.yml   # spring.threads.virtual.enabled: true

# hammer the order endpoint concurrently; with virtual threads, many requests block on inventory at once
# without exhausting a ~200 platform-thread pool. (k6 or a parallel curl burst.)
docker run --rm -i --network host -e TOKEN=$TOKEN grafana/k6 run - <<'JS'
import http from 'k6/http';
export const options = { vus: 500, duration: '20s' };
export default function () {
  http.post('http://localhost:8081/api/v1/orders',
    JSON.stringify({ sku: 'PIXEL-9', quantity: 1 }),
    { headers: { 'Content-Type':'application/json', Authorization:`Bearer ${__ENV.TOKEN}`,
                 'Idempotency-Key': `${__VU}-${__ITER}` } });
}
JS
# watch: order-service stays responsive at 500 concurrent VUs even though each blocks on inventory+DB;
# the real limiter you'll hit is the HikariCP pool (10) / inventory capacity, NOT an OS-thread ceiling.
docker compose -f shopflow/docker-compose.yml exec postgres psql -U orders -d orders \
  -c "select count(*) from pg_stat_activity where datname='orders';"   # DB connections stay ~pool size, not ~500
```

**Proved:** 500 concurrent requests, each blocking on IO, served without a platform-thread wall; the DB connection count stays near the pool size (the real limit), not near the request count.

## Tod ke dekho — Online
[`ONLINE-LABS.md`](../ONLINE-LABS.md) **section A (Codespaces)** — order-service + inventory + postgres chahiye.

---

## Interview concepts is passage se
- **Thread-per-request vs thread pool ceiling** — blocked-on-IO threads cap concurrency while CPU idles.
- **Virtual threads (Project Loom, Java 21)** — JVM-managed, unmount on blocking IO, millions cheap.
- **`spring.threads.virtual.enabled: true`** — keep blocking code, scale IO-bound concurrency.
- **Pinning** (`synchronized`/native) — virtual thread can't unmount → use `ReentrantLock`.
- **Bottleneck moves, not vanishes** — DB pool (HikariCP) / downstream capacity is the real limit.
- **IO-bound vs CPU-bound** — virtual threads help waiting, not computing.
- **vs Reactive (WebFlux)** — simple blocking code vs callbacks/backpressure.

**Interviewer's lens:** "virtual threads se unlimited concurrency?" ka sahi jawab — **nahi, bottleneck DB pool pe shift hota**. Aur pinning (`synchronized` + IO) ka trap. Ye Java-21 round ka signature hai.

> Interview line: *"ShopFlow Java 21 pe `spring.threads.virtual.enabled:true` chalata hai — har request ek virtual thread pe, jo blocking IO (inventory HTTP, JDBC) pe JVM se unmount ho jaata, carrier free, millions threads cheap. Thread-per-request ka simple blocking code rehta, par OS-thread ceiling hat jaata. Do catch: `synchronized`+IO se pinning (ReentrantLock use karo), aur real limit ab HikariCP pool (10) / downstream capacity hai — virtual threads waiting sasta karte, resource bada nahi. CPU-bound kaam inse scale nahi hota, sirf IO-bound."*
</content>
