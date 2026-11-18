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

## Use it
```java
BloomFilter<String> seen = BloomFilter.create(1_000_000, 0.01);
seen.add(url);
if (!seen.mightContain(url)) { /* definitely new -> crawl */ }

CountMinSketch freq = CountMinSketch.create(0.001, 0.01);   // ~1% over-count, 99% confidence
freq.add(sourceIp, 1);
long hits = freq.estimate(sourceIp);                        // >= true count, good enough for "is this a flood?"
```

## Where it fits in shopflow
A Bloom filter in front of the product cache would turn "key definitely not in the catalogue" into a
zero-DB-cost answer (an upgrade to the `shopflow-cache` penetration defence). A Count-Min sketch over request
keys is how `shopflow-ratelimit` would find the *heavy hitters* worth limiting hardest, in fixed memory.
Redis's own `PFCOUNT`/`CMS` commands are these structures.

## Tests
`BloomFilterTest` (no false negatives; measured false-positive rate under target), `CountingBloomFilterTest`
(remove makes absent; double-add survives one remove; no false negatives), `CountMinSketchTest` (never
underestimates over a 5000-event stream; a heavy hitter estimated within the ε bound).
