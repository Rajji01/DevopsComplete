# 35 · Probabilistic structures: Bloom & Count-Min — cheap approximate answers at scale — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in the **real** `shopflow-sketch` module: `BloomFilter.java` (no false negatives, sizing formulas), `CountingBloomFilter.java` (deletable, counters), `CountMinSketch.java` (frequency estimate, never underestimates), `Hashes.java`. Ties to [12 caching](../12-caching-cache-aside-failopen/) (Bloom as a cache/DB gate), [34 rate-limiting](../34-rate-limiting-algorithms/) & [26 scaling](../26-scaling-100x-bottlenecks/) (Count-Min for heavy-hitters / hot keys).
> **Theme:** bade stream/set pe **thodi galti ke badle bahut memory bachana** — aur galti ka **direction** (false-positive vs false-negative, over vs under) soch-samajh ke chunna.

---

## Scenario (read cold)

> *A review of `shopflow-sketch`: "these answer set-membership and frequency questions in a fraction of the memory — explain the guarantees and where you'd use each." Read once, then answer.*

Some questions are asked billions of times over a huge stream — "have I seen this key before?", "how many times?" — where storing every key exactly would cost gigabytes. Probabilistic structures answer them in **fixed, tiny space** by accepting a **bounded, one-directional error**. The direction of the error is the whole design.

**(1) Bloom filter — "have I *probably* seen this key?"** A bit array; each key sets **k** bits (via k hashes). A lookup: if **any** of its k bits is **unset**, the key is **definitely not present**; if **all** are set, it's **probably** present (bits collide, so false positives happen). The guarantee that matters: **false positives possible, false negatives never.** That asymmetry is the value — use it as a **cheap gate before an expensive check**: "does this row exist? is this URL seen?" If the filter says **no**, skip the DB entirely; if **yes**, pay for the real lookup. Sizing from the classic formulas: for `n` items at false-positive rate `p`, bits `m = -n·ln p / (ln2)²` and `k = (m/n)·ln2` hashes — more bits / right `k` → lower `p`.

**(2) Counting Bloom filter — a Bloom you can delete from.** A plain Bloom **can't remove** an item: clearing its bits might clear bits another item shares (→ false negatives). So instead of one **bit** per slot, keep a small **counter**: add **increments** the k slots, remove **decrements** them, "present" means all k are **non-zero**. Cost: a few bytes per slot instead of one bit. Caveat (stated in the code): remove assumes the item **was actually added** — removing something never added **corrupts counts** and can create false negatives.

**(3) Count-Min sketch — "how many times have I seen this key?"** A `depth × width` grid of counters. Each key hashes to **one cell per row** and **adds** there; the estimate is the **minimum across its rows**. Because collisions only ever **add** to a cell, the sketch **never underestimates** — and taking the **min** discards the rows where this key happened to collide with a heavy key, so the over-estimate is small. Sizing: `width = ⌈e/ε⌉` bounds the over-estimate to `ε × (total count)`; `depth = ⌈ln(1/δ)⌉` makes that hold with probability `1−δ`. It's used for **heavy-hitters / top-K**, DDoS-source detection, hot-key detection, and approximate frequencies in query planners.

The through-line: each structure **gives up exactness in one controlled direction** to buy order-of-magnitude memory savings — Bloom never says "no" wrongly (so it's safe as a *skip-the-work* gate), Count-Min never says "fewer" wrongly (so it's safe as a *catch-the-heavy* detector). Choosing one means choosing **which error you can tolerate**.

---

## Questions

### A — Literal
1. In a Bloom filter, which answer is certain and which is probabilistic, and why that way round?
2. Why can't a plain Bloom filter delete, and what does the counting variant change to allow it?
3. Count-Min takes the **minimum** across rows for its estimate. Why the minimum, and in which direction can it err?

### B — Inference
4. A Bloom filter is used as a gate in front of a database "does this key exist?" check. Trace what happens on a **false positive** and on a (impossible) **false negative**, and why only one of those would be a correctness bug.
5. Count-Min "never underestimates." Why does that property make it the right tool for **heavy-hitter / abuse detection** specifically, where underestimating would be dangerous?

### C — Trade-off
6. Bloom filter: making it bigger (more bits/`m`) lowers the false-positive rate. What are you trading, and what happens to the false-positive rate as the filter **fills up** beyond its designed `n`?
7. Counting Bloom (deletable) vs plain Bloom. What does the delete capability cost, and when is it worth it vs just rebuilding the filter periodically?

### D — Transfer / extension
8. ShopFlow wants to avoid a DB round-trip for the **very common "this is a brand-new key, definitely not in the table"** case (e.g. a new idempotency key / a cache that mostly misses). How does a Bloom filter save the DB hit, what's the worst case, and why is it safe (no wrong "skip")? Tie it to cache penetration (topic 12).
9. You want to **rate-limit only the abusive top-0.1% of API keys** without tracking a counter for every key (millions). Design it with Count-Min: what do you store, how do you decide "heavy," and what over-estimate risk do you accept (and is that risk on the safe side)?

### E — Spot-the-flaw
10. A dev uses a Bloom filter as the **source of truth** for "has this payment been processed?" — if the filter says present, they **skip** the payment as a duplicate. Occasionally a **legitimate new payment is silently dropped**. Explain the bug (which error direction bit them), and the correct role for a Bloom filter here.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **"Definitely not present" is certain**; **"probably present" is probabilistic**. Because a key sets k specific bits on insert, if **any** of those bits is still 0 the key was **never inserted** (certain no). If all k are 1, they could all be 1 due to **other** keys' insertions colliding — so "present" is only probable (false positives). The structure can never have a **false negative** (an inserted key always has all its bits set).
2. A plain Bloom stores one **bit** per slot shared across keys; clearing a key's bits on delete could clear bits that **another key also set**, breaking that other key's membership → **false negatives**, which Bloom must never have. The counting variant stores a small **counter** per slot: add **increments**, delete **decrements**, present = all **non-zero** — so removing one key only reduces shared slots by one and doesn't wrongly zero another key's slot (as long as it was really added).
3. Each row's counter for a key is the true count **plus** whatever other keys collided into that cell — so every row **over**-estimates (collisions only add). The **minimum** across rows picks the row with the **least collision**, giving the tightest over-estimate. So Count-Min can **over-estimate but never under-estimate**.
</details>

<details>
<summary>B — Inference</summary>

4. **False positive:** the filter says "probably present," so you **do** the real DB check, which returns "not found" — you paid an **unnecessary** lookup, but the answer is still correct (the DB is the truth). **False negative (impossible here):** the filter would say "definitely not present," you'd **skip** the DB, and you'd **miss a key that actually exists** — a **correctness bug**. Only the false negative is dangerous, and Bloom **structurally can't produce one**, which is exactly why it's safe as a skip-the-work gate (worst case = wasted work, never wrong result).
5. For abuse detection you must **never miss a real heavy hitter** (an attacker/hot key). If the estimate could **underestimate**, an abuser might slip under the threshold undetected — dangerous. Count-Min **never underestimates**, so a key that's truly heavy will **always** read as at-least-heavy; the only error is **over**-estimating a light key (a false alarm), which is the **safe** direction — you might occasionally scrutinize an innocent key, but you never let a real abuser through. Error direction matches the risk.
</details>

<details>
<summary>C — Trade-off</summary>

6. You trade **memory** (more bits `m` = more RAM) for a lower false-positive rate `p`. As the filter **fills past its designed `n`** (you insert more items than you sized for), **more bits get set**, so the probability that all k bits of an absent key are already set **rises** — the false-positive rate **climbs** (eventually toward 1, where it says "present" for everything and is useless). A Bloom filter must be **sized for its expected `n`**, and you rebuild/scale it (or use a scalable Bloom) when you exceed it.
7. The delete capability costs **more memory** (a counter — a few bytes — per slot instead of one bit, several× larger) and the **"only remove what you added" discipline** (a bad remove corrupts it). It's worth it when the set **genuinely churns** (items come and go and you must reflect removals promptly) and you can't afford staleness. If the set only grows, or you can **periodically rebuild** a plain Bloom from the current truth (cheaper memory, no delete hazard), prefer that — rebuild beats counting when deletes are rare or batchable.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. Keep a Bloom filter of **keys that exist in the table** (populated as rows are written / on startup). On a lookup: ask the filter first — if it says **"definitely not present,"** the key **cannot** be in the table, so you **skip the DB entirely** and return "miss/new" immediately; only if it says "probably present" do you pay the DB round-trip. **Worst case:** a false positive → you do a DB lookup that finds nothing (wasted, but correct). **Safe because** Bloom has **no false negatives** — it will never tell you to skip a key that actually exists, so you never wrongly return "new" for an existing row. This is the **cache-penetration** defence (topic 12): a flood of requests for non-existent keys would otherwise all fall through the cache to the DB; the Bloom filter absorbs the "definitely-not-there" ones in memory.
9. Feed every request's API key into a **Count-Min sketch** (fixed small memory, no per-key counter). To decide "heavy," compare a key's **estimated** count against a threshold (e.g. > ε-adjusted share of total, or maintain a small top-K heap of the highest estimates seen). **Over-estimate risk:** Count-Min may report a light key as somewhat heavier than it is → you might **throttle/scrutinise an innocent key occasionally** (false alarm). That's the **safe side**: you **never miss a real abuser** (no underestimate), you only risk over-catching — acceptable for abuse control, and far cheaper than a HashMap of millions of exact counters. (Pair with the real limiter from topic 34 once a heavy key is identified.)
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. They used the Bloom filter's **"present"** answer as **truth** and **skipped** work on it — but "present" is the **probabilistic** side (false positives). A **false positive** makes the filter say "present" for a **new** payment that was never processed, so they **skip it as a duplicate** and the legitimate payment is **silently dropped**. The error that bit them is a **false positive treated as certain** — Bloom guarantees no false **negatives**, not no false **positives**, so you may **only** trust its **"not present"** answer, never its "present." **Correct role:** use the filter to **cheaply skip the expensive check when it says "not present"** (definitely new → process), and when it says "probably present," **fall through to the authoritative check** (the real `processed_event`/PK dedupe of topics 03/25) to confirm. The Bloom filter is a **fast negative gate**, never the dedupe source of truth.
</details>

---

## Tod ke dekho — Local (real module + tests)

```bash
# the three structures and their guarantees:
grep -n "false negative\|DEFINITELY NOT\|m = -n\|k =" shopflow/shopflow-sketch/src/main/java/com/shopflow/sketch/BloomFilter.java
grep -n "decrement\|non-zero\|corrupt" shopflow/shopflow-sketch/src/main/java/com/shopflow/sketch/CountingBloomFilter.java
grep -n "minimum\|never\|width\|depth\|epsilon" shopflow/shopflow-sketch/src/main/java/com/shopflow/sketch/CountMinSketch.java

# run the tests (false-positive rate stays near target; CMS never underestimates; counting delete):
cd shopflow && ./mvnw -q -pl shopflow-sketch test
```

**Proved:** Bloom = no false negatives (safe skip-gate), counting Bloom = deletable at a memory cost, Count-Min = never underestimates (safe heavy-hitter detector). The sizing formulas and the min-across-rows are right there in the code.

## Tod ke dekho — Online
Pure library — no infra. On [`ONLINE-LABS.md`](../ONLINE-LABS.md) Codespaces: `./mvnw -pl shopflow-sketch test`, then tweak `n`/`p` and insert past `n` to watch the false-positive rate climb (trap of under-sizing).

---

## Interview concepts is passage se
- **Approximate structures** — exactness ek direction mein chhodo, memory bachao; error-direction = design.
- **Bloom** — no false negatives, false positives possible; sizing `m=-n·ln p/(ln2)²`, `k=(m/n)·ln2`; **fast negative gate**.
- **Counting Bloom** — deletable (counters), cost = bytes/slot, remove-what-you-added only.
- **Count-Min** — frequency, never underestimates (min across rows), `w=⌈e/ε⌉`, `d=⌈ln(1/δ)⌉`; heavy-hitters/top-K/DDoS.
- **Trust only the safe answer** — Bloom ka "no", Count-Min ka "at least this many".
- **Cache penetration** (topic 12) — Bloom non-existent keys ko DB tak jaane se roke.
- **Heavy-hitter → rate-limit** (topic 34) — Count-Min se abuser detect, phir throttle.

**Interviewer's lens:** kya tu **error-direction** samajhta (Bloom ka "present" trust mat karo, "absent" trust karo; Count-Min under-estimate kabhi nahi), sizing bol sakta, aur sahi jagah use karta (negative gate / heavy-hitter) — na ki source-of-truth banata.

> Interview line: *"Probabilistic structures thodi galti ke badle bahut memory bachate, aur **galti ka direction** design hai. **Bloom** 'dekha kya?' — all-bits-set=probably (false positive possible), any-unset=**definitely not** (false negative **kabhi nahi**); isliye **fast negative gate** — filter 'no' bole to DB skip, 'yes' bole to asli check karo (cache-penetration defence, topic 12). **Counting Bloom** delete kar sakta (bit nahi counter), par jo add kiya wahi remove karo. **Count-Min** 'kitni baar?' — har row add karta, estimate = **min across rows**, isliye **kabhi under-estimate nahi** — heavy-hitter/abuse detection ke liye perfect (abuser miss nahi hoga, bas innocent ko kabhi over-count). Rule: sirf **safe answer** trust karo — Bloom ka 'absent', Count-Min ka 'at least'."*
