# ServiceDown (page)

**Fires when:** Prometheus could not scrape a target for 2 minutes. Either the pods are gone or the network to them is.

```sh
kn get pods -l app.kubernetes.io/name=<service> -o wide     # Pending? CrashLoopBackOff? 0 pods?
kn get events --sort-by=.lastTimestamp | tail -20
kn describe pod <pod> | tail -25                              # OOMKilled? ImagePullBackOff? FailedScheduling?
kn get hpa,pdb                                                # HPA minReplicas 0? PDB blocking?
kubectl get nodes                                             # node NotReady?
```
| State | Fix |
|---|---|
| `ImagePullBackOff` | tag typo in the overlay / registry auth: `kn rollout undo deploy/<service>` |
| `OOMKilled` loop | raise memory limit (`kn set resources`), then find the leak |
| `Pending` (FailedScheduling) | no node capacity: cluster autoscaler? resource requests too big? |
| `CrashLoopBackOff` | `kn logs <pod> --previous`: config/secret/DB migration failure → rollback |
| pods Ready but target down | NetworkPolicy blocks the `monitoring` namespace, or the scrape annotation/port changed |
| Postgres StatefulSet down (dev) | PVC/disk full? `kn exec postgres-0 -- df -h`; restore from backup if corrupt (RestorePostgres.md) |

Other alerts (error rate, latency, breaker) for this service are **inhibited** while this one fires: fix this first.
