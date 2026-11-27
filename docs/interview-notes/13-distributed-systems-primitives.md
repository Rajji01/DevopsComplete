# Distributed-systems primitives (the `shopflow-*` technique libraries)

The repo ships a set of small, standalone, fully-tested Java libraries — each one *a technique an interviewer
asks about*, built so you can point at real code instead of reciting a definition. They're not wired into the
services (zero blast radius); they're a reference you can read, run the tests of, and defend. This file maps
each to the question it answers.

> Tip: har ek ka apna README hai (`shopflow/<module>/README.md`) with a diagram + the trade-off. Yahan sirf
> interview Q&A + kaunsa real problem solve karta hai. Jab bhi "how would you scale/coordinate X?" aaye, in
> primitives mein se ek naam lo aur file path do.

Format as always: **concept → where in ShopFlow → Q&A → common mistake.**

---

## 1. Mergeable state without coordination — CRDTs (`shopflow-crdt`)

**Concept.** Conflict-free replicated data types: every replica mutates its own copy with no lock and no
leader, even during a partition, and they converge because the `merge` is a **join** — commutative,
associative, idempotent. So gossip order, re-delivery and offline replicas don't change the result.

**Where in ShopFlow.** `shopflow/shopflow-crdt`: `GCounter` (grow-only, merge = per-slot max), `PNCounter`
(P−N, can decrement), `ORSet` (observed-remove, add-wins).

**Q: Why per-slot `max` on a G-Counter merge, not `sum`?** Because a slot only grows on its owning replica, so
`max` is "the latest that slot reached anywhere". `sum` would double-count the part a replica already saw on
a previous gossip — the merge must be **idempotent** (re-merging the same state changes nothing).

**Q: How does an OR-Set let a concurrent add win over a remove?** Each `add` carries a unique tag; `remove`
only tombstones the tags it has *observed*. A concurrent add on another replica has a new tag the remover
never saw, so it survives the merge — and a removed element can be re-added (the bug a naive 2P-Set can't fix).

**Q: Would you store a bank balance as a PN-Counter?** No — a CRDT counter can oversell / go temporarily
negative under concurrency. Money stays strongly consistent in `ledger-service` (DB + double-entry). CRDTs
are for *soft* state: carts, presence, like-counts, collaborative sets.

**Common mistake.** Picking a merge that works on the happy path but double-counts on replay, or using a CRDT
where you actually need linearizability (money, inventory oversell).

---

## 2. Ordering time across machines — logical clocks (`shopflow-clock`)

**Concept.** Wall-clocks drift and jump (NTP), so they can order two causally-related events wrong. Logical
clocks order by causality instead.

**Where in ShopFlow.** `shopflow/shopflow-clock`: `LamportClock`, `VectorClock`, `HybridLogicalClock`.

**Q: Lamport vs vector vs HLC — when each?**
- **Lamport** (one counter): gives *a* total order consistent with causality (`a→b ⇒ L(a)<L(b)`), good for a
  deterministic tie-break. Limit: `L(a)<L(b)` does NOT imply `a→b` — it **can't detect concurrency**.
- **Vector** (counter per node): detects causality exactly — `BEFORE`/`AFTER`/`EQUAL`/**`CONCURRENT`**. Use
  when you must know "conflict or stale duplicate?" Cost: O(nodes) state.
- **HLC** (physical + logical): a compact total order that stays within clock-skew of wall time AND is
  causal — what CockroachDB/YugabyteDB use for MVCC / last-writer-wins.

**Q: Why not just timestamp with `System.currentTimeMillis()`?** Skew/NTP-jump can make a later causal event
get an earlier stamp, so a last-writer-wins store picks the wrong winner. HLC keeps wall-clock meaning *and*
causal monotonicity.

**Common mistake.** Using a Lamport timestamp to decide "did these two events conflict?" — it can't; that's a
vector-clock question (`CONCURRENT`).

---

## 3. Answers over a stream in tiny space — sketches (`shopflow-sketch`, `shopflow-histogram`)

**Concept.** Trade a little accuracy for a lot of memory; the error's *direction/shape* is the design choice.

**Where in ShopFlow.** `shopflow/shopflow-sketch`: Bloom (membership, no false negatives), Counting-Bloom
(deletable), **Cuckoo** (membership *with delete*, two-bucket O(1) lookup), Count-Min (frequency, never
underestimates), **HyperLogLog** (distinct count, ±~1.04/√m, **mergeable** across shards).
`shopflow/shopflow-histogram`: **DDSketch** (quantiles p50/p99/p999 with a *relative*-error guarantee,
mergeable).

**Q: Count distinct users across 10 shards without shipping all the ids?** Each shard keeps a HyperLogLog;
merge them by element-wise max of registers → the union's distinct count, in a few KB, no id transfer.

**Q: Why DDSketch over a fixed-bucket histogram for latency?** Latency spans orders of magnitude and SLOs are
written as percentages ("p99 < 200 ms"). DDSketch's geometric buckets give the *same % accuracy* at 8 ms and
at 8 s; fixed-width buckets are too coarse for the fast path or waste resolution on the tail.

**Q: Cuckoo vs Counting-Bloom for a "seen recently, then expire" set?** Both delete, but the cuckoo filter
uses less space at the same false-positive rate and does two cache-local bucket reads per lookup.

**Common mistake.** Forgetting *which way* a sketch errs — Count-Min only over-counts (safe for "is this a
heavy hitter?"), Bloom only false-positives (safe as a pre-filter). Using one where the error direction is
wrong for the decision.

---

## 4. Which node owns this key? — consistent hashing & balancing (`shopflow-balance`)

**Concept.** `hash % N` reshuffles ~everything when N changes (cache avalanche). Consistent hashing moves
~1/N. Different variants trade movement vs load-evenness vs lookup cost vs statefulness.

**Where in ShopFlow.** `shopflow/shopflow-balance`: `HashRing` (vnodes), `RendezvousHash` (HRW),
**`BoundedLoadHashRing`** (fairness cap `(1+ε)·avg`), **`MaglevHash`** (even load + O(1) lookup table), plus
LB policies (round-robin, weighted, least-conn, P2C).

**Q: Four consistent-hash variants — why so many?** Different guarantees: ring = minimal movement but uneven
(needs vnodes), O(log n); rendezvous = minimal movement, naturally even, O(n); bounded-load = hard fairness
cap, stateful; Maglev = even + O(1) from an immutable table, slightly-above-minimal movement. A sharded cache
wants minimal movement; a stateless L4 balancer wants even load + O(1) — picking the wrong one is the mistake.

**Q: A hot key is overloading one cache node — fix without reshuffling everything?** Bounded-load consistent
hashing: cap each node at `(1+ε)·average`, overflow the hot key's requests clockwise to the next node with
room. Keeps ~1/N movement, adds a fairness bound.

**Common mistake.** Reaching for `hash % N` (avalanche on scale) or plain consistent hashing when the real
problem is *load skew* (needs bounded-load or Maglev), not movement.

---

## 5. How many requests may this key do? — rate limiting (`shopflow-ratelimit`)

**Concept.** Six algorithms, two backends (local / Redis-Lua), one config knob.

**Where in ShopFlow.** `shopflow/shopflow-ratelimit`: fixed/sliding-window (log & counter), token bucket,
leaky bucket, and **GCRA**. Wired into order-service's `OrderRateLimitFilter` (per-customer, Redis-shared).

**Q: What is GCRA and why does Redis-cell use it?** The token bucket computed from one timestamp (the
Theoretical Arrival Time) instead of a refilling token count. Same burst-to-capacity behaviour, but the state
is **one value** and "decide + update" is a single compare-and-set — ideal for a single-key *distributed*
limiter, and it yields an exact `Retry-After` for free.

**Q: Token bucket vs leaky bucket?** Token bucket lets you save up and burst to capacity (APIs); leaky bucket
forces a smooth output rate (protect a fragile downstream). Same `RateLimitRule`, different shape.

**Common mistake.** Per-pod limiters that silently allow `N × limit` across N pods — the whole reason the
order-service limiter can use the Redis backend for a global allowance.

---

## 6. Durability — write-ahead log (`shopflow-wal`)

**Concept.** Append the change to a log and `fsync` it *before* acknowledging; recover by replaying. The
pattern under databases, Kafka, and this repo's transactional outbox.

**Where in ShopFlow.** `shopflow/shopflow-wal`: `WriteAheadLog` — `[len][lsn][crc32][payload]` frames,
`FileChannel.force`, replay **stops at the first torn/CRC-failing frame** (the crash boundary), open
truncates the torn tail.

**Q: How does replay avoid half-applying the record that was being written at the crash?** Each frame has a
CRC over `lsn`+payload; replay reads the *valid prefix* and stops at the first frame that's truncated or whose
CRC fails — that point is where the crash happened. A short-read check alone would miss a *torn* write (right
length, wrong bytes); the CRC catches it.

**Q: How does the outbox relate to a WAL?** The outbox table **is** a WAL in a database: the event is written
in the same transaction as the business change (durable before anyone sees the result), then a relay replays
it to Kafka. Same shape as a DB redo log.

**Common mistake.** Trusting whatever bytes are on disk on recovery (feeding a half-written tail into replay)
instead of validating per-record and stopping at the first bad frame.

---

## 7. Self-tuning backpressure — adaptive concurrency (`shopflow-resilience`)

**Concept.** A fixed concurrency cap is a guess that's wrong twice (too low wastes a healthy dependency, too
high melts a sick one). AIMD finds it: Additive-Increase while healthy, Multiplicative-Decrease on stress —
TCP's congestion control for your thread pool.

**Where in ShopFlow.** `shopflow/shopflow-resilience`: `AimdConcurrencyLimiter` (alongside the fixed
`Bulkhead`, `CircuitBreaker`, `Retry`).

**Q: Why additive-up but multiplicative-down?** The cost of overshooting (cause the overload you're avoiding)
is far worse than under-probing, so you probe gently (+1) and retreat hard (×0.9) — same asymmetry as TCP.

**Q: How does it avoid inflating the limit to max while idle?** It only increases when the limit is actually
being *pressed* (inflight near the limit) at sample time — otherwise an under-used service would ratchet to
max and then admit a stampede the moment load arrived.

**Common mistake.** Treating adaptive concurrency as "a bigger fixed limit". The value is that it *tracks*
capacity and backs off on the first sign of latency/drops.

---

## The one-line map (for the rapid-fire round)

| "How would you…" | Primitive | Module |
|---|---|---|
| …let replicas edit offline and converge | CRDT (G/PN-Counter, OR-Set) | `shopflow-crdt` |
| …order events without trusting wall-clocks | Lamport / Vector / HLC | `shopflow-clock` |
| …count distinct across shards cheaply | HyperLogLog (mergeable) | `shopflow-sketch` |
| …track p99 latency in bounded memory | DDSketch (relative error) | `shopflow-histogram` |
| …shard without reshuffling / without hot nodes | consistent hashing / bounded-load / Maglev | `shopflow-balance` |
| …rate-limit globally across pods | GCRA / token bucket on Redis | `shopflow-ratelimit` |
| …survive a crash mid-write | write-ahead log (CRC, replay valid prefix) | `shopflow-wal` |
| …stop guessing a concurrency cap | AIMD adaptive limiter | `shopflow-resilience` |

Every row is real, tested code with a README — say "in my repo, `<module>`…" and give the path.
