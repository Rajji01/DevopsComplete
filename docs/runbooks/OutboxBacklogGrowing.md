# OutboxBacklogGrowing (page)

**Fires when:** more than 100 events are waiting in a service's outbox for 5 minutes. Orders still work (the customer is never blocked by this), but notifications and payments are **delayed**: nobody is being charged.

## 1. Is Kafka reachable from the service?
```sh
kn logs deploy/<service> --since=5m | grep -iE 'outbox|kafka|TimeoutException|NotLeader' | tail
kn get pods -l app.kubernetes.io/name=kafka                   # dev; prod: MSK console / CloudWatch ActiveControllerCount
kn exec deploy/<service> -- sh -c 'nc -zv kafka 9092'         # (alpine image has nc)
```
| Finding | Fix |
|---|---|
| broker down / not enough in-sync replicas (`acks=all` waits) | fix Kafka; the backlog drains itself, nothing to replay |
| relay logs "could not publish" with auth errors (prod) | IRSA role / MSK IAM policy / `SPRING_PROFILES_ACTIVE=aws` missing |
| no relay logs at all | scheduler thread stuck: thread dump (`jcmd 1 Thread.print`), restart one pod |
| `outbox_unpublished` flat but high | relay running but a single poison row keeps failing: look at the first unpublished row's payload |

## 2. Verify
`outbox_unpublished` drops to 0; notification-service logs show the emails flowing; `payments_total` increasing again.
