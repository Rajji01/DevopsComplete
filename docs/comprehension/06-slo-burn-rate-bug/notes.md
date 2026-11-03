# 06 · SLO burn-rate alerting (and a dead-alert bug) — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `monitoring/slo-rules.yml`, `recording-rules.yml`, `docs/SELF-WALK.md` Walk 8.

## Kya (one line)
Error budget (1−SLO) ko kitni tezi se jala rahe ho = **burn rate**; multi-window multi-burn-rate alerts se fast/slow page karo. ShopFlow mein ek bug tha: SLI ka latency term ek `le="1.0"` histogram bucket pe tika tha jo prod mein exist hi nahi karta tha → alert kabhi fire nahi kar sakta tha, phir bhi test green the.

## Easy way (Hinglish analogy)
Mahine ka kharch budget socho (error budget = 0.5%). **Burn rate** = tum budget kitni guna tez uda rahe ho. Burn 1 = theek 30 din mein khatam. Burn 14.4 = ek ghante mein mahine ka 2% uda diya → turant alarm.
Ab bug: tumhara "late delivery" detector ek **ghadi** pe depend karta tha jo lagi hi nahi thi (bucket missing) — to late deliveries count hi nahi hue, alarm kabhi baja hi nahi. Aur jis test se check kiya, usne apni **nakli ghadi** laga di thi — to test pass, real mein detector murda. Lesson: **jis cheez pe alert tika hai, wo cheez sach mein maujood hai — ye alag se test karo.**

## Kyu (ye problem solve karta hai)
- **Burn-rate alerting** simple threshold ("error > 2%") se behtar hai: wo budget-consumption se jodta hai — chhoti spike ignore, sustained fast-burn page. Multi-window (long AND short) se: long = sustained confirm, short = recover hote hi band + brief blip pe false page nahi.
- **Guard test** is problem ko pakadta hai: alert chup-chaap ek metric assume karta hai (`le="1.0"` bucket); agar config/library change se wo gayab ho jaye, math test kabhi nahi pakdega kyunki wo apna input khud banata hai.

## Kaise (step by step)
1. **SLO:** 99.5% POST /orders, no 5xx, <1s, 30d. Budget 0.5%. Threshold = `burn × 0.005`.
2. **Recording rules** (`recording-rules.yml`): `sli:checkout_bad = 5xx-rate + (2xx total − 2xx within le=1.0)`; `sli:checkout_error_ratio:rateXX = bad/total` for 5m/30m/1h/6h.
3. **Alerts** (`slo-rules.yml`): `BurnRateFast` = rate1h > 14.4×0.005 **and** rate5m > same (for 2m) → page. `BurnRateSlow` = rate6h > 6×0.005 **and** rate30m (for 15m) → page. (3d/1× → ticket.)
4. **Bug:** `percentiles-histogram: true` apne buckets banata, `le="1.0"` guaranteed nahi → prod mein wo series empty → slow-term collapse → ratio latency-blind → alert dead. Unit test ne khud `le="1.0"` inject kiya → green.
5. **Fix:** `management.metrics.distribution.slo.http.server.requests: 1s` (bucket force) + guard test (bucket maujood hai assert).

## Flow diagram (SLI → burn-rate alert, + where it broke)
```
 POST /orders metrics
   http_server_requests_seconds_count (by status)
   http_server_requests_seconds_bucket (by le)   ◄── needs le="1.0" boundary  ✗ (bug: missing)
        │
 recording: sli:checkout_bad = 5xx + (nonall − non5xx@le<=1.0)   ← slow term dies if bucket empty
        │
 sli:checkout_error_ratio:rate{5m,30m,1h,6h} = bad/total
        │
 slo-rules:  (rate1h > 14.4·0.005) AND (rate5m > …) for 2m → PAGE (fast)
             (rate6h > 6·0.005)    AND (rate30m > …) for 15m → PAGE (slow)
        │
   bucket empty ⇒ ratio never crosses threshold on latency ⇒ ALERT CAN'T FIRE
```

## Why tests passed but prod was dead
```
 promtool check rules     → sirf syntax/structure; data evaluate nahi karta        → miss
 promtool test rules      → math evaluate karta, PAR apna le="1.0" khud banata     → miss
 guard test (NEW)         → real /actuator/prometheus mein le="1.0" hai? assert    → CATCH
```

## Code + dry run (samajh pakki)

**Asli config (recording rule + alert):**
```yaml
# recording-rules.yml — "bad" = 5xx + slow-2xx (le=1.0 bucket pe depend)
- record: sli:checkout_bad:rate5m
  expr: |
    sum(rate(http_server_requests_seconds_count{...,status=~"5.."}[5m]))
    + ( sum(rate(http_server_requests_seconds_count{...,status=~"2.."}[5m]))
      - sum(rate(http_server_requests_seconds_bucket{...,status=~"2..",le="1.0"}[5m])) )  # ← le=1.0 chahiye
- record: sli:checkout_error_ratio:rate5m
  expr: sli:checkout_bad:rate5m / sli:checkout_requests:rate5m
# slo-rules.yml — multi-window burn rate
- alert: CheckoutSLOBurnRateFast
  expr: sli:checkout_error_ratio:rate1h > (14.4*0.005) and sli:checkout_error_ratio:rate5m > (14.4*0.005)
# application.yml — THE FIX: bucket ko guarantee karo
management.metrics.distribution.slo.http.server.requests: 1s   # → http_server_requests_seconds_bucket{le="1.0"}
```

**Dry run — bug (bucket missing, alert dead):**
```
 percentiles-histogram: true  → Micrometer apne buckets banata, le="1.0" guaranteed NAHI
 prod: query bucket{le="1.0"} → EMPTY vector
 sli:checkout_bad = 5xx + (2xx_total − <EMPTY>)  →  slow-term collapse → bad = no-data
 sli:checkout_error_ratio = no-data
 alert expr: no-data > 0.072  →  kabhi TRUE nahi  →  25-min outage, 0 page
 par promtool test rules: test khud le="1.0" inject karta → GREEN (jhooth)
```

**Dry run — fix (bucket forced):**
```
 distribution.slo … 1s  →  bucket{le="1.0"} ab EXPORT hota
 sli:checkout_bad = 5xx + (2xx_total − 2xx_within_1s)  →  real number
 outage: slow 2xx badhe → ratio > threshold (rate1h AND rate5m) for 2m → PAGE ✅
 guard test: /actuator/prometheus mein le="1.0" hai? assert → dependency chup-chaap gayab na ho
```
**Seekh:** alert ek metric pe tika hai; wo metric prod mein exist karta hai ya nahi — alag se test karo (formula nahi, assumption).

## Alternate approaches (kab kaunsa)
| Approach | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Static threshold** (`error>2% for 5m`) | simple expr | easy | budget se disconnected, noisy/slow | chhoti services |
| **Single-window burn rate** | ek window > threshold | budget-aware | recover hone mein slow / blip pe page | ok, basic |
| **Multi-window multi-burn** *(ShopFlow)* | long AND short, 2 severities | fast+precise, kam false page | zyada rules | user-facing SLO |
| **Guard test on metric** | assert bucket exists | missing-dependency pakadta | har SLI ke liye likhna | har latency SLI |

## Understanding check
1. Burn rate ka matlab aur 14.4× kaise nikla (2% in 1h)?
2. Short window pairing kya do cheezein deta hai?
3. `le="1.0"` empty ho to `sli:checkout_bad` ka kya hota, step by step?
4. `promtool check` aur `promtool test` dono kyu miss kar gaye?
5. 429/4xx SLO se bahar kyu?

## Interview mein ye bolo
*"Checkout SLO 99.5%/1s. Alerting multi-window multi-burn-rate hai (14.4× over 1h confirmed by 5m → page). SLI ka slow-term `le=1.0` histogram bucket pe depend karta tha; `percentiles-histogram` us boundary ko guarantee nahi karta, to prod mein wo empty tha aur alert structurally fire hi nahi kar sakta tha — jabki unit test khud bucket inject karke green de raha tha ('green that was red'). Fix do hisse: `distribution.slo … 1s` se bucket force, aur ek guard test jo real exported metrics mein us bucket ko assert karta. Lesson: formula ke saath uski assumption bhi test karo."*

> Related: [`../04-order-limbo-reconciler/`](../04-order-limbo-reconciler/) (gauge-driven alert), [`../07-k8s-readiness-cascade/`](../07-k8s-readiness-cascade/) (health signals).
</content>
