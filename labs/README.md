# Labs & Mastery hub

Ye ShopFlow repo ko ek **interactive learning lab** banata hai. Maqsad: har dimension pe tagda hona —
**debugger · coder · DevOps · HLD · LLD · Java/JPA concepts · prod issues/debugging · React+Java · AWS · interview Q&A · scenario-based.**

> **Method (har lab mein):** **break → samjho → tod → jod.** Pehle chalti cheez dekho, phir jaan-bujh ke todo,
> samjho kyu tooti, phir wapas jodo. Jo khud toda-joda wahi interview mein war-story banta hai.

## Kaise test karoge (interactive)

| Tareeka | Status | Kya |
|---|---|---|
| **Postman** | ✅ live | [`ShopFlow.postman_collection.json`](ShopFlow.postman_collection.json) — import karo, `make up` chalao, `0. Auth > Get token` chalao, phir har lab folder hit karo. Concurrency labs (oversell, rate-limit) Collection Runner mein N iterations. |
| **React lab-console** | ✅ live | [`lab-console.html`](lab-console.html) (single-file React, CDN — no build). Chalao: `make up`, phir `cd labs && python -m http.server 5500`, phir `http://localhost:5500/lab-console.html` kholo → **Login alice** → koi bhi lab **Run** → response + metric screen pe. (Dev CORS order/inventory pe enabled; Keycloak `webOrigins:*` pehle se.) |
| **psql / EXPLAIN** | ✅ live | DB-internal labs (index plan, locks) [`../docs/INDEXING-HIBERNATE.md`](../docs/INDEXING-HIBERNATE.md) ke andar. |
| **CLI (curl/k6/kubectl)** | ✅ live | break/fix labs [`../docs/LABS.md`](../docs/LABS.md), build-it [`../docs/PRACTICE-LABS.md`](../docs/PRACTICE-LABS.md). |

## Dimension map — har cheez ki apni jagah (specific entries)

| Banna hai | Material (abhi) | Interactive lab | Status |
|---|---|---|---|
| **Java / JPA concepts** | [`../docs/INDEXING-HIBERNATE.md`](../docs/INDEXING-HIBERNATE.md), [`../docs/interview-notes/02-java-spring-boot.md`](../docs/interview-notes/02-java-spring-boot.md) | Postman Lab 1/6 (idempotency), psql locks/oversell | ✅ (indexing/JPA done as the first full example) |
| **DB indexing** | [`../docs/INDEXING-HIBERNATE.md`](../docs/INDEXING-HIBERNATE.md) §1–8 | psql EXPLAIN labs A–D | ✅ |
| **Prod debugging** | [`../docs/debugging/`](../docs/debugging/README.md) (D01–D06: symptom→root cause→fix) | Postman Lab 2/4 (oversell, rate-limit) | ✅ + aur scenario labs planned |
| **Prod issues (break/fix)** | [`../docs/LABS.md`](../docs/LABS.md) (33 labs), [`../docs/SELF-WALK.md`](../docs/SELF-WALK.md) (real bugs found) | — | ✅ |
| **HLD (system design)** | [`../docs/VISUAL-FLOWS.md`](../docs/VISUAL-FLOWS.md) §1 (architecture), §4/5 (saga/outbox) | — | ✅ diagrams; design-Q labs planned |
| **LLD (class/patterns)** | [`../docs/START-HERE.md`](../docs/START-HERE.md) code-reading order, [`../docs/VISUAL-FLOWS.md`](../docs/VISUAL-FLOWS.md) | — | 🚧 LLD deep-dive (like the indexing one) planned |
| **DevOps / CI-CD / k8s / IaC** | interview-notes [04–09], [`../docs/VISUAL-FLOWS.md`](../docs/VISUAL-FLOWS.md) §12/13, [`../docs/SELF-WALK.md`](../docs/SELF-WALK.md) Walks 8–14 | — | ✅ |
| **Observability / SRE** | [`../docs/VISUAL-FLOWS.md`](../docs/VISUAL-FLOWS.md) §11, [`../docs/runbooks/`](../docs/runbooks/README.md) | — | ✅ |
| **React + Java** | Spring APIs (order/inventory) | React lab-console (8 panels, live) | ✅ console live (dev CORS) |
| **AWS services** | [`../docs/interview-notes/09-aws-production.md`](../docs/interview-notes/09-aws-production.md), [`../infra/terraform/aws/`](../infra/terraform/aws/README.md) | — | ✅ read-only; live labs need an AWS account |
| **Interview Q&A** | [`../docs/interview-notes/`](../docs/interview-notes/README.md) (01–12 + rapid-fire) | — | ✅ |
| **Comprehension round (Google-style)** | [`../docs/comprehension/`](../docs/comprehension/README.md) (15 topics) | React+Spring comprehension mode | 🚧 planned |
| **Scenario-based** | debugging/ + comprehension/ | Postman scenarios + planned | 🚧 growing |

## Abhi kya hit kar sakte ho (Postman labs)
1. **Idempotency-Key** — retry-safe create (201 → replay 200).
2. **Oversell under concurrency** — Runner x50 → exactly 3 CONFIRMED, baaki REJECTED.
3. **Ownership / IDOR + cancel** — dusre ki id → 404 (403 nahi).
4. **Rate limit** — Runner fast → 429.
5. **Validation** — bad body → 400 problem+json.
6. **Inventory reserve idempotency** — direct inventory-service, same orderRef → same reservation.

## Roadmap (incrementally banayenge, har ek apni specific entry ke saath)
- ~~React lab-console (local CORS)~~ ✅ done (`lab-console.html`, 8 panels: idempotency, oversell, IDOR, rate-limit, validation, reserve-idem, optimistic-lock, saga-async).
- React+Spring **comprehension mode** (Google-style: passage → sawaal → code dikhao → chalao).
- LLD deep-dive (INDEXING-HIBERNATE.md ki tarah) — design patterns + class-level.
- HLD scenario labs (design a feature → trade-offs).
- Aur prod-debugging scenario drills (debugging/ ke upar).

> **Rule:** har naya lab/topic ka ek **specific, honest entry** is table mein + sahi md file mein jata hai — "ek file mein sab kuch" nahi. Status (✅/🚧) sach batata hai, overclaim nahi.
