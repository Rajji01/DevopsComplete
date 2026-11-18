# 36 · Consistent hashing + load balancing — NOTES

> Pair: [`comprehension.md`](comprehension.md). Real `shopflow-balance`. Theme: **key→shard** aur **request→server**, dono mein set badalne pe minimal disruption.

---

## KYA (one breath)
2 alag sawaal: **key kaunse shard** (consistent hashing) aur **request kaunse server** (load balancer). `hash%N` scaling pe sab remap (cache cold-miss→DB herd); consistent-hashing ~1/N move; P2C global-view bina near-optimal balance.

## EASY WAY
> **"`%N` = node badla, sab remap (cache poora thanda). Ring = sirf 1/N move. P2C = do random mein halka chuno, almost-perfect bina global count."**

## DATA DISTRIBUTION (key → shard)
| Method | Node change pe movement | Lookup | Note |
|--------|------------------------|--------|------|
| **`hash % N`** | **~sab keys** (disaster) | O(1) | scaling = cache cold-miss → DB herd |
| **Consistent hash (ring)** | **~1/N** (sirf arc) | O(log positions) | **vnodes ~150** = even + spread |
| **Rendezvous (HRW)** | ~1/N (sirf us node ki keys) | **O(nodes)** | no vnode tuning, even; small-medium |

```
 RING: nodes+keys ek 64-bit ring pe; key → next node CLOCKWISE
   add/remove node → sirf us ARC ki keys move (~1/N), baaki same
   bina vnodes: N arcs uneven + removed node ka pura load ek neighbour pe
   with ~150 vnodes: arcs interleave → even load, removed load SAB pe spread
 RENDEZVOUS: har node ke liye hash(node,key), HIGHEST jeette; us node ki keys hi move
```

## LOAD DISTRIBUTION (request → server)
| Balancer | Kaise | Load-aware? | Cost/catch |
|----------|-------|-------------|------------|
| **Round-robin** | rotate | ✖ | slow node bhi turn milta |
| **Weighted RR** | cap-3 node 3x; **smooth** (nginx: `A A B A`) | capacity | burst `AAAB` nahi, interleave |
| **Least-connections** | fewest in-flight | ✅ real load | **global view chahiye**; `release` bhoole → node drop |
| **Power-of-two (P2C)** | 2 random → halka | ✅ | **no global view**; `O(log log n)` max load |

## P2C MAGIC (yaad rakho)
```
 pure random:  max load ~ O(log n / log log n) above avg
 P2C (2 samples, pick lighter): max load ~ O(log log n)  ← almost perfect
 ek extra sample se exponential improvement — aur koi GLOBAL count nahi chahiye
 → many independent LBs pe scale karta (least-connections nahi kar sakta)
```

## KYU (key reasons)
- **`%N` disaster:** placement count pe depend; count badla → remainder badla → sab remap → **har key cold-miss → DB herd** (scale karte hi gir jaata).
- **1/N optimal:** new node ko kuch to lena hi hai; "ek node ka hissa" (~1/N) minimum movement.
- **vnodes:** hash positions evenly-spaced nahi → N arcs uneven; ~150 vnodes interleave → even + spread.
- **least-conn global-view problem:** many LBs, har ek apna hi count dekhe → collectively ek backend overload. P2C local 2-sample se bachta.

## TIES
- **Sharded cache scale** (topic 12/26): consistent-hashing warna scale-up pe stampede.
- **Sharding** (topic 30): kaunse DB/shard pe data.
- **Capacity diff:** weighted-RR ya weighted-P2C.

## UNDERSTANDING CHECK (khud bol)
1. `%N` 4→6 servers pe kya? → *~sab keys remap, cache cold-miss, DB herd.*
2. vnodes kyun? → *N arcs uneven + removed load ek node pe; ~150 vnodes even+spread.*
3. ring vs rendezvous? → *ring O(log) large-scale, vnode-tune; rendezvous O(nodes) small-medium, no-tune even.*
4. P2C 2 samples se kya milta? → *max load O(log log n), bina global view → many LB scale.*
5. least-connections ka bug `release` bhoolne pe? → *count badhta rehta, node permanently "busy" → never chosen.*

## INTERVIEW LINE
> *"Do sawaal: key→shard, request→server. `hash%N` scaling pe **sab keys remap** → sharded cache poora cold → DB herd (scale karte hi). **Consistent hashing** (ring, next-clockwise) **~1/N** move; **vnodes** (~150) even-balance + removed-load spread. **Rendezvous/HRW** wahi bina ring (O(nodes), small clusters). Load: round-robin (blind), weighted-smooth (nginx), least-connections (real-load par global-view + `release` chahiye), **P2C** (2 random → halka, `O(log log n)` max load, **no global view** → many LB scale). Placement hi decide karta scaling smooth ya catastrophic."*

---
**Grounded:** `shopflow-balance/hash/{HashRing,RendezvousHash,Hashing}.java` (ring+vnodes, HRW), `lb/{RoundRobin,WeightedRoundRobin,LeastConnections,PowerOfTwoChoices}Balancer.java`. Ties: [30](../30-replication-multi-instance-db/) (sharding), [12](../12-caching-cache-aside-failopen/) (stampede), [26](../26-scaling-100x-bottlenecks/) (load).
