# 08 · Signing the images you actually deploy — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `.github/workflows/shopflow.yml` (scan → SBOM → push → cosign sign), `k8s/policies/kyverno/verify-image-signature.yaml`.

---

## Scenario (read cold)

> *Excerpt from ShopFlow's supply-chain security design. Read once, then answer.*

"Supply chain" security asks: **can you prove that the container running in production is the exact artifact your CI built from your reviewed source — and nothing else?** ShopFlow closes that loop in four steps.

**1. Scan.** After building each image, CI runs **Trivy**, failing the build on **fixable HIGH/CRITICAL** CVEs (`ignore-unfixed: true`, so it doesn't block on vulns with no patch yet).

**2. SBOM.** CI generates a **CycloneDX SBOM** — a machine-readable list of everything inside the image — and keeps it with the build. The stated point: when the next Log4Shell lands, you can answer *"which images contain log4j 2.14?"* by querying stored SBOMs, **without rebuilding anything**.

**3. Push + sign (main only).** On `main`, the image is pushed to GHCR tagged with the **commit SHA** (not `latest`), and then **signed with cosign, keyless**. Keyless means there is **no private key to store or leak**: cosign gets a short-lived certificate from Sigstore bound to the **GitHub Actions workflow's OIDC identity** (`id-token: write`), signs the pushed **digest**, and records it in the public **Rekor** transparency log. The signature says, in effect, *"this exact digest was signed by this workflow, on this repo, on this branch."*

**4. Admit only signed images.** A Kyverno `ClusterPolicy`, `verify-image-signature`, runs at **admission** in the `shopflow` namespace with `validationFailureAction: Enforce`. For images matching `ghcr.io/rajji01/shopflow-*` (and the ECR equivalent), it requires a **keyless** signature whose `subject` is exactly the workflow identity `…/shopflow.yml@refs/heads/main` and whose `issuer` is GitHub's OIDC provider, verified against Rekor. An image **not** signed by *that* identity is **rejected** — a pod referencing it never schedules. Crucially, the policy sets **`mutateDigest: true`**: it rewrites the image tag to the **verified digest**, so what was checked is byte-for-byte what runs (a tag could otherwise be re-pointed to a different image after the check).

One operational note: the CI's own `kyverno apply` test **excludes** this particular policy, because verifying a signature needs to reach a real registry and Rekor — so it is tested live in a cluster, not in the offline manifest-validation job.

---

## Questions

### A — Literal
1. Why does Trivy run with `ignore-unfixed: true`, and what severity levels fail the build?
2. What problem does the stored SBOM let you answer later **without rebuilding**?
3. What does `mutateDigest: true` do, and what attack does it close?

### B — Inference
4. "Keyless" signing uses no stored private key. From the passage, what plays the role of "identity" instead, and why is that safer than a long-lived key?
5. The Kyverno policy checks a very specific `subject` (`…/shopflow.yml@refs/heads/main`). Why isn't "the image is signed by *someone*" good enough — what does pinning the exact workflow+branch prevent?

### C — Trade-off
6. Images are tagged with the **commit SHA**, not `latest`. What does the SHA tag give you that `latest` cannot, in both deployment and incident-response terms?
7. The signature-verification policy is tested **live in a cluster**, not in the offline CI `kyverno apply` job. Why is it excluded there, and what's the risk of that gap — plus one way to cover it?

### D — Transfer / extension
8. An attacker with push access to GHCR uploads a malicious `ghcr.io/rajji01/shopflow-order-service:latest`. Trace what happens when a pod tries to run it in the `shopflow` namespace, step by step, and identify which control stops it.
9. Your team wants to additionally require that the image **passed the Trivy scan** before it can run (not just that it's signed). Using the same Sigstore machinery, what artifact would CI produce and what would the policy verify? (Hint: signatures aren't the only thing you can attest.)

### E — Spot-the-flaw
10. A dev, to "make staging work too," changes the policy's `subject` to a wildcard `…/shopflow.yml@*` (any branch/ref). What protection is lost, and how could an attacker or a careless feature branch now get an image admitted to production?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. `ignore-unfixed: true` so the build isn't blocked by vulnerabilities that **have no patch available yet** (you can't fix them anyway). It fails on **HIGH and CRITICAL** CVEs that **are** fixable.
2. *"Which images contain \<vulnerable library X\>?"* (e.g. log4j 2.14) — you query the stored SBOMs instead of rebuilding or re-scanning every image.
3. It **rewrites the image reference from a tag to the exact verified digest**, so the thing that runs is byte-for-byte the thing that was signature-checked. It closes the **time-of-check/time-of-use** gap where a mutable tag is re-pointed to a different (malicious) image after verification.
</details>

<details>
<summary>B — Inference</summary>

4. The **GitHub Actions workflow's OIDC identity** (the specific workflow file, repo, and branch) plays the role of "who signed this," via a **short-lived** Sigstore certificate recorded in Rekor. It's safer than a long-lived key because there's **no secret to store, leak, or rotate** — the signing credential exists only for the duration of the job, and the identity is cryptographically tied to *which workflow on which repo/branch* ran, which a stolen static key could never prove.
5. "Signed by someone" only proves *a* valid Sigstore signature exists — an attacker can sign their own malicious image with their own GitHub workflow and get a perfectly valid keyless signature. Pinning `subject` to **your** exact workflow+branch means only artifacts produced by **that reviewed pipeline on main** are accepted; it prevents an image signed by any *other* identity (including an attacker's, or a random fork's) from being admitted.
</details>

<details>
<summary>C — Trade-off</summary>

6. The **SHA tag is immutable and traceable**: it points to one exact build forever, so a deploy is reproducible and a rollback goes to a known-good artifact, and during an incident you can map the running image straight back to the exact commit. `latest` is **mutable** — it can silently change under you, two clusters pulling `latest` can run different code, and you can't reliably roll back to "the previous latest." SHA tags make "what is running?" answerable.
7. Verifying a signature requires reaching a **real registry and the Rekor log** over the network; the offline `kyverno apply` manifest-validation job has neither, so the policy can't be exercised there and is excluded. **Risk of the gap:** a typo/regression in *this* policy (wrong subject, wrong issuer) wouldn't be caught by CI and would only surface in-cluster. **Cover it:** a live integration test in a kind/minikube cluster with Kyverno installed that applies a known-signed image (admit) and an unsigned/other-identity image (reject), run in a dedicated CI job.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. A pod referencing `ghcr.io/rajji01/shopflow-order-service:latest` is submitted to the API server in the `shopflow` namespace → the Kyverno admission webhook intercepts it (`verifyImages`, `Enforce`) → it looks for a **keyless signature on that image's digest** whose `subject` = the ShopFlow `main` workflow and `issuer` = GitHub OIDC, checked against Rekor → the malicious image was **not** signed by that identity (the attacker can't assume the workflow's OIDC identity) → **verification fails → admission is denied → the pod is never created.** The control that stops it is the **Kyverno `verify-image-signature` admission policy**, not the registry (push access alone isn't enough to run in prod).
9. CI would produce a **signed attestation** (a signed, in-toto/SLSA-style predicate) stating "Trivy scan passed for this digest," pushed to the registry and logged in Rekor via `cosign attest`. The Kyverno policy would then use **`verifyImages` with an `attestations`/predicate check** (not just a bare signature) requiring a scan-passed attestation signed by the same workflow identity. So admission requires both "signed by our pipeline" **and** "our pipeline attests it passed the scan."
10. **(E answer below.)**
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. Wildcarding `subject` to `…/shopflow.yml@*` removes the **branch pin**, so an image signed by the ShopFlow workflow running on **any ref** — a feature branch, a fork's PR branch, an attacker-pushed branch — is now accepted in production. The protection lost is "**only artifacts built from reviewed `main` may run in prod**." An attacker who can push a branch (or a careless dev on an experimental branch) can trigger the workflow, get a valid keyless signature with `subject …@refs/heads/their-branch`, and that image now passes admission — bypassing code review and the release gate. The whole point of pinning the exact workflow **and branch** is to make the trust boundary "our reviewed main pipeline," not "our workflow, ever, anywhere."
</details>

---

## Tod ke dekho — Local (docker + cosign)

Full admission enforcement needs a cluster with Kyverno; but the sign/verify core you can do locally.

```bash
# 1) SBOM of a local image (what's inside) — Syft:
make build                                   # builds shopflow/*:local (or: docker build ...)
docker run --rm anchore/syft:latest shopflow/order-service:local -o cyclonedx-json | head -40

# 2) Scan like CI — Trivy, fail on fixable HIGH/CRITICAL:
docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:latest \
  image --severity HIGH,CRITICAL --ignore-unfixed shopflow/order-service:local

# 3) Keyless sign + verify (needs cosign + a registry you can push to, e.g. a GHCR PAT):
#    cosign sign --yes ghcr.io/<you>/shopflow-order-service@<digest>
#    cosign verify ghcr.io/<you>/shopflow-order-service@<digest> \
#      --certificate-identity-regexp '.*/shopflow.yml@refs/heads/main' \
#      --certificate-oidc-issuer https://token.actions.githubusercontent.com
#    -> prints the Rekor entry; a wrong identity/issuer -> verification FAILS

# 4) Admission policy in a cluster (minikube + Kyverno):
#    minikube start ; kubectl apply -f https://github.com/kyverno/kyverno/releases/download/v1.19.1/install.yaml
#    kubectl apply -f shopflow/k8s/policies/kyverno/verify-image-signature.yaml
#    kubectl -n shopflow run bad --image=nginx   # -> DENIED: image not signed by the ShopFlow workflow
```

**Proved:** SBOM lists contents, Trivy gates CVEs, cosign verify only passes for the exact workflow identity, and the Kyverno policy denies anything else at admission.

> The real signing happens in CI on `main` (no key to leak). Related CI: `.github/workflows/shopflow.yml` (scan → SBOM → push → cosign). The `kyverno apply` CI job **excludes** this policy on purpose (needs a live registry/Rekor).

## Tod ke dekho — Online
cosign sign/verify → [`ONLINE-LABS.md`](../ONLINE-LABS.md) **section A (Codespaces)** (docker + cosign). Admission policy → **section B (Cloud Shell + minikube + Kyverno)**.

---

## Interview concepts is passage se
- **Software supply chain** — "jo deploy hua wahi build hua tha?" ki proof.
- **Trivy scan** gate (`ignore-unfixed`, HIGH/CRITICAL).
- **SBOM (CycloneDX/Syft)** — future-CVE audit bina rebuild.
- **Keyless signing (cosign + Sigstore + Rekor + OIDC)** — no key to leak; identity = workflow.
- **Pin by digest (`mutateDigest`)** — TOCTOU/tag-repoint attack band.
- **Admission control (Kyverno `verifyImages`, Enforce)** — unsigned/other-identity image reject.
- **Subject+branch pinning** — trust boundary = reviewed `main` pipeline, not "anyone signed."
- **SHA tag, not `latest`** — immutable, traceable, rollback-able.
- **Attestations (SLSA)** — signatures se aage: "scan pass hua" bhi attest karo.

**Interviewer's lens:** kya tu "signed by someone" vs "signed by *our* workflow on main" ka farak bol pata hai, aur TOCTOU/tag-repoint ko `mutateDigest` se jodta hai. Keyless ka "no key to leak" bhi.

> Interview line: *"Supply chain loop: Trivy fixable HIGH/CRITICAL pe build fail, CycloneDX SBOM store (future CVE bina rebuild answer), image SHA-tag pe push, phir cosign se keyless sign — koi private key nahi, identity = GitHub workflow OIDC, Rekor mein log. Kyverno admission policy prod namespace mein sirf us exact workflow (`shopflow.yml@refs/heads/main`) ke signed images admit karti, baaki reject — aur mutateDigest se tag ko verified digest bana deti taaki check-ke-baad image swap na ho. Subject ko branch tak pin karna zaroori, warna koi bhi branch prod mein ghus jaaye."*
</content>
