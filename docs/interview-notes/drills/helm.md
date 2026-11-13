# Helm — interview drill set (ShopFlow edition)

> **Ye kya hai:** ek **question-only drill sheet** — basic se prod tak. Helm/k8s study notes `../05-kubernetes.md` + `../06-cicd-gitops.md` mein hain; ye wo set hai jise **ek-ek karke mock-interview** ki tarah drill karna.
>
> **Tags:** `[C]` = core · `[SF]` = ShopFlow se defend · `[P]` = prod / failure scenario.

## ShopFlow mein Helm kahan (grounding)

- **Chart:** `shopflow/helm/shopflow-service` — **ek reusable chart**, har service apne `values` se (order/inventory/payment/notification).
- **Templates:** `deployment.yaml`, helpers `shopflow.labels` / `shopflow.selectorLabels` (`_helpers.tpl`).
- **`fail` guard:** `{{- if and .Values.replicas .Values.hpa.enabled }}{{- fail "..." }}` — `replicas` **ya** HPA, dono nahi (fixed replicas har upgrade pe HPA se ladta — Walk 68).
- **Conditionals:** `{{- with .Values.replicas }}`, monitoring annotations `{{- if .Values.monitoring.scrape }}`, `serviceAccount.name`, `profiles`, `env`/`envFrom` blocks.
- **DB wiring:** `DB_URL`, `DB_USERNAME = {{ .Values.db.name }}` (username==dbname convention — Walk 119), `DB_PASSWORD` via `secretKeyRef`.
- **Kustomize bhi hai** (base+overlays) — do approaches jaan-bujh ke; values drift na ho iska dhyaan.

---

## TIER 1 — Fundamentals
1. Helm kya hai aur kyun — plain `kubectl apply` / Kustomize ke hote hue? `[SF][C]`
2. Chart, release, repository — teeno kya? `[C]`
3. `values.yaml` + templates + `_helpers.tpl` — kaise milke manifest bante? `[SF][C]`
4. `helm install` vs `helm upgrade` vs `helm template` — kab kaun? `[C]`
5. ShopFlow ek hi chart se 4 services kaise deploy karta (per-service values)? `[SF]`
6. `helm lint` / `helm template` CI mein kyun chalate ho? `[SF][P]`

## TIER 2 — Templating craft
7. `{{ .Values.x }}` vs `{{- .Values.x }}` — `-` (whitespace chomp) kya karta? `[C]`
8. `{{- with .Values.replicas }}` block ka faida kya (nil-safety)? ShopFlow kahan use karta? `[SF][C]`
9. `{{- if ... }}` conditional rendering — ShopFlow monitoring annotations / HPA mein kaise? `[SF]`
10. **Named templates** (`define`/`include`) — ShopFlow `shopflow.labels` helper kyun, repeat se kaise bachta? `[SF][C]`
11. `include` vs `template` — Helm mein `include` kyun prefer (pipe to `nindent`)? `[C]`
12. `toYaml` + `nindent` ka combo kya karta (ShopFlow `resources`, `env`)? `[SF]`
13. `required` aur **`fail`** functions — ShopFlow `fail` guard (replicas vs HPA) kis problem ko build-time pe pakadta? `[SF][P]`

## TIER 3 — Values, environments, overrides
14. Values precedence — default `values.yaml` < `-f custom.yaml` < `--set` — order kya? `[C]`
15. Multi-environment (dev/stage/prod) Helm se kaise? (per-env values files) `[P]`
16. **Helm vs Kustomize** — dono ka philosophy farq (templating vs overlay/patch)? ShopFlow dono kyun rakhta? `[SF][C]`
17. Helm aur Kustomize dono rakhne pe **value drift** ka risk — kaise avoid karte ho (ek source of truth)? `[SF][P]`
18. Secret values Helm mein kaise handle karte (plain values.yaml mein kyun nahi, `secretKeyRef`/helm-secrets/external)? `[SF][P]`

## TIER 4 — Release lifecycle & prod
19. `helm rollback` kaise kaam karta — kya store hota, release history kahan? `[P]`
20. Ek bad `helm upgrade` production gira gaya — rollback ka exact step, aur `--atomic` / `--wait` kya karte? `[P]`
21. Helm **hooks** (pre-install, post-upgrade, test) — ek real use-case (DB migration)? `[P]`
22. `helm upgrade` ke baad pods restart nahi hue jabki ConfigMap badla — kyun, aur checksum-annotation trick kya? `[P][C]`
23. Chart versioning — `version` vs `appVersion` ka farq? `[C]`
24. Helm chart ko GitOps (Argo CD) ke saath kaise use karte? (Argo Helm support vs `helm template` render) `[SF][P]`

## TIER 5 — Scenarios / failure
25. `helm upgrade` "another operation in progress" / pending-upgrade mein atak gaya — kaise recover? `[P]`
26. Values typo se galat manifest render hua par apply ho gaya — isse pehle kaise pakadte (`helm template | kubeconform`, lint, CI)? `[SF][P]`
27. Ek chart ko 20 teams use kar rahi, breaking change aana hai — kaise safely roll out (version bump, deprecation)? `[P]`
28. `--set` se override karne par nested value galat merge — list vs map merge behaviour ka gotcha? `[P]`

---

### Drill log
| # | Topic | Attempted | Confident |
|---|---|---|---|
| 1–6 | Fundamentals | ☐ | ☐ |
| 7–13 | Templating craft | ☐ | ☐ |
| 14–18 | Values & environments | ☐ | ☐ |
| 19–24 | Release lifecycle | ☐ | ☐ |
| 25–28 | Scenarios/failure | ☐ | ☐ |

> Tip: ShopFlow ka `fail` guard (Q13) aur Helm-vs-Kustomize drift (Q16–17) — ye tere project ke asli decisions hain, inpe confidently bol sakta hai. Yahi interviewer ko impress karta: "maine deliberately dono rakhe, aur drift se aise bacha."
