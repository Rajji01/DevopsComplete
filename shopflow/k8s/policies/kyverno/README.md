# Policy as code (admission control)

Rules the cluster enforces **before** a manifest is accepted, so a tired reviewer cannot let them
through. Two engines, same idea:

| | Kyverno (`kyverno/`) | OPA Gatekeeper (`gatekeeper/`) |
|---|---|---|
| Policy language | YAML (looks like a manifest) | Rego (a logic language) |
| Can mutate / generate | yes | no (validate only) |
| Image signature check | built-in (`verifyImages`, cosign) | needs an external data provider |
| Test offline | `kyverno apply policy.yaml --resource manifests.yaml` | `gator test` |
| Learning curve | low | medium (Rego) |

Both folders implement "no `:latest` tag". Only Kyverno implements the full set:

| Policy | Why |
|---|---|
| `disallow-latest-tag` | an untraceable image cannot be rolled back |
| `require-probes` | a pod without readiness gets traffic before it can serve |
| `require-resources` | no requests = scheduler blind, HPA blind, noisy neighbours |
| `require-non-root` | container breakout from root is a node compromise |
| `verify-image-signature` | only images signed by *our* CI (cosign keyless, GitHub OIDC) may run in prod |

Test locally (what CI does): `kyverno apply k8s/policies/kyverno/ --resource <(kustomize build k8s/overlays/prod)`
and against a deliberately bad pod: `kyverno apply k8s/policies/kyverno/ --resource k8s/policies/tests/bad-pod.yaml` (expect failures).
