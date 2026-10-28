# 08 · Supply-chain security (scan → SBOM → sign → admit) — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `.github/workflows/shopflow.yml`, `k8s/policies/kyverno/verify-image-signature.yaml`.

## Kya (one line)
Proof-chain: image ko scan karo (Trivy), uska SBOM rakho, push pe **keyless cosign** se sign karo (key nahi, GitHub workflow identity), aur cluster mein **Kyverno admission policy** sirf us exact workflow ke signed+digest-pinned images chalने de — baaki reject.

## Easy way (Hinglish analogy)
Dawai ki tamper-proof seal socho. **Scan** = factory mein quality check (kharab batch reject). **SBOM** = dabbe pe poori ingredient list (kal koi ingredient ban ho to turant pata "kaun-kaun si dawai mein hai"). **Sign (keyless)** = company ka hologram jo sirf asli factory lagaa sakti — aur ye hologram kisi tijori ki chaabi se nahi, factory ki **pehchaan** se banta (chaabi chori nahi ho sakti). **Admission policy** = medical store pe scanner jo bina **us** company ke hologram wali dawai bechne hi nahi deta. **mutateDigest** = seal check karne ke baad dabba badla na ja sake, isliye "jo check hua wahi bika."

## Kyu (ye problem solve karta hai)
Sawaal: *jo container prod mein chal raha, kya wo bilkul wahi hai jo CI ne reviewed source se banaya — kuch milawat to nahi?* Bina is chain ke: koi malicious image push kar de, ya `latest` tag kisi aur image pe point ho jaye, ya patched CVE chhoot jaye — aur prod mein chal jaye. Har step ek gap band karta:
- Trivy → known fixable vulns andar na jaayein.
- SBOM → future CVE ka audit bina rebuild.
- Keyless sign → "kisne banaya" ki cryptographic proof, bina leak-able key.
- Admission + digest pin → sirf trusted pipeline ka exact artifact chale; tag-swap na ho.

## Kaise (step by step, CI → cluster)
1. **Build** image (per service matrix) — tag = **commit SHA** (mutable `latest` nahi).
2. **Trivy scan** `--severity HIGH,CRITICAL --ignore-unfixed --exit-code 1` → fixable high/critical pe build fail.
3. **SBOM** `anchore/sbom-action` CycloneDX → `sbom-<svc>.cdx.json` artifact.
4. **Push** (sirf `main`) → GHCR.
5. **cosign sign** keyless: `id-token: write` → Sigstore short-lived cert bound to **workflow OIDC identity** → digest sign → **Rekor** transparency log.
6. **Pin SHA** into `k8s/overlays/prod` (GitOps: Argo CD sync).
7. **Kyverno `verify-image-signature`** (`Enforce`, ns `shopflow`): `verifyImages` keyless attestor `subject = …/shopflow.yml@refs/heads/main`, `issuer = token.actions.githubusercontent.com`, rekor; **`mutateDigest: true`**. Match na ho → pod reject.
   - CI ka `kyverno apply` test is policy ko **exclude** karta (live registry/Rekor chahiye) → cluster mein test hota.

## Flow diagram (build → run, trust chain)
```
 source (reviewed, main)
     │  CI (shopflow.yml)
     ▼
 build ─► Trivy scan ─(fixable HIGH/CRIT? fail)─► SBOM (CycloneDX) ─► push GHCR (tag=SHA, main only)
                                                                          │
                                                               cosign sign --keyless
                                                               (OIDC identity, Rekor log)
                                                                          │
     pin SHA → k8s/overlays/prod ──Argo CD──► cluster
                                                   │  Pod admission (ns shopflow)
                                                   ▼
                            Kyverno verifyImages (Enforce):
                              subject == shopflow.yml@refs/heads/main ?
                              issuer == github OIDC ? rekor ok ?
                                  │ yes → mutateDigest (tag→verified digest) → ADMIT
                                  │ no  → DENY (pod never schedules)
```

## Trust-boundary diagram (kaun admit hota)
```
 image signed by ... │ admitted in prod?
 ─────────────────────────────────────────
 ShopFlow wf @ main  │  YES  (reviewed pipeline)
 ShopFlow wf @ branch│  NO   (subject pinned to main)
 attacker's own wf   │  NO   (different subject/identity)
 unsigned (pushed)   │  NO   (no signature)
 right sig, tag moved │ runs the VERIFIED digest, not the swapped tag (mutateDigest)
```

## Alternate approaches (kab kaunsa)
| Approach | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Keyless cosign + Kyverno** *(ShopFlow)* | OIDC identity sign, admission verify | no key to leak, identity-bound, Rekor audit | CI/OIDC setup, live test | GitHub/GitLab CI |
| **Key-pair cosign** | static private key sign | simple, offline | key leak/rotate risk | air-gapped/no OIDC |
| **No signing, just scan** | Trivy only | easy | koi "kisne banaya" proof nahi | low-trust/demo |
| **Gatekeeper (OPA/Rego)** admission | Rego policy | expressive | no built-in image verify | complex policy logic |
| **Attestations (SLSA provenance)** | cosign attest predicate | "scan passed/provenance" bhi verify | zyada machinery | mature supply chain |

## Understanding check
1. Keyless signing mein "identity" kya hai, aur long-lived key se safe kyu?
2. `mutateDigest: true` kaunsa attack band karta?
3. "Signed by someone" kaafi kyu nahi — subject pin kyu?
4. SHA tag vs `latest` — deploy + incident mein kya milta?
5. Signature verify policy CI offline job mein kyu nahi, gap kaise cover karein?

## Interview mein ye bolo
*"Supply chain loop char step: Trivy fixable HIGH/CRITICAL pe fail, CycloneDX SBOM store (future CVE bina rebuild answer), image SHA-tag pe push phir cosign keyless sign — koi private key nahi, identity = GitHub workflow OIDC, Rekor mein log. Cluster mein Kyverno admission policy (Enforce, prod ns) sirf us exact workflow (`shopflow.yml@refs/heads/main`) ke signed images admit karti, baaki reject, aur mutateDigest se verified digest pe pin karti taaki check ke baad image swap na ho. Subject ko branch tak pin karna zaroori warna koi bhi branch prod mein ghus jaaye."*

> Related: [`../06-slo-burn-rate-bug/`](../06-slo-burn-rate-bug/) (CI/test gaps), [`../07-k8s-readiness-cascade/`](../07-k8s-readiness-cascade/) (admission/policies ke saath k8s security).
</content>
