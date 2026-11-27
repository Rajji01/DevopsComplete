# shopflow-sketch — probabilistic data structures

Trade a little accuracy for a LOT of memory. When exact membership/counts over a huge stream would need
gigabytes, these answer approximately in kilobytes — the trick is knowing which error is acceptable.

> Plain library like `shopflow-outbox` — never boots.

---

## Bloom filter — "have I probably seen this?"
k bits per key in a bit array. A lookup with any bit unset → **definitely not present**; all set → **probably
present**. **No false negatives, only false positives.** That asymmetry is the point: use it as a cheap gate
before an expensive check — if the filter says *no*, skip the DB/disk/network entirely.
- sizing: bits `m = -n·ln p / (ln2)²`, hashes `k = (m/n)·ln2`, for n items at false-positive rate p.
- examples: "is this URL already crawled?", LSM-tree/Cassandra skipping SSTables that can't hold a key, a
  cache admission gate.

## Counting Bloom filter — Bloom you can delete from
A plain Bloom can't remove (clearing a bit might clear one another item shares). Keep a small counter per
slot instead of a bit: add increments, remove decrements, present = all k non-zero. Costs a few bytes/slot.

## Count-Min sketch — "how many times have I seen this?"
A `depth × width` grid of counters; a key adds to one cell per row; the estimate is the **min** across its
rows. Collisions only ADD, so it **never underestimates**, and the min throws away the rows where this key
collided with a heavy one. Used for heavy-hitters / top-K, DDoS source detection, approximate frequencies in
query planners.
- sizing: `width = ⌈e/ε⌉` bounds the over-count to `ε·(total)`, `depth = ⌈ln(1/δ)⌉` makes it hold w.p. 1−δ.

```
why "min" works (Count-Min):
  key "x" hashes to row0→col5, row1→col2, row2→col9
  if a heavy key also lands on col5, row0 is inflated — but row1/row2 probably aren't,
  so min(rows) stays close to x's true count.
```

## HyperLogLog — "how many DISTINCT things have I seen?"
Count unique items (distinct visitors, cards, accounts, IPs) in a few KB instead of a `HashSet` that grows
with the cardinality. Hash each item; the first `precision` bits pick one of `m = 2^precision` registers, and
each register keeps the longest run of **leading zeros** seen in the rest (a run of k ≈ 2^k distinct items).
The harmonic mean of `2^register` across registers, bias-corrected, estimates the count. Error ≈ `1.04/√m`.
- re-adding the same item is a **no-op** (register keeps the MAX run) — so it's idempotent, unlike a counter.
- small sets use **linear counting** (`m·ln(m/V)`, V = empty registers) where the raw estimator is biased.
- **mergeable**: `a.mergeFrom(b)` = element-wise max → the distinct count of the UNION, with no shared set and
  no shipping of ids. That is *the* reason to use it across shards: each shard sketches its slice, merge to
  get the global unique count. Associative + commutative + idempotent.
- we hash to 64 bits, so the original paper's large-range correction (a 32-bit-hash artefact) is omitted.

```
HLL intuition: longest leading-zero run ≈ log2(distinct count)
  seeing "0001…" (3 leading zeros) in some register hints ~2^3 distinct items hit it;
  m registers + harmonic mean cut the variance to ~1.04/√m. precision 14 → 16 KB → ~0.8% error.
```

## Use it
```java
BloomFilter<String> seen = BloomFilter.create(1_000_000, 0.01);
seen.add(url);
if (!seen.mightContain(url)) { /* definitely new -> crawl */ }

CountMinSketch freq = CountMinSketch.create(0.001, 0.01);   // ~1% over-count, 99% confidence
freq.add(sourceIp, 1);
long hits = freq.estimate(sourceIp);                        // >= true count, good enough for "is this a flood?"

HyperLogLog uniques = HyperLogLog.create(14);               // 16 KB, ~0.8% error
uniques.add(customerId);
long distinct = uniques.cardinality();                      // distinct customers seen, in constant space
uniques.mergeFrom(otherShardsSketch);                       // union across shards, no id shipping
```

## Where it fits in shopflow
A Bloom filter in front of the product cache would turn "key definitely not in the catalogue" into a
zero-DB-cost answer (an upgrade to the `shopflow-cache` penetration defence). A Count-Min sketch over request
keys is how `shopflow-ratelimit` would find the *heavy hitters* worth limiting hardest, in fixed memory.
A HyperLogLog is how you'd answer "how many distinct customers / cards / IPs today?" per shard and roll it up
across pods without a central set — the fintech-flavoured version is distinct-counterparty or distinct-account
counts for monitoring and fraud signals. Redis's own `PFADD`/`PFCOUNT` (HLL), `BF` (Bloom) and `CMS` commands
are exactly these structures.

## Tests
`BloomFilterTest` (no false negatives; measured false-positive rate under target), `CountingBloomFilterTest`
(remove makes absent; double-add survives one remove; no false negatives), `CountMinSketchTest` (never
underestimates over a 5000-event stream; a heavy hitter estimated within the ε bound), `HyperLogLogTest`
(100k distinct estimated within the standard error; empty = 0; small-range linear counting exact; re-adding is
idempotent; merge of disjoint and of overlapping streams estimates the true union; bad precision / mismatched
merge rejected).
