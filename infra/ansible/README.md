# Ansible: ShopFlow on one VM (the pre-Kubernetes approach)

Not every team has a Kubernetes cluster, and many Indian companies still run Docker Compose on
EC2/VMs managed by Ansible. This playbook installs Docker, copies the compose stack, and runs it
as a systemd unit, so the same images and configuration run without K8s.

| | Ansible + Compose on a VM (`infra/ansible`) | EKS (`infra/terraform/aws` + `k8s/`) |
|---|---|---|
| Fits | 1–3 VMs, small team, predictable load | many services, autoscaling, multiple teams |
| Scaling | vertical (bigger VM) or manual second VM + LB | HPA/Karpenter |
| Zero-downtime deploy | hard (compose restarts containers) | built in (rolling, canary) |
| Self-healing | systemd restarts the stack; no node failover | pod + node level |
| Cost | ~$50–100/month | ~$400+/month |
| Ops knowledge needed | Linux + Docker | Kubernetes |

```sh
cp inventory.example.ini inventory.ini        # put your VM's IP and SSH user in it
ansible-playbook -i inventory.ini site.yml --check   # dry run
ansible-playbook -i inventory.ini site.yml
```
Secrets: `group_vars/shopflow.yml` holds only non-secrets; real passwords go in an Ansible Vault file
(`ansible-vault create group_vars/vault.yml`) and are referenced as `{{ vault_db_password }}`.
