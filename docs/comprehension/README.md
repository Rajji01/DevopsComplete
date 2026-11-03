# Comprehension Rounds — read a real scenario, reason, then break it yourself

Kai companies (Google included) ne pure-DSA screen ki jagah/saath ab **comprehension round** rakh diya hai. Tujhe ek technical passage milta hai jo pehle nahi dekha — design doc, incident, PR, code walkthrough — aur uspe reasoning/trade-off/extension sawaal. Yaad-dasht nahi, **samajh** test hoti hai.

Yeh folder sirf padhne ke liye nahi hai. Har topic ka poora loop hai: **padho -> reason karo -> alag notes se concept pakka karo -> local pe tod ke dekho -> online pe deploy karke dekho -> interview mein bol pao.** Sab ShopFlow ke asli code pe grounded, file paths ke saath.

## Har topic mein kya milega (2 files per topic)

Har topic apne folder mein (`NN-topic/`):

**`comprehension.md`** — the round itself
1. **Scenario / Passage** — ek self-contained engineering artifact (300–600 words).
2. **Questions**, 5 tiers: Literal -> Inference -> Trade-off -> Transfer/Extension -> Spot-the-flaw.
3. **Model answers** — `<details>` mein chhupe (pehle khud likho, phir milao).
4. **Tod ke dekho — Local** — minikube/docker pe break -> observe -> fix (asli commands).
5. **Tod ke dekho — Online** — laptop pe setup na ho to [`ONLINE-LABS.md`](ONLINE-LABS.md) ke free environments pe wahi.
6. **Interview concepts + interviewer's lens** — is passage se kaunse concept poochhe jaate hain, interviewer asli mein kya check kar raha hai.

**`notes.md`** — concept ke alag notes (jo tune maanga)
- **Kya** (one line) · **Easy way** (Hinglish analogy) · **Kyu** (problem jo ye solve karta hai / bina iske kya tootta hai) · **Kaise** (step-by-step, file paths ke saath) · **Flow diagram** (ASCII) · **Component/State diagram** (ASCII) · **Alternate approaches** (table: kab kaunsa) · **Understanding check** (khud se puchho) · **Interview mein ye bolo**.

## Kaise practice karo (padh ke chhodna = bekaar)

1. **Timer:** passage 2–3 min padho. Questions ke jawab **likh/bol ke** do — answer dekhe bina.
2. `<details>` expand karke model answer se milao. Rubric se khud ko score do (neeche).
3. **`notes.md`** padho — concept ka "kyu/kaise" andar baith jaye.
4. **Tod ke dekho** chalaao — jab tak khud break/fix nahi karega, yaad nahi rahega. Laptop pe Docker/minikube ho to Local; na ho to Online.
5. Har answer mein **"mere project mein ye `file X` karti hai"** jodo.

> Tip: Interviewer ko definition nahi, **samajh + evidence** chahiye. "Kyunki passage kehta hai relay at-least-once hai, isliye consumer idempotent hona zaroori hai" — aise passage se jodo, hawa se nahi.

## Scoring rubric (honest raho)

| Band | Kya dikhta hai |
|---|---|
| **Strong** | Passage ka evidence quote kiya, trade-off dono taraf, ek realistic failure/extension khud nikala |
| **OK** | Sahi jawab par "kyu" patla, alternative nahi socha |
| **Weak** | Passage se bahar ki ratti definition, ya detail ulta/miss padha |

## Index

Har row mein do files: `comprehension.md` (round) + `notes.md` (concept).

| # | Topic | Core concept | Round |
|---|---|---|---|
| 01 | [Dual write & the transactional outbox](01-outbox-dual-write/) · [notes](01-outbox-dual-write/notes.md) | DB+Kafka atomically, at-least-once, polling relay | HLD / Kafka |
| 02 | [Cancelling a paid order: refund saga](02-saga-cancel-refund/) · [notes](02-saga-cancel-refund/notes.md) | Saga choreography, compensation, money-before-stock | HLD / microservices |
| 03 | [Three kinds of idempotency](03-idempotency-three-ways/) · [notes](03-idempotency-three-ways/notes.md) | Idempotency-Key, dedupe-by-eventId, idempotent-by-state | Backend / Kafka |
| 04 | [Orders stuck in limbo](04-order-limbo-reconciler/) · [notes](04-order-limbo-reconciler/notes.md) | No txn around remote call, reconciler, "not every limbo auto-cancels" | Backend / reliability |
| 05 | [Overselling 3 PS5s to 300 buyers](05-overselling-concurrency/) · [notes](05-overselling-concurrency/notes.md) | Atomic SQL, row lock, idempotent reserve, rate limit | Concurrency / HLD |
| 06 | [The SLO alert that never fired](06-slo-burn-rate-bug/) · [notes](06-slo-burn-rate-bug/notes.md) | Multi-window burn-rate alerts + a real "green that was red" bug | SRE / observability |
| 07 | [Why readiness must not check the DB](07-k8s-readiness-cascade/) · [notes](07-k8s-readiness-cascade/notes.md) | Liveness vs readiness, cascading failure, zero-downtime | Kubernetes |
| 08 | [Signing images you actually deploy](08-supply-chain-signing/) · [notes](08-supply-chain-signing/notes.md) | SBOM, cosign keyless, Kyverno verifyImages, pin-by-digest | DevOps / security |
| 09 | [Retry, breaker, timeout when inventory is down](09-resilience-breaker-retry/) · [notes](09-resilience-breaker-retry/notes.md) | Resilience4j: transient vs business failure, fail-fast, FAILED | Backend / resilience |
| 10 | [JWTs, audience & the 404-not-403 trick](10-oauth2-jwt-idor/) · [notes](10-oauth2-jwt-idor/notes.md) | OAuth2 resource server, audience pinning, RBAC, IDOR | Security |
| 11 | [Poison pills, DLT & consumer lag](11-kafka-dlt-lag/) · [notes](11-kafka-dlt-lag/notes.md) | Partition ordering, dead-letter topic, lag, at-least-once | Kafka |
| 12 | [Cache-aside, evict & fail-open](12-caching-cache-aside-failopen/) · [notes](12-caching-cache-aside-failopen/notes.md) | Cache-aside, evict-not-update, cache never in write path, fail-open | Backend / caching |
| 13 | [Following one order: traces & logs](13-tracing-logs-correlation/) · [notes](13-tracing-logs-correlation/notes.md) | Distributed tracing, context over HTTP+Kafka, trace↔log correlation, sampling | SRE / observability |
| 14 | [Packaging manifests: Kustomize vs Helm](14-kustomize-vs-helm/) · [notes](14-kustomize-vs-helm/notes.md) | copy+patch vs template+values, overlays, GitOps, when-which | DevOps / k8s |
| 15 | [Shipping a version: canary vs blue-green](15-canary-bluegreen-rollouts/) · [notes](15-canary-bluegreen-rollouts/notes.md) | Argo Rollouts, metric analysis, auto-abort, instant rollback | DevOps / delivery |
| 16 | [Virtual threads: a cheap thread per request](16-virtual-threads/) · [notes](16-virtual-threads/notes.md) | Java 21 Loom, unmount on IO, pinning, bottleneck→pool | Java / concurrency |
| 17 | [Architecture rules that fail the build (ArchUnit)](17-archunit-guardrails/) · [notes](17-archunit-guardrails/notes.md) | layering as tests, service web-agnostic, build-fail enforcement | LLD / testing |
| 18 | [Scaling to load, surviving a node drain: HPA + PDB](18-hpa-pdb-autoscaling/) · [notes](18-hpa-pdb-autoscaling/notes.md) | HPA owns replicas, PDB voluntary-disruption budget, voluntary vs involuntary | Kubernetes / capacity |
| 19 | [Backups, RPO/RTO, and a secretly-corrupt backup](19-backups-rpo-rto/) · [notes](19-backups-rpo-rto/notes.md) | RPO vs RTO, pg_dump vs RDS PITR, atomic backup hygiene, restore-test | SRE / data |
| 20 | [AWS perms with no access key (IRSA)](20-eks-irsa-least-privilege/) · [notes](20-eks-irsa-least-privilege/notes.md) | IRSA/OIDC/STS, least-privilege IAM, resource-ARN scoping, Walk-19 wildcard trap | AWS / security |

**Status:** 01–20 built (har ek: comprehension + notes + local & online hands-on). Aage aur topics isi shape mein add ho sakte (HPA/PDB/autoscaling, Terraform/EKS prod + IRSA, backups RPO/RTO, External Secrets...).

## Shared

- [`ONLINE-LABS.md`](ONLINE-LABS.md) — laptop pe Docker/minikube/JDK na ho to **free online** pe poora stack chalana aur deploy karna: GitHub Codespaces, Google Cloud Shell, Killercoda, aur ek "sach mein internet pe deploy" path (Render + Neon + Upstash + Confluent Cloud free tiers). Har topic ka online hands-on yahin se link hota hai.

## Sibling series: debugging drills

Mechanism samajhne ke baad usko **live debug** karna seekho: [`../debugging/`](../debugging/README.md) (D01–D15) — sirf symptom (alert/graph/log) milta hai, tujhe root cause nikalna hai. Jodi: 01↔[D02](../debugging/D02-201-but-silent-downstream.md) · 03↔[D07](../debugging/D07-duplicate-emails-charges.md) · 04↔[D01](../debugging/D01-orders-stuck-confirmed.md) · 05/12↔[D06](../debugging/D06-oversold-flash-sale.md) · 06↔[D05](../debugging/D05-alert-that-didnt-fire.md) · 07↔[D11](../debugging/D11-readiness-cascade.md)/[D10](../debugging/D10-redis-down-thundering-herd.md) · 09/13↔[D03](../debugging/D03-latency-spike-no-errors.md) · 10↔[D09](../debugging/D09-401-after-idp-change.md) · 11↔[D01](../debugging/D01-orders-stuck-confirmed.md)/[D02](../debugging/D02-201-but-silent-downstream.md)/[D08](../debugging/D08-one-partition-stalled.md)/[D12](../debugging/D12-dlt-filling-up.md) · 12↔[D10](../debugging/D10-redis-down-thundering-herd.md) · 15/18↔[D14](../debugging/D14-deploy-drops-requests.md)/[D15](../debugging/D15-hpa-flapping.md) · 15↔[D13](../debugging/D13-rollout-stuck-or-paused.md). (Comprehension = "kaise kaam karta"; debugging = "toota to kahan dekhun".)

## Test karo — 3 tarah (React UI · Postman · curl)

Har API-testable topic ko teen tarah se chala ke dekh sakte ho. Pehle stack uthao: `make up` + `TOKEN=$(make -s token)`.

**1. React lab-console (UI, no build):** `cd labs && python -m http.server 5500` → `http://localhost:5500/lab-console.html` → **Login alice** → panel pe **Run**. Panels: idempotency · oversell · IDOR · rate-limit · validation · reserve-idem · optimistic-lock · saga-async. (dev CORS order/inventory pe on hai.)
**2. Postman:** `labs/ShopFlow.postman_collection.json` import → `0. Auth > Get token` → phir `Lab 1..6` hit karo (concurrency labs Runner x50).
**3. curl:** har topic ke `notes.md` mein ab ek **"Test karo"** block hai — ready curl (Postman mein paste bhi kar sakte).

Topic ↔ lab mapping:

| Topic | React panel | Postman | Endpoint |
|---|---|---|---|
| 03 idempotency | Idempotency, Reserve-idem | Lab 1, Lab 6 | `POST /api/v1/orders` (+`Idempotency-Key`), `POST /api/v1/reservations` |
| 05 overselling | Oversell (concurrency) | Lab 2 (Runner×50) | `POST /api/v1/orders` `PS5-SLIM` |
| 10 OAuth/IDOR | IDOR, Rate-limit, Validation | Lab 3, 4, 5 | `GET/DELETE /api/v1/orders/{id}`, rapid POST, bad body |
| 02 saga/cancel | Saga-async, (cancel) | Lab 3 cancel | `DELETE /api/v1/orders/{id}` |
| 01/04/06/09/11/12/13 | — (infra-level) | — | `notes.md` "Test karo" + topic "Tod ke dekho" (docker/kubectl/psql) |

> Infra topics (outbox, reconciler, SLO, tracing, Kafka, caching, k8s) UI se nahi — unke `notes.md`/"Tod ke dekho" mein docker/psql/kubectl commands hain.

## Isme aur `interview-notes/` mein farak

`interview-notes/` = **answer pata hai, revise karo** (Q -> model answer). Yeh folder = **naya passage, reason karo + tod ke dekho** — wahi jo round mein hota hai. Dono source of truth: [`../STATE.md`](../STATE.md).
</content>
