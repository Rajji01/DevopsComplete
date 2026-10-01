# Interview Notes: Java Backend + DevOps

Every file follows the same pattern: **concept → how ShopFlow uses it (with file paths) → interview Q&A → scenarios → common mistakes**. Answers are in English because interviews are in English. The `> Tip:` lines are short Hinglish hints.

| # | File | Read it when |
|---|---|---|
| 01 | [Project walkthrough](01-project-walkthrough.md) | **Always first.** "Tell me about your project" decides how the rest of the interview goes |
| 02 | [Java + Spring Boot](02-java-spring-boot.md) | Backend / Java developer rounds |
| 03 | [Microservices](03-microservices.md) | System design and microservices rounds |
| 04 | [Docker](04-docker.md) | DevOps rounds, and "how do you deploy?" |
| 05 | [Kubernetes](05-kubernetes.md) | DevOps / platform rounds (the most-asked topic) |
| 06 | [CI/CD + GitOps](06-cicd-gitops.md) | "Explain your pipeline" |
| 07 | [Observability + SRE](07-observability-sre.md) | On-call, production-debugging and SRE rounds |
| 08 | [Linux, networking, cloud, IaC](08-linux-networking-cloud-iac.md) | DevOps fundamentals, AWS, Terraform |

## How to prepare (not just read)

1. **Run the labs first** ([`../LABS.md`](../LABS.md)). Answers stick only after you've seen the thing break yourself.
2. Read a file, close it, and **answer the Q&A out loud** in 2–4 sentences. If you can't, re-read only that part.
3. For every answer, add *"in my project, ..."* using ShopFlow. Interviewers trust experience more than definitions.
4. One week before the interview, revise only the rapid-fire sheet below plus the 01 walkthrough.

> Tip: Rattna nahi hai. Har answer ka "why" samjho. Follow-up question hamesha "why" pe hi aata hai.

---

## Rapid-fire revision (1-line answers)

### Project
| Q | A |
|---|---|
| How do you prevent overselling? | One atomic SQL statement, `UPDATE ... SET quantity = quantity - n WHERE sku = ? AND quantity >= n`. The row lock serialises buyers and the WHERE re-checks the stock. A 20-thread test proves it. |
| How do you avoid duplicate orders? | An `Idempotency-Key` header with a unique column on orders, and `orderRef` as a unique key on reservations, so retries are safe. |
| Why no `@Transactional` around the inventory call? | Holding a DB connection while waiting on the network exhausts the pool. Instead each step is saved separately and the order status records progress. |
| What if inventory is down? | Timeout → retry with backoff (transient errors only) → circuit breaker opens → fast 503. The order is marked `FAILED` and readiness stays UP. |
| Why doesn't readiness include inventory? | Otherwise one service's outage would pull the healthy order pods out of the load balancer too (a cascading failure). |

### Java / Spring
| Q | A |
|---|---|
| HashMap internals? | An array of buckets indexed by `hash(key)`. Collisions are chained, and a bucket becomes a tree after 8 entries (when capacity ≥ 64). It resizes at 0.75 load factor. |
| `@Transactional` not working? | It's proxy-based: self-invocation, private methods, or a checked exception (no rollback by default) are the usual causes. |
| N+1 problem? | 1 query for the parents + N lazy queries for the children. Fix it with a fetch join / `@EntityGraph` / batch fetching. |
| Optimistic vs pessimistic locking? | Optimistic: a `@Version` check at update time, which fails on conflict (good when conflicts are rare). Pessimistic: `SELECT ... FOR UPDATE`, which blocks others. |
| Bean scopes? | singleton (default), prototype, request, session, application. |
| What is auto-configuration? | `@Conditional...` configuration classes that run when a class or property is present. Starters bring those dependencies in. |
| Virtual threads (Java 21)? | Cheap JVM-managed threads, great for blocking IO. Spring Boot enables them with `spring.threads.virtual.enabled=true`. |

### Microservices
| Q | A |
|---|---|
| Saga? | A sequence of local transactions with compensations instead of a distributed transaction. ShopFlow's cancel → release is the compensation step. |
| Transactional outbox? | Write the business row and an event row in the same DB transaction, and have a relay publish the event to Kafka. This fixes the dual-write problem. |
| Circuit breaker states? | CLOSED → (failure rate ≥ threshold) OPEN → (after a wait) HALF_OPEN → test calls → CLOSED or back to OPEN. |
| Retry best practice? | Retry only transient, idempotent operations, with exponential backoff + jitter, a max-attempts cap, and a timeout on every call. |
| Kafka ordering? | Guaranteed only within a partition, so use a key (e.g. orderId) to keep related events together. |

### Docker
| Q | A |
|---|---|
| CMD vs ENTRYPOINT? | ENTRYPOINT is the executable and CMD is its default arguments, which are easy to override. Use the exec (JSON) form so the app is PID 1 and receives SIGTERM. |
| Why multi-stage? | The build tools stay in the build stage, so the final image has only the JRE + app: smaller, with less attack surface. |
| Why layered jars? | Dependencies change rarely, so their layer stays cached, and a code change ships only a small layer. |
| Why not `:latest`? | It isn't immutable or traceable, so you can't roll back reliably. Tag with the git SHA. |

### Kubernetes
| Q | A |
|---|---|
| Liveness vs readiness vs startup? | Liveness failure = restart. Readiness failure = no traffic. Startup = gives a slow app time before liveness starts checking. |
| CrashLoopBackOff, how to debug? | `kubectl logs <pod> --previous`, `kubectl describe pod` (exit code, events), then check config/secrets and resources. |
| Exit code 137? | SIGKILL, usually OOMKilled (memory over the limit). Check `describe pod` → Last State. |
| QoS classes? | Guaranteed (requests = limits for every resource), Burstable (some requests), BestEffort (none, evicted first). |
| Zero-downtime deploy? | `maxUnavailable: 0` + readiness probe + `preStop` sleep + graceful shutdown. |
| ClusterIP vs NodePort vs LoadBalancer? | Internal only / a port on every node / a cloud load balancer in front (each type builds on the previous one). |
| StatefulSet vs Deployment? | StatefulSet gives stable pod names, ordered start-up, and a PVC per pod. Use it for databases. |
| Secret is encrypted? | No, only base64. Use encryption at rest + RBAC + an external secret manager. |

### CI/CD + observability
| Q | A |
|---|---|
| Your pipeline? | test → validate manifests → build image → Trivy scan → push (main only) → GitOps deploy. |
| Blue-green vs canary? | Blue-green switches all traffic at once and rolls back instantly. Canary shifts traffic gradually, which limits the blast radius. |
| Error-rate PromQL? | `sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) / sum(rate(http_server_requests_seconds_count[5m]))` |
| p99 latency PromQL? | `histogram_quantile(0.99, sum by (le) (rate(http_server_requests_seconds_bucket[5m])))` |
| SLI / SLO / SLA? | Measurement / internal target / contractual promise with penalties. Error budget = 1 − SLO. |
