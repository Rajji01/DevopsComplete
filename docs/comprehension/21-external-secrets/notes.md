# 21 · Secrets: literals vs External Secrets Operator — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `k8s/overlays/prod/external-secret.yaml`, `k8s/base/*/deployment.yaml` (`secretKeyRef: shopflow-db`), `irsa.tf`.

## Kya (one line)
Dev mein DB password Kustomize **literal** (git mein, throwaway); prod mein **git mein kabhi nahi** — External Secrets Operator AWS Secrets Manager se pull karke wahi `shopflow-db` k8s Secret mein sync karta (IRSA auth, refreshInterval se rotation), jo Deployments pehle se reference karte.

## Easy way (Hinglish analogy)
Ghar ki chaabi socho. **Dev** = chaabi doormat ke neeche (git mein) — ghar mein kuch keemti nahi, chalega. **Prod** = chaabi **bank locker (Secrets Manager)** mein; ek trusted helper (ESO) har ghante locker se chaabi la ke ghar ke **usi hook (`shdb` Secret)** pe taang deta jahan tum pehle se dhoondhte ho. Tumhe pata bhi nahi chalta chaabi kahan se aayi — bas hook pe mil jaati. Helper ke paas locker ka **sirf ek box** kholne ka permission (IRSA least-privilege), poora bank nahi.

## Kyu (ye problem solve karta hai)
Password kahin to rakhna hai. **Git sabse bura** — immutable, distributed, forever; ek commit = har clone/fork/history mein leak, rotate/audit kuch nahi. K8s Secret bhi sirf base64 (encrypted nahi). To prod mein: password **Secrets Manager** mein (encrypted, audited, rotatable), git mein nahi; ESO use cluster mein materialize karta jab chahiye. App ko farak nahi — wahi Secret milta.

## Kaise (step by step)
1. **Dev:** `secretGenerator` literal → `shopflow-db` Secret (base64, repo mein, dev values).
2. **Prod** (`external-secret.yaml`):
   - **SecretStore** `aws-secrets-manager`: provider AWS SecretsManager, region ap-south-1, auth = SA `external-secrets-sa`.
   - **ExternalSecret** `shopflow-db`: `refreshInterval:1h`, remote `shopflow/prod/db` (ek JSON, 4 keys) → target Secret `shopflow-db` (4 `*-db-password` keys).
   - **SA** `external-secrets-sa`: annotation `eks.amazonaws.com/role-arn` (IRSA) → `shopflow-external-secrets` role.
3. **IRSA role** (`irsa.tf`): sirf `secretsmanager:GetSecretValue`/`DescribeSecret` us **ek secret ARN** pe.
4. **Deployments**: `secretKeyRef: {name: shopflow-db, key: orders-db-password}` — dev/prod same.
5. **In-cluster security** (ESO ke alawa): encryption-at-rest (KMS) + RBAC.

## Flow diagram (prod secret → pod)
```
 AWS Secrets Manager [shopflow/prod/db]  (encrypted, rotatable, audited — NOT in git)
        ▲ GetSecretValue (IRSA: external-secrets-sa → role, read ONE secret ARN)
        │
 External Secrets Operator ── every refreshInterval(1h) ──► writes k8s Secret "shopflow-db"
        │                                                        │ (base64 in etcd — encryption-at-rest + RBAC)
 SecretStore + ExternalSecret (declare source+mapping)          ▼
                                            Deployment secretKeyRef: shopflow-db → env DB_PASSWORD → app
 dev: secretGenerator literal ─────────────────────────────────┘ (same Secret name, different source)
```

## Dev vs prod diagram
```
 concern         dev (literal)            prod (ESO + Secrets Manager)
 ───────────────────────────────────────────────────────────────────
 in git?         yes (base64, throwaway)  NO (only the ExternalSecret manifest, no value)
 encrypted?      no (base64)              AWS encrypted at rest + KMS for etcd
 rotation        manual                   refreshInterval re-sync (central rotation)
 audit           none                     CloudTrail
 auth to source  n/a                      IRSA (no keys), least-privilege ARN
```

## Alternate approaches (kab kaunsa)
| Approach | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **secretGenerator literal** *(dev)* | base64 in repo | simple | **in git**, no rotation | dev/throwaway |
| **External Secrets Operator** *(prod)* | SecretStore+ExternalSecret ← Secrets Manager | no git, rotation, IRSA, audit | operator + eventual sync | prod |
| **Sealed Secrets** | encrypt secret, commit ciphertext | git-ok (encrypted) | key mgmt, still in repo | GitOps, no cloud SM |
| **SOPS + KMS** | encrypted files in git | GitOps-friendly | tooling, decrypt in CI | GitOps |
| **CSI Secrets Store** | mount secret as volume from SM | not even a k8s Secret | driver setup | strict (no k8s Secret) |

## Understanding check
1. Dev vs prod — password kahan se aata?
2. `refreshInterval:1h` kya karta, trade-off?
3. ESO AWS se kaise auth karta, kitne permission?
4. `shopflow-db` same naam kyu — app ko farak kyu nahi?
5. "K8s Secret base64" hai to Secrets Manager kya deta (in-cluster alag kyu)?

## Interview mein ye bolo
*"Dev mein DB password Kustomize literal hai (throwaway), prod mein git mein kabhi nahi — External Secrets Operator Secrets Manager se `shopflow/prod/db` pull karke wahi `shopflow-db` k8s Secret mein sync karta (refreshInterval 1h → rotation auto). Deployments wahi `shopflow-db` reference karte, to dev→prod app code same, sirf source badla. ESO IRSA se auth karta (no keys), role sirf us ek secret ka GetSecretValue — least privilege. K8s Secret sirf base64, to encryption-at-rest + RBAC alag; aur git mein secret = permanently compromised (history immutable)."*

> Related: [`../20-eks-irsa-least-privilege/`](../20-eks-irsa-least-privilege/) (IRSA jo ESO ko auth deta), [`../08-supply-chain-signing/`](../08-supply-chain-signing/) (no-secrets-in-CI, OIDC). STATE §2 matrix: Secrets row.
</content>
