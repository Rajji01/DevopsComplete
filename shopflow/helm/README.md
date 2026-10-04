# Helm: the other way to package these manifests

`k8s/` uses **Kustomize** (plain YAML, patched per environment). This folder packages the same
Deployment/Service/HPA/PDB as a **Helm chart**, so you can compare both on the same service.

```sh
helm template order-service helm/shopflow-service -f helm/values/order-service.yaml | kubeconform -strict -
helm upgrade --install order-service helm/shopflow-service -n shopflow -f helm/values/order-service.yaml
helm upgrade --install order-service helm/shopflow-service -n shopflow \
  -f helm/values/order-service.yaml -f helm/values/order-service-prod.yaml --set image.tag=$(git rev-parse HEAD)
helm rollback order-service 1
```

| | Kustomize | Helm |
|---|---|---|
| Model | copy + patch (overlays) | template + values |
| Reuse across services | one base per service (copied) | one chart, N values files |
| Logic in manifests | none (good: readable; bad: repetition) | `if`/`range`/helpers (powerful, harder to read) |
| Releases / rollback | git history + `kubectl rollout undo` | `helm history` / `helm rollback` per release |
| Third-party software | awkward | the standard (ingress-nginx, External Secrets, Argo CD are Helm charts) |
| Typical team setup | **both**: Helm for vendor charts, Kustomize (or Helm values per env) for your own apps |

Rule of thumb: when you find yourself copying `k8s/base/<service>` for the fourth time, you want a chart.
