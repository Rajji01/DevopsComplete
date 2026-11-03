# 24 · NetworkPolicies — zero-trust pod networking — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `k8s/base/network-policies.yaml`. Pairs with [comp 07](../07-k8s-readiness-cascade/), [comp 20](../20-eks-irsa-least-privilege/).

## Kya (one line)
Default mein namespace ke saare pods aapas mein baat kar sakte — **NetworkPolicy** se **zero-trust**: `default-deny-ingress` (sab block), phir per-service **allow-list** (order←ingress/monitoring, inventory←order, postgres←services+backup) — par sirf tab kaam karta jab **CNI enforce kare** (Calico/Cilium).

## Easy way (Hinglish analogy)
Office building socho. **Default** = har kamre ka darwaza khula, koi bhi kisi ke kamre (DB) mein ghus jaaye. **Zero-trust** = pehle **saare darwaze lock** (default-deny), phir sirf **zaroori raaste** ka access-card do: reception→order-service, order-service→inventory, services→DB. Ek chor ek kamre mein ghus bhi gaya (RCE) to DB tak nahi pahunch sakta — card hi nahi. **Par** agar building ka **lock system (CNI) kaam hi na kare**, to darwaze pe "LOCKED" likha hai par khule hain — sabse khatarnak (jhoothi tasalli). Isliye **test karo** (ek band raasta try karke dekho refuse hua).

## Kyu (ye problem solve karta hai)
Flat open network = ek compromised pod poore namespace tak (DB, saare services) pahunch sakta — lateral movement. Zero-trust is blast radius ko us pod ke allowed-edges tak simit kar deta (defense in depth). Additive allow-over-deny isliye safe-by-default: rule bhoolo to **fail-closed** (blocked), open nahi.

## Kaise (step by step)
1. **`default-deny-ingress`**: `podSelector:{}`, `policyTypes:[Ingress]`, no ingress rules → sab pods pe sab incoming block.
2. **`order-service-ingress`**: from namespaceSelector `ingress-nginx` + `monitoring`, port 8081 (public entry + Prometheus scrape).
3. **`inventory-service-ingress`**: from podSelector `order-service` (+ ingress/monitoring), port 8082 (sirf order call kar sakta).
4. **`postgres-ingress`**: from the 4 services + `postgres-backup`, DB port.
5. **CNI**: Calico/Cilium chahiye (minikube `--cni=calico`); kubenet ignore karta → decorative.
6. (Egress optional: exfiltration/C2 roke; DNS/external allow karna padta.)

## Flow diagram (who-can-reach-what)
```
                 default-deny-ingress (sab block)
                          │ phir allow-list:
  internet ──ALB/ingress──► order-service :8081 ◄── monitoring (Prometheus scrape)
                               │ (only order allowed)
                               ▼
                         inventory-service :8082 ◄── monitoring
                               │
  all 4 services + backup ──► Postgres :5432   (koi aur pod DB tak NAHI)
  notification/payment ──X──► inventory   (edge nahi → blocked)
```

## Allow-matrix diagram
```
 source \ dest      order:8081   inventory:8082   postgres:5432
 ingress-nginx          ✓            ✓*                ✗
 monitoring             ✓            ✓                 ✗
 order-service          —            ✓                 ✓
 inventory/notif/pay    ✗            ✗                 ✓
 random/compromised     ✗            ✗                 ✗   ← blast radius contained
 (* allow present; real caller is order-service)
```

## Alternate approaches (kab kaunsa)
| Choice | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Flat (no policy)** | default | zero effort | ek RCE → sab reachable | kabhi nahi (prod) |
| **Default-deny + allow (ingress)** *(ShopFlow)* | NetworkPolicy | contain lateral movement, fail-closed | maintain edges, CNI needed | prod namespace |
| **+ Egress rules** | restrict outbound | stop exfil/C2 | DNS/external tricky | high-security |
| **Service mesh (Istio/Linkerd) mTLS + authz** | sidecar, identity | L7 policy, mTLS, observability | heavy | large/strict |
| **Cloud SG only** | AWS SG | infra-level | pod-granularity nahi | coarse |

## Understanding check
1. Default pod traffic kya, default-deny kya badalta?
2. Inventory ko kaun call kar sakta, kis port pe?
3. "Deny rule" kyu nahi hota (additive model)?
4. Policy laga di par block nahi kari — kyu (silent trap)?
5. Compromised pod kya reach kar sakta, kya nahi?

## Interview mein ye bolo
*"Default mein namespace ke saare pods baat kar sakte — ek compromised pod DB tak pahunch jaata. Zero-trust: `default-deny-ingress` (sab block), phir per-service allow-list — order sirf ingress-nginx/monitoring se, inventory sirf order-service se, Postgres sirf 4 services + backup se. Additive hai (allow-over-deny; bhoolo to fail-closed). Defense in depth: ek RCE bhi DB/dusre services tak pivot nahi kar sakta. Par ye tabhi kaam karta jab CNI enforce kare (Calico/Cilium) — warna manifests dikhte hain par block nahi karte (silent false-positive), isliye ek blocked path test karo. Egress rules se exfiltration/C2 bhi rok sakte."*

> Related: [`../07-k8s-readiness-cascade/`](../07-k8s-readiness-cascade/) (k8s networking), [`../20-eks-irsa-least-privilege/`](../20-eks-irsa-least-privilege/) (least-privilege, same defense-in-depth spirit). Policies: Kyverno/Gatekeeper (admission) alag layer.
</content>
