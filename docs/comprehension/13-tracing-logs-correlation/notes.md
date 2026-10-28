# 13 · Distributed tracing & trace-log correlation — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `order-service application.yml` (observation/OTLP/ECS/sampling), `monitoring/grafana/.../datasources.yml`, compose (Tempo/Loki/Alloy/Grafana).

## Kya (one line)
Ek request ki poori cross-service journey = **trace** (spans ka set, ek **trace id** se judi); context HTTP `traceparent` + **Kafka headers** se travel karta (`observation-enabled`); logs ECS JSON mein `trace.id` carry karte, to Grafana mein trace↔logs dono taraf jump karke "kahan atka" pata chalta.

## Easy way (Hinglish analogy)
Courier tracking socho — ek **tracking number (trace id)** parcel ke saath har hub (service) se guzarta. Har hub ek **scan (span)** lagata "yahan aaya, itni der ruka". Parcel truck se plane (HTTP se Kafka) badle to tracking number saath jaana chahiye warna tracking **tut** jaati aur aage ka safar alag parcel lagta. **Log** = har hub ki diary jisme wahi tracking number likha — to ek complaint pe tum tracking number se poora safar + har hub ki diary ek saath dekh lete. Timestamp se jodna = andaaza; tracking number se jodna = pakka.

## Kyu (ye problem solve karta hai)
- **Choreography mein koi ek service poora flow nahi dekhta** (order→inventory HTTP, order→payment→order Kafka). "Kahan galt hua?" ka jawab sirf end-to-end trace deta.
- **Kafka boundary pe trace tut sakti** — async hop pe context na jaye to payment ka kaam alag trace lagta. `observation-enabled` ise jodta.
- **trace.id in logs** = precise join key (timestamp ambiguous: same-ms requests, clock drift, async-later steps).
- **Sampling** = scale pe har request trace karna mehenga; 10% patterns deta, error-biased se rare failures bhi.

## Kaise (step by step)
1. **Propagation**: trace id entry pe banta. HTTP → `traceparent` header. Kafka → message headers, kyunki `spring.kafka.template.observation-enabled:true` + `listener.observation-enabled:true`.
2. **Export**: spans OTLP → Tempo (`management.otlp.tracing.endpoint`). `management.tracing.sampling.probability` = `${TRACING_SAMPLE:1.0}` (prod ~0.1).
3. **Logs**: `LOGGING_STRUCTURED_FORMAT_CONSOLE: ecs` (JSON), Spring `trace.id`/`span.id` inject karta. Alloy → Loki.
4. **Correlation** (`datasources.yml`): Tempo `tracesToLogsV2` (`filterByTraceID`) → span se Loki logs. Loki `derivedFields` (`"trace.id":"(\w+)"`) → log se Tempo trace.

## Flow diagram (one order, one trace)
```
 POST /orders ──trace-id created──┐
   span: POST /orders             │
   span: HTTP reserve ──traceparent header──► inventory-service (span, same trace)
   span: kafka produce ──trace in message headers──► payment-service
                                                      span: consume + charge (same trace)
                                      ──headers──► order-service PaymentEventListener (span)
   all spans ──OTLP──► Tempo

 logs (ECS JSON, each line has "trace.id") ──Alloy──► Loki
 Grafana:  Loki log --derivedField--> Tempo trace     (logs → trace)
           Tempo span --tracesToLogs--> Loki lines     (trace → logs)
```

## Debugging-path diagram
```
 customer complaint (order id)
      │ Loki: {app} |= "<orderRef>"
      ▼
 log line (has trace.id) --derivedField--> Tempo trace
      │ inspect spans: reserve 1.9s? payment span missing?
      ▼ Tempo span --tracesToLogs--> that service's logs
 root cause (e.g. payment-service lag -> never consumed)
 (un-sampled? fall back to logs by orderRef + RED metrics)
```

## Alternate approaches (kab kaunsa)
| Choice | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Distributed tracing + log correlation** *(ShopFlow)* | trace id propagate, ECS logs, Tempo/Loki | end-to-end "kahan atka" | infra (Tempo/Loki/Alloy) | microservices |
| **Logs only (grep by orderRef)** | structured logs | simple | no latency breakdown, manual stitch | small systems |
| **Metrics only (RED)** | counters/histograms | cheap, alerting | "which request" nahi batata | always (complement) |
| **100% sampling** | trace everything | nothing missed | storage/cost high | low traffic/debug |
| **Tail/error-biased sampling** | 100% errors, 10% rest | rare failures caught, cheap | extra config | prod at scale |

## Understanding check
1. Trace kis cheez se judi rehti services ke paar?
2. Kafka hop pe trace kaise jaati, kaunsa config?
3. trace.id vs timestamp — correlate karne mein kyu behtar?
4. Prod 10% sampling kya trade karti, kab dikkat?
5. Hand-made KafkaTemplate se do alag trace kyu, fix?

## Interview mein ye bolo
*"Choreography mein koi ek service poora flow nahi dekhta, isliye distributed tracing. Trace id entry pe banta aur propagate hota — HTTP traceparent header se, Kafka message headers se (producer template + consumer listener dono observation-enabled, warna async boundary pe trace tut jaati aur payment ka kaam alag trace lagta). Spans OTLP se Tempo. Logs ECS JSON mein trace.id ke saath, Alloy→Loki. Grafana mein Tempo↔Loki dono taraf pivot (tracesToLogs + derivedFields), to ek complaint se log line → poori trace → root cause. trace.id precise join key hai, timestamp ambiguous. Prod 10% sample cost ke liye, par error/slow pe tail-sampling se rare failures bhi pakdo."*

> Related: [`../11-kafka-dlt-lag/`](../11-kafka-dlt-lag/) (async boundary), [`../06-slo-burn-rate-bug/`](../06-slo-burn-rate-bug/) (metrics/RED), [`../04-order-limbo-reconciler/`](../04-order-limbo-reconciler/) (stuck order debug).
</content>
