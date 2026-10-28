# 09 · Timeout, retry, circuit breaker — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `InventoryClient.java`, `order-service application.yml` (resilience4j), `OrderService.placeOrder`.

## Kya (one line)
Har remote call par **timeout** (ek call ko bound karo), **retry** (transient blip ride karo, backoff ke saath), aur **circuit breaker** (jab service sach mein down ho to fast-fail karke use recover hone do) — teeno alag kaam, saath lagte hain.

## Easy way (Hinglish analogy)
Dost ko call kar rahe ho:
- **Timeout** = 10 ring tak phone nahi utha to kaat do (hamesha wait mat karo).
- **Retry** = ho sakta network glitch tha, thodi der baad 2-3 baar aur try karo (har baar thoda zyada ruk ke — backoff).
- **Circuit breaker** = agar dost ka phone lagataar band aa raha (50% calls fail), to 10 min ke liye **dial karna hi band** kar do — baar-baar try karke na apna time barbaad, na uske tower pe load. 10 min baad ek-do trial call; lag gaya to normal, warna phir band.
- **Business reject** = dost ne uthaya aur bola "abhi busy hoon" — ye **fail nahi** hai, system theek chala; isko breaker mein mat gino.

## Kyu (ye problem solve karta hai)
Remote call fail ho sakta hai. Bina in patterns ke: ek hanging call thread ko hamesha ke liye block (pool exhaust); ya retry storm down service ko aur maar de; ya har request down dependency pe atak ke poora service hang. Teeno milke **containment** dete hain: order FAILED mark hota, readiness UP rehti, reconciler settle karta.
Sabse bada point: **timeout sabse pehle zaroori** — kyunki retry/breaker dono *completed* calls pe kaam karte; jo call kabhi return hi na kare usko sirf timeout bachata.

## Kaise (step by step)
1. **Timeouts** (`InventoryProperties`): connect 1s, read 2s.
2. **InventoryClient**: `@Retry(name="inventory")` + `@CircuitBreaker(name="inventory")` → `Retry(CircuitBreaker(call))`.
3. **Retry** config: max-attempts 3, wait 200ms, exp-backoff ×2, retry-exceptions = `ResourceAccessException`, `HttpServerErrorException` **only**.
4. **Breaker** config: COUNT_BASED, window 10, min-calls 5, failure-rate 50% → open, wait 10s, half-open 2 calls, `ignore-exceptions: InventoryRejectedException`.
5. **On open/exhaust**: `placeOrder` catches `CallNotPermittedException`/`RestClientException` → `order.fail()` → `FAILED`, readiness UP. Reconciler later → CANCELLED.
6. **Retry safe** kyunki inventory `reserve` idempotent on `orderRef`.

## Flow diagram (call path)
```
 placeOrder → InventoryClient.reserve
        │
   Retry( CircuitBreaker( HTTP call [connect 1s / read 2s] ) )
        │
   breaker CLOSED ──► call
        ├─ success ──────────────► CONFIRMED
        ├─ InventoryRejected(409/404) ─► REJECTED  (no retry, breaker IGNORES)
        └─ transient (timeout/5xx) ─► retry ×3 (200ms ×2 backoff)
                 │ still failing, last-10 ≥50% fail
                 ▼
         breaker OPEN (10s) ──► CallNotPermitted (fast) ──► order FAILED (readiness UP)
                 │ after 10s
              HALF-OPEN (2 trial) ──success──► CLOSED
                                  ──fail─────► OPEN
```

## Pattern responsibility diagram
```
 timeout   : bounds ONE call               (hanging call killer)   ← must-have, first
 retry     : rides a transient blip         (idempotent only!)      cap + backoff + jitter
 breaker   : stops hammering a dead service (fast-fail, let recover) ignore business errors
 bulkhead  : caps concurrent calls          (isolate the pool)      [not used here, related]
```

## Alternate approaches (kab kaunsa)
| Choice | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Timeout + retry + breaker** *(ShopFlow)* | resilience4j annotations | containment, recovery | config tuning | sync remote calls |
| **No timeout (retry+breaker only)** | — | simpler | **hanging call hangs service** | kabhi nahi |
| **Retry a non-idempotent call** | blind retry | — | double side-effect | kabhi nahi (pehle idempotent banao) |
| **Fallback value on open** | breaker fallback | graceful UX | stale/incorrect data risk | reads where stale ok |
| **Bulkhead** | limit concurrency | ek dep baaki ko na doobe | extra config | shared pools |

## Understanding check
1. Teen patterns ka alag-alag kaam ek line mein?
2. 409 ko breaker/retry se bahar kyu rakha?
3. Breaker retry ke andar kyu (ordering ka faida)?
4. Timeout hata do to hanging call pe kya hota, retry/breaker kyu nahi bachate?
5. Non-idempotent call ke liye kaunsa pattern badalna padega?

## Interview mein ye bolo
*"Har remote call timeout (1s/2s), retry (transient only, 3× exp-backoff), aur circuit breaker (10-call window, 50% fail → 10s fast-fail, half-open 2) se wrapped hai — `Retry(CircuitBreaker(call))`, to breaker retries ko short-circuit karta. Business 409/404 na retry hota na breaker-count (warna flash sale breaker khol de). Open hone pe order FAILED, readiness UP, reconciler settle. reserve retry safe kyunki orderRef idempotency key hai. Timeout sabse zaroori — hanging call ko retry/breaker nahi, sirf timeout bachata."*

> Related: [`../04-order-limbo-reconciler/`](../04-order-limbo-reconciler/) (FAILED → settle), [`../07-k8s-readiness-cascade/`](../07-k8s-readiness-cascade/) (readiness UP during outage).
</content>
