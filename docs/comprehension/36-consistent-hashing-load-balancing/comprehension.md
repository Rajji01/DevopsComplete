# 36 · Distributing data & load: consistent hashing + load balancing — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in the **real** `shopflow-balance` module: `hash/HashRing.java` (consistent hashing + virtual nodes), `hash/RendezvousHash.java` (HRW), `lb/{RoundRobin,WeightedRoundRobin,LeastConnections,PowerOfTwoChoices}Balancer.java`. Ties to [30 replication/topology](../30-replication-multi-instance-db/) (sharding), [12 caching](../12-caching-cache-aside-failopen/) (sharded cache), [26 scaling](../26-scaling-100x-bottlenecks/) (spreading load).
> **Theme:** do alag sawaal — **"key kaunse shard pe"** (data distribution) aur **"request kaunse server pe"** (load distribution) — aur dono mein scaling event ko **minimal disruption** se handle karna.

---

## Scenario (read cold)

> *A review of `shopflow-balance`: "two different distribution problems — placing keys on shards, and routing requests to servers. Explain the algorithms and when each wins." Read once, then answer.*

**Problem one: which shard holds a key?** The naive answer is `hash(key) % nodeCount`. It balances well — until `nodeCount` **changes**. Add or remove one node and the modulus shifts for **almost every key**, so a near-**total reshuffle** happens; for a sharded cache that means a scaling event **cold-misses nearly everything** at once (a thundering herd onto the DB).

**Consistent hashing** fixes it. Hash **nodes and keys onto the same 64-bit ring**; a key is served by the **first node clockwise** from it. Now adding/removing a node only moves the keys in **that one arc** — about **1/N** of them — the rest stay put. The catch: with only N physical nodes you get N arcs of **wildly uneven** size (poor balance), and removing one dumps its **whole arc on a single neighbour**. The fix is **virtual nodes**: place each physical node at **many** ring positions (~150 vnodes each), so the arcs **interleave** — load is even, and a removed node's keys **spread across all others**, not one. This is how sharded caches/DBs (Dynamo, Cassandra, memcached clients) survive scaling.

**Rendezvous (Highest Random Weight) hashing** gets the same "minimal movement" **without a ring**: for a key, hash `(node, key)` for **every** node and pick the **highest**. Remove that node and only **its** keys move (each re-picks the next-highest); everyone else's winner is unchanged. Vs the ring: **no vnodes to tune and naturally even**, but routing is **O(nodes)** per lookup instead of **O(log positions)** — great for **small-to-medium** node counts (shard/replica selection).

**Problem two: which server handles the next request?** Four balancers, increasing in smarts:
- **Round-robin** — rotate through backends. Simple, but **ignores real load** (a slow backend still gets its turn).
- **Weighted round-robin** — a backend of capacity 3 gets picked 3× as often as a 1. The **smooth** variant (nginx's) **interleaves** (`A A B A` for `{A:3, B:1}`) instead of bursting `AAAB`, so the heavy node isn't hammered in a run.
- **Least-connections** — send to the backend with the **fewest in-flight** requests; it **reacts to real load** (a slow backend accumulates connections and stops being picked). You **must `release`** when a request finishes or its count only grows. Cost: it needs an **accurate global view** of in-flight counts — easy in one process, **hard across many LB instances**.
- **Power-of-two-choices (P2C)** — pick **two** backends at random, send to the **less loaded** of the two. The famous result: this one extra sample cuts the **maximum** load from `~O(log n / log log n)` above average (pure random) down to `~O(log log n)` — **almost perfectly balanced** — **without a global view**. Each LB only compares **two local samples**, so it scales to **many** balancers where true least-connections can't.

The through-line: distribution is about **what happens when the set changes or is uneven**. `hash % N` and plain random both look fine until scale/skew — consistent hashing minimises **key movement**, P2C minimises **load imbalance**, each buying robustness with a small, clever twist (vnodes; one extra sample).

---

## Questions

### A — Literal
1. What exactly goes wrong with `hash(key) % nodeCount` when a node is added, and why is consistent hashing better?
2. What problem do **virtual nodes** solve, and what two things go wrong **without** them?
3. Power-of-two-choices samples two backends. What does that one extra sample buy over pure random, and what does it avoid needing?

### B — Inference
4. Consistent hashing moves "about 1/N of keys" when a node joins/leaves. Why is that specifically the right amount, and why does it matter most for a **sharded cache**?
5. Least-connections "reacts to real load" but P2C is often preferred at scale. What's the specific weakness of least-connections that P2C sidesteps, and how?

### C — Trade-off
6. Consistent hashing ring (with vnodes) vs rendezvous hashing. Compare on lookup cost, balance, and tuning — when would you pick each?
7. Round-robin vs least-connections. What does least-connections handle that round-robin can't, and what's its operational cost (and the bug if you forget `release`)?

### D — Transfer / extension
8. ShopFlow runs a **sharded Redis cache** across 4 nodes and wants to scale to 6 without cold-missing everything. Which algorithm, what do virtual nodes do during the scale-up, and roughly what fraction of keys move?
9. You have **many** load-balancer instances (an LB per pod/region), backends of **equal** capacity, and you want near-optimal balance without a shared state store. Which algorithm and why — and what changes if the backends have **different** capacities?

### E — Spot-the-flaw
10. A dev shards a cache with `hash(key) % serverCount` and is happy with the balance. Months later they add two cache servers during a traffic spike to add capacity; **immediately the database falls over**. Explain the exact mechanism, and the one-line design change that would have prevented it.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. `% nodeCount` ties every key's placement to the **count**; change the count and the remainder changes for **almost all** keys, so **nearly every key remaps** to a different node — a total reshuffle. Consistent hashing places nodes and keys on a **ring** and routes to the next node clockwise, so adding a node only reassigns the keys in **its arc** (~1/N); the other ~(N−1)/N keys keep their node.
2. Virtual nodes solve **uneven balance**. Without them: **(a)** N physical nodes make N arcs of **very uneven** size (hash positions aren't evenly spaced), so load is lopsided; and **(b)** removing a node dumps its **entire arc onto the single next node** clockwise (a hotspot/overload). With ~150 vnodes each, positions **interleave**, so arcs are even and a removed node's keys **spread across all** remaining nodes.
3. One extra random sample cuts the **maximum** load from `~O(log n / log log n)` above average (pure random) to `~O(log log n)` — exponentially better balance — and it avoids needing a **global least-connections view**: each balancer just compares **two local samples**, so it works with many independent LBs that can't share an accurate global count.
</details>

<details>
<summary>B — Inference</summary>

4. 1/N is the **minimum** you can move while still rebalancing to N+1 nodes — a new node must take **some** share, and taking exactly "one node's worth" (~1/N) is optimal; everything else staying put is what you want. It matters most for a **sharded cache** because a moved key is a **cold miss** — it must be re-fetched from the DB. Moving ~1/N keys means ~1/N cold misses (a survivable bump); `hash % N` moving ~**all** keys means ~**everything** cold-misses at once → a DB-crushing thundering herd exactly when you were trying to add capacity.
5. Least-connections needs an **accurate global view of in-flight counts**. With a **single** LB that's easy, but with **many** LB instances each sees only its own connections — no instance has the true global picture, so they can **collectively overload** a backend they each think is lightly loaded (herd behaviour / stale view). P2C sidesteps it by **not needing a global view at all**: sampling two at random and picking the lighter gives near-optimal balance from **local** information, so it scales to many independent balancers.
</details>

<details>
<summary>C — Trade-off</summary>

6. **Ring (vnodes):** lookup is **O(log positions)** (a `TreeMap`/binary search over ring points) — fast at large scale — but you must **tune vnode count** for balance and store many positions. **Rendezvous:** **naturally even, no vnodes to tune**, trivially correct, but lookup is **O(nodes)** (hash every node per key). **Pick the ring** for large node counts / high lookup rates (caches, DBs with many shards); **pick rendezvous** for **small-to-medium** node counts where O(nodes) is cheap and you want simplicity and perfect balance (shard/replica selection).
7. Least-connections handles **heterogeneous/variable request cost** — a backend that's slow (long requests, GC, a bad node) **accumulates in-flight connections** and is **automatically avoided**, which round-robin (blindly rotating) can't do (it keeps feeding the slow node). **Cost:** maintaining accurate in-flight counts (global view across LBs is hard). **Bug if you forget `release`:** a backend's count only ever **increases**, so it looks permanently "busy" and is **never chosen again** — you silently drop it from rotation.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **Consistent hashing (ring) with virtual nodes.** Scaling 4→6: the two new nodes are each placed at ~150 ring positions interleaved among the existing ones; they **take over the arcs** that now fall to them, so only the keys in **those arcs move** — roughly **1/3 of keys** move (the new nodes claim ~2/6 of the ring), and those are **spread evenly** off all four old nodes (not yanked from one). So ~2/3 of keys stay hot in place; only ~1/3 cold-miss and refill gradually — a survivable bump instead of a full cold cache. Virtual nodes are what keep the moved load **even** and the scale-up **smooth**.
9. **Power-of-two-choices.** Equal capacity + many independent LBs + no shared state = exactly P2C's sweet spot: near-optimal balance (`O(log log n)` max load) from **local** two-sample decisions, no global counter to share or get stale. If backends have **different capacities**, plain P2C (which assumes equal weight) under-uses the big ones — switch to **weighted** routing: either **weighted round-robin** (smooth), or a **weighted P2C** (sample proportional to capacity / compare load-to-capacity ratios), so a 3×-capacity node gets ~3× the share while keeping P2C's no-global-view benefit.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. `hash(key) % serverCount` makes placement depend on the **count**. Going from 4→6 servers changes the modulus, so **almost every key now maps to a different server** → the sharded cache **cold-misses for nearly all keys at once**. Every one of those misses falls through to the **database**, simultaneously — a massive **thundering herd** that overloads the DB and takes it down, **precisely during the traffic spike** you were scaling to handle. The one-line fix: shard with **consistent hashing (a hash ring with virtual nodes)** instead of `% N`, so adding 2 servers moves only ~**1/3** of keys (spread evenly) and ~2/3 stay hot — no mass cold-miss, no herd. (Related to topic 12's stampede and topic 26's hot-tier scaling: *how* you place keys decides whether scaling is smooth or catastrophic.)
</details>

---

## Tod ke dekho — Local (real module + tests)

```bash
# consistent hashing + rendezvous:
grep -n "clockwise\|1/N\|VIRTUAL NODES\|vnode" shopflow/shopflow-balance/src/main/java/com/shopflow/balance/hash/HashRing.java
grep -n "Highest Random Weight\|O(nodes)\|highest" shopflow/shopflow-balance/src/main/java/com/shopflow/balance/hash/RendezvousHash.java
# the four balancers:
grep -rn "Power of Two\|least-connections\|global view\|smooth\|weight" shopflow/shopflow-balance/src/main/java/com/shopflow/balance/lb/ | head

# run the tests (key-movement on add/remove ~1/N; P2C balance; weighted distribution):
cd shopflow && ./mvnw -q -pl shopflow-balance test
```

**Proved:** the ring moves ~1/N keys on a node change (vs `%N`'s full reshuffle), vnodes even it out, rendezvous gives the same minimal-movement without a ring, and P2C balances near-optimally from two local samples — all asserted by the tests.

## Tod ke dekho — Online
Pure library — no infra. On [`ONLINE-LABS.md`](../ONLINE-LABS.md) Codespaces run `./mvnw -pl shopflow-balance test`; add/remove nodes and count how many keys remap (ring vs `%N`) to *see* the difference.

---

## Interview concepts is passage se
- **`hash % N` reshuffle** — node change → near-total remap → sharded-cache cold-miss/herd.
- **Consistent hashing** — ring, next-clockwise, ~1/N movement; **virtual nodes** for even balance + spread.
- **Rendezvous / HRW** — minimal movement without a ring; O(nodes) lookup; small-medium clusters.
- **Round-robin / weighted (smooth nginx)** — simple / capacity-aware.
- **Least-connections** — reacts to real load; needs global view; `release` or it's dropped.
- **Power-of-two-choices** — two samples → `O(log log n)` max load, no global view, scales to many LBs.
- **Placement decides scaling smoothness** — ties to cache stampede (12) and hot-tier scaling (26).

**Interviewer's lens:** kya tu **data-distribution vs load-distribution** alag karta, `%N` ka scaling-disaster bol sakta, consistent-hashing + vnodes se bacha sakta, aur **P2C ka "two random choices" magic** (no global view, near-optimal) samajhta.

> Interview line: *"Do alag sawaal: **key kaunse shard pe** aur **request kaunse server pe**. Sharding mein `hash % N` scaling pe **sab keys remap** kar deta → sharded cache poora cold-miss → DB herd (scale karte waqt hi gir jaata). **Consistent hashing** (ring, next-clockwise) sirf **~1/N keys** move karta; **virtual nodes** (~150 each) balance even karte aur removed node ka load **sab pe** spread. **Rendezvous/HRW** wahi minimal-movement bina ring (O(nodes), small clusters). Load balancing: round-robin (load-blind), weighted (capacity, smooth nginx), least-connections (real load par global-view chahiye, `release` bhoole to node drop), aur **power-of-two-choices** — do random mein se halka chuno → `O(log log n)` max load, **bina global view**, isliye many-LB pe scale karta."*
