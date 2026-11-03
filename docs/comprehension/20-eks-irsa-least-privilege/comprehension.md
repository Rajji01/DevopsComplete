# 20 · How a pod gets AWS permissions without a single access key (IRSA) — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `infra/terraform/aws/irsa.tf`, `eks.tf`, `ci-oidc.tf`, `docs/SELF-WALK.md` Walk 19 (least-privilege Kafka role).

---

## Scenario (read cold)

> *Excerpt from ShopFlow's AWS prod design. Read once, then answer.*

In prod, ShopFlow runs on **EKS**, and its pods need AWS permissions: order-service produces/consumes on **MSK** (Kafka with IAM auth), External Secrets reads the DB password from **Secrets Manager**, the load-balancer controller creates the **ALB**. The naive way to grant that is to bake an **access key** into each pod (env var / secret). ShopFlow does **not** — there are **no long-lived AWS keys anywhere**. It uses **IRSA (IAM Roles for Service Accounts)**.

IRSA works by **OIDC federation**. The EKS cluster has an **OIDC provider**; a Kubernetes **ServiceAccount** is annotated with an IAM **role ARN**; each pod gets a **projected ServiceAccount token** (a short-lived JWT) mounted in. When the pod calls AWS, the SDK presents that token to **STS `AssumeRoleWithWebIdentity`**, STS verifies it against the cluster's OIDC provider, and hands back **temporary** credentials for exactly that role. No static key is ever stored; the credentials are short-lived and auto-rotated. The Terraform maps `namespace:serviceaccount` → role, e.g. `shopflow:order-service` → the Kafka-clients role.

Each role is **least privilege**. The Kafka-clients role (for `order-service`, `notification-service`, `payment-service`) allows exactly what a producer/consumer needs — `Connect`, `DescribeCluster`, `CreateTopic`, `DescribeTopic`, `WriteData`, `ReadData`, and group `AlterGroup`/`DescribeGroup` — **scoped by resource ARN** to the `orders.events*` / `payments.events*` topics and the services' own consumer groups. It deliberately does **not** grant `DeleteTopic` or `AlterTopic`.

That last point is a real finding (Walk 19). An earlier version used `kafka-cluster:*Topic*` to cover "topic actions" — which **silently includes `DeleteTopic` and `AlterTopic`**. A compromised order-service pod could then **delete its own event topic**, a catastrophic blast radius from one wildcard. The fix was to enumerate the exact actions needed and scope them to specific topic ARNs — so even a fully compromised pod can only read/write its own topics, never destroy them.

The same OIDC idea secures CI: `ci-oidc.tf` lets the **GitHub Actions** workflow assume an AWS role via GitHub's OIDC provider — so the pipeline pushes images / signs with **no AWS keys in GitHub secrets** either.

---

## Questions

### A — Literal
1. What does IRSA stand for, and what does a pod present (and to which AWS API) to get credentials?
2. What maps a Kubernetes ServiceAccount to an IAM role, and give one example mapping from the design.
3. Which two topic actions does the Kafka-clients role deliberately **not** grant, and why?

### B — Inference
4. "No long-lived AWS keys anywhere." Walk the IRSA flow and explain *where* the trust comes from if there's no stored secret.
5. The role is scoped by **resource ARN** to `orders.events*`/`payments.events*`, not `*`. What does that scoping buy beyond the action-list restriction?

### C — Trade-off
6. IRSA (OIDC federation, temporary creds) vs the old way (an access key per pod/service). What does IRSA remove as a risk, and what's the added complexity cost?
7. The Walk-19 fix replaced `kafka-cluster:*Topic*` with an explicit action list. What's the trade-off of enumerating actions (vs a convenient wildcard), and why is it worth it for an IAM policy specifically?

### D — Transfer / extension
8. A new `analytics-service` pod needs **read-only** access to the `orders.events` topic (consume, never produce). Using the existing pattern, what do you create/change, and what must you be careful **not** to copy from the order-service role?
9. `ci-oidc.tf` gives GitHub Actions an AWS role with no stored keys. Explain why that's the same mechanism as pod IRSA, and what the "subject" being verified is in each case (pod vs CI).

### E — Spot-the-flaw
10. To "unblock a migration quickly," a dev grants the Kafka-clients role `Action: ["kafka-cluster:*"]` on `Resource: "*"` "temporarily". A security review flags it immediately. Describe the concrete blast radius this creates for a single compromised order-service pod, and why "temporary" wildcards are the classic IAM trap.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **IAM Roles for Service Accounts.** A pod presents its **projected ServiceAccount token** (a short-lived JWT) to **STS `AssumeRoleWithWebIdentity`**, which returns temporary credentials for the annotated role.
2. The **Terraform `oidc_providers.namespace_service_accounts` mapping** (ServiceAccount annotated with the role ARN). Example: `shopflow:order-service` → the Kafka-clients role (and `shopflow:external-secrets-sa` → the read-DB-secret role).
3. **`DeleteTopic`** and **`AlterTopic`** — a producer/consumer never needs them, and granting them would let a compromised pod destroy/reconfigure its own event topic (the Walk-19 blast-radius fix).
</details>

<details>
<summary>B — Inference</summary>

4. The trust comes from **OIDC federation**, not a stored secret. The EKS cluster runs an **OIDC provider** that AWS is configured to trust; each pod's projected SA token is a JWT **signed by that provider** and scoped to a specific `namespace:serviceaccount`. When the pod calls STS `AssumeRoleWithWebIdentity`, STS **verifies the token's signature and claims against the trusted OIDC provider** and checks that the role's trust policy allows that subject — then issues short-lived creds. So the "secret" is replaced by a **cryptographically verifiable identity** (who signed the token + which SA it's for) plus a role trust policy; nothing long-lived is stored or can leak.
5. Resource-ARN scoping limits **which topics** the allowed actions apply to. Even with the right actions, the pod can only `ReadData`/`WriteData` on `orders.events*`/`payments.events*` — **not** other teams' topics, internal topics, or the whole cluster. So a compromised pod's reach is bounded on **both** axes: *what* it can do (action list) **and** *to what* (resource ARN). Dropping either to `*` widens the blast radius.
</details>

<details>
<summary>C — Trade-off</summary>

6. **IRSA removes** the biggest cloud-credential risk: **long-lived access keys** that get committed to git, baked into images, logged, or leaked and then work forever. Temporary, auto-rotated, per-SA creds mean a leak is short-lived and narrowly scoped. **Cost:** more moving parts — an OIDC provider, per-SA IAM roles and trust policies, Terraform wiring, and understanding the AssumeRoleWithWebIdentity flow. It's more to set up and reason about than "paste a key", but it eliminates a whole class of breaches.
7. Enumerating actions is **more verbose and needs updating** when a genuinely new permission is required (vs a wildcard that "just works"). But for IAM it's worth it because a wildcard **silently grants permissions you never intended** — `*Topic*` quietly includes `DeleteTopic`. You can't see, from `*Topic*`, that you've handed out destruction rights. An explicit list makes the grant **auditable and intentional**: every permission is there because someone decided it should be, and a review can see exactly what a compromised principal could do. Least privilege is only real if the policy is legible.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. Create a **new ServiceAccount** (`shopflow:analytics-service`) and a **new IRSA role** mapped to it, with a policy allowing only `Connect`, `DescribeCluster`, `DescribeTopic`, **`ReadData`**, and `AlterGroup`/`DescribeGroup` for its **own** consumer group, scoped by ARN to `orders.events*`. **Do not copy** `WriteData` (it's read-only), `CreateTopic`, or the other services' group ARNs from the order-service role — copying a broader role is how least-privilege erodes. A new role per workload with exactly its needs, not a shared "kafka" role.
9. Both are **OIDC federation → STS AssumeRoleWithWebIdentity**: a short-lived JWT from a trusted OIDC provider is exchanged for temporary AWS creds, with the role's trust policy restricting **which subject** may assume it. The difference is the **provider and subject**: for a **pod**, the provider is the **EKS cluster's OIDC**, and the subject is `system:serviceaccount:shopflow:order-service`; for **CI**, the provider is **GitHub's OIDC** (`token.actions.githubusercontent.com`) and the subject is the **repo/workflow/branch** (e.g. `repo:Rajji01/DevopsComplete:ref:refs/heads/main`). Same mechanism, different identity provider — and in both cases, **no stored AWS key**.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. `kafka-cluster:*` on `Resource: "*"` grants **every** Kafka-cluster action on **every** resource in the cluster. A single compromised order-service pod could then **delete or alter any topic** (`DeleteTopic` on `payments.events`, `orders.events`, anything), **read/write every other service's topics and consumer groups**, and disrupt the whole event backbone — not just its own. That's the entire system's blast radius from one pod. "Temporary" wildcards are the classic IAM trap because (a) they **outlive the migration** — nobody circles back to tighten them, so "temporary" becomes permanent; and (b) once code starts relying on the broad grant, narrowing it later risks breaking things, so it ossifies. The right move is to add the *specific* new permission the migration needs, scoped by ARN, not a blanket wildcard — least privilege from the start, because tightening later rarely happens.
</details>

---

## Tod ke dekho — Local (read-only; real apply needs an AWS account)

```bash
# the IAM policy logic is in Terraform; validate + inspect it without an AWS account:
cd infra/terraform/aws
terraform init -backend=false && terraform validate     # structure is sound
terraform fmt -check                                     # style (what CI gates)

# read the least-privilege Kafka policy and the SA->role mappings:
sed -n '/kafka_clients/,/^}/p' irsa.tf                   # exact actions + topic ARN scoping (no DeleteTopic)
grep -n "namespace_service_accounts" irsa.tf             # which SA maps to which role
grep -n "AssumeRoleWithWebIdentity\|oidc" eks.tf ci-oidc.tf | head

# prove the Walk-19 point to yourself: search for a wildcard that would re-introduce the risk
grep -rn "Topic\*\|kafka-cluster:\*\|Resource *= *\"\\*\"" infra/terraform/aws || echo "no dangerous wildcards (good)"
```

**Proved:** the Kafka role lists explicit actions scoped to `orders.events*`/`payments.events*` ARNs with **no** `DeleteTopic`/wildcard — least privilege you can read off the policy. A real `terraform apply` (EKS/MSK/RDS) needs an AWS account; CI runs `validate`/`tflint`.

## Tod ke dekho — Online
AWS account chahiye → [`ONLINE-LABS.md`](../ONLINE-LABS.md) (the "real deploy" note). Bina account ke: Cloud Shell / Codespaces pe `terraform validate` + policy read.

---

## Interview concepts is passage se
- **IRSA (IAM Roles for Service Accounts)** — pod→AWS auth via OIDC federation + STS, **no access keys**.
- **OIDC federation / `AssumeRoleWithWebIdentity`** — projected SA token (JWT) → temp creds; SA↔role mapping.
- **Least privilege** — explicit actions + resource-ARN scoping; wildcard `*Topic*` silently grants Delete (Walk 19).
- **Blast-radius thinking** — a compromised pod can only reach its own topics/groups.
- **Same mechanism for CI** — GitHub OIDC → AWS role (no keys in secrets); subject = repo/workflow/branch.
- **Temporary creds > long-lived keys** — leak is short-lived, scoped, auto-rotated.

**Interviewer's lens:** "pod ko AWS access kaise, bina key ke?" → IRSA/OIDC/STS flow bol pana; aur `*Topic*` wildcard ka silent-DeleteTopic trap (least-privilege ka real example). Ye AWS/security round ke signatures.

> Interview line: *"Prod EKS pe pods ko AWS access IRSA se milta — no access keys. Har ServiceAccount ek IAM role se annotated, pod apna projected SA token (JWT) STS `AssumeRoleWithWebIdentity` ko deta, STS cluster ke OIDC provider se verify karke temporary creds deta. Har role least-privilege: Kafka role sirf Connect/Describe/Create/Read/WriteData specific topic ARNs (`orders.events*`) pe — `DeleteTopic` nahi, kyunki pehle `*Topic*` wildcard chup-chaap DeleteTopic de raha tha (Walk 19), to compromised pod apna topic uda sakta tha. Wahi OIDC mechanism CI ke liye — GitHub Actions bina AWS key ke role assume karta."*
</content>
