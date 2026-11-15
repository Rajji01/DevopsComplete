# shopflow-stream — streaming algorithms (one pass, bounded memory)

When the stream is too big to store and you get ONE pass, these answer the common questions in fixed
memory. Pure Java, fully unit-tested.

> Plain library like `shopflow-outbox` — never boots.

---

## Heavy hitters — "what are the top-K most frequent?"
Two classic approaches, both with K-ish counters instead of one-per-distinct-key:
- **Space-Saving (`SpaceSavingTopK`)** — K counters; a newcomer evicts the current minimum and inherits
  `min+1` (and remembers that inherited `error`). A genuinely heavy key climbs fast; its true count is in
  `[count-error, count]`. Great for top-N dashboards (top products / IPs / search terms).
- **Misra-Gries (`MisraGries`)** — k-1 counters; a new untracked item when full DECREMENTS all counters
  (and drops zeros). Guarantees every item with frequency `> n/k` survives as a *candidate* (a superset —
  confirm with a second pass). The ancestor of Count-Min and Space-Saving.

```
Space-Saving eviction (capacity 3):  [a:9][b:4][c:1]  offer "d"
  -> evict min (c:1), d enters at 1+1=2, error=1:    [a:9][b:4][d:2(err1)]
  a one-off "d" can't dislodge the real heavies; a repeating "d" quickly climbs.
```

## Reservoir sampling — "a uniform random sample of a stream of unknown length"
`ReservoirSampler` (Vitter's Algorithm R): fill k slots, then item i replaces a random slot with
probability k/i. Invariant: after n items every one is in the sample with probability exactly **k/n** — one
pass, O(k) memory, no second visit. Used for log sampling, A/B bucketing, "give me 100 random rows" from a
cursor you can't count.

## Use it
```java
var top = new SpaceSavingTopK<String>(100);
requests.forEach(r -> top.offer(r.ip()));
top.top().stream().limit(10).forEach(e -> log(e.item() + " ~" + e.count()));  // top 10 talkers

var sample = new ReservoirSampler<LogLine>(1000, new Random());
logLines.forEach(sample::offer);
ship(sample.sample());                                                        // 1000 uniform lines
```

## Where it fits in shopflow
Space-Saving/Misra-Gries over request keys are how you'd find the heavy hitters worth rate-limiting hardest
(pairs with `shopflow-ratelimit` + the `shopflow-sketch` Count-Min). Reservoir sampling is the honest way to
sample k6/load-test traffic or trace spans at a fixed budget regardless of total volume.

## Tests
`SpaceSavingTopKTest` (heaviest hitters surface above one-off noise; the always-tracked key has zero error),
`MisraGriesTest` (every item with freq > n/k survives as a candidate), `ReservoirSamplerTest` (sample capped
at k; **each element equally likely over 10000 trials**).
