# 38 · Streaming top-K & sampling — NOTES

> Pair: [`comprehension.md`](comprehension.md). Real `shopflow-stream`. Sibling of [35](../35-probabilistic-structures-bloom-countmin/) (Count-Min = "is key ka kitna"; ye = "kaun heavy" + fair sample).

---

## KYA (one breath)
Unbounded stream, **ek pass**, length pata nahi, **fixed memory**. **Misra-Gries** (`>n/k`, k-1 counters), **Space-Saving** (top-K, evict-min+1, error-bound), **Reservoir** (uniform size-k sample). Memory **O(K)** rakho, O(cardinality) nahi.

## EASY WAY
> **"Sab store nahi kar sakte → K counters se top-K (evict min, `count=min+1`), ya k-slot reservoir se fair sample (i-th prob k/i). HashMap-of-everything = OOM at high cardinality."**

## 3 ALGORITHMS
| Algo | Sawaal | Memory | Invariant | Use |
|------|--------|--------|-----------|-----|
| **Misra-Gries** | items `> n/k`? | k-1 counters | full→**decrement all** (cancel); over-approx | frequent-items (confirm pass) |
| **Space-Saving** | top-K heavy | K counters | evict-min, `count=min+1`, `error` | top-N products/IPs/terms |
| **Reservoir (Vitter R)** | uniform sample size k | O(k) | i-th replace prob `k/i`; each item `k/n` | log/trace sampling, A/B |

## SPACE-SAVING (yaad rakho)
```
 K counters full, naya item X aaya:
   evict MIN-count slot → X ko do, count = min + 1, error = min
   true count(X) ∈ [count - error, count]   (never underestimate heavy)
 frequent item churn se jaldi upar chadh jaata; one-off neeche gir jaate
 min O(K) yahan (real Stream-Summary = O(1))
```

## RESERVOIR (yaad rakho)
```
 pehle k items → reservoir bhar do
 i-th item (i>k): prob k/i pe random slot replace, warna discard
 after n items: har item reservoir mein prob EXACTLY k/n  (uniform)
 one pass, O(k), length pata nahi chahiye
 "every 100th" = systematic/biased; "first N" = start-biased → reservoir fair
```

## MISRA-GRIES (cancellation intuition)
- Decrement tabhi jab **k-1 counters full + naya untracked** = abhi k distinct items dekhe → har counter -1 (k items cancel).
- Truly-frequent (>n/k) item ka count **har k items pe max 1 baar** girta → n items pe max n/k loss < true freq → **survive**.

## O(K) vs O(cardinality) (the OOM trap)
```
 HashMap<key,count> of EVERYTHING → memory grows with DISTINCT keys (cardinality)
   100s of millions distinct over long window → OOM (staging mein chhupa, low cardinality)
 Space-Saving/Misra-Gries → O(K) fixed, chahe kitne bhi distinct keys
   trade: exact-for-all chhodo, top-K with [count-error,count] lo
```

## COMPOSE / TIES
- **Space-Saving (kaun heavy) + Count-Min (is key ka kitna)** (topic 35): "find them, then size them."
- **Heavy-hitter → limiter** (topic 34/26): sketch decide *who* hot, limiter decide *kya karna*.
- **Reservoir → trace sampling** (topic 13): unbiased 1% over bursty unknown volume.
- **Weighted/windowed reservoir**: recency/weight matter karein to.

## UNDERSTANDING CHECK (khud bol)
1. Misra-Gries kya guarantee, kitne counters? → *har item `>n/k`, k-1 counters; over-approx → confirm pass.*
2. Space-Saving full pe naya item? → *min evict, `count=min+1`, error=min; true ∈ [count-error,count].*
3. Reservoir i-th item replace prob? → *k/i; har item final prob k/n (uniform).*
4. HashMap-of-all OOM kyun, fix? → *memory O(cardinality); Space-Saving O(K).*
5. "every 100th" sampling galat kyun? → *systematic/rate-correlated bias; reservoir uniform k/n.*

## INTERVIEW LINE
> *"Unbounded stream, ek pass, fixed memory. **Misra-Gries**: k-1 counters se `>n/k` items (full→sab decrement cancel; over-approx). **Space-Saving**: top-K, naya→min evict+`count=min+1`, `error` se true ∈ `[count-error,count]` (heavy underestimate nahi) — top-N products/IPs. **Reservoir (Vitter R)**: uniform size-k sample unknown-length se, i-th prob `k/i`, each item `k/n` — one pass; 'every 100th'/'first N' biased. Insight: **O(K)** memory, O(cardinality) nahi (HashMap OOM). Space-Saving 'kaun heavy', Count-Min 'kitna' — compose, phir heavy key limiter (34) ko."*

---
**Grounded:** `shopflow-stream/{MisraGries,SpaceSavingTopK,ReservoirSampler}.java` (+ invariants in Javadoc). Ties: [35](../35-probabilistic-structures-bloom-countmin/) (Count-Min), [34](../34-rate-limiting-algorithms/)/[26](../26-scaling-100x-bottlenecks/) (heavy-hitter→limiter), [13](../13-tracing-logs-correlation/) (sampling).
