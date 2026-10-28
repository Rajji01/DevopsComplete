# 13 · Following one order across services: traces & logs — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `order-service application.yml` (`observation-enabled`, OTLP, ECS logs, sampling), `monitoring/grafana/.../datasources.yml` (Tempo↔Loki linking), `docker-compose.yml` (Tempo, Loki, Alloy, Grafana).

---

## Scenario (read cold)

> *Excerpt from ShopFlow's observability design. Read once, then answer.*

ShopFlow is choreographed: an order flows order-service → (HTTP) inventory-service, then order-service → (Kafka) payment-service → (Kafka) back to order-service. No single service sees the whole flow. So when one order misbehaves, "where did it go wrong?" is answered by **distributed tracing**.

A **trace** is the whole journey of one request, made of **spans** (one unit of work each: an HTTP call, a Kafka consume, a DB query). They're tied together by a **trace id** that is created at the entry point and then **propagated** downstream: across HTTP it rides in the standard `traceparent` header, and — crucially for this system — across **Kafka it rides in the message headers**. Spring is configured with `observation-enabled: true` on both the Kafka **template** (producer) and **listener** (consumer), so the trace context survives the hop through Kafka; without that, the trace would "break" at the async boundary and payment-service's work would look like an unrelated trace. Spans are exported via **OTLP** to **Tempo**.

Logs are **structured JSON** (`LOGGING_STRUCTURED_FORMAT_CONSOLE: ecs` — Elastic Common Schema), and Spring injects the current **`trace.id`** and `span.id` into every log line. Logs are shipped by **Alloy** to **Loki**.

The two are wired together in Grafana so you can pivot both directions:
- **Trace → logs:** Tempo's `tracesToLogsV2` (with `filterByTraceID`) — click a span and see exactly that trace's log lines in Loki.
- **Logs → trace:** Loki's `derivedFields` matches `"trace.id":"<id>"` in a log line and turns it into a link that opens the trace in Tempo.

So from a single customer complaint you can land on the log line, jump to the full trace, see that the `reserve` HTTP span took 1.9s and the payment span is missing, and know payment-service never consumed the event.

**Sampling** is tunable: `TRACING_SAMPLE` is `1.0` (100%) locally so you see everything, and typically `0.1` (10%) in production — because tracing every request at scale is expensive to store and process, and a 10% sample still catches patterns.

---

## Questions

### A — Literal
1. What is a trace made of, and what ties the pieces together across services?
2. How does the trace context travel across an HTTP call versus across Kafka, and what one config makes the Kafka hop work?
3. What are the two directions you can pivot between traces and logs in Grafana, and which component config enables each?

### B — Inference
4. If `observation-enabled` were **false** on the Kafka listener, what would a trace of an order look like, and why would that make debugging the saga harder?
5. A log line and a trace both exist for a failing order. Why is the **`trace.id` embedded in the log** the thing that makes them useful together, rather than just timestamps?

### C — Trade-off
6. Production samples at 10%, not 100%. What does sampling trade away, and why is 10% usually an acceptable trade?
7. Logs are structured **JSON (ECS)** rather than plain text. What does that cost (vs a human-readable line) and what does it enable that plain text can't?

### D — Transfer / extension
8. A customer complains their order is stuck. Describe the exact debugging path using this tooling, from their order id to the root cause, naming each pivot.
9. With 10% sampling, the **one** failing order you need to debug wasn't sampled — there's no trace. What are two ways to still investigate, and one config change that makes rare failures traceable without 100% sampling everywhere?

### E — Spot-the-flaw
10. A dev adds a manual `kafkaTemplate.send(...)` in a new feature using a **plain** `KafkaTemplate` they constructed themselves (not the Spring-managed, observation-enabled one), and later finds that orders which pass through this new step produce **two disconnected traces**. What broke, and what's the fix?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. A trace is made of **spans** (each one unit of work: an HTTP call, a Kafka consume, a DB query), tied together by a **trace id** created at the entry point and propagated to every downstream service.
2. Across **HTTP** it travels in the `traceparent` header; across **Kafka** it travels in the **message headers**. The config that makes the Kafka hop work is **`observation-enabled: true`** on the Kafka template (producer) and listener (consumer).
3. **Trace → logs** via Tempo's `tracesToLogsV2` (`filterByTraceID`), and **logs → trace** via Loki's `derivedFields` matching `"trace.id":"<id>"`. Tempo datasource config enables the first; Loki datasource config enables the second.
</details>

<details>
<summary>B — Inference</summary>

4. The trace would **break at the Kafka boundary**: order-service's spans (HTTP reserve, produce) would form one trace, and payment-service's consume/charge would appear as a **separate, unrelated trace** with a new trace id. Debugging the saga gets harder because you can no longer see the order's full journey end-to-end — you'd have to manually correlate two traces by `orderRef` and timing instead of following one linked trace.
5. The `trace.id` is a **precise, unique join key** between a log line and its trace (and across all services' logs for that one request). Timestamps are ambiguous — many requests happen in the same millisecond, clocks drift between hosts, and async steps happen much later — so correlating by time is guesswork. The embedded trace id lets you jump from *this* log line to *this exact* trace (and to every other service's logs for the same request) deterministically.
</details>

<details>
<summary>C — Trade-off</summary>

6. Sampling trades away **completeness**: at 10% you only store 1 in 10 traces, so any given request probably has no trace. It's acceptable because tracing **every** request at scale is expensive (storage, processing, network), and a 10% sample still reveals **patterns** — latency distributions, which span is slow, common error shapes — which is what tracing is mostly for. The cost shows up only when you need the trace for a *specific* un-sampled request.
7. **Cost:** JSON is harder for a human to eyeball in a raw terminal than a clean text line (more verbose, noisier). **Enables:** machine parsing — Loki/Elastic can **index and query by field** (`trace.id`, `status`, `application`, `level`), build the trace↔log links (the derived field matches a JSON field), and power dashboards/alerts. Plain text would need fragile regex parsing for all of that.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **(1)** Search Loki for the order id / `orderRef` → find the log line for that order. **(2)** The log line carries `trace.id`; click the Loki **derived field** → it opens the full **trace in Tempo**. **(3)** In the trace, inspect the spans: see the `reserve` HTTP span's duration, whether a payment-consume span exists, where an error/long gap is. **(4)** From a suspicious span, use Tempo's **traces-to-logs** to see that service's log lines for the trace. Root cause emerges (e.g. "reserve took 1.9s then payment span never appears → payment-service didn't consume → check its consumer lag"). Pivots: Loki→Tempo (derived field), Tempo→Loki (tracesToLogs).
9. **(1)** Fall back to the **structured logs** — even without a trace, the ECS logs across services share `orderRef`, so you can reconstruct the sequence by querying Loki for that `orderRef`. **(2)** Use **metrics** (orders_total by status, consumer lag, breaker state) to localize the failing stage. **Config change:** enable **tail-based / error-biased sampling** (sample 100% of traces that error or exceed a latency threshold, 10% of the rest) so rare failures are always captured without paying for 100% of the happy path.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. A hand-constructed `KafkaTemplate` is **not instrumented** — it doesn't inject the `traceparent`/trace context into the Kafka message headers, so the consumer on the other side starts a **fresh trace** instead of continuing the existing one → two disconnected traces. The Spring-managed `KafkaTemplate` works because it's **observation-enabled** (it propagates context). **Fix:** inject and use the Spring-managed, auto-configured `KafkaTemplate` (observation-enabled) rather than building one manually — so the producer span and header propagation happen automatically and the trace stays whole across the hop.
</details>

---

## Tod ke dekho — Local (docker compose + Grafana)

Goal: generate one order's trace and pivot between Tempo and Loki.

```bash
make up && TOKEN=$(make -s token)
# place an order (local sampling is 100%, so every request is traced)
ORDERREF=$(curl -s -X POST http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"sku":"PIXEL-9","quantity":1}' | jq -r .orderRef)
echo "orderRef=$ORDERREF"

# see the trace.id in structured (ECS JSON) logs:
docker compose -f shopflow/docker-compose.yml logs order-service | grep "$ORDERREF" | tail -3
#   -> JSON log lines containing "trace.id":"..."

# then in the browser (Grafana http://localhost:3000, admin/admin):
#   Explore -> Loki -> query: {service_name="order-service"} |= "<orderRef>"
#     -> click a line's `trace` derived field -> jumps to the trace in Tempo
#   In Tempo, open the trace -> see spans: POST /orders, reserve (HTTP to inventory), kafka produce,
#     payment-service consume+charge, back to order-service -> click a span -> "Logs for this span" (Loki)
```

**Proved:** one order = one trace across order→inventory (HTTP) and order→payment (Kafka); logs carry `trace.id`; Grafana pivots both ways. Break it: set `observation-enabled: false` on the Kafka listener, rebuild, and watch the payment hop become a *separate* trace.

## Tod ke dekho — Online
[`ONLINE-LABS.md`](../ONLINE-LABS.md) **section A (Codespaces)** — Tempo/Loki/Grafana compose mein; Grafana port forward karke Explore.

---

## Interview concepts is passage se
- **Trace / span / trace id** — ek request ki poori journey.
- **Context propagation** — `traceparent` (HTTP) + **Kafka headers** (async); `observation-enabled`.
- **Traces ↔ logs correlation** — `trace.id` in ECS JSON; Tempo tracesToLogs + Loki derivedFields.
- **Structured logging (ECS JSON)** — field-indexed, machine-queryable.
- **Sampling** — cost vs completeness; tail/error-biased sampling for rare failures.
- **Choreography needs tracing** — koi ek service poora flow nahi dekhta.
- **RED + traces + logs** — teeno milke "kahan atka" answer karte.

**Interviewer's lens:** "async (Kafka) ke paar trace kaise judi rehti" (headers + observation-enabled) aur "trace.id vs timestamp se correlate" — ye observability-round ke core hain. Sampling trade-off bhi.

> Interview line: *"Choreography mein koi ek service poora flow nahi dekhta, isliye distributed tracing. Trace id entry pe banta aur downstream propagate hota — HTTP mein traceparent header, Kafka mein message headers (producer template aur consumer listener dono observation-enabled, warna async boundary pe trace tut jaati). Spans OTLP se Tempo mein. Logs ECS JSON mein trace.id ke saath, Alloy→Loki. Grafana mein Tempo↔Loki dono taraf pivot (tracesToLogs + derivedFields), to ek complaint se log line → poori trace → root cause. Prod 10% sample (cost), par error/slow pe tail-sampling se rare failures bhi pakad lo."*
</content>
