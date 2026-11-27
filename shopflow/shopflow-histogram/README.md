# shopflow-histogram — streaming quantiles (DDSketch)

Answer "what's my p99 latency?" over an unbounded stream in a few KB, with a **relative**-error guarantee —
the accuracy model that matches how latency SLOs are actually written.

> Plain library like `shopflow-outbox` — never boots. Pure Java, fully unit-tested.

---

## DDSketch — relative-error quantiles
Masson, Rim & Lee (VLDB 2019). With `γ = (1+α)/(1−α)`, a positive value `x` goes into bucket
`i = ⌈log_γ x⌉`, covering `(γ^(i-1), γ^i]`. Every value in a bucket is within a factor of γ of its
representative value `2γ^i/(γ+1)` (the geometric midpoint), so a reported quantile is accurate to
`(γ−1)/(γ+1) = α`. The only state is a count per touched bucket — memory is the number of distinct buckets
(logarithmic in the value range), **not** the stream length.

```
relative vs absolute buckets — why it matters for latency:
  fixed 10ms buckets:  great at 8ms, useless at 8s (one giant bucket)
  DDSketch (α=1%):     p99 within 1% whether it's 8ms or 8s — buckets are a FIXED % apart
  SLOs are written "p99 < 200ms" (a percentage world), so relative error is the honest fit.
```

- **mergeable**: `a.mergeFrom(b)` adds per-bucket counts → the union's quantiles with the same α, so each
  shard sketches its own latencies and a global p99 is the merged sketch (no raw samples shipped).
- handles `0` with a dedicated zero counter; rejects negatives (it's for latencies / positive magnitudes).
- vs. an HdrHistogram-style fixed-bucket sketch: DDSketch gives the same % accuracy at every scale instead
  of spending resolution you guessed up front. (This teaching version is unbounded in bucket count; the
  paper adds a collapse step to cap memory for pathological ranges.)

## Use it
```java
DDSketch latency = DDSketch.create(0.01);   // every reported quantile within 1% of true
latency.add(responseMillis);
double p99 = latency.quantile(0.99);
double p999 = latency.quantile(0.999);
latency.mergeFrom(otherShardsSketch);        // global p99 across pods, no samples shipped
```

## Where it fits in shopflow
This is the structure behind the SLO work: the repo's RED/burn-rate rules read Prometheus histogram
buckets to get p99 latency and the SLI's "slow 2xx" count — DDSketch is the in-process, mergeable form of
exactly that, and the `le="1.0"` bucket reasoning in the SLO notes is the same relative-vs-absolute bucket
question. Where `shopflow-sketch` answers membership / frequency / cardinality in bounded space, this answers
*distribution* (quantiles) — the fourth question you ask of a stream, and the one latency SLOs live on.

## Tests
`DDSketchTest` (quantiles of 1..100000 within the α bound at p50/p90/p99/p999; same % accuracy at both ends
of a 1000× range; zero values handled; merge of two halves estimates the combined quantiles; reports its α
and count; rejects bad α / negative value / out-of-range quantile / empty-sketch query).
