# Roll back a deploy (procedure)

Choose by how the service was deployed. Mitigate first; the root cause can wait.

| Deployed with | Rollback | Time |
|---|---|---|
| Kustomize Deployment (`k8s/base`) | `kn rollout undo deploy/<service>` (or `--to-revision=N` from `kn rollout history`) | ~1 min |
| GitOps (Argo CD) | `git revert <commit>` on `main` → Argo syncs; or Argo UI → History → Rollback (then revert in git, or self-heal re-applies the bad version!) | 1–3 min |
| Helm | `helm rollback <release> <revision>` (`helm history <release>`) | ~1 min |
| Argo Rollouts canary | automatic on failed analysis; manual: `kubectl argo rollouts abort <rollout>` then `undo` | seconds |
| Argo Rollouts blue-green | before promotion: `abort`; after: `kubectl argo rollouts undo <rollout>` (blue is kept 60 s) | seconds |
| Ansible/VM | `ansible-playbook site.yml -e repo_version=<previous sha>` | ~5 min (rebuilds) |

After any rollback:
1. Confirm the old image is running: `kn get deploy <service> -o jsonpath='{.spec.template.spec.containers[0].image}'`.
2. Check the alert that triggered the rollback resolves.
3. **Database migrations are not rolled back** (Flyway is forward-only). The previous version must still work with the new schema: this is why every migration is expand-only (see `docs/LABS.md` Lab 12).
4. Write it up: what shipped, what broke, what the canary analysis should have caught.
