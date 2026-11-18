# shopflow-skiplist — an ordered set balanced by coin flips

A skip list gives you balanced-tree performance — **O(log n)** search/insert/delete and **O(log n + k)**
range scans — with none of the rotation bookkeeping of a red-black/AVL tree. Pure Java, fully unit-tested.

> Plain library like `shopflow-outbox` — never boots.

---

## How it works
Start with a sorted linked list (O(n) search). Now add "express lanes": promote each node to the next
level with probability ½, so level 1 has ~n/2 nodes, level 2 ~n/4, and so on. A search rides the top lane
until the next node would overshoot, drops a level, repeats — skipping roughly half the remaining list each
time, hence O(log n).
```
L3  head ------------------------------> 50 ------------------> nil
L2  head --------> 20 ----------------> 50 --------> 80 ------> nil
L1  head --> 10 -> 20 -> 30 ----------> 50 -> 70 -> 80 -------> nil
L0  head -> 10->20->30->40->50->60->70->80->90 -> nil   (full sorted list)
```
**The point:** correctness never depends on the coin flips — the list is always sorted and every op is
exact; randomness only affects speed. No rotation cases, so it's far simpler to get right than a balanced
BST. That's why **Redis uses it for sorted sets (ZSET)** and LevelDB/RocksDB for in-memory memtables.

## Why not just a TreeMap / hash set?
- vs a hash set: a skip list keeps order, so it does **range queries** and "first/next" — a hash set can't.
- vs a balanced BST: same big-O, much simpler code, and lock-free concurrent variants are easier (how
  `ConcurrentSkipListMap` works). The trade is probabilistic (not worst-case) guarantees.

## Use it
```java
var s = new SkipList<Integer>();
s.add(50); s.add(10); s.add(80); s.add(30);
s.toList();        // [10, 30, 50, 80]  — always sorted
s.contains(30);    // true
s.range(20, 60);   // [30, 50]          — the thing a hash set can't do
s.first();         // 10
```

## Where it fits in shopflow
It's the data structure under a leaderboard / "recent orders by time" / ZSET-style feature — the same
engine Redis sorted sets use, which several shopflow ideas lean on (rate-limit sliding-window logs, "top
orders"). Having it here makes the ZSET-shaped answers in the interview notes concrete.

## Tests
`SkipListTest`: stays sorted regardless of insert order (100 shuffled inserts); `contains` tracks
adds/removes; it's a set (no duplicates); `range` returns the inclusive sorted subset. Deterministic via a
seeded `Random` — though correctness holds for any seed.
