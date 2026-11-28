# shopflow-balance — consistent hashing + load balancing

Two related "where should this go?" problems, with the standard techniques for each. Pure Java, fully
unit-tested, no infra.

> Plain library like `shopflow-outbox` — never boots.

---

## Part 1 — Consistent hashing ("which shard owns this key?")
Plain `hash(key) % N` is a trap: change N (add/remove a shard) and **almost every key remaps** — a sharded
cache cold-misses entirely, a sharded DB reshuffles everything. Consistent hashing fixes this so only
~1/N of keys move when N changes.

- **`HashRing`** — nodes and keys hash onto a 64-bit ring; a key is served by the first node clockwise.
  Add/remove a node → only the keys in that one arc move. **Virtual nodes** (each physical node placed at
  ~150 ring points) are essential: without them 3 nodes give 3 lopsided arcs and removing one dumps its
  whole arc on a single neighbour; with them, load is even and a removed node's keys spread across ALL others.
- **`RendezvousHash` (HRW)** — same minimal-movement property without a ring: for a key, score every node by
  `hash(node, key)` and pick the max. No vnode tuning, naturally even; routing is O(nodes) instead of
  O(log positions). Good for small/medium node counts.
- **`BoundedLoadHashRing`** — consistent hashing with a FAIRNESS cap (Google, 2016). Plain `HashRing` keeps
  movement minimal but a hot key range can still pile onto one node; this caps any node at `(1+ε)·average`
  by continuing clockwise to the next node with room when the first is full — bounded load, still ~1/N
  movement on change. Stateful (tracks each key's assignment, `assign`/`release`) — the model for routing
  live requests/connections, not just hashing to a shard. Smaller ε = tighter balance, more keys bumped off
  their first choice (HAProxy/Vimeo use this).
- **`MaglevHash`** — Google's L4-balancer hash (NSDI 2016; also Envoy). Precomputes a prime-size lookup table
  from one permutation per backend (`offset = h1 % M`, `skip = h2 % (M-1) + 1`, `perm[j] = (offset+j·skip) % M`;
  prime M ⇒ skip coprime ⇒ visits every slot). Backends take turns claiming their next free preferred slot
  until full → **near-perfectly even** load (±1 slot) and **O(1) lookup** (`table[hash(key) % M]`, one array
  read). Trade-off: removing a backend reshuffles ~1/N (a touch more than a ring's strict minimum), rebuilt
  on membership change — the right call for a stateless L4 balancer that wants even load + constant-time route.

```
remove a node:
   mod N:            ~100% of keys remap   ✗  (cache avalanche)
   consistent hash:    ~1/N of keys remap  ✓  (only the gone node's share)
```

## Part 2 — Load balancing ("which backend serves this request?")
| Balancer | Picks | Reacts to load? | Best when |
|---|---|---|---|
| **RoundRobin** | strict cycle | no | equal backends, equal requests |
| **WeightedRoundRobin** | cycle, weighted + *smoothed* | no | unequal capacity (a "3" gets 3× a "1"), spread not bursty |
| **LeastConnections** | fewest in-flight | yes | uneven request costs; needs a global in-flight view + release |
| **PowerOfTwoChoices** | lighter of 2 random samples | yes (in-flight count) | many LB instances where a global view is impossible |
| **PeakEwma** | lighter of 2 samples, scored by `rtt·(pending+1)` | yes (**latency + load**) | backends that degrade in *speed*, not just depth (GC pauses, slow node) |

**The P2C insight** (a favourite interview topic): sampling just TWO backends and taking the lighter one
drops the peak load from ~`O(log n / log log n)` above average (pure random) to ~`O(log log n)` — nearly
perfect balance — with only local information. One extra random pick buys almost all the benefit of true
least-connections without its coordination cost.

**PeakEwma** (Finagle / Linkerd / Envoy `peak_ewma`) keeps P2C's cheap sampling but changes the *score*.
Counting in-flight requests can't tell a healthy node from a slow one — two nodes with 3 connections each
look identical even if one is mid-GC-pause and 10× slower. PeakEwma scores each sampled node by
`ewmaRtt · (pending + 1)`, so **slow** is as repellent as **busy**. The RTT estimate is a *peak* EWMA:
a higher-than-estimate sample snaps the cost **up instantly** (flee a spike now) while a lower sample is
blended in slowly and the estimate decays on idle — quick to distrust, cautious to re-trust, exactly the
bias you want when steering away from a degrading backend. A fresh node starts at a default RTT so it isn't
flooded before it's proven itself.

```
two nodes, both 3 in-flight, one mid-GC-pause (RTT 10x):
   P2C (count):    a tie -> keeps feeding the slow one   ✗
   PeakEwma:       cost = rtt·(pending+1) -> avoids it    ✓
```

## Use it
```java
var ring = new HashRing<String>(150);
ring.add("cache-1"); ring.add("cache-2"); ring.add("cache-3");
String shard = ring.route(userId);              // same user -> same shard, stable across scaling

Balancer<String> rr = new RoundRobinBalancer<>(List.of("be-1", "be-2", "be-3"));
String backend = rr.next();

var p2c = new PowerOfTwoChoicesBalancer<>(List.of("be-1","be-2","be-3"), new Random());
String be = p2c.acquire();
try { /* serve */ } finally { p2c.release(be); }

// latency-aware: 10s decay, 1s default RTT for new backends, real nanoTime clock
var ewma = new PeakEwmaBalancer<>(
        List.of("be-1","be-2","be-3"),
        java.util.concurrent.TimeUnit.SECONDS.toNanos(10),
        java.util.concurrent.TimeUnit.SECONDS.toNanos(1),
        new Random(), System::nanoTime);
String node = ewma.acquire();
long t0 = System.nanoTime();
try { /* serve */ } finally { ewma.complete(node, System.nanoTime() - t0); }  // feed back the RTT
```

## Where it fits in shopflow
Consistent hashing is how you'd shard the Redis cache (`shopflow-cache`) or route a key to one of many
distributed-lock nodes (`shopflow-ratelimit`/`shopflow-lock`) so the same key always hits the same node.
The load balancers are what an API gateway in front of the services does; least-connections / P2C are the
upgrade over the round-robin a basic Service/kube-proxy gives you.

## Tests
`HashTest` (removal moves only the gone node's keys; even distribution with vnodes; rendezvous stability),
`BoundedLoadHashRingTest` (no node exceeds the `(1+ε)·avg` cap over 300 keys; assignment idempotent + stable;
release frees capacity; tighter ε still respects its cap; bad args / empty ring rejected),
`MaglevHashTest` (100k keys spread within ±10% of ideal across 5 backends; lookup stable per key; removing a
backend moves &lt;30% of keys — ~its 1/5 share, not mod-N's ~100%; non-prime / too-small size / empty rejected)
and `LoadBalancerTest` (round-robin order; weighted proportions; least-connections reacts to release;
**P2C keeps the peak near average over 1000 picks**; **PeakEwma snaps up on a spike but decays down slowly,
and steers ~75% of traffic away from a 10× slower backend**; bad args rejected).
