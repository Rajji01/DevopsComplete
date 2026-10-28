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

**Status:** 01–15 built (har ek: comprehension + notes + local & online hands-on). Aage aur topics isi shape mein add ho sakte (HPA/PDB/autoscaling, Terraform/EKS prod + IRSA, backups RPO/RTO, ArchUnit guardrails, virtual threads, External Secrets...).

## Shared

- [`ONLINE-LABS.md`](ONLINE-LABS.md) — laptop pe Docker/minikube/JDK na ho to **free online** pe poora stack chalana aur deploy karna: GitHub Codespaces, Google Cloud Shell, Killercoda, aur ek "sach mein internet pe deploy" path (Render + Neon + Upstash + Confluent Cloud free tiers). Har topic ka online hands-on yahin se link hota hai.

## Isme aur `interview-notes/` mein farak

`interview-notes/` = **answer pata hai, revise karo** (Q -> model answer). Yeh folder = **naya passage, reason karo + tod ke dekho** — wahi jo round mein hota hai. Dono source of truth: [`../STATE.md`](../STATE.md).
</content>
