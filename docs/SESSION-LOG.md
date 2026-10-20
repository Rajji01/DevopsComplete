# SESSION LOG — session-to-session handoff

Chhota running log: CLI aur web (claude.ai) sessions ke beech "abhi kya chal raha hai, kya pending hai" track karne ke liye. Ye **personal tracker** hai; project ki authoritative state [`STATE.md`](STATE.md) mein rehti hai (yahan duplicate mat karo, bas pointer do).

Rule: naya entry **sabse upar** add karo (newest first), is line ke theek neeche. Ek entry = date + branch + kya hua + kya pending.

---

## 2026-10-20 — CLI (Opus 4.8) — Walk 8 (self-walk + improve)
**Branch:** `master`
**Hua (2 real findings fix kiye, dono "green jo actually red tha"):**
1. **Checkout SLO alerts prod mein kabhi fire nahi hote the.** `sli:checkout_bad` `http_server_requests_seconds_bucket{le="1.0"}` pe depend karta hai, par `percentiles-histogram: true` se wo bucket guaranteed nahi → PromQL empty vector → burn-rate alerts dead. Alert unit-test khud bucket inject karta hai isliye chhupa raha. **Fix:** order-service `application.yml` mein explicit `management.metrics.distribution.slo.http.server.requests: 1s` + guard test `httpServerRequestsHistogramHasTheOneSecondSloBucket`.
2. **Maven wrapper toota tha** — `shopflow/.mvn/wrapper/maven-wrapper.properties` distributionUrl `3.9.11/apache-maven-3.9.2-bin.zip` (404) → `./mvnw`/`make test`/CI test job clean checkout pe fail. **Fix:** `3.9.9/apache-maven-3.9.9-bin.zip`. Verified `./mvnw -version` chalta hai.
3. **Poori shopflow CI default branch pe kabhi chali hi nahi.** `shopflow.yml` trigger `main` pe tha, par repo ki default branch `master` hai → master pushes ungated (sirf PRs pe chalti thi). **Fix:** trigger mein `master` add kiya; publish/deploy steps `main`-gated hi rahe, to master push sirf verify karega (test JDK 21, validate, build, scan), push/sign/deploy nahi.

**Verify status (honest):**
- Wrapper fix: **locally verified** (`./mvnw -version` → Maven 3.9.9).
- Java change (SLO + test): **locally run NAHI** — JDK 21 nahi (sirf 17/8), Docker daemon band. Finding #3 ki wajah se **ab ye push khud master pe shopflow CI (Temurin 21) trigger karega**, jo is Java change ko verify karega. Red aaye to fix-forward.
- Lesson: `cmd | tail` exit code `tail` ka deta hai — pehla "baseline green" false tha. Hamesha asli status dekho.

**Pending / next:** STATE.md §4 — naya open item add kiya (SLI slow-4xx refinement) + baaki pichhle items.

---

## 2026-10-20 — CLI (Opus 4.8) — setup
**Branch:** `master`
**Hua:**
- Web session branch `claude/modest-bardeen-r4czhv` (32 commits, poora ShopFlow project + docs) ko `master` pe fast-forward kiya — kuch lose nahi hua. `origin/master` pe push (`9b466a1..6290cfc`).
- Nayi tracking/onboarding files: `CLAUDE.md` (rules), `docs/INDEX.md` (entry point/naksha), `SUMMARY.md` (snapshot), `GLOSSARY.md` (shabd-matlab), ye `SESSION-LOG.md`. README + CLAUDE in sabse chain kiya.
- Project padha + samjha (saga code + DevOps side). Memory save ki.

**Pending / next (details STATE.md §4):** shared rate-limit · Schema Registry · Kyverno signature e2e · chaos experiments · `RefundFailed` path · orphaned-CONFIRMED saga timeout.
