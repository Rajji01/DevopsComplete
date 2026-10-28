# 14 · Packaging manifests: Kustomize vs Helm — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `k8s/base/` + `k8s/overlays/{dev,prod}/` (Kustomize), `helm/shopflow-service/` + `helm/values/*` (Helm), `helm/README.md`.

---

## Scenario (read cold)

> *Excerpt from ShopFlow's "two ways to package the same manifests" note. Read once, then answer.*

The same service needs slightly different manifests per environment — dev points at an in-cluster Postgres/Kafka/Redis/Keycloak; prod points at RDS/MSK/ElastiCache/Cognito, adds External Secrets and IRSA service accounts, and runs more replicas. ShopFlow keeps **two** ways to manage that, side by side, so they can be compared.

**Kustomize** (`k8s/`) is **copy + patch, no templating logic**. A `base/<service>` holds plain, valid YAML; an **overlay** (`overlays/dev`, `overlays/prod`) patches it — changing the image, replica count, env, adding prod-only resources. There are no `if`s or loops: what you read is what you get, which is **readable** but means `base/<service>` is **copied per service** (order, inventory, payment, notification each have their own base). The **prod overlay pins the image to the commit SHA** that CI built, and **Argo CD syncs exactly that** rendered output into the cluster (GitOps).

**Helm** (`helm/shopflow-service`) is **template + values**: one **generic chart** with `if`/`range`/helpers, and a small (~15-line) **values file per service/env** (`values/order-service.yaml`, `values/order-service-prod.yaml`). One chart serves all four services; a new service is a new values file, not a new copy of the manifests. Helm tracks **releases**, so `helm history` and `helm rollback <release> <rev>` work per release. Helm is also **the standard for third-party software** — ingress-nginx, External Secrets, Argo CD itself all ship as Helm charts.

The note's framing: Kustomize's strength (no logic) is also its weakness (repetition); Helm's strength (templating) is also its weakness (a manifest with `if`/`range` is harder to read and easier to break). Most real teams use **both**: Helm for vendor charts, and either Kustomize or Helm-values-per-env for their own apps. The **rule of thumb**: *the fourth time you copy `base/<service>`, you want a chart.*

---

## Questions

### A — Literal
1. In one phrase each, what is Kustomize's model and what is Helm's model?
2. How does each tool handle serving **four** services — what gets duplicated in Kustomize, and what in Helm?
3. What does the prod **overlay** pin the image to, and which tool syncs it to the cluster?

### B — Inference
4. Kustomize overlays contain "no logic." Why is that simultaneously the reason they're easy to read **and** the reason they repeat themselves?
5. The note says Helm is "the standard for third-party software." Why does templating matter more for a vendor chart (ingress-nginx, External Secrets) than for your own four similar services?

### C — Trade-off
6. Helm gives `helm rollback` per release; Kustomize relies on git history + `kubectl rollout undo`. What does the Helm model give you that the Kustomize model doesn't, and what's the argument that git history is "enough"?
7. A manifest with `{{- if .Values.prod }}...{{- range .Values.env }}` is powerful. What concrete failure modes does that power introduce that a flat Kustomize base cannot have?

### D — Transfer / extension
8. Your team adds a 5th and 6th microservice, all structurally identical to the existing four. Using the rule of thumb, which tool should own your own apps now, and what exactly would adding the 6th service cost in each model?
9. You must deploy **ingress-nginx** (a third-party component) and **your own order-service**. Argue the common split: which tool for which, and why mixing them is normal rather than inconsistent.

### E — Spot-the-flaw
10. A dev deletes `base/inventory-service` and makes the dev overlay's inventory config a 300-line inline patch "to reduce file count." A week later, a one-line prod-only change to inventory requires editing the dev overlay too, and a typo in the patch ships to prod. What Kustomize principle was violated, and what should base-vs-overlay actually contain?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **Kustomize:** copy + patch (a base plus per-environment overlays, no templating). **Helm:** template + values (one parameterized chart rendered with a values file).
2. **Kustomize:** the `base/<service>` manifests are **copied per service** (four bases). **Helm:** **one chart** is reused; each service is just another **values file** (nothing structural duplicated).
3. The prod overlay pins the image to the **commit SHA** CI built; **Argo CD** syncs that rendered output into the cluster (GitOps).
</details>

<details>
<summary>B — Inference</summary>

4. "No logic" means every overlay is literal YAML you can read top-to-bottom with no mental interpreter — nothing is hidden behind a conditional. But because there's no abstraction mechanism, two services that are 95% identical can't *share* that 95% through a loop or template; each needs its own concrete base, so the common parts are physically **copied**. Readability and repetition are two sides of "what you see is exactly what runs."
5. A vendor chart must serve **thousands of different users' configurations** you can't anticipate — optional features, varying replica counts, TLS on/off, different ingress classes — so it needs `if`/`range` to be flexible. Your own four services are **under your control and nearly identical**, so the flexibility buys little; the templating overhead (harder to read/debug) isn't justified the way it is for software meant to be configured by strangers.
</details>

<details>
<summary>C — Trade-off</summary>

6. Helm tracks **named releases with revisions**, so `helm rollback order-service 3` restores the *entire* previous release (all its resources and values) as one atomic unit, and `helm history` shows what changed when — state Helm stores for you. The argument that git is "enough": your manifests are **already** versioned in git, so the real source of truth is the commit; `git revert` + re-sync (Argo CD) rolls back the same way, without Helm needing to store release state in-cluster. GitOps makes git the rollback mechanism.
7. Templating can **render invalid YAML** (a bad `{{ }}` produces malformed output that only fails at apply), **hide bugs behind conditions** that only trigger in one environment (the prod-only branch no one tested), and make **"what will actually deploy?"** require running `helm template` to know. A flat Kustomize base is already valid YAML you can read directly — none of those failure modes exist because there's nothing to evaluate.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. By the rule of thumb (the 4th copy → you want a chart), with 5–6 near-identical services your own apps should move to a **Helm chart** (one chart + a values file per service). Adding the 6th service then costs **one ~15-line values file** in Helm, versus **a whole new copied `base/<service>` directory** (deployment, service, hpa, pdb, kustomization) to maintain in Kustomize — and any cross-cutting change (e.g. a new securityContext field) means editing the chart once vs editing six bases.
9. **ingress-nginx → Helm** (it's published as a Helm chart; you consume it with a values file and get upstream updates via `helm upgrade`). **order-service → Kustomize or Helm-values** (your own app, your own manifests). Mixing is normal because the two tools solve different problems: Helm is how you **consume** third-party software you don't author, Kustomize/Helm-values is how you **manage** apps you do. "Both" isn't inconsistency — it's using each where it fits.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. It violates Kustomize's core principle: **the base holds the common, canonical manifest; overlays hold only the per-environment *differences*.** Inlining inventory's whole manifest into the dev overlay means there's **no shared base**, so prod and dev no longer derive from one source — a change meant for both must be made twice (drift risk), and a change meant for prod-only has nowhere clean to live, so it leaks into a giant patch where a typo ships. Base-vs-overlay should be: **base = the full, valid, environment-agnostic manifest; overlay = a small patch** (image, replicas, env, prod-only add-ons). The fix is to restore the base and keep each overlay thin.
</details>

---

## Tod ke dekho — Local (render both, no cluster needed)

Goal: see the *same* service rendered by Kustomize overlays and by Helm, and diff them.

```bash
# Kustomize: render dev and prod overlays (no apply)
kubectl kustomize shopflow/k8s/overlays/dev  | head -60
kubectl kustomize shopflow/k8s/overlays/prod | grep -E 'image:|replicas:|kind:' | head
#   prod pins image to a SHA, adds ExternalSecret / service accounts; dev points at in-cluster deps

# Helm: render the same service from the chart + values (no apply)
helm template order-service shopflow/helm/shopflow-service -f shopflow/helm/values/order-service.yaml | head -60
helm template order-service shopflow/helm/shopflow-service \
  -f shopflow/helm/values/order-service.yaml -f shopflow/helm/values/order-service-prod.yaml | grep -E 'image:|replicas:'

# both must produce schema-valid manifests (what CI checks):
kubectl kustomize shopflow/k8s/overlays/prod | kubeconform -strict -summary -ignore-missing-schemas -
helm template order-service shopflow/helm/shopflow-service -f shopflow/helm/values/order-service.yaml | kubeconform -strict -summary -
```

**Proved:** one base/chart → different per-env output; Kustomize = literal patched YAML, Helm = templated from a tiny values file. Apply on minikube with `make k8s-dev` (Kustomize) or `make helm-dev` (Helm).

> `make validate` runs exactly these renders + kubeconform (the CI gate).

## Tod ke dekho — Online
Render-only → [`ONLINE-LABS.md`](../ONLINE-LABS.md) **section A (Codespaces)** (kustomize/helm/kubeconform). Apply to a cluster → **section B (Cloud Shell + minikube)**.

---

## Interview concepts is passage se
- **Kustomize (copy+patch) vs Helm (template+values)** — model aur trade-off.
- **base + overlays (dev/prod)** — environment differences, image SHA pin.
- **GitOps (Argo CD)** — git = source of truth, rollback via revert + sync.
- **Helm releases / rollback** vs **git history + `rollout undo`**.
- **Vendor charts** — third-party software Helm se consume hota.
- **Rule of thumb** — 4th copy → chart; readability vs reuse.

**Interviewer's lens:** "tum kaunsa use karte ho aur kyu" — sahi jawab "both, where each fits" (vendor=Helm, own apps=Kustomize/Helm-values), aur templating ke concrete failure modes bol pana.

> Interview line: *"Kustomize copy+patch hai — base + dev/prod overlays, no logic, isliye readable par har service ka base copy hota; prod overlay image ko commit SHA pe pin karta aur Argo CD wahi sync karta (GitOps). Helm template+values hai — ek chart + per-service ~15-line values file, if/range/helpers, aur helm rollback per release. Helm third-party charts (ingress-nginx, External Secrets) ke liye standard hai. Hum dono use karte: vendor ke liye Helm, apni apps ke liye Kustomize/Helm-values. Rule of thumb: jab base/<service> chauthi baar copy karo, chart chahiye."*
</content>
