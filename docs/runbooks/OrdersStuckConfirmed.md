# OrdersStuckConfirmed (ticket)

**Fires when:** `orders_stuck_confirmed > 0` for 15 minutes — one or more orders have sat in `CONFIRMED` (waiting for payment) or `REFUND_PENDING` (waiting for a refund) longer than the grace period (`orders.stuck.grace`, default 10m).

**Why this is a ticket, not an auto-fix:** unlike `FAILED`/`PENDING` (no money moved → the reconciler cancels them safely), a `CONFIRMED` order may have been **paid** already with the `PaymentCaptured` event merely delayed. Auto-cancelling would release stock and strand a paid order. A human decides.

## 1. Which orders, and where did the event stop?
```sh
kn exec postgres-0 -- psql -U postgres -d orders -c \
  "select order_ref, status, updated_at from orders where status in ('CONFIRMED','REFUND_PENDING') and updated_at < now() - interval '10 minutes' order by updated_at;"
kn get pods -l app.kubernetes.io/name=payment-service        # down? CrashLoop? restarting?
kn exec kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group payment-service   # lag?
kn exec kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group order-service     # payments.events lag?
```
| Finding | Cause | Fix |
|---|---|---|
| payment-service down / CrashLoop | it never consumed `OrderConfirmed` | fix payment-service (ServiceDown.md); orders settle once it catches up — **no manual cancel needed** |
| payment-service healthy, its `orders.events` lag high | it is behind, catching up | wait / scale payment-service to the partition count; verify orders move to PAID |
| payment row exists & CAPTURED but order still CONFIRMED | the `PaymentCaptured` event or order-service's consumer is stuck | check order-service's `payments.events` lag; look for a poison record in `payments.events.DLT` |
| payment says FAILED but order still CONFIRMED | `PaymentFailed` lost | same: order-service consumer / DLT |
| genuinely orphaned (payment-service has no row and never will) | upstream bug | **manual**: decide to cancel (release stock) per order; never a blanket script |

## 2. Verify
`orders_stuck_confirmed` returns to 0; the affected orders are PAID / CANCELLED / REFUNDED.

## 3. Prevent (action items)
Consumer-lag alerting on both groups; a dashboard panel for order age by status; consider a longer-horizon *orchestrated* saga timeout if this recurs.
