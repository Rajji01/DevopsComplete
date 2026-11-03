# 27 · The SLI that catches slow AND broken: reading the observability stack — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `monitoring/recording-rules.yml` (`service:http_*` RED + `sli:checkout_*` multi-window), `monitoring/slo-rules.yml` (multi-burn alerts), `monitoring/alert-rules.yml`, Tempo/Loki/Alloy stack, `GlobalExceptionHandler` (status codes).
> **Capstone trilogy:** [25 correctness](../25-correctness-model-capstone/) · [26 scaling](../26-scaling-100x-bottlenecks/) · **27 observability (ye)**. Teeno architect-level synthesis — "sahi hai?", "scale pe tootega kahan?", "toota to pata kaise chalega + kahan dekhun?".
> **Debug it live:** [D05](../../debugging/D05-alert-that-didnt-fire.md) (SLI metric missing), [D03](../../debugging/D03-latency-spike-no-errors.md) (slow-not-broken).

---

## Scenario (read cold)

> *Excerpt from ShopFlow's monitoring design: "how we define 'working' and alert on it." Read once, then answer.*

ShopFlow's checkout SLO is one sentence: **"99.5% of checkout requests succeed (no 5xx) within 1s, over 30 days."** Notice it bundles **two** properties — *succeed* (availability) **and** *within 1s* (latency) — into one number. The error budget is 0.5%.

The SLI that measures it is built in layers of Prometheus recording rules. First, generic **RED** per service: `service:http_requests:rate5m` (request rate), `service:http_errors:rate5m` (5xx rate), `service:http_error_ratio:rate5m`, and `service:http_latency_p99:5m` (a `histogram_quantile(0.99, ...)` over the latency buckets). These are the "every service looks the same" dashboards.

Then the **checkout SLI**, which is deliberately *not* just "non-5xx". Its **denominator** is `status=~"[25].."` — only **2xx and 5xx** requests. **4xx is excluded on purpose**: a client sending a bad body (400) or hitting a sold-out SKU (409) is **not** the service failing its SLO, so those must not count for or against us. Its **numerator** (`sli:checkout_bad`) is `5xx + (all 2xx − 2xx-that-were-faster-than-1s)`. Read that again: "bad" = every 5xx **plus every 2xx that took longer than 1s**. The `(2xx total − 2xx with le="1.0")` term is the **latency** half, pulled straight from the histogram's `le=1.0` bucket. So a 200 OK that took 3s counts as a **budget burn** exactly like a 500 — which is what "succeed within 1s" actually means.

That SLI is computed over **four windows** — 5m, 30m, 1h, 6h — and the alerts are **multi-window, multi-burn-rate** (Google SRE workbook ch.5): `CheckoutSLOBurnRateFast` pages when the **1h** ratio **and** the **5m** ratio both exceed `14.4 × 0.005` (burning the monthly budget 14× too fast), `for: 2m`; `CheckoutSLOBurnRateSlow` pages on **6h and 30m** over `6 × 0.005`, `for: 15m`; a slow `1×` over 3d is a **ticket**, not a page. The long window decides *there is a problem*; the short window makes the alert **stop quickly** once it's fixed (no lingering page).

When one fires, the signals compose into a **reasoning order**: the **SLO/RED** tells you *that* checkout is bad and *whether it's errors or latency* (which half of the SLI moved); a **trace** (Tempo) localizes the latency/failure to a **span** (which service/dependency); **USE** on that resource (pool utilization/saturation) or its **logs** (Loki, pivoted by `trace.id`) tells you *why*. Metrics say *what and where*, traces say *which hop*, logs say *why* — you walk down, you don't guess.

The rule the team repeats: **an SLI must measure what the user feels (slow counts as broken), must not blame the user (4xx excluded), and every series an alert needs must actually exist in prod** — because an alert on an absent metric is silently dead.

---

## Questions

### A — Literal
1. The SLO sentence bundles two properties into "99.5%". What are they, and which part of the SLI formula encodes each?
2. Why is `4xx` excluded from the SLI's **denominator**, and give one 4xx example from ShopFlow that must not burn the budget.
3. What does the term `(2xx total − 2xx with le="1.0")` compute, and why is a 200-that-took-3s treated like a 500?

### B — Inference
4. Both alerts require a **long** window AND a **short** window to be over threshold. What does each window buy you — what goes wrong if you alert on the long window alone? On the short alone?
5. The fast alert's threshold is `14.4 × 0.005`. Explain where both numbers come from and what "14.4" means in budget terms.

### C — Trade-off
6. The SLI could have been simpler: "ratio of 5xx to total." What does the ShopFlow SLI (slow-counts-as-bad, 4xx-excluded) gain over that, and what does it cost in complexity/failure-modes?
7. You have RED metrics, traces, and logs. Why keep all three instead of "just log everything and grep"? What is each uniquely good at, and what's the cost of over-relying on logs?

### D — Transfer / extension
8. A page fires: the **5m and 1h** ratios are both high. You open the SLI's two halves and see the **5xx term is flat** but the **slow-2xx term is what's elevated**. Walk the exact next three steps (which tool, what you expect) to get from "latency is burning the budget" to a root cause.
9. You add a new critical endpoint `POST /api/v1/refunds`. Describe what you must add at **each** layer (recording rule, SLO alert, dashboard) for it to be observable to the same standard — and the one failure mode (from D05) you must guard against.

### E — Spot-the-flaw
10. To "reduce noise," a dev changes the fast alert to use **only** the 1h window (drops the 5m `and` clause). A week later, a 30-minute incident is fully resolved, but the page **keeps firing for another ~50 minutes** after checkout is healthy again. Explain why, and why the original two-window design didn't have this problem.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **Availability** ("succeed / no 5xx") and **latency** ("within 1s"). Availability = the `5xx` term in the numerator; latency = the `(2xx total − 2xx with le="1.0")` term (the 2xx requests slower than 1s). Denominator (2xx+5xx) normalizes both into one ratio.
2. Because **4xx is the client's fault, not the service failing** — counting it would make a service look like it's missing its SLO when it's correctly rejecting bad input. Examples: **400** (malformed order body, validation), **409** (sold-out / not-cancellable), **422** (Idempotency-Key cross-user), **429** (rate-limited), **404** (foreign order / IDOR). None of those are the service being unavailable, so they're excluded from both numerator and denominator.
3. It computes the **rate of 2xx requests that took *longer* than 1s**: total 2xx minus the 2xx that fell in the `le="1.0"` histogram bucket (≤1s). A 200-at-3s is outside `le=1.0`, so it survives the subtraction and lands in "bad" — because the SLO promised "succeed **within 1s**," and a 3s success broke that promise just as a 500 would. The user felt a failure either way.
</details>

<details>
<summary>B — Inference</summary>

4. The **long window** (1h/6h) establishes **significance** — the problem is real and sustained, not a 10-second blip, so you don't page on noise. The **short window** (5m/30m) establishes **currency** — the problem is *still happening right now*, so the alert **resets quickly** once fixed. Long-alone: you page on a brief spike that's already gone (false page), and the alert lingers long after resolution (because a 1h average stays high for ~an hour). Short-alone: you page on every tiny transient (massive noise). Requiring both = "real AND ongoing."
5. `0.005` = `1 − SLO` = `1 − 0.995` = the **error budget fraction** (0.5%). `14.4` = the **burn rate** that would exhaust a **30-day** budget in a specific short time: burning at 14.4× the sustainable rate consumes **2% of the monthly budget in 1 hour** (the SRE-workbook page threshold). Threshold `= burn_rate × (1 − SLO) = 14.4 × 0.005 = 0.072`, i.e. page when the error ratio exceeds ~7.2%.
</details>

<details>
<summary>C — Trade-off</summary>

6. **Gains:** it measures **what the user actually experiences** — a site that's "up" but takes 3s per checkout is failing the user, and a plain 5xx-ratio SLI would call that 100% healthy. It also **doesn't punish** the service for correct 4xx rejections, so the number reflects *our* reliability, not client behavior. **Costs:** more complex SLI (two terms, histogram-bucket dependency), and a **new failure mode** — it now depends on the `le="1.0"` bucket **existing** in prod; if that bucket isn't emitted (histogram config drift), the latency term silently breaks (the D05 / topic 06 bug). More signal, more to keep honest.
7. Each is best at a different question: **metrics (RED/SLO)** = cheap, always-on, aggregate — "is it bad, and is it errors or latency?" (what/whether); **traces** = per-request causal path — "**which hop/service/dependency** owns the time/failure?" (where); **logs** = the specific detail — "**why** did that span fail (stack trace, bad id)?" (why). Logs alone can't cheaply tell you aggregate health or localize latency across services without heroic grep, and high-cardinality logging is expensive and slow to query. Over-relying on logs = you pay a lot to answer "where/whether" badly, when a metric/trace answers it instantly. Use the cheap aggregate to decide *there's a problem*, the trace to *localize*, the log to *explain*.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. "Latency, not errors, is burning the budget." Next three steps:
   - **(1) Metric → narrow:** check `service:http_latency_p99:5m` per `application` to see **which service's** p99 moved (order-service itself, or does its latency trace to a downstream?). Expectation: one service's p99 is elevated.
   - **(2) Trace (Tempo) → localize:** open a slow checkout trace; read which **span** owns the time (e.g. the `reserve` HTTP span to inventory, vs order-service's own DB spans). Expectation: one span dominates — that's the hop to investigate (exactly D03's move).
   - **(3) USE / logs on that resource → explain:** on the implicated service, check **saturation** (HikariCP `active`/`pending`, thread pool) and pivot its **logs by `trace.id`** in Loki. Expectation: a saturated pool (pending > 0) or a slow-dependency log — the *why*. Then fix the cause (capacity/timeout/shed), and watch the short-window SLI drop to confirm.
9. At **each layer**: a **recording rule** for the new SLI (`sli:refund_requests` with `status=~"[25].."`, `sli:refund_bad` = `5xx + slow-2xx`, and the 30m/1h/6h windows); an **SLO alert** (multi-window multi-burn, same `burn × 0.005` thresholds, with a runbook); a **dashboard** RED panel. The failure mode to guard (D05): the alert depends on series that must **exist** — add a **guard/absent() alert** (or a promtool test) that fires if `sli:refund_*` or the `le="1.0"` bucket is missing, so a never-emitted metric can't make the SLO alert silently dead. (Also: define what 4xx means for refunds and exclude it consistently.)
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. The 1h window is a **1-hour moving average**. A 30-minute burst of errors stays "baked into" that average for roughly an hour **after** it ends — the ratio decays slowly as the bad minutes age out of the window. With only the 1h clause, the alert keeps seeing an over-threshold 1h ratio for ~50 more minutes even though checkout recovered, so it **keeps paging** long after there's anything to do (alert fatigue, false urgency). The original design `AND`-ed a **5m** window: once the incident is actually over, the **5m** ratio drops below threshold within minutes, which **breaks the `and`** and **resolves the page** promptly — the short window is precisely the "is it still happening *now*?" guard. Dropping it traded a little noise-reduction for a lingering, lying page. (The fix is the opposite: keep both windows; if noise is the worry, tune `for:` or thresholds, don't remove the currency signal.)
</details>

---

## Tod ke dekho — Local (dekho SLI kaise bani hai)

Goal: khud rules padho/test karo — metric stack grounded hai, invent nahi.

```bash
# the RED + SLI recording rules, and the le=1.0 latency term:
grep -nE "record:|status=~|le=\"1.0\"" shopflow/monitoring/recording-rules.yml

# the multi-window multi-burn alerts (14.4/1h+5m, 6/6h+30m):
grep -nE "alert:|rate1h|rate5m|rate6h|rate30m|0.005|for:" shopflow/monitoring/slo-rules.yml

# promtool unit tests prove the alert fires on the right shape (and the guard for the missing-bucket bug):
ls shopflow/monitoring/tests/ && grep -rnE "CheckoutSLOBurnRate|le|absent|promql_expr_test" shopflow/monitoring/tests/ | head

# live (needs stack): make up, then in Prometheus (localhost:9090) eval:
#   sli:checkout_error_ratio:rate5m        # the budget-burn ratio
#   service:http_latency_p99:5m            # per-service p99
#   histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket{uri="/api/v1/orders"}[5m])))
```

**Proved:** SLI = availability (5xx) **+** latency (slow-2xx via `le=1.0`) over 2xx+5xx (4xx excluded); alerts need **both** windows; a promtool test locks the shape. Live burn demo: topic 06's "break the SLI" + D05.

## Tod ke dekho — Online
Stack chahiye → [`ONLINE-LABS.md`](../ONLINE-LABS.md) (Codespaces pe `make up` + Prometheus/Grafana). Ya `promtool test rules` CI mein chalta — rules bina cluster ke bhi test ho jaati.

---

## Interview concepts is passage se
- **SLI/SLO/error budget** — "99.5% succeed within 1s" = availability **+** latency ek number mein.
- **4xx denominator se bahar** — client ki galti SLO burn na kare (400/409/422/429/404).
- **Latency-as-error via histogram `le` bucket** — slow-2xx = bad; aur uska failure mode (bucket absent = D05).
- **Multi-window multi-burn-rate** — long = significance, short = currency; `burn × (1−SLO)`; 14.4/6/1 → page/page/ticket.
- **RED vs USE** — request-view vs resource-view.
- **Signal hierarchy** — metric (what/where) → trace (which hop) → log (why); guess mat karo, utro.
- **Monitor the monitor** — alert jis series pe depend karta wo exist kare (absent/guard test).

**Interviewer's lens:** kya tu SLI ko **user-experience se** define karta (slow=broken, 4xx≠hamari galti), multi-window ka *kyun* (significance+currency) bol pata, aur incident mein **metric→trace→log utarna** jaanta — guessing ke bajaye.

> Interview line: *"Checkout SLO: 99.5% requests 1s ke andar succeed, 30 din. SLI do hisson mein — bad = 5xx **plus** slow-2xx (histogram `le=1.0` bucket se), denominator sirf 2xx+5xx (**4xx exclude** — client ki galti budget na jalaye). Alert multi-window multi-burn: 1h **and** 5m > 14.4×0.005 → page (long=real hai, short=abhi ho raha, fix pe jaldi resolve). Fire ho to **metric→trace→log** utarta: SLO batata errors ya latency, Tempo span batata kaunsा hop, Loki log `trace.id` se batata kyun. Aur jis metric pe alert depend karta wo prod mein exist kare — warna alert chup-chaap dead (woh le=1.0 wala bug)."*
