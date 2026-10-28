# SESSION LOG — session-to-session handoff

Chhota running log: CLI aur web (claude.ai) sessions ke beech "abhi kya chal raha hai, kya pending hai" track karne ke liye. Ye **personal tracker** hai; project ki authoritative state [`STATE.md`](STATE.md) mein rehti hai (yahan duplicate mat karo, bas pointer do).

Rule: naya entry **sabse upar** add karo (newest first), is line ke theek neeche. Ek entry = date + branch + kya hua + kya pending.

---

## 2026-10-29 — CLI (Fable/Opus) — comprehension rounds added
**Branch:** `master` (pulled first: already up to date; `claude/modest-bardeen-r4czhv` fully in master).
**Hua:** Naya `docs/comprehension/` section banaya — Google-style comprehension-round prep (DSA ki jagah). **15 topics**, har ek apne folder mein `comprehension.md` (cold passage + 5-tier Qs: literal/inference/trade-off/transfer/spot-flaw + `<details>` model answers + "Tod ke dekho" local minikube/docker + online hands-on + interviewer's lens) **aur** `notes.md` (kya/easy/kyu/kaise/flow+component diagram/alternate table/understanding check):
  01 outbox-dual-write · 02 saga-cancel-refund · 03 idempotency-three-ways · 04 order-limbo-reconciler · 05 overselling-concurrency · 06 slo-burn-rate-bug · 07 k8s-readiness-cascade · 08 supply-chain-signing · 09 resilience-breaker-retry · 10 oauth2-jwt-idor · 11 kafka-dlt-lag · 12 caching-cache-aside-failopen · 13 tracing-logs-correlation · 14 kustomize-vs-helm · 15 canary-bluegreen-rollouts (15 topics total).
  Plus `README.md` (round kya hai + practice method + rubric + index) aur `ONLINE-LABS.md` (Codespaces/Cloud Shell/Killercoda/Render+Neon+Upstash+Confluent free-tier setup — kyunki is machine pe Docker/JDK nahi).
  Sab real shopflow code pe grounded (file paths + exact commands/curl `/api/v1/orders`, FakePaymentGateway limit 100000 → PS5 qty2 declined, decrementStock atomic SQL, SLI `le=1.0` bucket bug, readiness group `readinessState,db`, cosign keyless + Kyverno verify). INDEX §1+tree, STATE §1 row, ye log update kiye.
**Verify (honest):** docs hain; passages/commands source ke against likhe gaye, par **is machine pe chala ke verify nahi** kiye (Docker daemon + JDK 21 nahi — STATE §3). `make lint`/`validate`/`test` nahi chalaye (pure markdown add; koi YAML/code change nahi). Commit/push nahi kiya (Abhishek ke bolne pe).
**Pending / next:** (a) hands-on commands ko Codespaces pe ek baar run karke verify karna. (b) aur comprehension topics isi mould mein (optional): HPA/PDB autoscaling, Terraform/EKS prod + IRSA/OIDC, backups RPO/RTO (pg_dump CronJob vs RDS PITR), ArchUnit guardrails, virtual threads, External Secrets, NetworkPolicies. (c) STATE §4 ke purane open items jyo-ke-tyo.

---

## 2026-10-20 — CLI (Opus 4.8) — Walk 8 (self-walk + improve)
**Branch:** `master`
**Hua (2 real findings fix kiye, dono "green jo actually red tha"):**
1. **Checkout SLO alerts prod mein kabhi fire nahi hote the.** `sli:checkout_bad` `http_server_requests_seconds_bucket{le="1.0"}` pe depend karta hai, par `percentiles-histogram: true` se wo bucket guaranteed nahi → PromQL empty vector → burn-rate alerts dead. Alert unit-test khud bucket inject karta hai isliye chhupa raha. **Fix:** order-service `application.yml` mein explicit `management.metrics.distribution.slo.http.server.requests: 1s` + guard test `httpServerRequestsHistogramHasTheOneSecondSloBucket`.
2. **Maven wrapper toota tha** — `shopflow/.mvn/wrapper/maven-wrapper.properties` distributionUrl `3.9.11/apache-maven-3.9.2-bin.zip` (404) → `./mvnw`/`make test`/CI test job clean checkout pe fail. **Fix:** `3.9.9/apache-maven-3.9.9-bin.zip`. Verified `./mvnw -version` chalta hai.
3. **Poori shopflow CI default branch pe kabhi chali hi nahi.** `shopflow.yml` trigger `main` pe tha, par repo ki default branch `master` hai → master pushes ungated (sirf PRs pe chalti thi). **Fix:** trigger mein `master` add kiya; publish/deploy steps `main`-gated hi rahe, to master push sirf verify karega (test JDK 21, validate, build, scan), push/sign/deploy nahi.

**Verify status (honest):**
- Wrapper fix: **locally verified** (`./mvnw -version` → Maven 3.9.9).
- Java change (SLO + test): **CI (Temurin 21) pe VERIFIED** ✅ — run `39421250786` ka `Unit + integration tests` job PASS. (Local pe JDK 21/Docker na hone se yahin nahi chala.)
- Lesson: `cmd | tail` exit code `tail` ka deta hai — pehla "baseline green" false tha. Hamesha asli status dekho.

**CI pe pata chala (master pe pehli baar chalne se) — 3 pre-existing failures, triage pending:**
- `k6 inspect` (loadtest-syntax job), `tflint` AWS ruleset (lint job), `helm lint/template | kubeconform` (validate-manifests job).
- Mere changes se NAHI (tests + terraform + kustomize/kyverno/promtool sab pass). Sources clean dikhte hain → tool/plugin/schema-download ya version drift lagta hai. **Precise fix ke liye run `39421250786` ke job logs chahiye.** STATE.md §4 mein triage item daala.

**Pending / next:** STATE.md §4 — naya open item add kiya (SLI slow-4xx refinement) + baaki pichhle items.

---

## 2026-10-20 — CLI (Opus 4.8) — setup
**Branch:** `master`
**Hua:**
- Web session branch `claude/modest-bardeen-r4czhv` (32 commits, poora ShopFlow project + docs) ko `master` pe fast-forward kiya — kuch lose nahi hua. `origin/master` pe push (`9b466a1..6290cfc`).
- Nayi tracking/onboarding files: `CLAUDE.md` (rules), `docs/INDEX.md` (entry point/naksha), `SUMMARY.md` (snapshot), `GLOSSARY.md` (shabd-matlab), ye `SESSION-LOG.md`. README + CLAUDE in sabse chain kiya.
- Project padha + samjha (saga code + DevOps side). Memory save ki.

**Pending / next (details STATE.md §4):** shared rate-limit · Schema Registry · Kyverno signature e2e · chaos experiments · `RefundFailed` path · orphaned-CONFIRMED saga timeout.
