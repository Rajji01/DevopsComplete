# D05 · The outage happened, but the SLO alert never fired

> Skill: observability / "monitor the monitor." Related mechanism: [`../comprehension/06-slo-burn-rate-bug/`](../comprehension/06-slo-burn-rate-bug/).

---

## Page (symptom)

Post-incident review. Yesterday checkout was degraded for 25 minutes (p99 ~6s, a chunk of requests over the 1s SLO). Customers complained on Twitter. But **no SLO alert ever fired** — `CheckoutSLOBurnRateFast/Slow` stayed green the whole time. The on-call never got paged; they found out from Twitter.

Your job: figure out **why the alert didn't fire**, not what caused the latency. An alert that can't fire is a worse bug than the outage.

**Before details: how do you debug an alert that *didn't* fire? What do you evaluate, and in what order?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- The latency *did* happen — traces and raw `http_server_requests_seconds_count` confirm ~6s p99 for 25 min.
- `promtool test rules monitoring/tests/alerts_test.yml` → **PASS** (the SLO alert's unit tests are green).
- In Prometheus, evaluating the alert's recording rule `sli:checkout_error_ratio:rate1h` during the incident window → **"No data" (empty vector)**.
- Breaking it down: `sli:checkout_requests:rate5m` has data; the **"bad" term's latency part is empty**.
- Querying `http_server_requests_seconds_bucket{uri="/api/v1/orders", le="1.0"}` directly → **no series returned**.
- order-service has `management.metrics.distribution.percentiles-histogram.http.server.requests: true`.
</details>

---

## Your move

1. An alert "didn't fire." Name the three layers where that can break, and which one `promtool test rules` passing **rules out** (and which it does **not**).
2. The recording rule evaluates to **empty vector**, not a low number. Why is "no data" far more dangerous than "a wrong number" for an alert?
3. The SLI's "bad" = 5xx + slow. The slow part needs `…bucket{le="1.0"}`, which returns nothing. Walk the PromQL: what does `5xx + (total − <empty>)` evaluate to, and why does that silence the alert?
4. The unit test is green but prod is blind. What exactly is the test testing, and what is it **not** testing?
5. What two changes fix it so this class of "silent alert" can't recur?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**Three layers an alert can fail at (Q1):** (1) the **alert rule logic** (threshold/`for` wrong), (2) the **routing** (Alertmanager drops/misroutes it), (3) the **input data** (the metric the rule reads doesn't exist / is empty). `promtool test rules` passing **rules out #1** (the math is correct *given input*) — but it **does not** test #3, because the test **feeds its own synthetic series**. So "tests green, didn't fire" points you straight at **#3: the input data**.

**"No data" > "wrong number" (Q2).** A wrong number might still cross a threshold sometimes; **an empty vector never crosses anything** — `empty > 0.072` is not true, it's *no result*, so the alert condition is never satisfied no matter how bad the outage. A metric that silently becomes empty turns an alert into decoration. This is why you alert on **absence** too (`absent()`), and why you test that the inputs exist.

**Walk the PromQL (Q3).** `sli:checkout_bad = 5xx_rate + (nonall_rate − within_1s_rate)`, where `within_1s_rate` comes from `…bucket{le="1.0"}`. That bucket series is **empty**. In PromQL, an arithmetic op where one side is an empty vector yields an **empty result** for that term — so the "slow" contribution collapses, and `sli:checkout_bad` (hence `sli:checkout_error_ratio`) evaluates to **no data**. The burn-rate alert compares *no data* to its threshold → never fires. The latency half of the SLI was **structurally dead**.

**Why the bucket is missing (root cause).** `percentiles-histogram: true` makes Micrometer emit its **own** exponential bucket boundaries, which are **not guaranteed to include exactly `le="1.0"`**. The recording rule hard-codes `le="1.0"`. In prod that boundary doesn't exist → empty series → dead SLI. (This is the real Walk 8 bug; here you're *debugging it from the symptom side*.)

**What the test tested (Q4).** `promtool test rules` **injects its own `le="1.0"` series** and checks the burn-rate math. So it proves "given a 1.0 bucket, the formula is right" — true — but **never proves the 1.0 bucket exists in production**. Green test, blind prod: the test's inputs are fiction, the alert's real inputs are empty.

**Fix (Q5):**
1. **Make the bucket real:** declare an explicit SLO boundary — `management.metrics.distribution.slo.http.server.requests: 1s` — so Micrometer always publishes `le="1.0"`.
2. **Guard it:** add a test that asserts the `le="1.0"` bucket is present in the **real** exported metrics (not a synthetic one), so a future histogram change can't silently drop it. Optionally add an **`absent()`** alert on the SLI series so "no data" itself pages.

**Confirm:** `…bucket{le="1.0"}` returns a series; `sli:checkout_error_ratio` evaluates to a real number under load; a synthetic slow-traffic test actually trips the burn-rate alert.
</details>

---

## The pattern

- **To debug an alert that didn't fire, evaluate it bottom-up: inputs → rule → routing.** Check the data the rule reads *first*; most "silent alert" bugs are empty/renamed inputs, not bad thresholds.
- **"No data" is a failure mode, not a safe default.** Empty vectors never trigger comparisons. Alert on `absent()` for anything load-bearing.
- **A passing rules test proves the math, not the metric.** Any test that supplies its own series can't prove those series exist in prod. Pin real-metric existence separately.
- **Never query a histogram at an `le` you didn't explicitly configure.** Default bucket sets are implementation details that move under you.
- **Monitor the monitor.** The scariest outages are the ones your dashboards stayed green through.

> Grounded in: `monitoring/recording-rules.yml` (`sli:checkout_bad` subtracts `le="1.0"`), `slo-rules.yml`, `order-service/application.yml` (`distribution.slo … 1s` fix), `docs/SELF-WALK.md` Walk 8.
</content>
