# PaymentRefundFailed (ticket)

**Fires when:** the payment processor **declined a refund** (Walk 20). The order stays `REFUND_PENDING`, the payment stays `CAPTURED` (we did not fake a refund that did not happen), and the customer's money has **not** been returned yet. Rare — a human must resolve it; no retry loop fixes a hard decline.

> Not a page: money is safe with us, nothing is customer-facing-down. But it must not be ignored — a customer is owed a refund.

```sh
# which orders / amounts, and why the processor said no
kn logs deploy/payment-service --since=30m | grep -E 'refund DECLINED' | tail -20
kn exec postgres-0 -- psql -U postgres -d payments -c \
  "select order_ref, amount, status from payment where status='CAPTURED' order by created_at desc limit 20;"
# the stuck orders (the same ones OrdersStuckConfirmed will also flag after the grace period)
kn exec postgres-0 -- psql -U postgres -d orders -c \
  "select order_ref, updated_at from orders where status='REFUND_PENDING' order by updated_at;"
```

| reason (from the log) | Cause | Fix |
|---|---|---|
| "refund above the processor limit" (fake gateway) | a test/limit left low, or a genuinely large refund | raise `payment.refund-limit`, or process the refund manually via the provider console |
| provider declines / timeouts (real gateway) | provider issue, expired original charge, closed card | retry via the provider; if it cannot auto-refund, issue a manual credit and record it |
| every refund failing | gateway outage or a credential/config break | check the provider status + `application-aws.yml` secrets; pause cancellations if needed |

**Resolve a single stuck order:** once the money is actually returned (auto-retry succeeds, or a manual credit), drive the order to `REFUNDED` and release the stock — in this build that means re-emitting/redelivering the `OrderRefundPending` event (payment-service retries from `CAPTURED`) **or** a one-off correction; never flip the order to `REFUNDED` without confirming the money moved.

**Verify:** `payments_refund_failed_total` stops increasing; the affected orders leave `REFUND_PENDING`; `OrdersStuckConfirmed` clears. Post-mortem if it was a provider/config outage.
