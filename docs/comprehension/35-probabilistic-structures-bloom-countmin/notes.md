# 35 · Bloom & Count-Min — NOTES

> Pair: [`comprehension.md`](comprehension.md). Real `shopflow-sketch`. Theme: **thodi galti → bahut memory bachat; galti ka direction = design.**

---

## KYA (one breath)
Billions-scale set/stream pe "dekha kya?" / "kitni baar?" ko **fixed tiny memory** mein answer — ek direction mein bounded error maan ke. **Bloom** (no false negatives), **Counting Bloom** (deletable), **Count-Min** (never underestimate).

## EASY WAY
> **"Bloom ka 'NAHI' hamesha sach (absent = skip DB); 'haan' sirf probably. Count-Min kabhi kam nahi batata (heavy-hitter miss nahi hoga). Sirf **safe answer** trust karo."**

## 3 STRUCTURES
| Structure | Sawaal | Guarantee (error-direction) | Use |
|-----------|--------|------------------------------|-----|
| **Bloom** | "dekha kya?" | false-**positive** possible, false-**negative** **never** | fast negative gate (DB/cache skip) |
| **Counting Bloom** | "dekha kya?" + delete | same + deletable (counters) | churning set (add/remove) |
| **Count-Min** | "kitni baar?" | **over**-estimate possible, **under** **never** | heavy-hitter / top-K / DDoS |

## BLOOM (yaad rakho)
```
 insert key → k hashes → set k bits
 lookup: koi bhi bit 0  → DEFINITELY NOT (certain) → skip expensive check
         saare bits 1   → PROBABLY (false positive ho sakta) → asli check karo
 NEVER false negative (inserted key ke saare bits set rehte)
 sizing: m = -n·ln p / (ln2)²   ;   k = (m/n)·ln2     (n se zyada bhar do → p badh jaata)
 TRUST only the "NO".
```

## COUNT-MIN (yaad rakho)
```
 depth × width grid; key → har row mein 1 cell, ADD
 estimate = MIN across rows   (collisions sirf ADD karti → over-estimate; min = least-collided row)
 NEVER underestimate.  width = ⌈e/ε⌉ (over ≤ ε·total), depth = ⌈ln(1/δ)⌉ (prob 1−δ)
 TRUST only the "at least this many".
```

## ERROR-DIRECTION = WHY IT'S SAFE
| Structure | Safe answer | Worst case | Kyun safe |
|-----------|-------------|------------|-----------|
| Bloom | "absent" → skip | false positive → wasted DB lookup (correct) | skip kabhi galat nahi (no false negative) |
| Count-Min | "heavy" (≥threshold) | over-count innocent → false alarm | abuser kabhi miss nahi (no underestimate) |

## COUNTING BLOOM (delete)
- Bit nahi **counter**: add=increment, remove=decrement, present=all non-zero.
- Cost: bytes/slot (plain Bloom se bada). Caveat: **jo add kiya wahi remove** karo, warna counts corrupt → false negatives.
- Deletes rare/batchable → **plain Bloom rebuild** better.

## WHERE IN A SYSTEM (ShopFlow ties)
- **Bloom = cache penetration guard** (topic 12): non-existent keys ko DB tak jaane se roke (filter "absent" → skip).
- **Bloom before dedupe** (03/25): "absent"→definitely new, process; "present"→asli `processed_event`/PK check (filter **source of truth nahi**).
- **Count-Min → heavy-hitter → rate-limit** (topic 34/26): har-key counter ke bina abusive top-0.1% detect, phir throttle.

## GOTCHAS (interview)
- **Bloom ka "present" trust mat karo** (false positive); sirf "absent".
- **Count-Min ka under kabhi nahi** → abuse detection safe-side.
- **Bloom source-of-truth kabhi nahi** (payment dedupe = silent drop bug).
- **Under-size Bloom** (n se zyada) → false-positive rate chadhta (eventually useless).
- **Counting Bloom bad-remove** → corruption.

## UNDERSTANDING CHECK (khud bol)
1. Bloom ka kaunsa jawab certain? → *"definitely not present" (absent); "present" probabilistic.*
2. Count-Min min kyun? → *collisions over-estimate; min = least-collided row (tightest, never under).*
3. Bloom payment-dedupe source-of-truth kyun galat? → *false positive → naya payment "present" → silently drop.*
4. Heavy-hitter ke liye Count-Min kyun safe? → *never underestimate → abuser miss nahi; bas innocent over-count (safe side).*
5. Bloom DB-hit kaise bachata? → *"absent"→key table mein nahi ho sakti→DB skip; worst case false-positive=wasted lookup.*

## INTERVIEW LINE
> *"Probabilistic structures thodi galti ke badle bahut memory, aur **galti ka direction** design hai. **Bloom** 'dekha kya?' — any-bit-unset = **definitely not** (false negative kabhi nahi), all-set = probably (false positive); isliye **fast negative gate** — 'absent' pe DB skip (cache-penetration, topic 12), 'present' pe asli dedupe check (source-of-truth nahi). **Counting Bloom** deletable (counter). **Count-Min** 'kitni baar?' — estimate=min across rows, **kabhi under-estimate nahi** → heavy-hitter/abuse detection perfect (abuser miss nahi, innocent kabhi over-count). Sirf safe answer trust: Bloom ka 'absent', Count-Min ka 'at least'."*

---
**Grounded:** `shopflow-sketch/{BloomFilter,CountingBloomFilter,CountMinSketch,Hashes}.java` (+ sizing formulas in code). Ties: [12](../12-caching-cache-aside-failopen/) (penetration), [03](../03-idempotency-three-ways/)/[25](../25-correctness-model-capstone/) (dedupe truth), [34](../34-rate-limiting-algorithms/)/[26](../26-scaling-100x-bottlenecks/) (heavy-hitter).
