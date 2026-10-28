# Online labs — run and deploy ShopFlow without a local setup

Har comprehension ka "Tod ke dekho" do tarike se karo: **Local** (tere laptop pe Docker/minikube) ya **Online** (jab laptop pe Docker/minikube/JDK 21 na ho, ya machine slow ho). Yeh page one-time setup hai; har topic yahin ke section pe link karta hai.

> Is machine pe abhi Docker daemon aur JDK 21 nahi hain (`docs/STATE.md` §3). Isliye online path sirf "alternative" nahi — abhi ke liye yahi tera asli hands-on environment hai.

Teen "chalane" wale (ephemeral, free) + ek "sach mein internet pe deploy" wala (free tiers):

| # | Environment | Kiske liye best | Signup | Kitni der zinda |
|---|---|---|---|---|
| A | **GitHub Codespaces** | Poora `docker compose` stack (4 services + Kafka + PG + Redis + Grafana) | GitHub (free 120 core-hrs/mo) | Jab tak tab khula, phir suspend |
| B | **Google Cloud Shell** | `kubectl` + `minikube`/k8s labs, `docker build` | Google account (free) | 5 GB home persist; session ~1 hr idle |
| C | **Killercoda / Play-with-K8s** | Sirf Kubernetes YAML labs (probes, rollouts, policies) | Killercoda free | ~1 hr ephemeral |
| D | **Render + Neon + Upstash + Confluent Cloud** | "Sach mein deploy": order-service public URL, managed PG/Redis/Kafka | 4 free signups | Free tier (sota hai idle pe) |

---

## A. GitHub Codespaces — poora compose stack browser mein (recommended)

Codespaces ek Linux VM + VS Code browser mein deta hai, jisme Docker already hai. ShopFlow ka `make up` seedha chal jaata hai.

```bash
# 1. Repo kholo: github.com/Rajji01/DevopsComplete -> green "Code" button -> Codespaces -> Create
# 2. Terminal mein:
cd shopflow
docker compose up --build -d          # ya repo root se: make up
docker compose ps                     # sab healthy hone do (~2-3 min)

# 3. JWT lo (alice):
TOKEN=$(curl -s -X POST http://localhost:8180/realms/shopflow/protocol/openid-connect/token \
  -d grant_type=password -d client_id=shopflow-web -d username=alice -d password=alice | jq -r .access_token)

# 4. Ek order lagao:
curl -s -X POST http://localhost:8081/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"sku":"PS5-SLIM","quantity":1}' | jq
```

Ports: Codespaces "Ports" tab mein 8081 (order), 8082 (inventory), 9090 (prometheus), 3000 (grafana) ko forward/public karke browser mein khol. Teardown: `docker compose down -v`.

> Codespaces free tier 2-core / 4-core deta hai. Poora stack (14 containers) 4-core / 8 GB machine pe aaram se. Agar heavy lage, sirf zaroori services uthao: `docker compose up -d postgres kafka order-service inventory-service prometheus`.

## B. Google Cloud Shell — Kubernetes (minikube) labs online

Cloud Shell free hai, `kubectl`+`docker` already hain, aur `minikube` install kar sakte ho. k8s wale comprehensions (probes, rollouts, HPA, policies) yahan karo.

```bash
# shell.cloud.google.com kholo, phir:
git clone https://github.com/Rajji01/DevopsComplete && cd DevopsComplete
curl -LO https://storage.googleapis.com/minikube/releases/latest/minikube-linux-amd64
sudo install minikube-linux-amd64 /usr/local/bin/minikube
minikube start --driver=docker

make k8s-dev                          # Kustomize dev overlay apply
kubectl get pods -w                   # pods aate hue dekho
```

Yahan images build karni pad sakti hain (`eval $(minikube docker-env) && make build`) kyunki dev overlay local images reference kar sakta hai — har topic ka Local section exact command deta hai.

## C. Killercoda — sirf YAML k8s labs, zero setup

[killercoda.com](https://killercoda.com) → "Kubernetes" playground → ek terminal + ready cluster. Repo ki raw YAML paste/apply karke probes/rollouts/secrets wale labs turant. Build/compose nahi chahiye to sabse fast.

## D. Sach mein internet pe deploy (free tiers) — "deploy online, issue fix online"

Jab tu bole "online pe service setup + code deploy + issue fix", matlab ye: ek service ko public URL pe le aao, managed infra ke saath, aur jo tootay usay dashboard/logs se theek karo. Minimal real deploy = **inventory-service** (sabse kam dependency: sirf Postgres + optional Redis; Kafka/Keycloak nahi chahiye).

| Cheez | Free service | Note |
|---|---|---|
| Compute (container) | **Render** (web service, Docker) ya **Fly.io** / **Railway** | Render free web service idle pe sleep karta hai |
| Postgres | **Neon** ya **Supabase** | `DB_URL=jdbc:postgresql://...` env set karo |
| Redis | **Upstash** (serverless Redis) | `REDIS_HOST`/TLS; ya `CACHE_TYPE` off karke skip |
| Kafka (order/payment/notif ke liye) | **Confluent Cloud** / **Upstash Kafka** | `KAFKA_BOOTSTRAP_SERVERS` + SASL creds |
| Identity (order-service JWT) | **Keycloak on Render** ya skip auth in a "dev" profile | Cognito prod path `infra/terraform/aws` |

Flow (inventory-service example):
1. Neon pe DB banao -> connection string lo.
2. Render -> New Web Service -> repo connect -> Docker -> build arg `SERVICE=inventory-service`.
3. Env set: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `CACHE_TYPE=none` (ya Upstash).
4. Deploy -> public URL -> `GET https://<app>.onrender.com/actuator/health` 200 aana chahiye.
5. **Issue fix drill:** galat `DB_URL` do -> readiness DOWN -> Render logs mein `Connection refused` -> fix -> redeploy. Yahi "online issue fix" ka asli abhyaas hai.

> Full event-driven stack (saga/outbox) online chalane ke liye Kafka chahiye = Confluent Cloud free cluster + SASL config. Pehle Codespaces (A) pe end-to-end dekh lo, phir ek service ko Render (D) pe public karo — dono milke "local samajh + real deploy" dono de dete hain.

## Kaunsa environment kis comprehension ke liye

| Comprehension | Best online env |
|---|---|
| 01 outbox, 02 saga, 03 idempotency, 06 SLO | **A** (compose: Kafka+services+prometheus chahiye) |
| 04 limbo/reconciler, 05 overselling | **A** (order+inventory+load) |
| 07 k8s readiness/probes | **B** ya **C** (cluster chahiye) |
| 08 supply-chain signing | **A** ya local (docker build + cosign; CI already karta hai) |
| "Real deploy" ka swaad | **D** (inventory-service pe) |
</content>
