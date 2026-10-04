# 07 — Observability & SRE (Interview Notes)

> Source of truth in this repo:
> - `shopflow/monitoring/prometheus.yml` — scrape config (job `shopflow`, path `/actuator/prometheus`, 15s, three targets)
> - `shopflow/monitoring/alert-rules.yml` — `HighErrorRate`, `HighP99Latency`, `InventoryCircuitOpen`, `ServiceDown`, `OutboxBacklogGrowing`, `KafkaConsumerLagHigh`
> - `shopflow/monitoring/grafana/provisioning/datasources/datasources.yml` — Prometheus, Tempo and Loki datasources as code, with traces↔logs links
> - `shopflow/monitoring/tempo.yml` (OTLP receivers 4318/4317, local storage), `shopflow/monitoring/alloy.river` (tails Docker container logs → Loki)
> - `shopflow/docker-compose.yml` — runs Prometheus `v3.5.0`, Grafana `12.1.1`, Tempo `2.8.1`, Loki `3.5.3`, Alloy `v1.10.0` locally
> - `shopflow/pom.xml` — `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` for every service; `*/application.yml` — Actuator, Micrometer, `management.tracing.*`, `management.otlp.tracing.endpoint`
> - `shopflow/k8s/base/*-service/deployment.yaml` — `prometheus.io/*` annotations, `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, `OTLP_ENDPOINT` from the `shopflow-endpoints` ConfigMap
> - Custom metrics in code: `orders` counter with tag `status` (`OrderService`), `inventory.reservations.rejected` counter (`ReservationService`), `outbox.unpublished` gauge (`OutboxRelay`), `notifications.sent` / `notifications.duplicates` counters (`OrderEventListener`)
>
> Not in the repo (say so honestly): Alertmanager, dashboards JSON, SLO definitions, exemplars. Tracing and the Loki stack are covered in depth in [11-security-caching-performance.md](11-security-caching-performance.md) Part D.

---

## 0. Cheat table

| Item | ShopFlow |
|---|---|
| Metrics lib | Micrometer + `micrometer-registry-prometheus` → `/actuator/prometheus` |
| Exposed endpoints | `management.endpoints.web.exposure.include: health,info,prometheus` |
| Common tag | `management.metrics.tags.application: ${spring.application.name}` |
| Histograms | `management.metrics.distribution.percentiles-histogram.http.server.requests: true` → `_bucket` series for `histogram_quantile` |
| Scrape | `scrape_interval: 15s`, targets `order-service:8081`, `inventory-service:8082`, `notification-service:8083` |
| Alert severities | `page` (HighErrorRate, InventoryCircuitOpen, ServiceDown, OutboxBacklogGrowing) / `ticket` (HighP99Latency, KafkaConsumerLagHigh) |
| Logs | ECS JSON on stdout (`LOGGING_STRUCTURED_FORMAT_CONSOLE: ecs` in K8s **and** compose) → Alloy → Loki; fields `trace.id`/`span.id` link to Tempo |
| Traces | Micrometer Tracing → OTel → OTLP/HTTP `:4318` → Tempo; sampling `${TRACING_SAMPLE:1.0}`; Kafka hops via `observation-enabled: true` |
| Health | `/actuator/health/liveness`, `/actuator/health/readiness` (readiness = `readinessState,db`) |
| Version info | `/actuator/info` (Maven `build-info` goal) |

---

## 1. Monitoring vs observability; the three pillars

- **Monitoring**: watching known failure modes with predefined dashboards/alerts ("is CPU > 80%?").
- **Observability**: ability to ask *new* questions about system internals from its outputs, without shipping new code ("why are only orders for SKU X slow from 14:02?").

| Pillar | What | Good for | Cost/limits | Tools |
|---|---|---|---|---|
| **Metrics** | Numeric time series with labels | Alerting, trends, dashboards, SLOs | Cheap; high-cardinality labels explode storage | Prometheus, Micrometer, CloudWatch, Datadog |
| **Logs** | Timestamped events (ideally structured JSON) | Details of a specific error/request | Expensive at volume | Loki, ELK/OpenSearch, CloudWatch Logs |
| **Traces** | A request's path across services as spans | Where latency/errors happen in a distributed call | Sampling needed | OpenTelemetry, Jaeger, Tempo, X-Ray, Zipkin |

Correlation is the superpower: alert (metric) → exemplar/trace ID → logs filtered by `trace.id`.
(Profiles — continuous profiling, e.g. Pyroscope — are sometimes called the 4th pillar.)

---

## 2. Structured JSON logging (ECS)

ShopFlow deployments set:
```yaml
- name: LOGGING_STRUCTURED_FORMAT_CONSOLE
  value: ecs                 # JSON logs for Loki / ELK
```
That's Spring Boot's built-in structured logging (Boot 3.4+), bound from env var to `logging.structured.format.console=ecs`. ECS = Elastic Common Schema. It is set in K8s and, since the Loki stack was added, also in compose (`x-service-env`), so Alloy ships one JSON object per line and Loki can filter on `trace.id` (illustrative — exact field layout, dotted vs nested, depends on the Boot version). Running a service bare with `./mvnw spring-boot:run` still gives human-readable logs:
```json
{"@timestamp":"2026-10-01T09:12:44.120Z","log.level":"WARN","process.thread.name":"http-nio-8081-exec-3",
 "service.name":"order-service","log.logger":"com.shopflow.order.order.OrderService",
 "message":"inventory call failed, order FAILED","ecs.version":"8.11"}
```
Why JSON:
- Fields are queryable (`log.level="ERROR" AND service.name="order-service"`) without fragile regex.
- Multi-line stack traces stay in one event.
- Easy to add MDC context: `orderRef`, `traceId`, `userId` (not PII!).

12-factor rule: **log to stdout**, let the platform collect (the container runtime writes stdout to `/var/log/pods/...` on the node, symlinked as `/var/log/containers/*.log`; a DaemonSet agent ships it). Don't write log files inside the container (ShopFlow even has `readOnlyRootFilesystem: true`).

Log levels: ERROR (needs attention), WARN (unexpected but handled — e.g. a circuit open), INFO (business events), DEBUG (off in prod; switchable via `/actuator/loggers` if exposed). Never log secrets, passwords, tokens, full card numbers.

---

## 3. Prometheus

### Architecture
```
   ┌───────────────┐  pull every 15s   ┌──────────────────────────┐
   │ order-service │◀──────────────────│      Prometheus          │──▶ Grafana (PromQL queries)
   │ /actuator/    │  GET /actuator/   │  TSDB (local disk)       │
   │  prometheus   │  prometheus       │  rule eval every 15s     │──▶ Alertmanager ─▶ PagerDuty/
   └───────────────┘                   │  (alert-rules.yml)       │    (route by     Slack/email
   ┌───────────────┐◀──────────────────│                          │     severity)
   │ inventory-svc │                   └──────────────────────────┘
   └───────────────┘     service discovery: static (compose) / kubernetes_sd (K8s)
```
- **Pull model**: Prometheus scrapes HTTP endpoints. Pros: knows when a target is down (`up == 0`), targets don't need to know Prometheus. Short-lived jobs use **Pushgateway**.
- `prometheus.yml` in compose uses `static_configs` with DNS names. In K8s you use `kubernetes_sd_configs` + relabeling on the `prometheus.io/scrape|path|port` pod annotations (ShopFlow's Deployments have them), or with **Prometheus Operator** (kube-prometheus-stack) a `ServiceMonitor`/`PodMonitor` CRD — the Operator ignores annotations by default. NetworkPolicies already allow the `monitoring` namespace to reach 8081/8082.
- Single Prometheus = single node, local storage, 15d retention by default (`--storage.tsdb.retention.time`). Long-term/HA: Thanos, Cortex/Mimir, VictoriaMetrics, Amazon Managed Prometheus.

### Data model
```
http_server_requests_seconds_count{application="order-service", method="POST", uri="/api/v1/orders",
                                   status="201", outcome="SUCCESS", exception="none"}  1532 @ t
└─────────── metric name ────────┘└──────────────────────── labels ───────────────────────┘ value
```
Each unique name+label set = one **time series**. **Cardinality** = number of series. Never put unbounded values (user IDs, order IDs, raw URLs with IDs) in labels. Micrometer uses the URI *template* (`/api/v1/orders/{id}`) — good.

### Metric types

| Type | Behaviour | Example in ShopFlow | Query with |
|---|---|---|---|
| **Counter** | Only goes up (resets on restart) | `http_server_requests_seconds_count`, `orders_total{status}`, `inventory_reservations_rejected_total` | `rate()` / `increase()` — never raw |
| **Gauge** | Up and down | `jvm_memory_used_bytes`, `hikaricp_connections_active`, `resilience4j_circuitbreaker_state` | raw, `avg_over_time` |
| **Histogram** | Counts observations in cumulative buckets `le=` + `_sum` + `_count` | `http_server_requests_seconds_bucket` (enabled by `percentiles-histogram`) | `histogram_quantile()` |
| **Summary** | Client-side quantiles | Micrometer `percentiles:` option | can't aggregate across instances! |

Micrometer naming: `orders` counter → `orders_total`; `inventory.reservations.rejected` → `inventory_reservations_rejected_total`; timers get `_seconds` suffix.

---

## 4. PromQL — explaining ShopFlow's alert rules

### Building blocks
```promql
http_server_requests_seconds_count                       # instant vector (latest value per series)
http_server_requests_seconds_count[5m]                   # range vector (samples in last 5m)
rate(http_server_requests_seconds_count[5m])             # per-second avg increase over 5m, handles counter resets
increase(orders_total[1h])                               # total increase over 1h (= rate × 3600)
sum by (application) (rate(...[5m]))                     # aggregate, keep only label "application"
sum without (instance, pod) (...)                        # aggregate, drop these labels
```
Rule of thumb: **rate first, then sum** (`sum(rate(x[5m]))`, never `rate(sum(x))` — summing counters breaks reset detection). Range window ≥ 4× scrape interval (15s → `[1m]` minimum; ShopFlow uses `[5m]` for smoothness).

`rate` vs `irate`: `rate` = average over window (alerts, dashboards); `irate` = last two samples (spiky, zoomed-in graphs only).

### Rule 1 — `HighErrorRate` (page)
```promql
sum by (application) (rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
  / sum by (application) (rate(http_server_requests_seconds_count[5m])) > 0.05
for: 5m
```
Read it as:
1. `rate(..._count{status=~"5.."}[5m])` — per-second rate of 5xx responses, per series (per pod, uri, method...).
2. `sum by (application)` — add up all pods/URIs → one number per service.
3. Divide by the total request rate per service → **error ratio** (0.0–1.0). Both sides keep only `application` label, so they match 1:1.
4. `> 0.05` — more than 5% failing.
5. `for: 5m` — must be true for 5 consecutive minutes (pending → firing) → filters blips.
It's a **symptom** (users see errors) → page. A ratio (not an absolute count) works at any traffic level. Edge: at near-zero traffic, 1 error out of 2 requests = 50% → for low-traffic services add a minimum-traffic condition (`and sum by (application)(rate(...[5m])) > 0.1`).

### Rule 2 — `HighP99Latency` (ticket)
```promql
histogram_quantile(0.99,
  sum by (application, le) (rate(http_server_requests_seconds_bucket{uri!~"/actuator.*"}[5m]))) > 1
for: 10m
```
1. `_bucket{le="0.1"}`, `{le="0.5"}`... cumulative counters: how many requests took ≤ le seconds. Exists only because `percentiles-histogram.http.server.requests: true`.
2. `uri!~"/actuator.*"` — exclude health/metrics scrapes (fast, frequent, would hide real latency).
3. `rate(...[5m])` → per-second rate per bucket.
4. `sum by (application, le)` — aggregate across pods **but keep `le`** — `histogram_quantile` needs the bucket boundaries. Forgetting `le` is the #1 PromQL bug.
5. `histogram_quantile(0.99, ...)` → estimated 99th percentile in seconds (linear interpolation within the bucket → approximate).
6. `> 1` for 10 min → ticket, not page: slow but working; fix during business hours.
Why p99 not average: averages hide the tail — 1% of users waiting 5s is invisible in a 120ms average.

### Rule 3 — `InventoryCircuitOpen` (page)
```promql
sum by (application) (rate(resilience4j_circuitbreaker_not_permitted_calls_total{name="inventory"}[5m])) > 0
for: 2m
```
`not_permitted_calls_total` counts calls the breaker rejected without calling inventory (Resilience4j also exports a `..._state` gauge per state: `closed`, `open`, `half_open`, with value 1 for the current one). Config in order-service: opens when ≥ 50% of the last 10 calls failed (min 5 calls), stays open 10s, then half-open with 2 trial calls. If it has been rejecting calls for two full minutes, order placement is failing fast → page. It's a **cause** signal, but an early, high-confidence one (the file's header: *"Alerts on symptoms users feel (errors, latency), plus the circuit breaker as an early cause signal"*).
Why not simply `resilience4j_circuitbreaker_state{state="open"} == 1`? With `wait-duration-in-open-state: 10s` + automatic transition, a failing breaker cycles open → half_open → open. A rule evaluation that lands on `half_open` resets the `for:` timer, so that alert flaps or never fires. `not_permitted_calls_total` is a counter that keeps rising for as long as the breaker rejects calls, so `rate(...) > 0 for 2m` is stable. Alternative: `max_over_time(..._state{state="open"}[2m]) == 1`.

### Rule 4 — `ServiceDown` (page)
```promql
up{job="shopflow"} == 0
for: 2m
```
`up` is synthesized by Prometheus per target: 1 = last scrape succeeded, 0 = failed. Catches crashed service, wrong port, network/NetworkPolicy blocking monitoring. Note: in K8s with service discovery, the `job` label depends on your scrape config — keep it `shopflow` or the rule silently never fires. Also add `absent(up{job="shopflow"})` to catch "no targets at all".

### Rule 5 — `OutboxBacklogGrowing` (page)
```promql
max by (application) (outbox_unpublished) > 100
for: 5m
```
`outbox_unpublished` is a Micrometer **gauge** registered in `OutboxRelay` (`meterRegistry.gauge("outbox.unpublished", repo, OutboxRepository::countByPublishedAtIsNull)`) — events written to the DB but not yet acknowledged by Kafka. `max by (application)` because every order-service pod reports the same table count (they share the DB); summing would multiply it by the pod count. Growing backlog = Kafka unreachable or the relay stuck, while users still get 201 — this is the one alert that sees a broker outage from the producer side. Runbook: relay logs ("could not publish outbox event … will retry"), broker health, `KAFKA_BOOTSTRAP_SERVERS`.

### Rule 6 — `KafkaConsumerLagHigh` (ticket)
```promql
sum by (application) (spring_kafka_listener_records_lag_max) > 1000
for: 10m
```
Lag = latest offset − committed offset: notification-service is falling behind the producers. Ticket, not page — nothing is lost (retention), it is a throughput problem (pods ≤ partitions, slow processing, poison-pill retry loop). **Verify the series name against the real `/actuator/prometheus` output**: Micrometer's Kafka client binder exposes consumer lag as `kafka_consumer_fetch_manager_records_lag_max{client_id=...}`; `spring_kafka_listener_*` are the listener observation timers. If the rule's metric does not exist the alert never fires — which is exactly why `promtool check rules` (syntax) is not enough; add an `absent()` rule or a unit test with `promtool test rules` using the real metric name. Saying this in an interview shows you validate alerts, not just write them.

### More useful queries (for Grafana)
```promql
# RPS per service
sum by (application) (rate(http_server_requests_seconds_count[1m]))
# Error ratio per endpoint
sum by (uri) (rate(http_server_requests_seconds_count{application="order-service",status=~"5.."}[5m]))
  / sum by (uri) (rate(http_server_requests_seconds_count{application="order-service"}[5m]))
# Orders by outcome (custom counter)
sum by (status) (increase(orders_total[1h]))
# Inventory rejections per minute
rate(inventory_reservations_rejected_total[5m]) * 60
# Hikari pool saturation
hikaricp_connections_active / hikaricp_connections_max
hikaricp_connections_pending > 0
# JVM heap usage %
sum by (pod) (jvm_memory_used_bytes{area="heap"}) / sum by (pod) (jvm_memory_max_bytes{area="heap"})
# GC pause time per second
rate(jvm_gc_pause_seconds_sum[5m])
# Pod restarts (needs kube-state-metrics)
increase(kube_pod_container_status_restarts_total{namespace="shopflow"}[15m]) > 0
# CPU throttling (cAdvisor)
rate(container_cpu_cfs_throttled_periods_total[5m]) / rate(container_cpu_cfs_periods_total[5m])
```

### Validating rules
CI: `promtool check rules /m/alert-rules.yml` (in `validate-manifests` job). Locally `promtool test rules` allows unit tests for alerts with synthetic series.

---

## 5. Grafana

- Visualization layer; queries Prometheus (and Loki, Tempo, CloudWatch...).
- ShopFlow provisions the datasource as code: `monitoring/grafana/provisioning/datasources/prometheus.yml` (`url: http://prometheus:9090`, `isDefault: true`), mounted read-only into `/etc/grafana/provisioning`. Admin password from env `GF_SECURITY_ADMIN_PASSWORD` (`admin` locally only).
- Next step: provision dashboards as JSON too (or import community "JVM (Micrometer)" and "Spring Boot Statistics" dashboards); variables (`$application`, `$pod`); one RED dashboard per service.
- Grafana can also alert, but keep alert rules in Prometheus files under version control (like ShopFlow) so they are code-reviewed and tested by `promtool`.

---

## 6. Alerting philosophy

| Principle | Meaning | ShopFlow |
|---|---|---|
| **Alert on symptoms, not causes** | Page on what users feel (errors, latency, unavailability); causes (CPU, memory) go on dashboards | `HighErrorRate`, `HighP99Latency`, `ServiceDown` |
| Exception: high-confidence early causes | A cause that always means user impact soon | `InventoryCircuitOpen`, `OutboxBacklogGrowing` (notifications silently delayed; HTTP looks healthy) |
| Async pipelines need their own symptom | HTTP metrics cannot see a consumer falling behind | `KafkaConsumerLagHigh` (ticket) |
| **Page vs ticket** | Page = wake a human now, urgent + actionable; ticket = fix in working hours | `severity: page` vs `severity: ticket` labels |
| Every page actionable | If the response is "ignore it", delete/demote the alert | — |
| `for:` duration | Avoid flapping on blips | 1m–10m |
| Runbooks | Each alert links to "what to check / how to mitigate" | Add `runbook_url` annotation (next step) |
| Avoid alert fatigue | Too many pages → people ignore real ones | Only 6 rules, 4 of them pages |

Routing by severity happens in **Alertmanager** (not in the repo):
```yaml
route:
  receiver: slack-tickets
  group_by: [alertname, application]
  routes:
    - matchers: [severity="page"]
      receiver: pagerduty-oncall
receivers:
  - name: pagerduty-oncall
    pagerduty_configs: [{ routing_key: <secret> }]
  - name: slack-tickets
    slack_configs: [{ channel: '#shopflow-alerts' }]
```
Alertmanager also does **grouping** (one notification for 10 pods), **inhibition** (suppress `HighErrorRate` on order-service while `ServiceDown` on inventory fires), and **silences** (planned maintenance).

Also needed: an alert that the monitoring itself works — a "Watchdog"/dead-man's-switch alert that always fires; if it stops arriving, Prometheus/Alertmanager is broken.

---

## 7. RED, USE, Golden Signals

| Method | For | Signals | ShopFlow metrics |
|---|---|---|---|
| **RED** (Tom Wilkie) | Request-driven services | **R**ate, **E**rrors, **D**uration | `http_server_requests_seconds_count`, `{status=~"5.."}`, `_bucket` |
| **USE** (Brendan Gregg) | Resources (CPU, memory, disk, pools) | **U**tilization, **S**aturation, **E**rrors | Hikari active/max (U), `hikaricp_connections_pending` (S), connection timeouts (E); CPU throttling; heap |
| **4 Golden Signals** (Google SRE) | User-facing systems | Latency, Traffic, Errors, Saturation | RED + saturation (pool, heap, CPU) |

Rule: RED on every service dashboard top row; USE for the resources behind it (DB pool, JVM, nodes, Postgres).

---

## 8. SLI, SLO, SLA, error budgets

| Term | Definition | Example for ShopFlow |
|---|---|---|
| **SLI** (indicator) | A measured ratio of good events / valid events | `non-5xx order requests / all order requests`; `requests faster than 500ms / all` |
| **SLO** (objective) | Target for an SLI over a window | 99.5% of `POST /api/v1/orders` succeed over 30 days; 99% under 500ms |
| **SLA** (agreement) | Contract with customers, with penalties; looser than SLO | 99.0% monthly availability or service credits |
| **Error budget** | `1 − SLO` — allowed unreliability | 0.5% of requests may fail ≈ 3.6h of full outage per 30 days |

Availability math: 99.9% / 30d = 43.2 min downtime; 99.95% = 21.6 min; 99.99% = 4.3 min. Each extra nine ≈ 10× more effort/cost.

**Error budget policy**: budget left → ship features fast; budget burned → freeze risky releases, prioritize reliability work. It turns reliability into a shared, data-driven decision between dev and ops.

SLI in PromQL (availability, 30d):
```promql
1 - (
  sum(increase(http_server_requests_seconds_count{application="order-service",status=~"5..",uri!~"/actuator.*"}[30d]))
  / sum(increase(http_server_requests_seconds_count{application="order-service",uri!~"/actuator.*"}[30d]))
)
```
**Burn-rate alerting** (better than fixed thresholds): burn rate = error ratio / (1 − SLO). For a 99.5% SLO, ShopFlow's 5% threshold = burn rate 10 (30-day budget gone in 3 days). Google SRE Workbook multi-window approach (30-day SLO): page if burn rate > 14.4 over 1h **and** 5m (2% of budget in 1h — for 99.5% that's an error ratio > 7.2%); page if > 6 over 6h and 30m (5% of budget); ticket if > 1 over 3d and 6h (10% of budget). The short window makes the alert reset quickly after recovery. Tools: Sloth, Pyrra generate these rules.

> Tip: SLO hamesha user-centric ho — "CPU < 70%" SLO nahi hai, wo sirf metric hai.

---

## 9. Distributed tracing with OpenTelemetry (implemented: Micrometer Tracing → OTLP → Tempo)

Concepts:
```
Trace 4bf92f... (one POST /api/v1/orders)
├─ span: order-service  POST /api/v1/orders            320ms
│  ├─ span: SELECT orders (JDBC)                         4ms
│  ├─ span: HTTP POST inventory-service /api/v1/reservations  290ms   ← retry #1 failed, #2 ok
│  │   └─ span: inventory-service POST /api/v1/reservations 270ms
│  │       └─ span: UPDATE products ... (JDBC)          260ms   ← slow query, lock wait
│  └─ span: INSERT orders                                6ms
```
- **Trace** = tree of **spans**; each span has trace ID, span ID, parent ID, timing, attributes, status.
- **Context propagation**: W3C `traceparent` header passed on every outgoing call (RestClient) so inventory's spans join the same trace; for Kafka the same header rides in the record headers (`spring.kafka.template.observation-enabled` / `listener.observation-enabled: true`), so the outbox relay's producer span and notification-service's consumer span share the trace that began with the HTTP request.
- **Sampling**: head-based (decide at start, e.g. 10%) or tail-based in the Collector (keep all errors/slow traces).

How ShopFlow does it (option 1) and the alternative (option 2):
1. **Micrometer Tracing** (Spring-native, **what is in the repo**): parent `pom.xml` adds `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` to every service; `management.tracing.sampling.probability: ${TRACING_SAMPLE:1.0}` ("100% locally; 0.1 is typical in prod") and `management.otlp.tracing.endpoint: ${OTLP_ENDPOINT:http://localhost:4318/v1/traces}` — compose sets `OTLP_ENDPOINT=http://tempo:4318/v1/traces`, K8s reads it from the `shopflow-endpoints` ConfigMap (`http://tempo.monitoring:4318/v1/traces`; prod: `otel-collector.monitoring`). Tests disable export (`management.otlp.tracing.export.enabled: false`). The auto-configured `RestClient.Builder` propagates context automatically (`InventoryClient` is built from it); trace/span IDs appear in the ECS log fields for correlation. Exemplars (histogram bucket → trace) are **not** enabled yet.
2. **OpenTelemetry Java agent** (zero code): `JAVA_TOOL_OPTIONS="-javaagent:/otel/opentelemetry-javaagent.jar ..."`, `OTEL_SERVICE_NAME=order-service`, `OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318` (agent 2.x defaults to OTLP http/protobuf on 4318; 4317 is gRPC). Auto-instruments Spring MVC, JDBC, HTTP clients, Kafka. Note ShopFlow already uses `JAVA_TOOL_OPTIONS` for heap flags — you'd append to it. Trade-off vs Micrometer: no code/deps, but startup cost, less control, and metrics/traces come from different instrumentation.

Pipeline: apps → (optionally an **OTel Collector**: batching, tail sampling, attribute scrubbing) → backend. Locally ShopFlow sends straight to **Tempo** (`monitoring/tempo.yml`: OTLP http `0.0.0.0:4318` + grpc `4317`, `backend: local`, blocks + WAL under `/var/tempo`, volume `tempodata`); prod would go through ADOT/OTel Collector to X-Ray or Tempo. The NetworkPolicy *egress* rules already allow 4318 to the `monitoring` namespace for all three services.

Grafana wiring (`datasources.yml`): Tempo datasource with `tracesToLogsV2` (`datasourceUid: loki`, `filterByTraceID: true`) — click a span, see that trace's log lines; Loki datasource with a `derivedFields` regex `"trace\.id":"(\w+)"` → `datasourceUid: tempo` — click a trace id in a log line, open the trace. That is the "metrics → trace → logs" loop from §1 made concrete.

---

## 10. Log aggregation: Loki vs ELK

| | **Loki** (+ Promtail/Alloy/Fluent Bit) | **ELK / EFK** (Elasticsearch + Logstash/Fluentd + Kibana) |
|---|---|---|
| Indexing | Only labels (namespace, app, pod); content scanned at query time | Full-text index of every field |
| Cost | Cheap storage (S3), low RAM | Heavy (JVM, disks, shards) |
| Query | LogQL, Prometheus-like labels | KQL/Lucene, powerful full-text & aggregations |
| UI | Grafana (same place as metrics) | Kibana |
| Best for | K8s logs, correlation with Prometheus | Search-heavy, analytics, security (SIEM) |

Kubernetes collection pattern: **DaemonSet** agent on each node tails `/var/log/containers/*.log`, adds K8s metadata (namespace, pod, labels), ships to backend. ShopFlow's compose equivalent is **Grafana Alloy** (`monitoring/alloy.river`): `discovery.docker` over `/var/run/docker.sock` → `discovery.relabel` copies the compose service label into a `service` label → `loki.source.docker` → `loki.write` to `http://loki:3100/loki/api/v1/push`. In K8s the same Alloy config would use `discovery.kubernetes` as a DaemonSet. Because ShopFlow emits ECS JSON, the agent can parse fields directly (`| json` in LogQL):
```logql
{namespace="shopflow", app="order-service"} | json | log_level="ERROR"
sum by (app) (count_over_time({namespace="shopflow"} | json | log_level="ERROR" [5m]))
{service="notification-service"} | json | message=~"EMAIL.*"                 # compose: label from Alloy relabel
{service=~".*-service"} | json | trace_id="4bf92f3577b34da6a3ce929d0e0e4736"   # every service's lines for one trace
```
AWS option: Fluent Bit → CloudWatch Logs (Container Insights) or OpenSearch.

Retention & cost: keep hot logs 7–30 days, archive to S3; sample DEBUG/INFO noisy logs; drop health-check access logs.

---

## 11. Incident response & postmortems

### Incident lifecycle
```
Detect (alert / user report) → Triage (severity, assign Incident Commander)
  → Mitigate (rollback, scale, failover, feature flag off)   ← restore service FIRST
  → Communicate (status page, stakeholders, regular updates)
  → Resolve → Postmortem → Action items tracked to completion
```
Roles: **Incident Commander** (coordinates, decides), **Ops/Tech lead** (hands on keyboard), **Communications lead**, scribe. Severity levels (SEV1 = full outage/data loss … SEV4 = minor).

Key metrics: **MTTD** (detect), **MTTA** (acknowledge), **MTTR** (restore), MTBF.

### Blameless postmortem template
1. Summary & impact (duration, % users, orders failed, SLO budget consumed)
2. Timeline (UTC) — detection, actions, recovery
3. Root cause(s) & contributing factors (5 whys) — systems and processes, not people
4. What went well / what went badly / where we got lucky
5. Action items: owner + due date (prevent, detect faster, mitigate faster)

---

## 12. On-call scenarios (practice these out loud)

### S1. "p99 latency alert on order-service at 2 AM"
1. Scope: all endpoints or one? (`sum by (uri)` latency). One pod or all? (`by (pod)`).
2. Recent change? Deploy in last hour (`kubectl rollout history`, Argo CD history) → rollback first if correlated.
3. Downstream: order-service calls inventory with `read-timeout: 2s` and Resilience4j `max-attempts: 3` (= 1 call + 2 retries, waits 200ms → 400ms) → worst case ≈ 3 × 2s + 0.6s ≈ 6.6s, so one slow inventory multiplies order p99. Check inventory's own latency and circuit breaker state.
4. Saturation: Hikari pending (`hikaricp_connections_pending`), GC pauses (`jvm_gc_pause_seconds`), CPU throttling, pod count vs HPA max (`kubectl get hpa`).
5. DB: slow queries (`pg_stat_activity`, locks on `products` rows).
6. Mitigate: rollback / scale out / raise HPA max / shed load; then root cause.

### S2. "5xx spike — HighErrorRate firing"
1. Which service and which status? 500 (bug), 502/504 (ingress can't reach pods/timeouts), 503 (ShopFlow returns 503 ProblemDetail when inventory is unavailable / circuit open).
2. Logs: `{namespace="shopflow"} | json | log_level="ERROR"` → exception type.
3. If 503 from order-service + `InventoryCircuitOpen` → inventory is the real problem; investigate there (inhibition rule would hide the duplicate page).
4. If only during deploys → readiness/graceful shutdown issue (see [05-kubernetes.md §12](05-kubernetes.md#12-graceful-shutdown-sequence)).
5. Mitigate: rollback if deploy-related; scale; fix dependency.

### S3. "Pods restarting repeatedly"
```bash
kubectl get pods -n shopflow                       # RESTARTS column
kubectl describe pod <p> -n shopflow               # Last State: OOMKilled / Error, exit code; probe failure events
kubectl logs <p> -n shopflow --previous
```
- OOMKilled (137) → memory limit 512Mi vs heap 75%; check off-heap; heap dump; leak after recent deploy?
- Exit 3 → Java heap OOM (`ExitOnOutOfMemoryError`).
- Liveness failures under load → GC pauses or thread starvation; liveness `timeoutSeconds` too low; never have liveness depend on DB.
- Startup probe failures → slow startup (CPU throttling, long Flyway migration).
- Metric: `increase(kube_pod_container_status_restarts_total{namespace="shopflow"}[15m]) > 0`.

### S4. "DB connection pool exhausted" (`HikariPool-1 - Connection is not available, request timed out after 30000ms`)
Signals: `hikaricp_connections_active == hikaricp_connections_max` (max = `DB_POOL_SIZE`, default 10), `hikaricp_connections_pending > 0`, latency up, then 5xx.
Causes:
- Slow queries / lock contention holding connections longer.
- Long transactions or holding a connection during a remote call (ShopFlow's `OrderService.placeOrder` deliberately has **no @Transactional** around the inventory HTTP call — a good design point; `open-in-view: false` also prevents holding connections through view rendering).
- Connection leak (unclosed connections) → `leak-detection-threshold`.
- Traffic spike + HPA scaling: **pods × pool size** must stay below Postgres `max_connections` (100 by default). ShopFlow: `DB_POOL_SIZE=5` in the Deployments and `max_connections=200` on the StatefulSet → 2 services × 10 pods × 5 = 100 worst case. At real scale: PgBouncer in front of Postgres.
Mitigate: kill long-running queries, rollback bad deploy, temporarily scale DB; don't just blindly raise pool size (more connections can make the DB slower).

### S5. "Disk full on a node / Prometheus"
`df -h`, `du -sh /var/lib/* | sort -h`; container logs, images (`crictl rmi --prune`), emptyDir usage. For Prometheus: retention (`--storage.tsdb.retention.time`), high-cardinality metrics (`topk(10, count by (__name__)({__name__=~".+"}))`).

### S6. "No alerts fired but users complained"
Monitoring gap: missing symptom alert, `job` label mismatch (`ServiceDown` uses `job="shopflow"`), `absent()` not covered, Alertmanager down (need Watchdog), thresholds too loose, low traffic hiding ratios. Fix in postmortem action items.

---

## 13. Interview Q&A

**Q1. Logs vs metrics vs traces — when do you use which?**
Metrics for alerting and trends (cheap, aggregated); logs for the details of a specific event; traces to see where time/errors go across services. Alert from metrics, jump to a trace, then to logs with that trace ID.

**Q2. How does your app expose metrics?**
Spring Boot Actuator with the Micrometer Prometheus registry at `/actuator/prometheus`. I tag every metric with `application`, enable histogram buckets for `http.server.requests` so I can compute p99 in PromQL, and add business counters: `orders` by status and `inventory.reservations.rejected`.

**Q3. Explain your error-rate alert expression.**
Rate of 5xx per second summed by application, divided by total request rate by application, greater than 5% for 5 minutes. It's a ratio so it works at any traffic level, `for` avoids paging on blips, and it's a symptom users feel so it pages.

**Q4. Why `sum by (application, le)` in the latency query?**
`histogram_quantile` needs the `le` bucket label to interpolate the percentile. I aggregate across pods/URIs but keep `le`; dropping it returns nothing useful.

**Q5. Counter vs gauge vs histogram vs summary?**
Counter only increases (use rate); gauge goes up and down; histogram buckets observations and quantiles are computed server-side and are aggregatable across pods; summary computes quantiles in the client and can't be aggregated. I use histograms for latency.

**Q6. What's the difference between SLI, SLO and SLA?**
SLI is the measurement (good/total requests), SLO the internal target (99.5% over 30 days), SLA the external contract with penalties, set looser than the SLO. Error budget = 1 − SLO, used to balance feature velocity vs reliability.

**Q7. Why alert on symptoms rather than causes?**
Causes like high CPU don't always hurt users and produce noisy pages; symptoms like error rate and latency always matter and catch unknown causes too. Causes belong on dashboards for diagnosis. I make an exception for the circuit-breaker-open alert because it reliably means orders are failing.

**Q8. Why p99 and not average latency?**
Averages hide tail latency; with many backend calls per user action, the slow tail hits most users. p99 shows the experience of the slowest 1%.

**Q9. What is high cardinality and why is it dangerous?**
Too many unique label combinations (e.g. userId label) → millions of series → Prometheus memory blows up, queries slow. Use bounded labels; put IDs in logs/traces.

**Q10. How did you add distributed tracing?**
Micrometer Tracing with the OTel bridge and OTLP exporter in the parent pom, so every service exports spans to Tempo over OTLP/HTTP on 4318 (`OTLP_ENDPOINT`), sampling via `TRACING_SAMPLE` (1.0 locally, 0.1 in prod). W3C trace context is propagated through `RestClient` and through Kafka record headers (`observation-enabled`), so one trace covers HTTP → inventory and the outbox → notification hop. Trace ids land in the ECS JSON logs; Grafana's Tempo and Loki datasources link both ways. The alternative would have been the OTel Java agent with zero code changes.

**Q11. Pull vs push monitoring?**
Prometheus pulls: it controls scrape rate and detects down targets via `up`. Push (Pushgateway, StatsD, OTLP push) suits short-lived jobs and firewalled sources.

**Q12. What's in a good postmortem?**
Blameless; impact, timeline, root and contributing causes, what went well/badly, and owned, dated action items — focused on improving systems, not blaming people.

---

## 14. Common mistakes

- Querying counters without `rate()`; `rate(sum())` instead of `sum(rate())`.
- Dropping `le` before `histogram_quantile`; using summaries and averaging quantiles across pods.
- Including `/actuator/*` in latency SLIs (health checks make latency look great).
- Alerting on CPU/memory and paging on causes; no `for:`; alerts without runbooks.
- High-cardinality labels (IDs, raw URLs).
- Plain-text multi-line logs, logging secrets/PII, writing logs to files inside containers.
- No monitoring of the monitoring (Watchdog, `absent()`).
- SLOs that aren't user-centric, or SLO = 100%.
- Treating dashboards as observability without correlation (no trace IDs in logs).
- Raising DB pool size as a reflex during pool exhaustion.

> Tip: On-call scenario mein pehle "mitigate" bolo (rollback/scale), phir "root cause" — interviewer yahi order sunna chahta hai.
