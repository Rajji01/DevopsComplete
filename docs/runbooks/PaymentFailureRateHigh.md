# PaymentFailureRateHigh (page)

**Fires when:** more than half of payments failed over 10 minutes. Some declines are normal; this many means the gateway, our pricing, or the event contract broke.

```sh
kn logs deploy/payment-service --since=10m | grep -E 'payment for order|ERROR' | tail -20
kn exec postgres-0 -- psql -U postgres -d payments -c "select failure_reason, count(*) from payment where created_at > now() - interval '15 minutes' group by 1 order by 2 desc;"
```
| failure_reason | Cause | Fix |
|---|---|---|
| `card limit exceeded` for normal amounts | PriceList / quantity bug, or a bad `OrderConfirmed` payload | compare `amount` in events vs catalogue; rollback the producer if a deploy changed the contract |
| gateway timeouts / 5xx (real gateway) | provider outage | check provider status page; enable retry with idempotency key at the gateway; inform support |
| everything declined for one SKU | wrong price (₹0 or huge) | fix the price; replay the affected `OrderConfirmed` events from the DLT / outbox |

Every failed payment already cancelled its order and released the stock (saga compensation), so customers can retry; tell support what to tell them.
