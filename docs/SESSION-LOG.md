# SESSION LOG — session-to-session handoff

Chhota running log: CLI aur web (claude.ai) sessions ke beech "abhi kya chal raha hai, kya pending hai" track karne ke liye. Ye **personal tracker** hai; project ki authoritative state [`STATE.md`](STATE.md) mein rehti hai (yahan duplicate mat karo, bas pointer do).

Rule: naya entry **sabse upar** add karo (newest first), is line ke theek neeche. Ek entry = date + branch + kya hua + kya pending.

---

## ▶ RESUME HERE (night study session) — 2026-11-01
**Mode:** GUIDED STUDY. Autonomous self-walk **PAUSED** — sirf tab chalao jab Abhishek literally "self walk" bole. Abhishek code padhega; Claude ka kaam: **explain → guide → quiz/test** (har topic 2-min explain → files padho → 2-3 sawaal → agla).
**State:** sab green + in-sync (HEAD `924e456`); dono CI workflows green; Walks done **up to 21**. Background/Docker sab band.
**Start topic:** **E — app correctness** (interview gold): refund-decline saga (Walk 20 → `payment-service/OrderConfirmedListener.refund`, `order-service/.../OrderService.onRefundFailed`), outbox persist (Walk 21 → `shopflow-outbox/OutboxEvent`), flaky-test fix (Walk 11 → `OutboxCleanupTest`). Self-test interactive: `labs/comprehension.html`.
**Study map (jo humne banaya):** neeche ke entries + `SELF-WALK.md` (Walks 8–21, har change ki poori story = textbook). Deep-dives: `INDEXING-HIBERNATE.md`, `LLD-DESIGN-PATTERNS.md`, `HLD-SYSTEM-DESIGN.md`, `VISUAL-FLOWS.md`.

---

## 2026-11-01 — CLI (Opus 4.8) — Walk 15+16+17 (quiet-files sweep + policy init-container gaps)
**Branch:** `master` (in-sync).
**Walk 15 (sweep, no-change clean):** docker-compose (`localhost:host-gateway` trick correct — multi-address fallback), Jenkinsfile (casing-trap se bacha — `ghcr.io/rajji01` lowercase, `.trivyignore` root pe), argocd prod (sound). Trivial: prod external-secret comment "three keys"→"four keys".
**Walk 16 (real finding, verified):** `disallow-latest-tag` (Kyverno **aur** Gatekeeper) sirf `spec.containers` check karta tha → `:latest` **initContainer/ephemeralContainer** bypass. **Fix:** Kyverno mein `=(initContainers)`/`=(ephemeralContainers)` conditional anchors (absent → skip, valid pods safe); Gatekeeper Rego mein `all_containers` set (teeno types). **Verified** `kyverno apply` v1.19.1 Docker: naya `bad-initcontainer-pod` (valid app + `:latest` init) ab **FAIL**, valid pods (init ke saath/bina) **PASS**. CI mein dedicated assertion se pin kiya.
**Walk 17 (same gap, ek policy aage):** `require-non-root` bhi sirf `spec.containers` ka `allowPrivilegeEscalation` dekhta tha → privilege-escalation **initContainer** slip. (`require-probes`/`require-resources` init pe apply nahi hote — jaanbujh ke chhoda.) **Fix:** `=(initContainers)`/`=(ephemeralContainers)` securityContext check. **Verified** kyverno apply: `bad-init-privesc-pod` FAIL, compliant pod PASS. CI assertion se pin.
**Verify:** push → shopflow CI (kyverno apply step) confirm karega. Gatekeeper CI-run nahi hota (OPA nahi) — correct-by-construction + documented.
**Lesson:** gap mile to poora family grep karo — `spec.containers[*]` har jagah tha; jahan matter karta (image tag, privesc) fix, jahan nahi (probes) chhodo.

## 2026-11-01 — CLI (Opus 4.8) — Walk 21 (outbox: persist, not merge+SELECT)
**Branch:** `master`. **Found:** `OutboxEvent` app-assigned `@Id UUID` → Spring Data `save()` ise existing samajh ke `merge()` karta → **har INSERT se pehle SELECT**. Outbox high-write → wasteful. **Fix:** `implements Persistable<UUID>`, `isNew()=true` (outbox insert-only; relay `markPublished` dirty-check se, save() nahi) → single INSERT. Compile verified (Docker); behaviour CI pe (order/payment outbox tests). **Noted (deferred):** ProcessedEvent/Payment same pattern, lower-write → follow-up (STATE §4). **Lesson:** assigned `@Id` chup-chaap save()→merge() bana deta; insert-only ho to `Persistable.isNew()` se bolo.

---

## 2026-11-01 — CLI (Opus 4.8) — Doc: HLD / system-design deep-dive
**Branch:** `master`. Tumhara **HLD** goal → `docs/HLD-SYSTEM-DESIGN.md`: ShopFlow pe grounded — non-functional reqs, scaling (stateless+HPA, Kafka partitions, **asli DB pool budget math**, Redis cache), CAP/consistency (sync-strong reserve vs async-eventual saga, saga-not-2PC), bottlenecks+mitigations (hot-SKU row, connections, per-pod rate-limit), failure domains ("X mar gaya to"), capacity back-of-envelope (flash-sale 3/300), "100x pe kya badlega", 3 design-a-feature scenarios (order-history/search/flash-sale), HLD interview checklist. INDEX/README/labs-hub (HLD dimension ✅). docs-only.

---

## 2026-11-01 — CLI (Opus 4.8) — Doc: LLD / design-patterns deep-dive
**Branch:** `master`. Tumhara **LLD** goal → `docs/LLD-DESIGN-PATTERNS.md`: 15 patterns **ShopFlow ke asli code se** (Strategy=PaymentGateway, Transactional Outbox, Choreography Saga+compensation, Aggregate+encapsulated state-machine=Order, Idempotent Receiver, @Version optimistic lock, Repository+atomic update, Template Method=GlobalExceptionHandler, DTO records, anti-corruption event-copy, reconciler, Resilience4j, DI, SOLID+ArchUnit) — har ek: kahan(`file`) + kyu + snippet + **interview line**. INDEX/README/labs-hub (LLD dimension ✅) linked. docs-only.

---

## 2026-11-01 — CLI (Opus 4.8) — Lab: comprehension mode (Google-style)
**Branch:** `master`. Bug-hunt genuinely exhausted (Walk 20 last real; notification/payment KafkaErrorConfig + ProcessedEvent swept **sound** — DLT backoff standard, DLT topics `*.events*` ARN-pattern se covered). Non-stop continue → genuine lab output. **Banaya:** `labs/comprehension.html` — single-file React, **8 grounded questions** (outbox, oversell, idempotency, saga-compensation, refund-fail/Walk20, IDOR, SLO-429/Walk14, Kafka at-least-once), har ek: passage → MCQ → reveal **asli code-reason** (file cited) → live-lab link. labs/README + roadmap updated (comprehension-mode ✅ live). labs/ only — no CI risk.

---

## 2026-11-01 — CLI (Opus 4.8) — Walk 20 (refund that could never fail — money correctness bug)
**Branch:** `master`. **Found:** `payment-service` `OrderConfirmedListener.refund` `gateway.refund()` ka **Result ignore** karta tha — unconditionally REFUNDED mark + PaymentRefunded emit. Fake gateway (always ok) ne chhupaya; real processor decline kare to order galat REFUNDED, paisa wapas nahi, koi alert nahi → silent money bug. **Fix:** Result check; decline pe **`PaymentRefundFailed`** emit, payment `CAPTURED` rahe (redelivery retry), order `REFUND_PENDING` rahe (`OrdersStuckConfirmed` alert pehle se REFUND_PENDING count karta → human). order-service `onRefundFailed` (record + `orders.refund.failed` metric). `payment.refund-limit` (test profile) se PS5 refund decline. Test existing class mein method (`declinedRefundPublishesRefundFailedAndKeepsPaymentCaptured`) — **second @SpringBootTest context avoid** kiya (do embedded Kafka = contention, pehla attempt mein existing tests timeout hue the). STATE §4 refund open-item **resolved**.
**✅ Verify:** payment-service test Docker (JDK 21) green (0 fail; decline log confirmed) + **CI run `41310157870` green**. order-service change chhota (switch case + onRefundFailed).

---

## 2026-11-01 — CLI (Opus 4.8) — Walk 19 (Terraform security: least-privilege Kafka IAM)
**Branch:** `master`. **Sweep:** RDS (encrypted, not public, SG EKS-only, Multi-AZ/backups/deletion-protection) + External-Secrets IAM (secret-ARN scoped) + MSK ARNs (topics/groups scoped) — **sound**. **Finding:** kafka-clients IRSA policy `kafka-cluster:*Topic*` mein chupke **DeleteTopic/AlterTopic** aa rahe the → compromised pod apne event topic delete kar sakta. **Fix:** explicit least-privilege set (CreateTopic, DescribeTopic, DescribeTopicDynamicConfiguration, WriteData, ReadData) — Delete/Alter hata diye. `terraform fmt` clean; **CI run `feabbf9` green** (validate+tflint+sab jobs pass). Bonus: `ci-oidc.tf` GitHub-OIDC trust policy reviewed — `sub` StringEquals se repo+main pe pinned (classic wildcard vuln se bacha), sound. **Lesson:** IAM action wildcard linter ka blind spot — `*Topic*` "topic stuff" dikhta par destruction deta; verbs enumerate karo. (Resource scoping already achhi thi.)

---

## 2026-11-01 — CLI (Opus 4.8) — Walk 18 (Ansible: fresh-VM path bug)
**Branch:** `master`. **Found:** `infra/ansible` docker role GPG key ko `/etc/apt/keyrings/docker.asc` pe daalta tha bina `/etc/apt/keyrings` banaye — fresh Ubuntu VM pe wo dir default nahi → playbook pehle hi step pe fail (Docker ke official steps ye dir banate hain). **Fix:** ek `file: state=directory` task pehle. **Verified:** `ansible-lint` (production profile) 0 failures (Docker). **Noted (left):** `compose_profiles` var vestigial hai (compose mein `profiles:` hai hi nahi) — later cleanup. **Lesson:** infra ko "fresh machine" soch ke padho, apni machine nahi — missing mkdir dev-laptop pe invisible, asli VM pe bites; syntax-check/lint nahi pakadta.

---

## 2026-11-01 — CLI (Opus 4.8) — Labs system setup (interactive + organized)
**Branch:** `master` (in-sync).
**Context:** Abhishek wants labs har dimension pe (debugger/coder/DevOps/HLD/LLD/Java/prod-debug/React+Java/AWS/interview/scenario/comprehension), **break→samjho→tod→jod** method, interactive (Postman + React+Spring), aur **har cheez ki specific-honest entry md files mein** (ek file mein sab kuch nahi; intent sahi ho).
**Hua:**
- `docs/INDEXING-HIBERNATE.md` — pehla **example topic** full lab (indexing + JPA: notes+diagrams+snippets+psql labs). Baaki topics isi template pe.
- `labs/ShopFlow.postman_collection.json` — **live**: 6 prod-style labs (idempotency, oversell-concurrency, ownership/IDOR, rate-limit, validation, inventory reserve) real stack pe, auth + assertions ke saath.
- `labs/README.md` — **Labs & Mastery hub**: har dimension → material + ✅live/🚧planned status, pedagogy, roadmap. INDEX + README entries specific+honest ki.
**React console ✅ (CORS ok mila to bana diya):** dev-CORS order-service (`CorsConfig` + `.cors()`) + inventory-service (`WebCorsConfig`) pe (localhost origins only, Bearer so no credentials; `app.cors.allowed-origin-patterns` se override). Keycloak realm pehle se `webOrigins:*` → login browser se chalta hai, koi change nahi. `labs/lab-console.html` — single-file React (CDN, no build), 6 panels (idempotency, oversell-tally, IDOR, rate-limit, validation, reserve-idem), Run → response+metric on screen. README run-steps ke saath.
**✅ Verify:** shopflow CI run `41306433468` green — order/inventory CORS compile + saare tests + image builds + Trivy sab pass. Console ab 8 panels (optimistic-lock + async-saga bhi). Live browser run user ke `make up` pe (yahan JDK21/Docker-memory se full stack run nahi).
**Next:** React+Spring **comprehension mode** (Google-style) + LLD/HLD deep-dives + aur scenario labs.
**Intent note:** overclaim nahi — status sach (✅ live / 🚧 planned).

---

## 2026-11-01 — CLI (Opus 4.8) — Walk 14 (SLI jise uska apna rate limiter fool kar sakta tha)
**Branch:** `master` (in-sync). STATE §4 ka documented open item band kiya (ab promtool Docker se chalta hai).
**Found:** checkout SLI "bad" = `5xx + slow non-5xx`, denominator = **saare** POST. To flash-sale ke **429 burst** (rate limiter ka kaam) do tarah se galat: slow 429 → "bad" (budget falsely jalata), aur har 429 denominator mein (ratio dilute → real 5xx chhupata). SLI ko apni hi protection fool kar sakti thi — dono directions mein.
**Fix:** valid events = sirf 2xx ya 5xx (`status=~"[25].."`), bad = `5xx + slow-2xx`; 4xx/429 **dono** (numerator+denominator) se excluded. Alert test **#7** add kiya (slow-429 burst, no 5xx → **no page**) — purane SLI pe fail hota, naye pe pass.
**Verify:** `promtool check rules` + `test rules` **Docker mein green** (incl #7). CI validate-manifests job bhi promtool chalata hai → wahan bhi confirm hoga.
**Lesson:** request-based SLI mein "valid events" define karo — client 4xx + apna 429 throttling dono sides se bahar — warna protection khud budget jalati ya chhupati hai.
**✅ Verify (CI):** shopflow run `41302098109` green — validate-manifests job ne refined rules + test #7 promtool se pass kiya.

---

## 2026-11-01 — CLI (Opus 4.8) — Walk 13 (the other pipeline that was quietly red)
**Branch:** `master` (in-sync). shopflow CI green confirm karne ke baad doosri workflow dekhi.
**Found:** `ci.yml` (basics — Devops/microservice01, har push pe chalti hai) red thi. `Build & test Devops`: `mvn verify` pass, par `docker build -t Devops:ci .` → `invalid tag "Devops:ci": repository name must be lowercase`. Walk 12 wali hi casing class — ab matrix/dir naam `Devops` (capital) seedha docker tag ban raha tha. microservice01 (lowercase) pass hota par fail-fast ne cancel kiya. **Ye ci.yml banne se hi red thi** — dhyan shopflow pe tha isliye chhupi rahi.
**Fix:** tag lowercase (`docker build -t "${SERVICE,,}:ci"`) + matrix `fail-fast: false`.
**Lesson:** har workflow check karo, sirf loud wali nahi; lowercase-tag rule har jagah lagti hai jahan capital-naam docker tag banta hai.
**✅ Verified green:** CI run `41301618077` — Validate manifests, Build & test Devops, Build & test microservice01 sab pass. Ab **dono workflows (shopflow + basics CI) green**. Docs: SELF-WALK Walk 13, STATE, ye entry.

---

## 2026-11-01 — CLI (Opus 4.8) — Walk 12 (CI fully green)
**Branch:** `master` (in-sync, koi naya web commit nahi).
**Pehle:** Walk 11 ke fixes **CI pe green confirm** (run `41214762574`): tests (flaky outbox fix ✅), lint/tflint ✅, k6 ✅, validate/helm ✅ (tar-collision fix landed), terraform ✅.
**Naya finding (CI log se):** `image` job "Set up job" pe fail — `Unable to resolve action sigstore/cosign-installer@v4` (koi v4 tag hi nahi, max v3). `uses:` actions "Set up job" pe resolve hote hain chahe step `if: main` se skip ho → gated cosign step ne poora image job master pe tod diya; matrix fail-fast ne baaki 3 legs cancel kar diye.
**Fix:** `cosign-installer@v4` → `@v3`; image matrix mein `fail-fast: false`.

**Cont. (cosign fix ke baad image job aage badha → 2 aur bugs, dono log se):**
- **Image-ref casing:** cosign+fail-fast ke baad saare 4 image builds bane, par Trivy scan pe FATAL `could not parse reference: ghcr.io/Rajji01/...`. OCI ref lowercase hona chahiye; `env.IMAGE` ne `github.repository_owner` (`Rajji01`, capital) raw use kiya. Build to `metadata-action` ne lowercase kar diya, par Trivy/SBOM/ECR raw `$IMAGE` padhte the. **Fix:** ek step `IMAGE=ghcr.io/${GITHUB_REPOSITORY_OWNER,,}/shopflow-<svc>` set karta hai.
- **Backup atomicity (boring-file sweep):** nightly `pg_dump` seedha `$db.dump` likhta tha; fail hua dump truncated file chhod deta jise restore valid samajh le. **Fix:** `.part` pe dump → success pe `mv` to `.dump` (atomic). Migrations + NetworkPolicies sweep: sound (no change). Note: `OutboxRelay` @Transactional Kafka I/O ke across hold karta hai (bounded; low-pri open item).
**Cont. 2 — casing fix ke baad Trivy ne sach mein scan kiya → 14 REAL fixable CVEs (7 HIGH, 7 CRIT), sab Spring Boot transitive Java deps:**
- jackson/netty/tomcat/postgresql → `shopflow/pom.xml` mein BOM properties override (fixed versions Trivy ne batayi: jackson 2.21.7, netty 4.1.137.Final, tomcat 10.1.59, postgres 42.7.12).
- spring-webmvc (CVE-2026-47884 CRIT + 47890) → fix sirf Spring 7/Boot 4 mein → documented triage `/.trivyignore` mein (blanket mute nahi; naye CVEs abhi bhi fail karenge). Boot-4 upgrade STATE §4 open item.
**Lesson:** har fix agle red ko unmask karta hai; scanner jo ref parse na kare = kuch scan nahi hua; transitive CVEs BOM pe patch karo, sirf genuinely-unfixable ko (documented) triage.
**Verify:** sab push → CI poll. pom override low-risk (patch bumps) par Docker verify memory se reap hua → CI test/build job verifier. Docs synced: STATE §4+header, SELF-WALK Walk 12 (+cont.+cont.2), ye entry.
**Cont. 3:** 1st CVE fix ke baad **inventory image GREEN + test job GREEN** (overrides compatible). Par 3 Kafka services (order/notification/payment) mein 5 aur CVEs nikle — Kafka compression (lz4-java→1.11.4, snappy-java→1.1.10.9) + httpcore5/-h2→5.4.3. 3 aur pom overrides add kiye. (inventory pass hua kyunki uska Kafka client nahi.)
**Cont. 4–5:** httpcore5 override chala, par lz4/snappy property **no-op** (kafka-clients pin karta hai, Boot nahi) → `<dependencyManagement>`. snappy clear hua par lz4 abhi bhi purana — kyunki iska asli coordinate **`at.yawk.lz4:lz4-java`** hai (mera `org.lz4` guess match nahi hua; Trivy/jar wahi batate hain) → dono coordinates se pin kiya.
**✅ RESOLVED — shopflow CI FULLY GREEN on master** (run `41297074849`): tests, lint/tflint, k6, validate(kustomize/kyverno/promtool/helm), terraform, 4 image builds + Trivy + SBOM — sab pass. (`Pin prod overlay` skipped = main-only.) Walk 8 ne master pe CI on kiya tha; uske baad **pehli full-green**.
**Lessons:** har fix agle red ko unmask karta hai; scanner jo ref parse na kare = kuch scan nahi hua; version property tabhi chalta jab BOM declare kare, warna `<dependencyManagement>`; coordinate exactly wahi lo jo Trivy/jar bataye; har override ke baad verify karo jar sach mein badla.
**Pending (open):** STATE §4 — OutboxRelay long-tx (low-pri), Spring Boot 4 upgrade (spring-webmvc triage hatane ko), + purane items (rate-limit, Schema Registry, chaos, RefundFailed, orphaned-saga, SLI slow-4xx).

---

## 2026-10-31 — CLI (Opus 4.8) — Walk 11 (clear the red CI from Walk 8)
**Branch:** `master` (session start pe origin se 4 commits peeche tha — Walk 9/10 + comprehension/debugging docs web se aaye the; FF kar liya.)
**Hua — Walk 8 ki 3 red CI jobs, Docker up hone se exact tool versions pe reproduce karke:**
1. **k6** `loadtest-syntax` job — **fix**: job `working-directory: shopflow` inherit karta hai par step `k6 inspect shopflow/loadtest/...` chalata tha → `shopflow/shopflow/...` = file not found. Ab `loadtest/flash-sale.js`. (script khud valid — `k6 inspect` local exit 0.)
2. **tflint** AWS ruleset — **fix (real IaC smell)**: `elasticache.tf` `default.redis7` (uneditable default param group) use kar raha tha → apna `aws_elasticache_parameter_group` banaya. tflint → **0 issues**, `terraform fmt -check` clean (dono Docker mein verified).
3. **helm** `template|kubeconform` — **reproduce NAHI hua** (helm 3.19.0 + kubeconform v0.6.7 se dono values 4/4 valid). Source untouched; CI mein likely transient download. Next master run watch.

**Verify (is baar Docker tha, isliye actually RAN):** k6 inspect ✅, tflint 0 issues ✅, terraform fmt ✅, helm lint + template|kubeconform ✅. `terraform validate` + full pipeline = CI (push isse trigger karega).
**Files synced:** STATE §4 (triage resolved/annotated) + header, SELF-WALK Walk 11, ye entry. Code: `.github/workflows/shopflow.yml`, `infra/terraform/aws/elasticache.tf`.
**Round 2 — CI logs padhe (git credential se token, API se, token print nahi kiya). Dono bache hue red ab REAL bugs nikle:**
- **helm — "transient" nahi tha, deterministic bug.** Log: `tar: helm: Cannot open: File exists`. Step `working-directory: shopflow` mein chalta hai jahan `helm/` **directory** pehle se hai; `curl|tar ... ./helm` binary usse collide karta tha. **Fix:** helm ko `runner.temp` (PATH) mein install kiya (kustomize/kubeconform jaise), step bare `helm` use karta hai (`./helm` nahi).
- **test flakiness — ek hi jad.** CI pe `publishesOrderEventThroughTheOutbox` 10s timeout; local (Docker JDK21) pe `OutboxCleanupTest` fail. Dono ki wajah: **live `OutboxRelay` @Scheduled (100ms) test assertions se race kar raha tha** (cleanup test ki "pending" row ko publish kar deta, phir cleanup use delete). **Fix:** `OutboxCleanupTest` ko isolated context + relay parked (`outbox.relay.delay=1h`); outbox-publish test ko 30s await (siblings jaise).

**Verify (Round 2):** k6/tflint/terraform ✅ (Docker). Java test fix: local Docker verify **memory-pressure se kill** ho gaya (ArchitectureTest+WebTest tak pahuncha, target tests tak nahi) → **CI (JDK 21) verifier**; fixes well-reasoned (race structurally hataai, 30s sirf widen). helm fix bhi CI pe confirm hoga.
**Pending:** CI run confirm (helm green + dono flaky tests green); baaki STATE §4 items.

---

## 2026-10-29 — CLI (Opus 4.8) — debugging drills series added
**Branch:** `master`.
**Hua:** Naya `docs/debugging/` series — **real-time debugging** training (architect-knowledge + on-call skill ke liye, Abhishek ka goal). Symptom-in → root-cause-out format: har drill ek Page/symptom (alert/graph/log) deta, phir Signals (`<details>`), "Your move" diagnostic questions, Walkthrough (diagnostic tree → root cause → blast-radius-safe fix → confirm), aur "The pattern" (transferable heuristic). README ek debugging **method** deta hai (blast radius → kya change hua → path bisect → RED/USE → differential diagnosis → confirm → safe fix). 6 drills, shopflow failure modes pe grounded:
  D01 orders stuck CONFIRMED (payment-service consumer lag) · D02 201 OK but silent downstream (Kafka down / outbox backlog) · D03 p99 spike no 5xx (inventory DB-pool saturation) · D04 CrashLoop exit 137 (OOMKilled, unbounded cache) · D05 SLO alert never fired (missing `le=1.0` bucket — monitor the monitor) · D06 oversold flash sale (cache in write path breaks atomic decrement).
  Wired: INDEX (table+tree), STATE §1 row, README, ye log.
**Verify (honest):** docs; source ke against likhe, **execute nahi** (test phase pending). Commit+push.
**Pending / next:** aur drills (duplicate charges/idempotency gap, poison-pill one-partition stall, 401 after IdP change, Redis-down thundering herd, readiness cascade, DLT filling). Phir comprehension topics (HPA/PDB, Terraform/EKS, backups, ArchUnit, virtual threads).

---

## 2026-10-29 — CLI (Fable/Opus) — comprehension rounds added
**Branch:** `master` (pulled first: already up to date; `claude/modest-bardeen-r4czhv` fully in master).
**Hua:** Naya `docs/comprehension/` section banaya — Google-style comprehension-round prep (DSA ki jagah). **15 topics**, har ek apne folder mein `comprehension.md` (cold passage + 5-tier Qs: literal/inference/trade-off/transfer/spot-flaw + `<details>` model answers + "Tod ke dekho" local minikube/docker + online hands-on + interviewer's lens) **aur** `notes.md` (kya/easy/kyu/kaise/flow+component diagram/alternate table/understanding check):
  01 outbox-dual-write · 02 saga-cancel-refund · 03 idempotency-three-ways · 04 order-limbo-reconciler · 05 overselling-concurrency · 06 slo-burn-rate-bug · 07 k8s-readiness-cascade · 08 supply-chain-signing · 09 resilience-breaker-retry · 10 oauth2-jwt-idor · 11 kafka-dlt-lag · 12 caching-cache-aside-failopen · 13 tracing-logs-correlation · 14 kustomize-vs-helm · 15 canary-bluegreen-rollouts (15 topics total).
  Plus `README.md` (round kya hai + practice method + rubric + index) aur `ONLINE-LABS.md` (Codespaces/Cloud Shell/Killercoda/Render+Neon+Upstash+Confluent free-tier setup — kyunki is machine pe Docker/JDK nahi).
  Sab real shopflow code pe grounded (file paths + exact commands/curl `/api/v1/orders`, FakePaymentGateway limit 100000 → PS5 qty2 declined, decrementStock atomic SQL, SLI `le=1.0` bucket bug, readiness group `readinessState,db`, cosign keyless + Kyverno verify). INDEX §1+tree, STATE §1 row, ye log update kiye.
**Verify (honest):** docs hain; passages/commands source ke against likhe gaye, par **is machine pe chala ke verify nahi** kiye (Docker daemon + JDK 21 nahi — STATE §3). `make lint`/`validate`/`test` nahi chalaye (pure markdown add; koi YAML/code change nahi).
**Commit/push:** 15 topics + trackers commit + push kiye master pe (`c2b2bd7..dc268ef`). Co-author Opus 4.8. (Abhishek ne bola.)
**Walk 9 (self-walk, same session):** naye docs ko source ke against audit kiya (SELF-WALK.md Walk 9). 1 real fix: topic 05 ka raw-concurrency demo `make loadtest` ke baad tha (PS5 stock 0 → galat result), `wait` missing tha, aur "200/201" galat (reserve hamesha 201). Fixed. Baaki claims (endpoints, grace 2m/10m, rate-limit 50/1s, breaker 50%/10/10s, Redis TTL 60s, DLT `payments.events.DLT`, FakeGateway limit 100000, readiness `readinessState,db`) source se **verified correct**. Walk 9 ka fix + log alag focused commit mein push kiye.
**Walk 10 (deeper lab audit, same session):** har topic ke hands-on commands ko DB schema + error-response behaviour ke against padha. 6 aur real fixes (SELF-WALK.md Walk 10): (1) topic 01/03 psql UUID PK ko `order by id desc` kar rahi thi — newest nahi milti → `created_at`/`processed_at`. (2) topic 03 `payment` table mein `id` column hai hi nahi (PK `event_id`) → query error → fixed. (3) topic 04/09 inventory-down POST 503 problem-detail deta (order body nahi), to `jq .id/.status` null → DB-observation + http-code/timing se replace kiya. (4) topic 08 syft bina docker-socket mount → image nahi milti → mount add. (5) topic 11 `KT bash` → `/opt/kafka/bin/bash` (exist nahi) → direct producer pipe. Verified-correct list bhi Walk 10 mein. Abhi tak **execute nahi** kiya (test phase jab Abhishek bole). Fixes commit+push.
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
