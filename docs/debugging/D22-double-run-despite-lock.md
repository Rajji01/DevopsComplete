# D22 · The job ran twice even though it held a distributed lock

> Skill: distributed-lock safety debugging (the stalled-holder / fence-token gap). Related mechanism: [`../comprehension/32-distributed-locks-fencing/`](../comprehension/32-distributed-locks-fencing/), [`../comprehension/03-idempotency-three-ways/`](../comprehension/03-idempotency-three-ways/).

---

## Page (symptom)

A once-per-cycle job — say a payout/settlement worker that "only one node runs at a time" — occasionally **runs twice** and produces a **double effect** (two payouts, a duplicated settlement). The team is baffled: the job **takes a distributed lock** (`shopflow-lock` over Redis) before doing work, and the lock code is correct — acquire with TTL, release with an ownership-token check. Logs show **one node acquired the lock**, did the work, released it. Yet the effect happened **twice**, seconds apart, from **two different nodes**.

It's **rare** (a few times a week), correlates with **GC pauses / node stalls** (long GC logs around the incidents), and is **impossible to reproduce** by just running the job repeatedly.

**Before details: the lock is correctly implemented, only one holder at a time is visible, yet two nodes did the work. What situation lets two nodes BOTH believe they hold the same lock?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- `shopflow-lock` acquire uses a **TTL** (say 30s) and an **ownership token** (release checks it) — both correct.
- Incident timeline: node A acquires (fence token 42), begins work; a **GC pause of ~40s** on node A (> the 30s TTL) shows in its GC log; the lock **expires**; node B acquires (fence token 43), does the work; node A **resumes** and **also** does the work.
- The protected resource (the payout) does **not** check or record any fence token — it just performs the write.
- Both nodes' logs individually look fine ("acquired lock", "did work", "released").
- The lock's `LockToken` already carries a `fenceToken` field — but the **downstream resource ignores it**.
- The operation is **not** idempotent (no dedupe key on the payout).
</details>

---

## Your move

1. Two nodes both did the work. Name the exact sequence that let both believe they held the lock, and why neither the TTL nor the ownership token caught it.
2. Why is this invisible when you inspect each node's logs separately, and why only "a few times a week"?
3. The `LockToken` already has a `fenceToken`. What must the **resource** do with it to make this safe — and why must the check live at the resource, not in the lock?
4. There are two independent fixes (one at the lock/resource layer, one that sidesteps locks entirely). Give both and say which you'd prefer for a payout and why.
5. Someone proposes "just make the TTL 5 minutes so it never expires mid-job." Why does that not fix it and introduce a new problem?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**The sequence (Q1).** Node A acquires the lock (TTL 30s, fence 42) and starts work. A then suffers a **GC pause of ~40s — longer than the TTL**. During the pause, the lock **expires** (Redis frees it on TTL; A is frozen and can't renew or release). Node B acquires the now-free lock (fence 43) and does the work. Node A **un-pauses**, still believing it holds the lock — it **never crashed and never released**, so from A's view nothing is wrong — and does the work too. **Two holders, in sequence.** The **TTL didn't catch it** (it expired *silently under* A — expiry frees the lock but can't reach into A to stop it); the **ownership token didn't catch it** (A genuinely holds token 42; the token proves "A took a lock," not "A's lock is still valid *now*"). A stall, unlike a crash, is invisible to both mechanisms.

**Why log-invisible / rare (Q2).** Each node did a legitimate acquire→work→release in its **own** timeline, so **per-node logs look correct** — you only see the bug by **overlaying both nodes' timelines** against the lock's expiry and the GC pause. It's **rare** because it needs a specific coincidence: a GC pause (or STW/VM freeze) **longer than the TTL** happening **while** the job runs and another node is ready to take over — an uncommon alignment, hence "a few times a week" and never on demand.

**What the resource must do (Q3).** Fencing: the **protected resource** records the **highest fence token it has honored**, and **rejects any operation carrying a lower-or-equal token**. So when A resumes and tries to pay with **fence 42**, the resource sees it already honored **43** (from B) and **refuses** A's write. The check **must** live at the **resource**, because the resource is the only place that observes the *actual* writes in their *real* order — the lock can't tell that A stalled (A looks identical to a healthy holder from the lock's side). Safety is a property of **what reaches the resource**, so the resource must be the gate. (Kleppmann's point: a lock service hands out fence tokens; the resource enforces them. ShopFlow's `LockToken` already carries the token — the bug is the resource ignoring it.)

**Two fixes (Q4).**
- **(a) Fence at the resource:** pass `LockToken.fenceToken()` to the payout and have the payout store/compare the highest honored token per key, rejecting stale ones. Fixes it for *any* lock holder.
- **(b) Make the operation idempotent (sidestep locks):** key the payout by a **business id** (invoice/settlement id) with a unique constraint / `processed_event` dedupe, so a second payout for the same id is a **no-op regardless of locks or stalls** (topics 03/25). 
**Prefer (b) for a payout** — money is correctness-critical and distributed locks are fragile (GC, clock skew, the Redlock debate); idempotency makes the *operation* safe without depending on any lock holding, which is the stronger guarantee. (Use the lock then only for **efficiency** — avoiding duplicate work — not for correctness.)

**Why longer TTL is wrong (Q5).** A 5-minute TTL makes the expiry **less likely** but not impossible (a long STW/VM-freeze or a job that overruns still crosses it), so it **doesn't fix the class of bug** — it just makes it rarer and harder to catch. And it introduces a new problem: if a holder **crashes**, the lock is now stuck for **5 minutes** before anyone else can run — you traded a rare double-run for a routine long stall on every crash. The right answer is a **short** TTL (fast crash recovery) **+ watchdog `extend`** for healthy long jobs **+ fencing/idempotency** for the stall case — not a big TTL.

**Root cause:** a distributed lock was used as if the lock alone guaranteed single execution, but a **GC pause longer than the TTL** produced two simultaneous holders; the protected resource **ignored the fence token** (and the operation wasn't idempotent), so both runs took effect.

**Confirm the fix:** after fencing the resource (or making the payout idempotent), inject/replay the stall scenario (hold a token, let it expire, have a second holder proceed, then replay the first) → the stale write is **rejected** (or deduped); production double-runs stop even though GC pauses still happen.
</details>

---

## Reproduce / test (apne haath se)
```bash
# the module encodes exactly this: TTL, ownership token, AND a fence token the resource must honor.
sed -n '1,30p' shopflow/shopflow-lock/src/main/java/com/shopflow/lock/LockToken.java   # the fenceToken comment IS this drill
cd shopflow && ./mvnw -q -pl shopflow-lock test    # DistributedLockTest / RedLockTest assert fencing + quorum

# the mechanism, by hand (two "clients" on one Redis key, simulate a stall):
#  client A: acquire(key, ttl=3s) -> fence 1; then SLEEP 4s (the "GC pause")
#  client B: after ~3s, acquire(key, ttl=3s) -> fence 2; write to resource (resource records highest=2)
#  client A: wakes, writes with fence 1 -> resource must REJECT (1 < 2)
# without the resource check, both writes land = the double-run bug.
```
**Proved:** `LockToken.fenceToken` exists precisely so the resource can reject a stale holder; the bug is a resource that ignores it. The fix is fencing at the resource and/or an idempotent operation.

## The pattern

- **A lock alone never guarantees single execution.** A **stall** (GC pause/VM freeze) longer than the TTL creates two holders that both think they're valid — TTL and ownership token can't see a stall (only a crash/release).
- **Fencing lives at the resource.** The resource records the highest fence token and rejects lower ones; the lock just mints monotonic tokens.
- **Per-node logs hide it.** Overlay timelines (acquire/expire + GC pauses) to see two holders; it's rare because it needs a stall > TTL during contention.
- **For correctness (money), prefer idempotency over locking.** Key the operation; a duplicate is a no-op regardless of locks. Use locks for *efficiency*, not correctness.
- **A bigger TTL is not a fix** — it only hides the race and makes crash recovery slow. Short TTL + watchdog `extend` + fence/idempotency.

> Grounded in: `shopflow-lock` (`LockToken.fenceToken`, `DistributedLock`, `RedLock` honest caveat), comprehension 32, and idempotency (03/25). The payout resource ignoring the fence token is a reasoned scenario illustrating the module's own documented hazard.
