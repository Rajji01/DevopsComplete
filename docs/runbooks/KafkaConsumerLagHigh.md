# KafkaConsumerLagHigh (ticket)

**Fires when:** a consumer's lag exceeds 1000 records for 10 minutes: it is falling behind the producers.

```sh
kn exec kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group <group>
kn get pods -l app.kubernetes.io/name=<consumer>              # fewer pods than partitions? CrashLoop?
kn logs deploy/<consumer> --since=10m | grep -E 'ERROR|DLT|Backoff' | tail
```
| Finding | Cause | Fix |
|---|---|---|
| lag grows on one partition only | poison record retried with backoff | it will go to the DLT after ~10 s; inspect `orders.events.DLT` |
| all partitions, consumer CPU high | consumer too slow / DB slow in the listener | scale pods **up to the partition count**; more pods than partitions idle |
| consumer restarting | OOM / bad release | rollback |
| lag after a big burst, dropping | normal catch-up | nothing; adjust alert threshold if it repeats |

Remember: at-least-once + idempotent consumers mean restarts and reprocessing are safe. Never reset offsets to "skip" lag without understanding what is skipped.
