# 24 · Zero-trust inside the cluster: NetworkPolicies — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `k8s/base/network-policies.yaml`. Pairs with [comp 07](../07-k8s-readiness-cascade/), [comp 20](../20-eks-irsa-least-privilege/).

---

## Scenario (read cold)

> *Excerpt from ShopFlow's network security design. Read once, then answer.*

By default, **every pod in a Kubernetes namespace can talk to every other pod** — flat, open east-west traffic. That means a single compromised pod (a bad dependency, an RCE) can reach the database, every service, anything. ShopFlow closes that with **NetworkPolicies**: **zero-trust** — **deny all incoming traffic, then allow only what's actually needed.**

It starts with a **`default-deny-ingress`** policy: `podSelector: {}` (every pod), `policyTypes: [Ingress]`, and **no `ingress` rules** — which means **nothing** can connect to any pod. Then each service gets a narrow allow-list:
- **order-service** accepts ingress **only from the `ingress-nginx` and `monitoring` namespaces**, on port 8081 — so it's reachable through the **ingress/ALB** (public entry) and by **Prometheus** (scraping), and from nothing else.
- **inventory-service** accepts **only from order-service pods** (+ ingress/monitoring), port 8082 — so **only order-service can call inventory**; a compromised notification pod can't.
- **Postgres** accepts **only from the four services + the backup job**, on the DB port — so even inside the namespace, the DB isn't reachable by anything that isn't a legitimate client.

The important subtlety: NetworkPolicies are **additive allow-lists over a default-deny**. The `default-deny` blocks everything; each specific policy *adds* a permitted source. There's no "deny this one" — you allow exactly the edges you want and everything else stays blocked. And it only works if the **CNI enforces NetworkPolicy** — plain kubenet ignores them; you need **Calico/Cilium** (minikube: `--cni=calico`). If the CNI doesn't enforce them, the manifests apply cleanly but **do nothing** — a dangerous false sense of security.

This is **defense in depth**: even if an attacker gets code execution in one pod, the network won't let them pivot to the database or to services they shouldn't reach — the blast radius is contained to that pod's allowed edges.

---

## Questions

### A — Literal
1. What's the default pod-to-pod traffic in a namespace, and what does `default-deny-ingress` change?
2. Who is allowed to connect to inventory-service, and on what port?
3. What must be true of the CNI for these policies to actually take effect?

### B — Inference
4. NetworkPolicies are "additive allow-lists over a default-deny." Why is there no need for (and no concept of) a "deny this specific source" rule?
5. order-service's policy allows the `monitoring` namespace. Why is that a *required* allow, not a security hole?

### C — Trade-off
6. Zero-trust networking (default-deny + allow-list) vs the default flat open network. What does it cost in effort/operability, and what attack does it contain?
7. The policies control **ingress** (incoming). What's the trade-off of *also* adding **egress** (outgoing) rules, and when is it worth it?

### D — Transfer / extension
8. You add a new `analytics-service` that reads from Postgres and calls inventory-service. What NetworkPolicy edits are needed so it works **without** opening anything else?
9. A pod is compromised (RCE). Walk through what the attacker **can** and **cannot** reach with these policies in place, and why that's smaller than a flat network.

### E — Spot-the-flaw
10. A team applies all these NetworkPolicies to their EKS cluster, sees them in `kubectl get netpol`, and declares the namespace "locked down". A pentest then reaches Postgres directly from a random pod. The manifests are correct. What was missed, and why is this failure mode especially dangerous?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **Default: every pod can talk to every other pod** (flat, open east-west). `default-deny-ingress` (podSelector `{}`, Ingress, no rules) makes **nothing** able to connect to any pod — a deny-all baseline you then selectively open.
2. **Only order-service pods** (plus the `ingress-nginx` and `monitoring` namespaces), on **port 8082**.
3. The **CNI must enforce NetworkPolicy** — Calico, Cilium, etc. (minikube `--cni=calico`). Plain kubenet ignores them, so they'd apply but do nothing.
</details>

<details>
<summary>B — Inference</summary>

4. Because the model is **default-deny + allow**: the baseline already blocks everything, so you never need to *subtract* — you only *add* the specific edges you want. A "deny this source" would be meaningless when the default is already deny; you simply don't add an allow for it. This is also why the model is safe-by-default: forgetting to add a rule **fails closed** (blocked), not open.
5. Because **Prometheus (in the `monitoring` namespace) must scrape** order-service's `/actuator/prometheus` metrics endpoint to collect RED/SLO data. If the policy didn't allow monitoring, scraping would be blocked and you'd lose observability on that service. It's a **required, scoped** allow (only the monitoring namespace, only the metrics-serving port), not a hole — it permits exactly the metrics pull, nothing else.
</details>

<details>
<summary>C — Trade-off</summary>

6. **Cost:** you must enumerate and maintain the allowed edges — every new service/dependency needs a policy, and a missing allow shows up as a confusing "connection refused" (fails closed). It's more upfront and ongoing work than a flat network. **Contains:** **lateral movement** — a compromised pod can't pivot to the database or to services it has no business calling, so one RCE doesn't become a namespace-wide breach. The blast radius shrinks from "everything" to "that pod's allowed edges."
7. **Egress** rules restrict where a pod can *send* traffic — valuable to stop a compromised pod from **exfiltrating data** or **calling out** to an attacker's server (C2), and to force traffic through known destinations. The **trade-off**: egress is harder to get right (DNS, external APIs, AWS endpoints all need allowing) and a missing egress allow breaks legitimate outbound calls in subtle ways. Worth it for **high-security** workloads or ones handling sensitive data; ingress-only is a reasonable first layer, egress is the next tightening.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. Two edits: **(1)** add `analytics-service` to the **`postgres-ingress`** allowed sources (its pod label), so it can reach the DB; **(2)** add an `analytics-service → inventory-service` allow (extend `inventory-service-ingress` to also permit the analytics-service pod selector). Also give analytics-service **its own ingress policy** (default-deny already blocks it; allow only who should call it, e.g. ingress/monitoring). Nothing else opens — each new edge is explicit; you don't touch the other services' policies.
9. **Can reach:** only what its own pod's allowed edges permit — e.g. if the compromised pod is notification-service, it can reach Postgres (it's a listed DB client) and receive from the sources its ingress allows, but **cannot** reach inventory-service (only order-service is allowed there) or any pod whose policy doesn't list it. **Cannot reach:** arbitrary pods, the DB from a pod not on the allow-list, services it has no edge to. On a **flat** network it could reach *everything* (DB, all services) — so the policies shrink the attacker's reachable surface from the whole namespace to a handful of pre-approved edges.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. The **CNI doesn't enforce NetworkPolicy**. The manifests are valid and `kubectl get netpol` lists them, but if the cluster's CNI is a plain/non-enforcing one (or the EKS VPC CNI without the Calico/Cilium policy add-on enabled), the API server **stores** the policies and **nothing applies them** — all traffic still flows. This is especially dangerous because it's a **silent false-positive**: everything *looks* locked down (the objects exist), so nobody re-checks, while the network is actually wide open. The fix/verification: confirm the CNI enforces NetworkPolicy (Calico/Cilium installed and active), and **test** it — from a pod that shouldn't reach Postgres, try to connect and confirm it's **refused**. "The policy exists" ≠ "the policy is enforced."
</details>

---

## Tod ke dekho — Local (minikube with a policy-enforcing CNI)

```bash
# NetworkPolicy needs an enforcing CNI:
minikube start --cni=calico
make k8s-dev
kubectl -n shopflow get networkpolicy          # default-deny-ingress + per-service allows

# PROVE enforcement (the only thing that counts): from a throwaway pod, Postgres must be REFUSED
kubectl -n shopflow run probe --rm -it --image=busybox --restart=Never -- \
  sh -c "nc -w3 -z postgres 5432; echo exit=$?"     # NON-zero (blocked) if enforced
# from order-service (allowed), inventory IS reachable:
kubectl -n shopflow exec deploy/order-service -- sh -c "nc -w3 -z inventory-service 8082; echo exit=$?"  # 0

# WITHOUT an enforcing CNI (e.g. default minikube), the SAME probe SUCCEEDS -> policies are decorative.
```

**Proved:** with Calico, a random pod is refused to Postgres while order-service reaches inventory; without an enforcing CNI the policies apply but don't block. (`docker-compose` has no NetworkPolicy — this is a k8s/minikube lab.)

## Tod ke dekho — Online
k8s + enforcing CNI → [`ONLINE-LABS.md`](../ONLINE-LABS.md) **section B/C** (Cloud Shell/Killercoda; `--cni=calico`).

---

## Interview concepts is passage se
- **Zero-trust east-west** — default-deny-ingress, then allow-list.
- **Additive allow over default-deny** — no "deny rule"; forgetting fails **closed**.
- **Least-connectivity / defense in depth** — one RCE can't pivot to DB/other services.
- **Per-service policies** — order←ingress/monitoring; inventory←order; postgres←services+backup.
- **CNI must enforce** (Calico/Cilium) — else manifests are decorative (silent false-positive).
- **Ingress vs egress** — egress stops exfiltration/C2 but harder (DNS/external).
- **Verify, don't assume** — test a blocked path (`nc` refused).

**Interviewer's lens:** "pod-to-pod kaise lock karoge?" → default-deny + allow-list; "policy laga di par kaam nahi kari" → CNI enforce nahi karta (silent trap); aur blast-radius containment ka samajhna.

> Interview line: *"Default mein namespace ke saare pods ek doosre se baat kar sakte — ek compromised pod DB tak pahunch jaata. Hum zero-trust lagate: `default-deny-ingress` (sab block), phir per-service allow-list — order-service sirf ingress-nginx/monitoring se, inventory sirf order-service se, Postgres sirf 4 services + backup se. Ye additive hai (default-deny ke upar allow; bhoolo to fail-closed). Defense in depth: ek RCE bhi DB/dusre services tak pivot nahi kar sakta. Par ye tabhi kaam karta jab CNI enforce kare (Calico/Cilium) — warna manifests dikhte hain par block nahi karte, silent false-positive. Isliye test karo: ek blocked path (`nc` refused) verify karo."*
</content>
