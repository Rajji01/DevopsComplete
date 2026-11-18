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
| **PowerOfTwoChoices** | lighter of 2 random samples | yes | many LB instances where a global view is impossible |

**The P2C insight** (a favourite interview topic): sampling just TWO backends and taking the lighter one
drops the peak load from ~`O(log n / log log n)` above average (pure random) to ~`O(log log n)` — nearly
perfect balance — with only local information. One extra random pick buys almost all the benefit of true
least-connections without its coordination cost.

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
```

## Where it fits in shopflow
Consistent hashing is how you'd shard the Redis cache (`shopflow-cache`) or route a key to one of many
distributed-lock nodes (`shopflow-ratelimit`/`shopflow-lock`) so the same key always hits the same node.
The load balancers are what an API gateway in front of the services does; least-connections / P2C are the
upgrade over the round-robin a basic Service/kube-proxy gives you.

## Tests
`HashTest` (removal moves only the gone node's keys; even distribution with vnodes; rendezvous stability)
and `LoadBalancerTest` (round-robin order; weighted proportions; least-connections reacts to release;
**P2C keeps the peak near average over 1000 picks**).
