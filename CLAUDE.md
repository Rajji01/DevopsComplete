# CLAUDE.md — rules for any Claude session on this repo (CLI + web)

Padho ise sabse pehle. Ye un rules ka set hai jo har Claude session (CLI ya claude.ai web) ko follow karne hain.

> **Entry point = [`docs/INDEX.md`](docs/INDEX.md)** — poora naksha + kis file mein kya jaata hai + reading chain wahan hai. Project ki asli state ke liye [`docs/STATE.md`](docs/STATE.md) — **wahi single source of truth hai**. Ye file sirf "kaise kaam karna hai" batati hai.

## 1. Ye repo kya hai
DevOps + Java backend mastery ka learning + interview-prep repo (owner: Abhishek, `Rajji01` on GitHub).
- `shopflow/` — asli production-style microservices project (order / inventory / payment / notification + `shopflow-outbox`), Kafka saga + transactional outbox, k8s (Kustomize + Helm + Argo Rollouts + Kyverno), Terraform (AWS), Ansible, monitoring, CI/CD.
- `Devops/`, `microservice01/`, `notes.txt` — purana "before" learning setup, jaan-bujh ke rakha hua. Mat hatao.
- `docs/` — STATE, START-HERE, EASY-NOTES, LABS, PRACTICE-LABS, ROADMAP, SELF-WALK, runbooks, interview-notes.

## 2. Source of truth
- [`docs/STATE.md`](docs/STATE.md) jeet-ta hai. Koi doc STATE se disagree kare to **doc galat hai, use fix karo**.
- Koi bhi change karo to **usi commit mein** STATE.md ke section 1–4 update karo (agar relevant ho).
- New alert banaya → runbook (`docs/runbooks/`) + unit test + `docs/runbooks/README.md` mein row, teeno.
- New approach (existing topic ka) → STATE.md §2 matrix mein row add karo, purana approach **mat hatao** (dono jaan-bujh ke rakhe hain).

## 3. Branch workflow
- `master` = canonical, yahi deploy/push hoti hai. Local aur `origin/master` in-sync rakho.
- Web (claude.ai) sessions apni `claude/<...>` branch pe kaam karti hain. Unka kaam laane ka tarika: `git fetch`, phir `git merge --ff-only origin/claude/<branch>` master pe (ya conflict ho to normal merge). Fast-forward prefer karo.
- Commit/push **sirf tab jab Abhishek bole**. Default branch pe seedha bade kaam se pehle poochho.

## 4. Change karne se pehle / baad
Verify karo — jo CI chalata hai wahi (details: [`Makefile`](Makefile), `make help`):
```
make lint       # yamllint, hadolint, gitleaks, tflint
make validate   # kustomize+kubeconform, kyverno, promtool, amtool, helm lint, ansible, terraform validate
make test       # cd shopflow && ./mvnw -B verify  (50+ tests: H2, embedded Kafka, WireMock)
```
- Ye commands ke liye tools (docker, kubectl, mvn, etc.) chahiye. Agar environment mein nahi hai to jo chala sakte ho chalao, aur jo nahi chala sakte **saaf bata do** — jhooth "done" mat bolo. STATE.md §3 batati hai kya yahan verify hota hai aur kya CI/labs mein.

## 5. Docs aur tone
- Hinglish bilkul theek hai (bahut saari notes Hinglish mein hi hain — EASY-NOTES, START-HERE, PRACTICE-LABS). Us file ka existing style match karo.
- Notes honest rakho: "in my project file X does Y" — interview mein wahi kaam aata hai. Exaggerate mat karo.
- Session ke beech handoff ke liye [`docs/SESSION-LOG.md`](docs/SESSION-LOG.md) update karo — kya hua, kya pending.

## 6. Commits
- Chhote, focused commits. Message mein "kya + kyu".
- Jab Claude commit kare, message ke end mein:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`

## 7. Jaldi se kahan kya (cheat sheet)
| Chahiye | Jagah |
|---|---|
| Poora map + one-day plan | `docs/START-HERE.md` |
| Project ki current state / kya verified | `docs/STATE.md` |
| Concept samajhna (Hinglish) | `docs/EASY-NOTES.md` |
| Interview answer | `docs/interview-notes/README.md` → topic file |
| Alert aaye to kya karein | `docs/runbooks/<AlertName>.md` |
| Saari commands | `make help` |
| Kya chal raha / pending (session handoff) | `docs/SESSION-LOG.md` |
