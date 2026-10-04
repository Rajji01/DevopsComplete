# DevOps Complete

Goal: **mastery in DevOps + Java backend**, by building, shipping, running and debugging real services.

| Start here | What it is |
|---|---|
| [`shopflow/`](shopflow/README.md) | Production-style microservices: order, inventory and notification services (Spring Boot 3.5, Java 21, PostgreSQL, Kafka with transactional outbox, Redis, OAuth2/JWT, Resilience4j, OpenTelemetry), Docker Compose stack, Kubernetes (Kustomize, HPA, PDB, NetworkPolicies), CI/CD with image scanning, Jenkinsfile, Argo CD |
| [`infra/terraform/aws/`](infra/terraform/aws/README.md) | Production on AWS: VPC, EKS (IRSA), ECR, RDS Multi-AZ, ElastiCache, MSK, Cognito, Secrets Manager, GitHub OIDC |
| [`docs/SELF-WALK.md`](docs/SELF-WALK.md) | Review log: every bug found in this repo, why it mattered, how it was fixed, what is still open |
| [`docs/EASY-NOTES.md`](docs/EASY-NOTES.md) | **Start here if concepts feel heavy**: har pattern ka problem → kyu → kaise (steps) → ShopFlow mein kahan → prod kaise behtar hua, simple Hinglish mein |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | 18-week mastery roadmap built around this repo |
| [`docs/LABS.md`](docs/LABS.md) | 23 hands-on break/fix labs (OOMKilled, CrashLoopBackOff, circuit breaker, zero-downtime deploy, kill Kafka, duplicate events, poison pill, JWT, cache, tracing, ...) |
| [`docs/interview-notes/`](docs/interview-notes/) | Interview notes: project walkthrough, Java/Spring, microservices, Docker, Kubernetes, CI/CD, observability, Linux/networking, AWS production, Kafka, security/caching/performance, and a hard-questions file |

## Basics (first learning project)

Two simple Spring Boot services, their Docker images, and Kubernetes manifests (run on minikube) for pods, deployments, services, volumes, ConfigMaps, Secrets, Jobs and CronJobs. Command cheatsheet: [`notes.txt`](notes.txt).

## Services

| Service | Dir | Port | Image | Endpoints |
|---|---|---|---|---|
| backend | `Devops/` | 8080 | `rajji01/backend-micro001` | `/api/m1` property value, `/api/m2` call helper via ClusterIP, `/api/m3` call helper via service DNS, `/api/m4` value from ConfigMap |
| helper | `microservice01/` | 8081 | `rajji01/backend-micro002` | `/api2/m2`, `/api2/readFile` (file from emptyDir volume) |

The backend reaches the helper through `helper-service` (ClusterIP, port 8088 → 8081).

Configuration comes from env vars, with defaults so the apps also run locally:

| Env var | Service | Default |
|---|---|---|
| `TESTING_PROPERTY` | both | `local-default` |
| `HOST_PORT` | backend | `9092` |
| `HELPER_SERVICE` | backend | `helper-service` |
| `HELPER_CLUSTER_IP` | backend | `10.100.13.14` (your own value: `k get svc helper-service`) |
| `CACHE_FILE_PATH` | helper | `/cache/myfile.txt` |

## Build & run

```sh
# test
cd Devops && ./mvnw verify

# image (multi-stage: Maven build happens inside Docker)
docker build -t rajji01/backend-micro001 Devops
docker build -t rajji01/backend-micro002 microservice01

# run locally
docker run -d -p 8080:8080 rajji01/backend-micro001
curl localhost:8080/api/m1
```

## Deploy to minikube

```sh
kubectl apply -f Devops/backend-cm.yaml          # ConfigMap (host-port)
kubectl apply -f microservice01/helperdeploy.yaml
kubectl apply -f microservice01/helper-service.yaml
kubectl apply -f Devops/backenddeploy.yaml
kubectl apply -f Devops/backend-service.yaml     # NodePort 30004
minikube service backend-service --url
```

Note: `Devops/backend-cm.yaml` and `Devops/testing-config-map.yaml` both define the `app-properties` ConfigMap; the one applied last wins. The backend needs the `host-port` key from `backend-cm.yaml`.

## CI (basics)

`.github/workflows/ci.yml` runs on every push and PR. It runs the Maven tests and a Docker build for both services, then validates every manifest with [kubeconform](https://github.com/yannh/kubeconform).
