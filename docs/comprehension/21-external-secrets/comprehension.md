# 21 · Where the DB password lives: External Secrets — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `k8s/overlays/prod/external-secret.yaml`, `k8s/base/*/deployment.yaml` (`secretKeyRef: shopflow-db`), `infra/terraform/aws/irsa.tf` (external-secrets IRSA).

---

## Scenario (read cold)

> *Excerpt from ShopFlow's secrets design. Read once, then answer.*

Every service needs its database password. The question is **where that password comes from**, and ShopFlow answers it **differently in dev and prod** — on purpose.

In **dev**, the password is a Kustomize **`secretGenerator` literal** — it's in the repo (base64, dev-only values). Convenient, and fine because the dev DB is throwaway. But a Kubernetes **Secret is only base64, not encrypted** — anyone who can read the Secret (or the git repo) has the password. That's unacceptable for prod.

In **prod**, no password is ever in git. ShopFlow uses the **External Secrets Operator (ESO)**. Three objects:
- A **`SecretStore`** points at **AWS Secrets Manager** (region `ap-south-1`), authenticating as the ServiceAccount `external-secrets-sa`.
- An **`ExternalSecret`** (`shopflow-db`) says: pull the secret `shopflow/prod/db` from Secrets Manager (one JSON holding four keys — orders/inventory/notifications/payments), and **sync** it into a Kubernetes Secret **also named `shopflow-db`**. It has `refreshInterval: 1h`, so a **rotated** secret in AWS is picked up within an hour.
- The Deployments already reference `shopflow-db` via `secretKeyRef` — so the app doesn't know or care where the value came from; ESO just **materializes** the Secret the Deployment expects.

How does ESO get permission to read Secrets Manager with **no AWS access key**? The `external-secrets-sa` ServiceAccount is annotated with an IAM role ARN (**IRSA**); that role's policy allows only `secretsmanager:GetSecretValue`/`DescribeSecret` on **that one secret's ARN** (`irsa.tf`). So the operator can read exactly the DB secret and nothing else — least privilege again.

The result: the password lives in **AWS Secrets Manager** (encrypted, audited, rotatable), **never in git**, and reaches the pod as the same `shopflow-db` Secret the Deployment already used. The source changed; the app didn't.

---

## Questions

### A — Literal
1. Where does the DB password come from in dev vs prod?
2. What three object kinds make up the prod setup, and what does `refreshInterval: 1h` do?
3. How does the External Secrets Operator authenticate to AWS Secrets Manager — and with what permissions?

### B — Inference
4. The `ExternalSecret` target is named `shopflow-db` — the same name the Deployments' `secretKeyRef` already used. Why does that naming choice mean the app code / Deployments need **no change** when moving dev→prod?
5. "A Kubernetes Secret is only base64, not encrypted." Given that, what does using Secrets Manager actually buy you — the Secret still lands in the cluster as base64?

### C — Trade-off
6. Dev uses in-repo literal secrets; prod uses ESO + Secrets Manager. Why is the in-repo literal acceptable in dev but negligent in prod, and what's the cost of ESO you accept for that safety?
7. `refreshInterval: 1h` means a rotated password is picked up within an hour, not instantly. What's the trade-off of a longer vs shorter interval?

### D — Transfer / extension
8. You add a new secret — a third-party API key for notification-service. Using the existing pattern, what do you create/change, and what must the IRSA policy allow (and **not** over-allow)?
9. A Secret in Kubernetes is base64, readable by anyone with `get secret` RBAC. Beyond ESO, name two more controls that make the *in-cluster* secret genuinely secure.

### E — Spot-the-flaw
10. To "simplify", a dev deletes the `ExternalSecret` and hardcodes the prod DB password as a `secretGenerator` literal in `overlays/prod` "just like dev". It works. Why is this a serious incident even though nothing breaks functionally, and what has now happened that can't be undone?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **Dev:** a Kustomize `secretGenerator` **literal** (base64, in the repo, throwaway values). **Prod:** pulled from **AWS Secrets Manager** by the External Secrets Operator into the `shopflow-db` Secret — never in git.
2. **`SecretStore`** (points at Secrets Manager, auths as `external-secrets-sa`), **`ExternalSecret`** (maps the remote `shopflow/prod/db` keys → the `shopflow-db` Secret), and the **`ServiceAccount`** (IRSA-annotated). `refreshInterval: 1h` = re-pull from Secrets Manager hourly, so a **rotated** secret is picked up within an hour.
3. Via **IRSA** — the `external-secrets-sa` ServiceAccount is annotated with an IAM role, and that role's policy allows only `secretsmanager:GetSecretValue`/`DescribeSecret` on **that one secret's ARN**. No access key; least privilege.
</details>

<details>
<summary>B — Inference</summary>

4. Because the contract between the app and the secret is the **Kubernetes Secret named `shopflow-db`** (referenced via `secretKeyRef`), not where its value originated. In dev a `secretGenerator` produces that Secret; in prod ESO **materializes the same-named Secret** from Secrets Manager. The Deployment reads `shopflow-db` either way, so the pod template, env vars, and app code are identical — only the *source* of the Secret swapped. This is the overlay pattern: same base, different source per environment.
5. The in-cluster Secret being base64 is about **access inside the cluster** (handled by RBAC + encryption-at-rest). Secrets Manager addresses a **different** set of problems: the password is no longer **in git** (the biggest leak vector — commits, forks, history, laptops), it's **encrypted at rest in AWS with audit logs**, it can be **rotated centrally** (and ESO re-syncs), and access is **IAM-controlled**. So ESO removes the *git/source* exposure and adds rotation/audit; RBAC + encryption-at-rest handle the *in-cluster* exposure. Both layers are needed; ESO isn't claiming to encrypt the in-cluster Secret.
</details>

<details>
<summary>C — Trade-off</summary>

6. In **dev** the DB is throwaway with disposable credentials, so a leaked dev password costs ~nothing and the convenience of a literal is worth it. In **prod** the password guards real customer data; putting it in git exposes it to everyone with repo access **forever in history**, with no rotation or audit — a breach waiting to happen. The **cost of ESO** you accept: operational complexity — running the operator, a `SecretStore`/`ExternalSecret` per secret, IRSA wiring, and an eventual-consistency (hourly) sync instead of a static value. Worth it to keep prod secrets out of git.
7. A **shorter** interval picks up a rotation faster (smaller window where the old password is still live in-cluster) but adds more calls to Secrets Manager (cost/throttling) and churn. A **longer** interval is cheaper/quieter but means a rotated or revoked secret lingers in the cluster longer. 1h is a common balance; for a credential you rotate aggressively on compromise you'd shorten it (or trigger an immediate refresh).
</details>

<details>
<summary>D — Transfer / extension</summary>

8. Add the key to the Secrets Manager secret (or a new secret), add a **data entry** to the `ExternalSecret` (or a new `ExternalSecret`) mapping that remote key → a key in a target Secret, and reference it from notification-service's Deployment via `secretKeyRef`. The **IRSA policy** must allow `secretsmanager:GetSecretValue` on **that specific secret's ARN** — and must **not** broaden to `Resource: "*"` or all of Secrets Manager (that would let a compromised operator read every secret in the account). One secret, one ARN; least privilege.
9. (a) **Encryption at rest** for etcd (KMS-backed envelope encryption), so the Secret isn't plaintext-at-rest in etcd; (b) **RBAC** tightly scoping who/what can `get`/`list` Secrets in the namespace (and `automountServiceAccountToken: false` where not needed); plus (c) NetworkPolicies and not logging secret values. ESO keeps it out of git; these keep it safe *inside* the cluster.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. Hardcoding the **prod** password as a literal commits it to **git** — and git history is **immutable and distributed**: the secret is now in every clone, every fork, the remote's history, CI caches, and developer laptops, **permanently**, even if you "delete" it in a later commit. Nothing breaks functionally, which is exactly why it's dangerous — it's a silent, serious **credential exposure**. What can't be undone: the password must now be treated as **compromised and rotated** (you can't un-leak it); scrubbing git history is painful and never fully reliable across forks/mirrors. The whole point of ESO was to make this impossible; the "simplification" reintroduced the one thing prod secrets management exists to prevent.
</details>

---

## Tod ke dekho — Local (render/validate; live needs AWS + ESO)

```bash
# the prod overlay renders the ESO objects; validate structure without a cluster:
kubectl kustomize shopflow/k8s/overlays/prod | grep -A3 -E "kind: (SecretStore|ExternalSecret)"
cd infra/terraform/aws && terraform validate      # the IRSA role + read-db-secret policy

# prove the least-privilege shape (read-only, one secret):
grep -n -A12 "read_db_secret" infra/terraform/aws/irsa.tf   # GetSecretValue/DescribeSecret on the DB secret ARN only
grep -rn "shopflow-db\|secretKeyRef" shopflow/k8s/base/order-service/deployment.yaml   # the Deployment just references the Secret
```

**Proved:** the Deployment references `shopflow-db` the same in dev and prod; in prod the `ExternalSecret` fills it from Secrets Manager via an IRSA role scoped to exactly that secret. A live demo needs an EKS cluster with ESO installed + an AWS secret (STATE §4 / your account).

## Tod ke dekho — Online
AWS + EKS + ESO chahiye → [`ONLINE-LABS.md`](../ONLINE-LABS.md) (real-deploy note). Bina account: `kustomize build` + `terraform validate`.

---

## Interview concepts is passage se
- **Secrets: dev (literal) vs prod (External Secrets Operator ← Secrets Manager)** — nothing in git.
- **ESO flow** — `SecretStore` + `ExternalSecret` sync into a k8s Secret the Deployment already references.
- **"Secret is only base64"** — ESO removes git exposure; RBAC + encryption-at-rest secure in-cluster.
- **Rotation** — `refreshInterval` picks up rotated secrets; central rotation in Secrets Manager.
- **IRSA least privilege** — read exactly one secret ARN, no keys, no wildcard.
- **Git is forever** — a committed secret is compromised + unrevokable.

**Interviewer's lens:** "secret kahan rakhte ho?" → git mein kabhi nahi; "k8s Secret encrypted?" → nahi, base64 (encryption-at-rest + RBAC + ESO chahiye); aur committed-secret = compromised ka samajhna.

> Interview line: *"Dev mein DB password Kustomize literal hai (throwaway), par prod mein git mein kabhi nahi — External Secrets Operator Secrets Manager se `shopflow/prod/db` pull karke `shopflow-db` k8s Secret mein sync karta (refreshInterval 1h → rotation auto). Deployments wahi `shopflow-db` reference karte, to dev→prod app code same — sirf source badla. ESO IRSA se auth karta (no keys), role sirf us ek secret ka GetSecretValue de — least privilege. K8s Secret sirf base64 hai, to encryption-at-rest + RBAC alag se; aur git mein secret daalna = permanently compromised."*
</content>
