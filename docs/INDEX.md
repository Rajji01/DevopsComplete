# INDEX — sab yahan se shuru (project ka ek-jagah naksha + tracker)

> **Entry point.** Koi bhi session (tu ya Claude) yahin se start kare. Ye file batati hai *kis file mein kya jaata hai* aur *kis order mein padhna hai* — taaki is ek file se poora project track ho jaye. Har file aage apna kaam karti hai (chain).

## 0. Reading chain (isi order mein)
```
INDEX.md (yahan ho)
   │
   ├─▶ SUMMARY.md ........... 30-sec snapshot: project kya + abhi kahan
   ├─▶ CLAUDE.md (root) ..... kaam karne ke rules (source of truth, branch, verify)
   ├─▶ STATE.md ............. AUTHORITATIVE state (kya hai/verified/pending) ← jhagde mein jeet-ta
   ├─▶ SESSION-LOG.md ....... pichhli session kya chhod gayi (newest first)
   ├─▶ START-HERE.md ........ poora map + ek-din ka plan + code reading order
   └─▶ GLOSSARY.md .......... koi shabd/folder samajh na aaye to
```
Pehli baar? → SUMMARY → STATE §1 → START-HERE.
Kaam continue karna? → SESSION-LOG top entry → CLAUDE.md → kaam.

## 1. Har doc — "ismein kya jaata hai" (taaki content sahi jagah likhe)
| File | Ye isme likho | Ye ismein mat likho |
|---|---|---|
| `docs/INDEX.md` (ye) | navigation, file-roles, reading order | actual notes/state |
| `docs/SUMMARY.md` | 30-sec snapshot, status ek nazar | detail (pointer do) |
| `CLAUDE.md` (root) | Claude ke rules: workflow, verify, tone | project facts |
| `docs/STATE.md` | **authoritative**: kya hai, verified kaise, open items, approach matrix | opinions |
| `docs/SESSION-LOG.md` | per-session: hua kya, pending kya (newest first) | permanent facts (wo STATE mein) |
| `docs/START-HERE.md` | onboarding map, one-day plan, code reading order | changing status |
| `docs/GLOSSARY.md` | har shabd/folder ka 1-line matlab | deep explanation (wo EASY-NOTES) |
| `docs/EASY-NOTES.md` | concept ka problem→kyu→kaise (Hinglish) | — |
| `docs/LABS.md` | break/fix labs | — |
| `docs/PRACTICE-LABS.md` | khud-banao tasks + solution | — |
| `docs/ROADMAP.md` | 18-week plan | — |
| `docs/SELF-WALK.md` | har bug ki kahani (kyu/kaise fix) | — |
| `docs/runbooks/*` | alert pe kya karein | — |
| `docs/interview-notes/*` | topic-wise interview answers | — |

## 2. Poora project structure (tree)
```
DevopsComplete/
├── CLAUDE.md ................ Claude session rules
├── README.md ............... repo intro + services table
├── Makefile ................ saare commands (make help)
├── notes.txt ............... purane docker/k8s command cheatsheet
├── docs/
│   ├── INDEX.md ............ YE FILE (entry/naksha)
│   ├── SUMMARY.md .......... snapshot
│   ├── STATE.md ........... source of truth
│   ├── SESSION-LOG.md ..... session handoff
│   ├── START-HERE.md ...... onboarding map
│   ├── GLOSSARY.md ........ shabd-matlab
│   ├── EASY-NOTES.md / LABS.md / PRACTICE-LABS.md / ROADMAP.md / SELF-WALK.md
│   ├── runbooks/ .......... per-alert on-call steps
│   └── interview-notes/ ... 01..12 + README
├── shopflow/ ............... asli microservices project
│   ├── order-service/  inventory-service/  payment-service/  notification-service/
│   ├── shopflow-outbox/ ... shared outbox lib
│   ├── docker-compose.yml . local stack (make up)
│   ├── k8s/ ............... base + overlays/{dev,prod}, rollouts, policies
│   ├── helm/ ............. Helm chart (deploy ka dusra tarika)
│   ├── monitoring/ ....... prometheus/alertmanager/grafana + rules + tests
│   ├── loadtest/ ......... k6
│   ├── argocd/  Jenkinsfile  Dockerfile(.distroless)
├── infra/
│   ├── terraform/aws/ .... AWS prod infra as code
│   └── ansible/ ......... VM pe stack (K8s ke bina)
├── Devops/  microservice01/  "before" learning setup
└── .github/workflows/ ..... CI (ci.yml basics, shopflow.yml full)
```

## 3. Track kaise karun (har kaam ka loop)
1. Kaam shuru → SESSION-LOG top entry + STATE §1/§4 padho.
2. Change karo.
3. `make lint && make validate && make test` (jo chale; jo na chale wo saaf batao).
4. STATE.md (§1–4) + SESSION-LOG (naya entry) update — **usi commit mein**.
5. Commit (message: kya+kyu). Push sirf jab Abhishek bole.

## 4. Jaldi jump
Map → START-HERE · State → STATE · Pichhla kaam → SESSION-LOG · Shabd → GLOSSARY · Commands → `make help` · Rules → CLAUDE.md
