# 32 · Distributed locks: TTL, ownership, and the fence token that actually makes it safe — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in the **real** `shopflow-lock` module: `DistributedLock.java` (tryAcquire/extend/release, jittered backoff), `LockToken.java` (token + **fenceToken**), `LockStore.java` (atomic acquire/release/extend; InMemory + Redis+Lua), `RedLock.java` (majority quorum across N independent stores). Continues [28 concurrency](../28-concurrency-control-locks-jpa-db/) (DB locks) and [30 replication](../30-replication-multi-instance-db/) (cross-service = no DB lock).
> **Theme:** ek lock lena aasaan hai; use **safe** banana (crashed holder, slow holder/GC-pause, double-ownership) mushkil. Fence token wahi karta.

---

## Scenario (read cold)

> *A review of ShopFlow's `shopflow-lock` module: "mutual exclusion across processes — show me why each field exists and where a naive lock breaks." Read once, then answer.*

Sometimes a job must run on **exactly one** node — a scheduled reconciliation, a cache rebuild, a "only one worker touches this account." A single-DB row lock (topic 28) can't help when the contenders are **different processes** coordinating through Redis. So ShopFlow has a `DistributedLock` over a pluggable `LockStore` (in-memory for tests, **Redis + Lua** in prod), and it's built around three non-obvious safety properties.

**(1) Always a TTL — so a dead holder doesn't lock forever.** Every `tryAcquire(key, ttl)` takes the lock **with an expiry**. If the holder crashes mid-work, the lock **frees itself** when the TTL passes — no human un-sticking a lock held by a process that no longer exists. The cost: the TTL is a bet on "my work finishes in time," which sets up problem (3).

**(2) An ownership token — so you only release YOUR lock.** Acquire returns a `LockToken` carrying a `token` = a UUID **unique to this acquisition**. `release`/`extend` go through the store **atomically** and check that token: "release only if `token` is the current owner." Without it, this classic bug: your lock **expires**, client B acquires it, then your slow `release()` runs and **deletes B's lock** — now two clients think they're exclusive. The token makes release a safe **check-and-delete**, which is why the store methods are **Lua scripts**, not GET-then-DEL from Java (that gap is the bug).

**(3) A fence token — the only thing that makes a lock safe against a *stalled* holder.** Here's the hard case: client A acquires the lock, then suffers a **GC pause (or VM freeze) longer than the TTL**. The lock expires, client B acquires it — now **A and B both believe they hold the lock**. A wakes up, oblivious, and writes to the protected resource. No lock protocol alone can prevent this (A didn't crash; it just paused). The fix is a **fence token**: a number that **strictly increases on every successful acquire** of the key. The protected **resource** records the **highest fence token it has seen** and **rejects any write carrying a lower one**. So A (old, lower fence) wakes up and its write is **refused** by the resource; B (new, higher fence) proceeds. This is Kleppmann's "How to do distributed locking" — **the lock alone is never enough; fencing at the resource is what's actually safe.**

For higher availability, `RedLock` takes the lock across **N independent** stores (think N separate Redis masters, **not** replicas): you hold it only if a **majority** (`N/2 + 1`) grant it, so one node failing doesn't take locking down, and two clients can't both win a majority. If you don't reach quorum, you **release the minority** you did grab (never sit on a partial lock). The module is **honest** about the famous Kleppmann-vs-antirez debate: Redlock's safety under GC pauses / clock jumps is **disputed**, and per-node fence tokens don't compose into one global monotonic token (it returns the max as best-effort). So the rule it states: **for correctness-critical locking, pair ANY lock with fencing at the resource; for "mostly avoid duplicate work" efficiency locking, Redlock is fine — know which you need.**

Two more touches: waiters spin with **jittered backoff** (`base/2 + random`) so a crowd doesn't retry in lockstep and thunder the store; and `extend` renews the TTL **while you still hold it** (the watchdog primitive) so long jobs can keep a short TTL (quick self-heal on crash) without the lock expiring under them.

---

## Questions

### A — Literal
1. Why is every lock taken with a TTL, and what does the TTL cost you?
2. What exactly does the `token` (UUID) field prevent, and why must `release` be atomic (a Lua script, not GET-then-DEL)?
3. What is a fence token, who checks it, and against which failure is it the *only* real defence?

### B — Inference
4. Walk the GC-pause scenario step by step and show precisely where the fence token stops the damage that TTL + ownership token **cannot**.
5. Redlock needs N **independent** stores, "not replicas." Why does using replicas of one Redis instead of independent masters defeat the point?

### C — Trade-off
6. Short TTL vs long TTL. What does each risk, and how does `extend` (the watchdog) let you get the good side of both?
7. The module distinguishes **efficiency** locking ("mostly avoid duplicate work") from **correctness** locking. Give a ShopFlow example of each, and why Redlock-without-fencing is acceptable for one but not the other.

### D — Transfer / extension
8. You must ensure "only one node runs the nightly reconciliation." Which primitive(s) do you use, what TTL strategy, and do you need a fence token here? Justify.
9. A payout service uses a distributed lock so "only one worker pays a given invoice." Under a GC pause two workers run. Explain what breaks **without** fencing, and exactly what the invoice-payment resource must do with the fence token to be safe. Then: why might ShopFlow prefer **idempotency** (topic 03/25) over locking here anyway?

### E — Spot-the-flaw
10. A dev implements the lock as: `if (redis.get(key) == null) { redis.set(key, "locked", ttl); doWork(); redis.del(key); }`. List three distinct bugs (one per safety property above), and the one-line fix for each.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. So a **crashed holder's lock frees itself** — a process that dies mid-work doesn't hold the lock forever (no human intervention). The **cost**: the TTL is a bet that your work finishes within it; if your work runs longer (or you stall), the lock can **expire while you still think you hold it** (problem 3). So TTL buys crash-recovery at the price of needing fencing (or `extend`) for safety.
2. The `token` is **unique to this acquisition**, so `release`/`extend` can verify **you are the current owner** — it prevents the "expire → B acquires → A's late `release` deletes B's lock" double-ownership bug. It must be **atomic** because a GET-then-DEL from Java has a gap: between checking "is it mine?" and deleting, the lock could expire and be re-acquired by B, and you'd delete B's. A **Lua script** does check-and-delete as one indivisible operation in Redis, closing that gap.
3. A fence token is a number that **strictly increases on every successful acquire** of the key. The **protected resource** (not the lock) checks it: it remembers the **highest token it has accepted** and **rejects writes with a lower one**. It is the only real defence against a **stalled (not crashed) holder** — e.g. a GC pause longer than the TTL, where two clients simultaneously believe they hold the lock. No lock protocol alone can prevent that; fencing at the resource can.
</details>

<details>
<summary>B — Inference</summary>

4. **(1)** A acquires (fence = 42), starts work. **(2)** A hits a GC pause **> TTL**. **(3)** The lock **expires**; B acquires (fence = 43) and writes to the resource — the resource records "highest seen = 43". **(4)** A **wakes up**, still believing it holds the lock (TTL + ownership token both think A is fine — A never crashed, never released), and tries to write **with fence = 42**. **(5)** The resource sees 42 < 43 and **rejects** A's write. TTL didn't help (it expired *silently* under A); the ownership token didn't help (A genuinely held token 42, it just went stale) — **only** the resource comparing fence tokens catches that A is operating on an outdated claim. That's the whole point: safety lives at the **resource**, not the lock.
5. Redlock's safety comes from needing a **majority of independent failure domains** — two clients can't each get `N/2+1` grants from **disjoint** nodes. If the "N stores" are **replicas of one master**, they share fate and share state: a failover can hand the lock to a second client (the replica hadn't yet replicated the first client's lock), and "majority" is meaningless because they're not independent — you've just got one logical store wearing N hats. Redlock assumes N **independent** masters precisely so a single node's failure/failover can't grant the lock twice.
</details>

<details>
<summary>C — Trade-off</summary>

6. **Short TTL:** fast self-heal if the holder crashes (lock frees quickly), but **risks expiring under a holder** whose work legitimately takes longer → two holders. **Long TTL:** safe from premature expiry, but a crashed holder **blocks everyone for a long time** before the lock frees. **`extend` (watchdog)** resolves it: keep the TTL **short** (quick crash recovery) and have the holder **renew** it periodically while it's still working and healthy — so a live long job keeps the lock, but a **crashed** job stops renewing and the short TTL frees it quickly. Best of both, as long as renewal + fencing cover the stall case.
7. **Efficiency locking:** "only one node rebuilds the product cache" — if the lock briefly fails and two nodes rebuild, you've just done **duplicate work**, no corruption; Redlock-without-fencing is fine (worst case = wasted CPU). **Correctness locking:** "only one worker pays this invoice / moves this money" — two holders = **double payment**, real damage; here a lock is **not** sufficient, you **must** fence at the resource (or make the operation idempotent). The module's rule: decide which kind you have **before** choosing, because Redlock's disputed safety only matters for the correctness kind.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **Reconciliation = efficiency locking** (if two nodes ran it, the recon is idempotent/harmless, just wasteful). Use `DistributedLock.tryAcquire(key, ttl)` (one-shot; if you don't get it, another node is running it — skip this tick). TTL = a bit longer than a typical run, and use **`extend`** as a watchdog if runs vary, so a long-but-healthy run keeps the lock while a crashed one frees fast. A **fence token is not strictly required** here because the worst case is duplicate work, not corruption — though if recon *writes* adjustments, making those writes idempotent is the real safety net. (Lock to avoid waste; idempotency for correctness.)
9. **Without fencing:** two workers (A paused, B took over) both call "pay invoice X" → **double payment**. The lock gave mutual exclusion only until A's stall broke it. **Fenced resource:** the payment resource must record, per invoice, the **highest fence token that paid it**, and **reject** any pay request carrying a **lower-or-equal** fence — so A's stale (lower) request is refused, B's proceeds once. **Why idempotency is better here:** money is correctness-critical and distributed locks are fragile (GC/clock/Redlock debate), so ShopFlow's whole design (topics 03/25) prefers making the *operation* idempotent — pay keyed by invoice id / `processed_event`, so a duplicate pay is a **no-op regardless of locks**. Idempotency doesn't depend on a lock holding; it's the stronger guarantee. (Fence tokens are "idempotency for operations you can't otherwise key.")
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. Three bugs:
   - **(no atomicity / TOCTOU)** `get(key)==null` then `set` is **two operations** — two clients can both see null and both set → both "hold" the lock. **Fix:** atomic `SET key token NX PX ttl` (set-if-absent in one op) — the store's single Lua/`NX` acquire.
   - **(no ownership token)** `del(key)` deletes **whoever** holds it — if your lock expired and B re-acquired, your `del` **removes B's lock**. **Fix:** store a **unique token** and release via an **atomic check-and-delete** ("del only if value == my token", a Lua script).
   - **(no fencing / no self-heal reasoning)** even fixed, a **GC pause > TTL** gives two holders and `doWork()` corrupts the resource. **Fix:** carry a **fence token** and have the **resource reject stale (lower) tokens** — and/or make `doWork` **idempotent**. (Bonus: no TTL-renew watchdog means a long job's lock expires mid-work; `extend` while healthy.)
</details>

---

## Tod ke dekho — Local (real module, real tests)

```bash
# the module + its tests (in-memory store; the concepts are all here):
find shopflow/shopflow-lock -name '*.java'
sed -n '1,30p' shopflow/shopflow-lock/src/main/java/com/shopflow/lock/LockToken.java   # token vs fenceToken comments
grep -n "quorum\|majority\|release the minority\|disputed" shopflow/shopflow-lock/src/main/java/com/shopflow/lock/RedLock.java

# run the lock tests (fencing + Redlock quorum are asserted):
cd shopflow && ./mvnw -q -pl shopflow-lock test    # DistributedLockTest, RedLockTest
grep -n "fence\|extend\|release\|quorum" shopflow-lock/src/test/java/com/shopflow/lock/*.java | head
```

**Proved:** the module encodes TTL (self-free), ownership token (safe release), **fence token** (stall safety), and Redlock majority — and the tests assert them. Fencing lives at the **resource**, which is why the lock returns the token for the caller to pass downstream.

## Tod ke dekho — Online
Redis chahiye (RedisLockStore + Lua) → [`ONLINE-LABS.md`](../ONLINE-LABS.md): Upstash/managed Redis, do clients se same key pe `tryAcquire` — ek jeetta, doosra khali; ek ko pause karke TTL expire karo aur fence-token reject dekho.

---

## Interview concepts is passage se
- **Distributed lock vs DB lock** — alag processes, Redis+Lua, atomic ops.
- **TTL** — crashed holder self-frees; cost = expiry-under-holder.
- **Ownership token** — release/extend only yours; atomic check-and-delete (Lua, not GET-DEL).
- **Fence token** — monotonic, resource rejects lower; **only** stall/GC-pause safety (Kleppmann).
- **Redlock** — majority of N *independent* stores; release minority; disputed safety (Kleppmann vs antirez).
- **Efficiency vs correctness locking** — duplicate-work OK vs double-pay not OK; correctness ⇒ fence or idempotency.
- **Jittered backoff + watchdog `extend`** — no thundering retries; short TTL + renew.
- **Idempotency > lock** for money — operation keyed, no-op on duplicate regardless of lock (03/25).

**Interviewer's lens:** kya tu jaanta ki **lock akela safe nahi** — TTL (crash) aur token (release) dono stall/GC-pause ko nahi pakadte, **fence token at the resource** hi pakadta; Redlock ki majority + honest caveat; aur ki money ke liye **idempotency** lock se behtar hai.

> Interview line: *"Distributed lock (Redis+Lua, `shopflow-lock`) teen cheezein chahiye: **TTL** (crashed holder self-free), **ownership token** (apna hi lock release karo — atomic check-and-delete, GET-DEL nahi), aur **fence token** — strictly-increasing number jo **resource** check karta aur lower reject karta. Fence token hi **GC-pause** wala case pakadta jahan A pause ke baad do holders ban jaate — TTL/token nahi pakadte (A crash nahi hua, stall hua); yeh Kleppmann ka point. **Redlock** = N independent stores ka majority (`N/2+1`), minority release karo; par safety disputed hai (Kleppmann vs antirez). Isliye: efficiency locking (duplicate cache-rebuild) Redlock-without-fence theek; **correctness** (double-pay) ke liye fence at resource **ya** — behtar — operation ko **idempotent** banao (invoice id se keyed), jo lock pe depend hi nahi karta."*
