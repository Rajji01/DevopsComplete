# 38 · One-pass streaming: top-K heavy hitters & uniform sampling in fixed memory — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in the **real** `shopflow-stream` module: `MisraGries.java` (frequent items > n/k, k-1 counters), `SpaceSavingTopK.java` (top-K, evict-min, error bound), `ReservoirSampler.java` (Vitter's Algorithm R). Sibling of [35 Bloom/Count-Min](../35-probabilistic-structures-bloom-countmin/) (Count-Min answers "how many for *this* key"; these find *which* keys are heavy / a fair sample). Feeds [34 rate-limiting](../34-rate-limiting-algorithms/)/[26 scaling](../26-scaling-100x-bottlenecks/) (hot-key/abuse) and [13 tracing](../13-tracing-logs-correlation/) (sampling).
> **Theme:** stream **ek baar** guzarta, length **pata nahi**, memory **fixed** — phir bhi "top-K kaun" aur "fair random sample" nikalna.

---

## Scenario (read cold)

> *A review of `shopflow-stream`: "a stream you can't store — find the heavy hitters and a fair sample in one pass, fixed memory. Explain the three algorithms." Read once, then answer.*

Some data arrives as an **unbounded stream** (every request, every click, every log line) — you can't store it all, you get **one pass**, and you often don't even know the **length** in advance. Three classic algorithms solve the two questions that come up most.

**(1) Misra-Gries — frequent items with `k-1` counters.** Finds every item whose true frequency exceeds **`n/k`** (n = stream length) using only **k-1** counters. The rule is elegantly simple: on each item, if it's already tracked, **count it**; else if a counter is **free**, start one; else **decrement every counter** and drop any that hit zero (a new item "cancels" one of each other). The survivors are **candidates** — a classic **over-approximation**: everything truly frequent **is** in the set, but the set may also hold a few that aren't, so a **confirming second pass** is needed for exact counts. It's the **ancestor** of Count-Min and Space-Saving.

**(2) Space-Saving — top-K heaviest, the practical one.** Keeps exactly **K counters**. When a new item arrives and all K slots are taken, **evict the current minimum** and give its slot to the newcomer with `count = min + 1` — so a genuinely frequent item **quickly climbs past the churn** of one-off items, while an `error` field remembers it may have **inherited up to `min` spurious counts**. So each item's **true count is in `[count - error, count]`** — bounded error, one pass, fixed memory. This is what powers "**top N products / IPs / search terms**." (Finding the min is O(K) in this clarity version; the real Stream-Summary structure makes it O(1).)

**(3) Reservoir sampling (Vitter's Algorithm R) — a fair random sample of a stream you can't size.** Keep a uniform random sample of size **k** from a stream whose **length you don't know** and can't hold. Fill the reservoir with the first k items; thereafter the **i-th** item replaces a random existing slot with probability **k/i**. The invariant that makes it work: after seeing n items, **every one of them is in the reservoir with probability exactly k/n** — a truly uniform sample, in **one pass, O(k) memory, no second visit**. Used for **log sampling**, A/B bucketing, and "pick N rows from a cursor."

The through-line: when you **can't store the stream**, you trade exactness for **fixed memory + one pass** — Misra-Gries/Space-Saving give you the **heavy hitters** with a **bounded over-count**, reservoir gives you a **provably uniform sample** with no length known. Each leans on a tiny invariant (decrement-cancels; evict-min+1; replace-with-prob-k/i) that holds no matter how the stream behaves.

---

## Questions

### A — Literal
1. What does Misra-Gries guarantee to find, with how many counters, and why is a second pass needed?
2. In Space-Saving, what happens when a new item arrives and all K counters are full, and what does the `error` field bound?
3. State the reservoir-sampling invariant and the replacement probability for the i-th item.

### B — Inference
4. Misra-Gries's "decrement **every** counter when full" looks strange. Explain why that one move can't drop a truly-frequent item below the `n/k` threshold (intuition, not proof).
5. Space-Saving gives `count = min + 1` to an evicted slot's newcomer rather than `1`. Why the `min + 1`, and why is that safe (never underestimates a true heavy hitter)?

### C — Trade-off
6. Space-Saving (heavy hitters) vs Count-Min (topic 35, frequency of a given key). They overlap — when do you reach for each, and how do they compose?
7. Reservoir sampling gives a uniform sample but **can't be weighted** naively and **forgets recency** (old items are as likely as new). When is plain reservoir wrong, and what would you switch to?

### D — Transfer / extension
8. ShopFlow wants a live "**top 20 most-requested products**" and to **rate-limit the abusive top-0.1% of API keys**, both over a firehose, in fixed memory. Which algorithm for each, and how does it feed the limiter (topic 34)?
9. You must **sample 1% of traces** (topic 13) for storage, from an unknown and bursty volume, without biasing toward any time window. Which algorithm, and why is a naive "keep every 100th" or "keep first N" wrong?

### E — Spot-the-flaw
10. A dev finds top products by keeping a `HashMap<product, count>` of **every** product ever seen, sorting at the end. It works in staging. In prod with **hundreds of millions of distinct products/keys** over a long window, the service **OOMs**. Explain why, and which algorithm fixes it with what trade-off.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. It finds **every item whose true frequency exceeds `n/k`** (all genuine heavy hitters), using **`k-1`** counters. A second pass is needed because the survivors are only **candidates** — the set is an **over-approximation** (contains all truly-frequent items **plus** possibly a few that aren't, with inflated-then-decremented counts), so to get **exact** counts / filter the false candidates you re-scan once counting only those items.
2. The algorithm **evicts the counter with the current minimum count** and reassigns that slot to the new item with **`count = min + 1`**. The `error` field records that this item may have **inherited up to `min` counts** that weren't really its own, so its **true count lies in `[count - error, count]`** — i.e. `error` bounds how much it could be over-counted.
3. **Invariant:** after n items, **each** seen item is in the reservoir with probability **exactly `k/n`** (uniform). The **i-th** arriving item (for i > k) replaces a uniformly-random existing slot with probability **`k/i`** (and is discarded with probability `1 − k/i`).
</details>

<details>
<summary>B — Inference</summary>

4. A decrement only happens when **all k-1 counters are full and a new, untracked item arrives** — i.e. you've just seen **k distinct items** (the newcomer + k-1 tracked), and the decrement removes one count from **each**, "cancelling" k items against each other. For a truly-frequent item (> n/k), its count is decremented **at most once per k items in the stream**, so over n items it loses **at most n/k**, which is **less than its true frequency** — so it **can't be fully cancelled out** and survives as a candidate. (The cancellation can only erase items that don't appear often enough to outrun the decrements.)
5. Giving `min + 1` assumes, pessimistically, that the new item **might** have occurred as often as the slot's previous (minimum) occupant — so it starts **at least as high as any count it's replacing**. That's what makes Space-Saving **never underestimate**: a real heavy hitter that keeps arriving will keep incrementing from an already-high floor and stay in the table, while the `error = min` records the possible inflation so you still know the **true count is ≥ `count - error`**. Starting at `1` instead could let a frequent item that arrives *after* the table fills get repeatedly evicted before it climbs — losing it.
</details>

<details>
<summary>C — Trade-off</summary>

6. **Space-Saving** answers "**which** items are the top-K?" (it *maintains the heavy set*). **Count-Min** answers "**how many times** did *this specific* key occur?" (you must already know the key to query it). Reach for **Space-Saving** when you need the **list** of hot items (top products/IPs) and don't have the keys in advance; reach for **Count-Min** when you have a key and want its approximate count (e.g. per-key rate decisions). They **compose**: Space-Saving finds the candidate heavy keys, Count-Min (or a confirming counter) gives each a tighter frequency — "find them, then size them."
7. Plain reservoir is **uniform over the whole stream**, so it's wrong when you need **recency** (a sample of "recent" behaviour — old events shouldn't count equally; use a **sliding-window / time-decayed** or exponentially-biased reservoir) or **weighted** sampling (items have different importance — use **A-Res / weighted reservoir** where replacement probability scales with weight). Use plain reservoir only when **every item over all time is equally interesting**; otherwise switch to the windowed/weighted variant.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **Top-20 products → Space-Saving** with K a few × 20 (e.g. K=100 counters) — it keeps the heavy set in fixed memory, one pass, and you read off the top 20 with their `[count-error, count]` bounds. **Abusive top-0.1% keys → Space-Saving (or Misra-Gries) to surface the candidate heavy keys**, optionally confirmed by a **Count-Min** count; once a key is identified as heavy, hand it to the **distributed limiter (topic 34)** keyed by that key (e.g. a stricter token bucket / block). So streaming heavy-hitter detection **feeds** the rate limiter: the sketch decides *who* is hot, the limiter decides *what to do*. Both stay in fixed memory regardless of key cardinality.
9. **Reservoir sampling.** "Keep every 100th" biases toward the stream's **rate pattern** (during a burst you sample a dense slice; it's a **systematic** sample, not uniform, and correlates with periodicity) and "keep first N" biases entirely toward the **start** (you'd never sample later traces once full). Reservoir guarantees **each trace has an equal k/n chance** regardless of when or how bursty it arrived — a **uniform** 1% across the whole unknown, bursty volume — which is exactly the unbiased sample you want for representative trace storage. (Adaptive/consistent trace sampling adds head-based decisions, but the fairness core is reservoir.)
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. The `HashMap<product, count>` stores **one entry per distinct key ever seen** — memory grows with **cardinality** (distinct products/keys), which over a long window with hundreds of millions of distinct values is **unbounded** → it exhausts the heap and **OOMs**. Staging has few distinct keys so the map is small (the trap hides at low cardinality). The fix: **Space-Saving (or Misra-Gries)** with a **fixed K counters** — memory is **O(K)** regardless of how many distinct keys stream through, and you still get the top-K with a bounded error. **Trade-off:** you give up **exact** counts for **all** items (you only track K, with `[count-error, count]` bounds on those) — but for "top N" that's exactly what you need, and it's the difference between **O(cardinality)** (OOM) and **O(K)** (constant) memory. (Same family as topic 35: accept bounded approximation to make the memory fixed.)
</details>

---

## Tod ke dekho — Local (real module + tests)

```bash
# the three algorithms and their invariants:
grep -n "n/k\|decrement\|over-approx\|candidate" shopflow/shopflow-stream/src/main/java/com/shopflow/stream/MisraGries.java
grep -n "evict\|min + 1\|error\|\\[count" shopflow/shopflow-stream/src/main/java/com/shopflow/stream/SpaceSavingTopK.java
grep -n "k/i\|k/n\|reservoir\|probability" shopflow/shopflow-stream/src/main/java/com/shopflow/stream/ReservoirSampler.java

# run the tests (heavy hitters found; error bound holds; reservoir uniformity):
cd shopflow && ./mvnw -q -pl shopflow-stream test
```

**Proved:** Misra-Gries keeps all `> n/k` items in `k-1` counters (over-approx), Space-Saving tracks top-K with `[count-error, count]` bounds in O(K) memory, and reservoir keeps a uniform sample in one pass — all in fixed memory regardless of stream length/cardinality.

## Tod ke dekho — Online
Pure library — no infra. On [`ONLINE-LABS.md`](../ONLINE-LABS.md) Codespaces run `./mvnw -pl shopflow-stream test`; feed a skewed stream and confirm the heavy hitters are caught with K ≪ distinct-keys.

---

## Interview concepts is passage se
- **Streaming constraint** — one pass, unknown length, fixed memory → trade exactness.
- **Misra-Gries** — `>n/k` with `k-1` counters; decrement-all cancellation; over-approx → confirm pass; ancestor of CMS/Space-Saving.
- **Space-Saving** — top-K, evict-min + `count=min+1`, `error` → true count `[count-error, count]`; never underestimates heavy.
- **Reservoir (Vitter R)** — uniform size-k sample, i-th replaces with prob `k/i`, each item prob `k/n`; one pass O(k).
- **O(K) not O(cardinality)** — the fix for the HashMap-OOM at high key cardinality.
- **Composition** — Space-Saving finds *which* heavy, Count-Min sizes *this* key (topic 35); heavy-hitter → limiter (34).
- **Weighted/windowed reservoir** — when recency/weight matters.

**Interviewer's lens:** kya tu **cardinality-bounded** memory samajhta (HashMap OOM vs O(K)), Space-Saving ka `min+1`/error-bound bol sakta, reservoir ka `k/n` uniformity (kyun "every 100th"/"first N" biased), aur Space-Saving-vs-Count-Min ka farak (which vs how-many).

> Interview line: *"Unbounded stream, ek pass, fixed memory — sab store nahi kar sakte. **Misra-Gries**: `k-1` counters se har item `> n/k` dhoondo (full pe sab decrement = cancellation; over-approx, confirm-pass chahiye). **Space-Saving**: top-K, naya aaye to **min evict + `count=min+1`**, `error` yaad rakhta (true count `[count-error, count]`) — heavy hitter kabhi underestimate nahi; 'top N products/IPs' isi se. **Reservoir (Vitter R)**: unknown-length stream se uniform size-k sample, i-th item prob `k/i` replace, har item prob `k/n` — one pass; 'every 100th'/'first N' biased hote. Key insight: memory **O(K)** rakho, `O(cardinality)` nahi (HashMap OOM). Space-Saving 'kaun heavy', Count-Min 'is key ka kitna' — compose karo, phir heavy key ko limiter (topic 34) de do."*
