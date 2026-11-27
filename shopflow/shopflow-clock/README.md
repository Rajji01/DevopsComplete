# shopflow-clock — logical clocks

Order events across machines **without** trusting wall-clocks (which drift, jump on NTP corrections, and are
never exactly in sync). Logical clocks order by *causality* — what actually influenced what — instead of by
a timestamp that might be wrong.

> Plain library like `shopflow-outbox` — never boots. Pure Java, fully unit-tested.

---

## Lamport clock — a single counter, total order consistent with causality
One integer. A local event does `++time`; receiving a message does `time = max(time, received) + 1`. That
guarantees: **if a→b (a happened-before b), then L(a) < L(b)**. Great for a deterministic tie-break ("lowest
timestamp, then lowest node id wins").
- honest limit: the converse is **false** — `L(a) < L(b)` does NOT mean a→b; they might be concurrent and
  just got different numbers. A Lamport clock gives an order but **cannot detect concurrency**.

```
Lamport: L(a) < L(b) is NECESSARY but not SUFFICIENT for a -> b.
  so it's perfect for "pick a winner deterministically",
  useless for "were these two concurrent?" -> use a vector clock.
```

## Vector clock — one counter per node, detects causality AND concurrency
A map `node -> count`. Each node bumps its OWN component on a local event; on receive it takes the
element-wise `max` of the sender's vector, then bumps its own. Comparing two vectors is exact:

| Relationship | Meaning |
|---|---|
| `BEFORE` | every component ≤ other, at least one `<` → this → other |
| `AFTER` | the mirror (other → this) |
| `EQUAL` | identical vectors |
| `CONCURRENT` | each has a component the other doesn't dominate → neither happened-before → a **conflict** |

`CONCURRENT` is the case a Lamport clock can't see, and the one that matters: it's exactly what signals "two
independent writes, you must resolve a conflict" — a Dynamo-style sibling, or an OR-Set add-vs-remove.

## Hybrid Logical Clock (HLC) — physical-ish time that's also causal
The clock CockroachDB / YugabyteDB use. State is a pair `(l, c)`: `l` tracks the largest physical time seen
(own or any peer's), `c` is a small tie-breaker. A timestamp stays within the clock skew of **wall-clock
time** (so it's human-meaningful, unlike a bare Lamport counter) AND is monotonic with causality
(`a→b ⇒ HLC(a) < HLC(c)`) even when machines' physical clocks are skewed — the property a last-writer-wins
store needs to pick the right winner.

| Event | Rule |
|---|---|
| local / send | `l = max(l, now)`; `c++` if physical time didn't advance `l`, else `c = 0` |
| receive `(l_m, c_m)` | `l = max(l, l_m, now)`; set `c` so the result strictly dominates both local state and the message |

Real systems pack `(l, c)` into one 64-bit int (≈48 bits ms + 16 bits counter); here it's a readable pair.
Use it over Lamport when you want timestamps that *mean* something in seconds, and over a vector clock when
you need a compact total order rather than full concurrency detection.

## Use it
```java
HybridLogicalClock hlc = new HybridLogicalClock();
var ts = hlc.now();          // stamp a write; close to wall-clock, safe for ordering/LWW
hlc.update(incomingTs);      // on receive: result dominates both, carrying causality across skew

LamportClock clock = new LamportClock();
long t = clock.sendTimestamp();         // stamp an outgoing message (a send is a local event)
clock.onReceive(incomingTimestamp);     // advance past both on receive

VectorClock vc = new VectorClock();
vc.increment("order-svc");              // a local event on this node
vc.mergeFrom(otherNodesClock);          // on receive: element-wise max...
vc.increment("order-svc");              // ...then bump your own
if (a.isConcurrentWith(b)) { /* conflict -> resolve (last-writer-wins, merge, sibling) */ }
```

## Where it fits in shopflow
Logical clocks are the primitive *underneath* the mergeable structures: a `shopflow-crdt` OR-Set's "did this
add happen-before that remove, or concurrently?" is a vector-clock question, and any eventually-consistent
replica deciding "is this a conflict or a stale duplicate?" is comparing vector clocks. shopflow's own saga
orders events through Kafka partition keys (the `orderRef`), not a vector clock — but the *reasoning* ("these
two consumers saw events in different orders; is that a real conflict?") is exactly this, and the honest
limits here are why the saga leans on idempotent, status-guarded consumers rather than trusting timestamps.

## Tests
`LamportClockTest` (local events monotonic; receive advances past both; a→b implies L(a) < L(b)),
`VectorClockTest` (identical = EQUAL; a causal chain is BEFORE/AFTER; independent events on different nodes
are CONCURRENT — the case Lamport can't detect; merge takes the element-wise max; unknown node reads as 0),
`HybridLogicalClockTest` (frozen physical time → counter climbs then resets when it advances; `l` never runs
behind the wall clock; a receive from a skewed-ahead peer still comes out strictly greater — causality across
skew; same-`l` receive beats the larger counter).
