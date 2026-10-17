# Progressive delivery: the same services as Argo Rollouts

`k8s/base` deploys with a plain `Deployment` + `RollingUpdate` (zero downtime, but every pod gets
the new version within a minute, and "is it healthy?" means only "readiness passed"). This folder
is the advanced approach for the same two services, kept side by side so both can be compared:

| | Deployment RollingUpdate (`k8s/base`) | Rollout canary (`order-service`) | Rollout blue-green (`inventory-service`) |
|---|---|---|---|
| Traffic to new version | all, gradually by pod count | 10% → 25% → 50% → 100%, with pauses | 0% until promoted, then 100% at once |
| Decision to continue | readiness probe only | **AnalysisTemplate**: Prometheus error ratio of the canary must stay < 2% | manual (or automated) promotion after preview tests |
| Rollback | `kubectl rollout undo` (~1 min) | automatic abort when analysis fails; old pods still serving | instant: switch the active Service back |
| Cost | 1 extra pod (maxSurge) | a few canary pods | 2× pods during the preview |
| Needs | nothing | Argo Rollouts controller, Prometheus, a traffic router for exact weights (ALB/NGINX/Istio); without one, weight ≈ pod ratio | Argo Rollouts controller |

Install: `kubectl create ns argo-rollouts && kubectl apply -n argo-rollouts -f https://github.com/argoproj/argo-rollouts/releases/latest/download/install.yaml`
Apply: `kubectl apply -k k8s/rollouts` (replaces the two Deployments in the namespace; Services/HPA/PDB are reused).
Watch: `kubectl argo rollouts get rollout order-service -n shopflow --watch`, promote: `kubectl argo rollouts promote inventory-service -n shopflow`.
