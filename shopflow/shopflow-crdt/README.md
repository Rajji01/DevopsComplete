# shopflow-crdt — conflict-free replicated data types

Let every replica read and write its OWN copy with no lock and no coordination, even during a network
partition, and still have them all converge to the same value once they gossip. The trick is that the
`merge` is a **join**: commutative, associative and idempotent — so it doesn't matter what order replicas
exchange state in, how many times the same update is re-delivered, or who was offline.

> Plain library like `shopflow-outbox` — never boots. Pure Java, fully unit-tested.

These are **state-based** CRDTs (CvRDTs): a replica ships its whole state and the receiver merges it.

---

## G-Counter — grow-only counter
Each replica owns one slot and only ever adds to its OWN slot; the value is the sum of all slots. Merge =
per-slot **max**. Because a slot only grows on one replica, max is "the latest that slot has reached
anywhere", so replayed or out-of-order gossip never double-counts.
- use: a replicated view/like count, a quota, "how many times did any node do X".
- limit: can't decrement, and keeps one entry per replica.

```
why per-slot MAX (not sum) on merge:
  A: {a:5}  --gossip-->  B learns {a:5}
  A increments -> {a:8};  B merges again -> max(5,8)=8, NOT 5+8.
  summing would double-count the part B already saw. max is idempotent.
```

## PN-Counter — counter that can decrement
Two G-Counters: P (increments) and N (decrements); value = `P − N`. A decrement is "an increment on the N
side", so each half stays grow-only and the max-merge is still valid. Merge = merge both halves.
- use: a soft, partition-tolerant stock/balance count across regions.
- honest limit: it can go temporarily negative / oversell under concurrency — fine for a *soft* count, NOT a
  strongly-consistent ledger. Money that must never oversell belongs in `ledger-service` (DB + double-entry),
  not a CRDT.

## OR-Set — observed-remove set (add-wins)
A set several replicas edit offline. Every `add` tags the element with a unique id; `remove` tombstones only
the tags it has **observed**; an element is present iff it has an add-tag that isn't tombstoned. So a
concurrent `add` (a new tag the remover never saw) **survives** a concurrent `remove` — *add-wins* — and an
element can be re-added after removal (the bug a naive add-set/remove-set "2P-Set" can never fix). Merge =
union of add-tags ∪ union of tombstones.
- use: a shopping cart or label set synced across devices; collaborative editing; a presence set.
- limit: tombstones accumulate (real OR-Sets compact them with version vectors; this teaching version keeps
  them).

## Use it
```java
GCounter views = new GCounter();
views.increment("pod-eu");           // each replica adds to its own slot
views.mergeFrom(otherReplica);       // converge: per-slot max
long total = views.value();

PNCounter stock = new PNCounter();
stock.decrement("pod-eu", 3);        // sold 3 here
stock.mergeFrom(otherReplica);       // P and N halves merge independently
long remaining = stock.value();      // may be negative under concurrency — soft count only

ORSet<String> cart = new ORSet<>();
cart.add("sku-1");
cart.mergeFrom(phoneReplica);        // add-wins union; re-adds survive a concurrent remove
boolean inCart = cart.contains("sku-1");
```

## Where it fits in shopflow
CRDTs are the "mergeable state" idea taken past counting: the same property that lets `shopflow-sketch`'s
HyperLogLog merge distinct-counts across shards lets these merge counters and sets across replicas without a
leader. The deliberate boundary: shopflow's *money* is strongly consistent (`ledger-service`, DB + outbox +
double-entry), because a CRDT balance can oversell. CRDTs are the right tool one layer out — carts, presence,
soft counters, collaborative sets — where availability-under-partition beats strict consistency. Redis's
`CRDT`/active-active (and Riak, Automerge, Yjs) are these structures in production.

## Tests
`GCounterTest` (independent increments sum after merge; merge commutative + idempotent; per-slot max so
replayed gossip doesn't double-count; decrement rejected), `PNCounterTest` (inc/dec net out; can go negative;
two replicas converge across inc+dec; idempotent merge), `ORSetTest` (add→remove absent; re-add after remove
returns it; concurrent add wins over remove; merge converges in either direction; idempotent merge).
