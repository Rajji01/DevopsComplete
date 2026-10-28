# 06 · The SLO alert that never fired — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `monitoring/slo-rules.yml`, `monitoring/recording-rules.yml` (`sli:checkout_*`), `order-service application.yml` (metrics distribution), `docs/SELF-WALK.md` Walk 8.

---

## Scenario (read cold)

> *A real post-mortem from ShopFlow (Walk 8). Read once, then answer.*

ShopFlow's checkout SLO: **99.5% of `POST /api/v1/orders` succeed (no 5xx) within 1 second, over 30 days.** The error budget is 0.5%. Alerting uses **multi-window, multi-burn-rate** rules from the Google SRE workbook: page when burning the budget **14.4× too fast over 1h** (confirmed by a 5-minute window), page at **6× over 6h** (confirmed by 30m), ticket at **1× over 3d**. Threshold = `burn_rate × (1 − SLO)` = `burn_rate × 0.005`.

The SLI's "bad" requests are defined in a recording rule as **5xx responses, plus successful-but-slow ones**. "Slow" is computed as *(all non-5xx requests) minus (non-5xx requests that finished within 1s)* — and "within 1s" reads the Prometheus histogram bucket **`http_server_requests_seconds_bucket{..., le="1.0"}`**. In other words, the latency half of the SLI depends entirely on a bucket boundary at exactly **`le="1.0"`** existing in the metrics.

Everything looked green for weeks. The alert rules passed `promtool check rules`, and the **alert unit tests passed** too. Then Walk 8 asked a blunt question: *has this alert ever actually fired in a real Prometheus?* It had not — and it **could not**.

The order-service was configured with `percentiles-histogram: true`, which makes Micrometer publish its **own** set of exponential histogram buckets for latency. Those buckets are **not guaranteed to include a boundary at exactly `le="1.0"`**. So in a real Prometheus, `http_server_requests_seconds_bucket{le="1.0"}` returned an **empty vector**. The "slow" term `(non-5xx total) − (empty)` collapsed, the SLI's latency signal was effectively dead, and the burn-rate expression never crossed its threshold no matter how slow checkout got. The alert was **structurally incapable of firing**.

Why did the tests miss it? The alert **unit test injected a synthetic `le="1.0"` series itself** to drive the scenario — so the test provided the very bucket that production lacked. The test proved the *math* of the burn-rate rule, not the *existence* of the bucket it depends on. Green in CI, dead in prod.

The fix had two parts: **(1)** force the bucket to exist — set `management.metrics.distribution.slo.http.server.requests: 1s` on order-service so Micrometer always publishes an `le="1.0"` boundary; **(2)** add a **guard test** asserting that the `le="1.0"` bucket is actually present in the exported metrics, so the dependency can never silently vanish again.

---

## Questions

### A — Literal
1. What two categories of request does the SLI count as "bad"?
2. Which single Prometheus series does the "slow" half of the SLI depend on, and what did it return in real Prometheus?
3. What two changes made up the fix?

### B — Inference
4. The alert passed `promtool check rules` **and** its unit tests, yet could never fire. Explain precisely why neither check could have caught this bug.
5. If the `le="1.0"` bucket is an empty vector, trace what happens to `sli:checkout_bad` and then to the burn-rate alert expression, step by step.

### C — Trade-off
6. The team used **multi-window** burn-rate alerts (e.g. 1h *and* 5m must both exceed the threshold) instead of a single window. What does the second, shorter window buy, and what noise does the pairing avoid?
7. This whole class of bug is "the dashboard is green but the system is unprotected." Why is a *guard test on the metric's existence* a better defence than, say, more alert-math unit tests?

### D — Transfer / extension
8. A new SLO is proposed for `GET /api/v1/products/{sku}` at **300ms**. Using the lesson from this post-mortem, list the two things you must verify **before** trusting its burn-rate alert.
9. The `429` (rate-limited) and other `4xx` responses are deliberately **excluded** from the SLI's "bad" count. Argue why counting a `429` against the checkout SLO would be the wrong call, and name the one case where you might reconsider.

### E — Spot-the-flaw
10. A teammate "verifies" a brand-new latency alert by writing a `promtool test rules` case that feeds in `http_server_requests_seconds_bucket{le="0.5"}` series and asserts the alert fires. The alert's expression references `le="0.5"`. CI is green. What specific real-world failure mode does this test still not rule out, and what extra check closes the gap?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **(a)** 5xx responses (server errors) and **(b)** successful-but-**slow** responses — non-5xx requests that took longer than 1s.
2. `http_server_requests_seconds_bucket{application="order-service", uri="/api/v1/orders", method="POST", status!~"5..", le="1.0"}`. In real Prometheus it returned an **empty vector** (that bucket boundary didn't exist).
3. **(1)** Set `management.metrics.distribution.slo.http.server.requests: 1s` so Micrometer always publishes an `le="1.0"` bucket; **(2)** add a guard test asserting that bucket is present in the exported metrics.
</details>

<details>
<summary>B — Inference</summary>

4. `promtool check rules` only validates **syntax/structure** of the rules file — it never evaluates them against real data, so it can't know a referenced series will be empty. The **unit tests** (`promtool test rules`) *do* evaluate the math, but they **fabricate their own input series** — including an `le="1.0"` bucket that production didn't emit. So the tests proved "given this bucket, the burn-rate math is correct," which was true; they never proved "this bucket exists in production." Both checks were blind to the missing-dependency failure.
5. The "slow" term is `(non-5xx total rate) − (non-5xx within-1s rate)`. If the `le="1.0"` series is empty, the subtrahend is absent, so in PromQL the subtraction yields **no result for that term** (empty − present → empty), collapsing the slow contribution. `sli:checkout_bad` then reflects **only** 5xx (or goes empty depending on evaluation), so `sli:checkout_error_ratio` loses all latency signal. The burn-rate expr compares that crippled ratio to the threshold and **never exceeds it on latency**, so the alert can't fire however slow checkout becomes.
</details>

<details>
<summary>C — Trade-off</summary>

6. The short window makes the alert **stop quickly once the problem is fixed**: the long (1h) window establishes the sustained burn, but it's slow to clear; requiring the short (5m) window to *also* be hot means the alert resolves soon after recovery instead of lingering for most of an hour. The pairing also **avoids false pages** from a brief spike — a 30-second blip trips the 5m window but not the 1h, so it won't page. You need *both* sustained and recent to fire.
7. Because the failure wasn't in the math — it was a **missing input**. More alert-math tests all share the same blind spot: they supply their own inputs. A guard test asserts a *property of the real exported metrics* (the `le="1.0"` bucket exists), which is the actual precondition the alert silently assumes. It catches the dependency vanishing (config change, library upgrade, histogram setting flip) that no amount of math-testing would reveal. Test the assumption, not just the formula.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **(1)** That an `le="0.3"` (300ms) histogram bucket actually exists in the exported metrics for that endpoint — i.e. add `0.3s` to the SLO distribution config and a guard test for it. **(2)** That the alert has been exercised against a **real Prometheus** (or at least that its SLI series are non-empty in prod), not just a unit test that injects its own buckets. Same two-part lesson: make the bucket real, and prove the alert can fire on real data.
9. A `429` means the **rate limiter did its job** — the system deliberately shed load to protect itself; the request didn't "fail" due to a defect, the client was asked to back off. Counting it against availability would punish the service for a working safety mechanism and make the SLO reflect traffic shape rather than reliability. Likewise generic `4xx` are the client's bad input. **Reconsider** only if `429`s are so pervasive that they represent a real capacity shortfall users experience as an outage — then you'd track them as a separate saturation signal, not fold them into the availability SLI.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. It still doesn't rule out that **`le="0.5"` is never exported by the real application** — exactly the Walk 8 bug. The test fabricates the `le="0.5"` series, so a green CI only proves the alert's arithmetic, not that production emits a 0.5s bucket. If Micrometer's histogram config doesn't include a 0.5 boundary, the alert is dead in prod despite green tests. **Extra check:** a guard assertion against the actual `/actuator/prometheus` output (or the metrics in an integration test) that `http_server_requests_seconds_bucket{le="0.5"}` is present — plus configuring `distribution.slo … 500ms` so it always is.
</details>

---

## Tod ke dekho — Local (docker compose + Prometheus)

Goal: see the SLI series, confirm the `le="1.0"` bucket now exists, and reproduce the "empty bucket → dead alert" logic.

```bash
make up && TOKEN=$(make -s token)
# drive some checkout traffic
for i in $(seq 1 30); do curl -s -X POST http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"sku":"PIXEL-9","quantity":1}' -o /dev/null; done

# 1) The bucket the SLI depends on MUST exist (this is the Walk 8 fix):
curl -s http://localhost:8081/actuator/prometheus | grep 'http_server_requests_seconds_bucket' | grep 'le="1.0"' | grep '/api/v1/orders'
#   -> a line exists  (before the fix: nothing -> dead SLI)

# 2) See the SLI recording rules in Prometheus (http://localhost:9090 -> Graph), query each:
#      sli:checkout_requests:rate5m
#      sli:checkout_bad:rate5m
#      sli:checkout_error_ratio:rate1h
#   If sli:checkout_bad is empty while traffic flows -> the bucket dependency is broken.

# 3) Validate the rules + run the alert unit tests exactly like CI:
cd shopflow
promtool check rules monitoring/alert-rules.yml monitoring/recording-rules.yml monitoring/slo-rules.yml
promtool test rules monitoring/tests/*.yml
```

**Proved:** the alert's correctness depends on a *metric that must physically exist*; step 1 is the guard the post-mortem added. Delete the `distribution.slo` config line, rebuild, and watch step 1 go empty and `sli:checkout_bad` lose its latency signal — the bug, live.

> Reproduce the "green that was red": `promtool test rules` passes because the test injects `le="1.0"`; step 1 against a real `/actuator/prometheus` is what actually catches the missing bucket.

## Tod ke dekho — Online
[`ONLINE-LABS.md`](../ONLINE-LABS.md) **section A (Codespaces)** — Prometheus + order-service chahiye; `promtool` repo mein.

---

## Interview concepts is passage se
- **SLI / SLO / error budget** aur **burn rate = bad_ratio ÷ (1−SLO)**.
- **Multi-window multi-burn-rate alerting** (Google SRE) — fast page + slow page + ticket; short window kyu.
- **Histogram buckets & `le`** — latency SLI ek bucket boundary pe tiki hoti hai.
- **"Green that was red"** — test ne apna input inject kiya, prod dependency verify nahi.
- **Guard test on metric existence** — assumption test karo, sirf formula nahi.
- **Why 429/4xx SLO se bahar** — safety mechanism / client error, reliability defect nahi.

**Interviewer's lens:** kya tu "test green par prod dead" ka root cause articulate kar pata hai (fabricated input vs real dependency), aur burn-rate ka matlab + multi-window ka faida bol pata hai. Ye SRE round ka classic hai.

> Interview line: *"Checkout SLO 99.5%/1s, multi-window burn-rate alerts (14.4× 1h + 5m, 6× 6h + 30m). SLI ka 'slow' term `le=1.0` histogram bucket pe depend karta tha, par `percentiles-histogram` us boundary ko guarantee nahi karta — real Prometheus mein wo bucket empty, isliye alert kabhi fire hi nahi kar sakta tha, jabki unit test khud bucket inject karke green de raha tha. Fix: `distribution.slo … 1s` se bucket force kiya + ek guard test jo uski maujoodgi assert karta."*
</content>
