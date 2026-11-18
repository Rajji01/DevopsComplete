# 32 · Distributed locks / fencing — NOTES

> Pair: [`comprehension.md`](comprehension.md). Grounded in real `shopflow-lock` (DistributedLock/LockToken/LockStore/RedLock). Continues 28 (DB locks) → cross-node locks. Theme: **lock lena aasaan, safe banana mushkil.**

---

## KYA (one breath)
Alag processes ke beech mutual exclusion (Redis+Lua). 3 safety: **TTL** (crashed holder self-free), **ownership token** (apna hi lock release), **fence token** (resource lower reject — stall/GC-pause safety). Money ke liye lock se behtar **idempotency**.

## EASY WAY
> **"TTL crash sambhalta, token galat-release sambhalta, par **stall (GC-pause)** ko sirf **fence token at the resource** sambhalta. Lock akela kabhi safe nahi."**

## 3 SAFETY PROPERTIES (+ real field)
| Property | Field | Rokta | Bina iske bug |
|----------|-------|-------|---------------|
| **TTL** | `expiresAtMillis` | dead holder forever-lock | crash → lock stuck, koi free na kare |
| **Ownership token** | `token` (UUID/acquire) | galat lock delete | A expire→B acquire→A ka slow `release` B ka delete |
| **Fence token** | `fenceToken` (monotonic) | **stalled** holder (GC>TTL) 2 holders | A pause→B le→A jaaga→dono likhte→corruption |

## THE GC-PAUSE SCENARIO (sabse tagda — yaad rakho)
```
 A acquire (fence=42) ──► GC PAUSE > TTL ......................... A frozen
                          lock EXPIRES
 B acquire (fence=43) ──► writes to resource (resource: highest=43)
 A wakes (thinks it holds!) ──► write with fence=42
 resource: 42 < 43 → REJECT A.   ✅ only fencing caught it.
 (TTL expired silently under A; token still "A's" — neither helped)
```
**Fence check RESOURCE pe hota, lock pe nahi.** Resource highest-seen rakhta, lower reject.

## WHY ATOMIC / LUA (token safe release)
```
 BAD:  if redis.get(key)==null: set(...)        <- 2 ops, TOCTOU, dono le lete
       ... del(key)                             <- kiska? B ka ho sakta
 GOOD: SET key token NX PX ttl                  <- atomic acquire (set-if-absent)
       release = Lua: if get(key)==token: del   <- atomic check-and-delete
 "Every LockStore method must be ATOMIC — that's the whole game." (Redis = Lua, not Java GET-DEL)
```

## REDLOCK (HA across N independent stores)
```
 N independent Redis masters (NOT replicas!)
 acquire on all → held ≥ quorum (N/2+1) ? YES → lock (fence = max, best-effort)
                                          NO  → release the minority (no partial lock)
 - 1 node down → lock still works (majority)
 - 2 clients can't both get majority (disjoint)
 HONEST caveat: GC/clock safety DISPUTED (Kleppmann vs antirez); per-node fence don't compose.
 Replicas use karo → failover 2nd client ko lock de de → majority meaningless.
```

## EFFICIENCY vs CORRECTNESS LOCKING (decide FIRST)
| Kind | Example (ShopFlow) | Lock fail = | Redlock-no-fence? |
|------|--------------------|-------------|-------------------|
| **Efficiency** | ek node cache rebuild / recon | duplicate work (harmless) | **OK** |
| **Correctness** | ek worker invoice pay / money move | double-pay (damage) | **NO** → fence **ya idempotency** |

## EXTRA PRIMITIVES
- **Jittered backoff** (`base/2 + random`): crowd lockstep retry na kare (store thunder na ho).
- **`extend` (watchdog)**: short TTL (fast crash-free) + healthy holder renew → long job bhi safe. Crash → renew ruke → short TTL free.

## KYU idempotency > lock (money)
- Lock fragile (GC/clock/Redlock-debate). Money = correctness.
- **Operation ko keyed idempotent banao** (invoice id / `processed_event`) → duplicate = no-op **lock ki parwah kiye bina**. Yeh stronger guarantee hai (03/25). Fence token = "idempotency for ops you can't otherwise key."

## UNDERSTANDING CHECK (khud bol)
1. Lock liya TTL ke saath — kya risk? → *work TTL se lamba/stall → expire-under-holder → 2 holders.*
2. `release` Lua kyun, Java GET-DEL kyun nahi? → *gap mein lock expire+re-acquire → doosre ka lock delete; atomic check-and-delete chahiye.*
3. GC-pause pe kaun bachata, TTL/token kyun nahi? → *fence token at resource (lower reject); A crash nahi hua stall hua, TTL/token nahi pakadte.*
4. Redlock replicas pe kyun galat? → *failover 2nd client ko de de, independent nahi, majority meaningless.*
5. Invoice-pay: lock ya idempotency? → *idempotency (invoice-keyed, no-op on dup, lock-independent) > lock; ya fence at resource.*

## INTERVIEW LINE
> *"`shopflow-lock`: distributed lock ko 3 cheez safe banati — **TTL** (crashed holder self-free), **ownership token** (apna hi release, atomic Lua check-and-delete), aur **fence token** (strictly-increasing, **resource** lower reject karta). Fence hi **GC-pause** wala do-holder case pakadta (Kleppmann) — TTL/token nahi, kyunki holder crash nahi stall hua. **Redlock** = N independent stores ka majority, minority release; par safety disputed. Efficiency locking (cache rebuild) Redlock-no-fence theek; **correctness** (double-pay) ke liye fence **ya idempotency** — aur money ke liye idempotency behtar, kyunki wo lock pe depend hi nahi karti."*

---
**Grounded:** `shopflow-lock/DistributedLock.java` (tryAcquire/extend/release, jittered backoff), `LockToken.java` (token + fenceToken + comments), `LockStore.java` (atomic acquire/release/extend, InMemory+Redis+Lua), `RedLock.java` (quorum `N/2+1`, release minority, honest Kleppmann-vs-antirez caveat). Tests: `DistributedLockTest`, `RedLockTest`. Idempotency-preferred ties to [03](../03-idempotency-three-ways/)/[25](../25-correctness-model-capstone/).
