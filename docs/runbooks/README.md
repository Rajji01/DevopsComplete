# Runbooks

One page per alert: **what it means → what to check first (commands) → likely causes → fix → how to verify → when to escalate**. Every alert in `shopflow/monitoring/*.yml` carries a `runbook:` annotation pointing here, so the on-call engineer gets the link in Slack/PagerDuty.

> Tip: Runbook woh document hai jo 3 AM ko aadhi neend mein bhi follow ho sake. Isliye commands copy-paste ready hain aur "soch ke dekho" nahi likha.

| Alert | Severity | Runbook |
|---|---|---|
| HighErrorRate | page | [HighErrorRate.md](HighErrorRate.md) |
| HighP99Latency | ticket | [HighP99Latency.md](HighP99Latency.md) |
| InventoryCircuitOpen | page | [InventoryCircuitOpen.md](InventoryCircuitOpen.md) |
| ServiceDown | page | [ServiceDown.md](ServiceDown.md) |
| OutboxBacklogGrowing | page | [OutboxBacklogGrowing.md](OutboxBacklogGrowing.md) |
| KafkaConsumerLagHigh | ticket | [KafkaConsumerLagHigh.md](KafkaConsumerLagHigh.md) |
| PaymentFailureRateHigh | page | [PaymentFailureRateHigh.md](PaymentFailureRateHigh.md) |
| OrdersStuckConfirmed | ticket | [OrdersStuckConfirmed.md](OrdersStuckConfirmed.md) |
| CheckoutSLOBurnRateFast / Slow | page | [CheckoutSLOBurnRate.md](CheckoutSLOBurnRate.md) |
| (procedure) Restore PostgreSQL from backup | — | [RestorePostgres.md](RestorePostgres.md) |
| (procedure) Roll back a deploy | — | [Rollback.md](Rollback.md) |

Incident flow (any page): acknowledge in PagerDuty → open `#shopflow-incidents` → runbook → **mitigate first** (rollback / scale / disable), root-cause later → post-mortem within 48 h (`docs/runbooks/postmortem-template.md`).
