# CheckoutSLOBurnRateFast / Slow (page)

**SLO:** 99.5% of `POST /api/v1/orders` succeed (no 5xx) within 1 s, over 30 days. Budget: 0.5% bad requests ≈ **3.6 hours** of total failure per month.

**Fast (1h window, burn 14.4×):** at this rate the whole month's budget is gone in ~2 days. Treat like an outage.
**Slow (6h window, burn 6×):** ~5 days to exhaustion. Fix today, not necessarily tonight.

## 1. Error budget or latency?
Grafana → *Checkout SLI error ratio vs budget* panel; then:
```promql
# share of bad checkouts that are 5xx (vs slow)
sum(rate(http_server_requests_seconds_count{application="order-service",uri="/api/v1/orders",method="POST",status=~"5.."}[30m]))
/ sli:checkout_bad:rate30m
```
- mostly 5xx → [HighErrorRate.md](HighErrorRate.md) (usually inventory down → `InventoryCircuitOpen`)
- mostly slow → [HighP99Latency.md](HighP99Latency.md) (DB, Kafka `acks=all`, inventory slow)

## 2. Error budget policy (agreed with product)
| Budget remaining | Rule |
|---|---|
| > 50% | ship normally |
| 10–50% | no risky releases on Fridays; every deploy canary-only (`k8s/rollouts`) |
| < 10% | feature freeze: only reliability work until the 30-day window recovers |

## 3. Verify
`sli:checkout_error_ratio:rate1h < 0.005` and the alert resolves. Record the budget consumed in the post-mortem.
