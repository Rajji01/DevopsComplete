# 20 · EKS, IRSA & least privilege — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `infra/terraform/aws/irsa.tf`, `eks.tf`, `ci-oidc.tf`, SELF-WALK Walk 19.

## Kya (one line)
Prod EKS pe pods ko AWS access **IRSA** (IAM Roles for Service Accounts) se milta — ServiceAccount ek IAM role se mapped, pod apna short-lived SA-token STS `AssumeRoleWithWebIdentity` ko deke temporary creds leta, **koi access key kahin nahi** — aur har role least-privilege (explicit actions + resource ARN).

## Easy way (Hinglish analogy)
Office socho. **Purana tarika** = har employee ko ek **master chaabi** de do (access key) — kho gayi/chori hui to hamesha chalti rahegi, poora office khula. **IRSA** = employee apna **ID card** (SA token) reception (STS) ko dikhata, reception OIDC se verify karke **ek ghante ka visitor pass** deta jo sirf uske floor ke liye valid (temporary + scoped). Chaabi kahin rakhi hi nahi. Aur pass pe likha "sirf 3rd floor read-only" (least privilege) — compromise ho to bhi poora building nahi.

## Kyu (ye problem solve karta hai)
Long-lived access keys sabse bada cloud-credential risk: git mein commit, image mein bake, logs mein leak — aur leak hui to **hamesha** chalti. IRSA inhe hatata: temporary, auto-rotated, per-SA creds — leak short-lived + narrowly scoped.
Aur least privilege: wildcard (`*Topic*`) chup-chaap zyada de deta (DeleteTopic) → compromised pod apna event topic uda sakta. Explicit actions + ARN scoping se blast radius bounded.

## Kaise (step by step)
1. **OIDC provider:** EKS cluster ka OIDC provider (`module.eks.oidc_provider_arn`) AWS trust karta.
2. **SA↔role:** Terraform `namespace_service_accounts` se SA → IAM role (e.g. `shopflow:order-service` → kafka-clients role). SA annotation pe role ARN.
3. **Token exchange:** pod ka projected SA token (JWT) → STS `AssumeRoleWithWebIdentity` → STS OIDC se verify + role trust-policy check → temporary creds.
4. **Least privilege (irsa.tf):** 3 roles — external-secrets (read DB secret), kafka-clients (Connect/Describe/Create/Read/WriteData + group, **ARN-scoped** to `orders.events*`/`payments.events*`, **no DeleteTopic/AlterTopic** — Walk 19), lb-controller (ALB).
5. **CI bhi same:** `ci-oidc.tf` — GitHub Actions OIDC → AWS role (no keys in secrets); subject = repo/workflow/branch.

## Flow diagram (pod → AWS, no key)
```
 Pod (SA: order-service, annotated role ARN)
   │ projected SA token (short-lived JWT, signed by EKS OIDC)
   ▼
 STS AssumeRoleWithWebIdentity
   │ verify token vs trusted OIDC provider + role trust policy (subject = ns:sa)
   ▼
 temporary creds (auto-rotated) scoped to the role
   │
 role policy: least privilege → actions [Connect,Read,WriteData,...]  on  ARNs [orders.events*, payments.events*]
                                   (NO DeleteTopic / NO Resource "*")
```

## Blast-radius diagram (least privilege)
```
 grant                         compromised pod can...
 ─────────────────────────────────────────────────────
 kafka-cluster:*  on  *        delete/alter/read/write ANY topic & group   ← catastrophic
 *Topic*          on  topic/*  silently incl. DeleteTopic (Walk 19 bug)     ← can delete own topic
 explicit actions on orders.events*/payments.events*  →  read/write only its own topics  ← bounded ✅
```

## Alternate approaches (kab kaunsa)
| Approach | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **IRSA (OIDC+STS)** *(ShopFlow)* | SA→role, temp creds | no keys, scoped, rotated | OIDC/role setup | EKS prod |
| **Access key per pod** | key in secret/env | simple | long-lived leak = forever | kabhi nahi |
| **Node instance role (kiam/kube2iam legacy)** | node-level role | pre-IRSA | all pods share node role | legacy |
| **EKS Pod Identity** | newer AWS assoc | simpler than IRSA | newer, migrating | new clusters |
| **Wildcard IAM** | `*` actions/resource | "just works" | silent over-grant | kabhi nahi |

## Understanding check
1. IRSA full form + pod kya, kis API ko present karta?
2. SA↔role mapping kaun karta, ek example?
3. Kaunse 2 topic actions deliberately nahi diye, kyu?
4. `*Topic*` wildcard ka silent risk (Walk 19)?
5. CI OIDC aur pod IRSA same mechanism — subject kya-kya?

## Interview mein ye bolo
*"Prod EKS pe pods ko AWS access IRSA se — no access keys. Har SA ek IAM role se annotated; pod apna projected SA token (JWT) STS `AssumeRoleWithWebIdentity` ko deta, STS cluster ke OIDC se verify karke temporary creds deta. Har role least-privilege: Kafka role sirf Connect/Describe/Create/Read/WriteData, specific topic ARNs (`orders.events*`) pe scoped — `DeleteTopic` nahi, kyunki `*Topic*` wildcard chup-chaap DeleteTopic de raha tha (Walk 19), compromised pod apna topic uda sakta. Wahi OIDC CI ke liye — GitHub Actions bina AWS key ke role assume karta, subject = repo/workflow/branch."*

> Related: [`../08-supply-chain-signing/`](../08-supply-chain-signing/) (GitHub OIDC keyless cosign), [`../18-hpa-pdb-autoscaling/`](../18-hpa-pdb-autoscaling/) (same EKS). SELF-WALK Walk 19 (the least-privilege fix).
</content>
