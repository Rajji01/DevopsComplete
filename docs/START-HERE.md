# Start here: ek din mein is project ko samajhne ka plan

Ye repo bada ho gaya hai (4 services, 3 tarah ke deploy, AWS, 40+ labs, 12 notes files). Is page ka kaam: **kis order mein padho** taaki ek din (6–7 ghante) mein poori picture dimaag mein ban jaye, aur baaki hafton mein labs ke saath gehra ho.

> Tip: Pehle din code mat likho. Padho, chalao, dekho. Haath tab lagana jab map dimaag mein ho.

## Poora map (10 minute)

```
                               tum (browser / curl, JWT token)
                                          │
   ┌──────────────────────────────────────┼──────────────────────────────────────────┐
   │ LOCAL (docker compose)               ▼                        PROD (AWS, Terraform)     │
   │  Keycloak ── token ──▶ order-service ──REST──▶ inventory-service ──▶ Redis            │
   │                           │  outbox                 │ atomic UPDATE                     │
   │                           ▼                         ▼                                  │
   │                      Postgres(orders)        Postgres(inventory)       RDS / ElastiCache │
   │                           │ relay                                                      │
   │                           ▼                                                            │
   │     Kafka orders.events ──┬──▶ notification-service (email log)                        │
   │                           └──▶ payment-service ──▶ payments.events ──▶ order-service  │
   │                                     (charge / decline)              (PAID / CANCELLED)  │
   │                                                                      MSK / Cognito      │
   │  Prometheus ─▶ Alertmanager ─▶ (PagerDuty/Slack)   Tempo (traces)   Loki (logs)  Grafana │
   └─────────────────────────────────────────────────────────────────────────────────────────┘
          ▲                                   ▲                                   ▲
   Docker images (CI: test → lint →     Kubernetes: Kustomize | Helm |      GitOps: CI pins SHA,
   scan → SBOM → sign → push)           Argo Rollouts; Kyverno policies      Argo CD syncs
```

Teen sawaal jo poore project ko jodte hain:
1. **Sahi jawab kaise?** atomic update, idempotency, ownership (Level 1 notes)
2. **Dependency toote to?** timeout/retry/breaker, outbox, idempotent consumer, reconciler (Level 2)
3. **Prod mein kaun chalayega, kaise?** K8s, CI/CD, alerts+runbooks, policies, AWS (Level 3)

## Din ka schedule

| Time | Kya | Kahan | Output |
|---|---|---|---|
| 0:00–0:30 | Repo ka map + state | ye page, [`STATE.md`](STATE.md) §1–2 | pata hai kya-kya hai aur basic vs advanced kaunsa |
| 0:30–1:30 | Concepts, easy language | [`EASY-NOTES.md`](EASY-NOTES.md) Part 0–3 | har pattern ka "kyu" |
| 1:30–2:00 | Stack chalao | `make up`, `make token`, ek order, Grafana | sab kuch apni aankhon se |
| 2:00–3:00 | Code padho, request ke order mein | niche "code reading order" | flow dimaag mein |
| 3:00–3:30 | Break | | |
| 3:30–4:30 | Todo aur dekho | [`LABS.md`](LABS.md) Lab 2, 4, 17, 26 | race, breaker, outbox, saga live |
| 4:30–5:30 | DevOps side | [`EASY-NOTES.md`](EASY-NOTES.md) Part 5–7, [`runbooks/`](runbooks/README.md) ek runbook, [`STATE.md`](STATE.md) §2 matrix | deploy, alert, policy, backup ka "kyu" |
| 5:30–6:00 | History padho | [`SELF-WALK.md`](SELF-WALK.md) | har bug ki story (interview material) |
| 6:00–6:30 | Revision | [`interview-notes/README.md`](interview-notes/README.md) rapid-fire | 1-line answers |

Agle dinon: [`ROADMAP.md`](ROADMAP.md) phase by phase, har phase mein labs + [`PRACTICE-LABS.md`](PRACTICE-LABS.md) ke tasks, aur us phase ki [`interview-notes/`](interview-notes/) file.

## Code reading order (2:00–3:00 slot)

Ek order ka safar, file by file. Har file 5–10 minute, comments padho, "ye kyu hai" khud se poochho.

| # | File | Dekho |
|---|---|---|
| 1 | `order-service/.../security/SecurityConfig.java` | kaun call kar sakta hai; actuator kyu open |
| 2 | `order-service/.../order/OrderController.java` | validation, Idempotency-Key, 201 vs 200, `Caller` |
| 3 | `order-service/.../order/OrderService.java` | `placeOrder` (no @Transactional around HTTP!), `saveWithEvent` (TransactionTemplate), `reconcileFailedOrders`, `onPaymentFailed` |
| 4 | `order-service/.../inventory/InventoryClient.java` + `application.yml` `resilience4j:` | retry sirf transient pe, breaker numbers |
| 5 | `inventory-service/.../product/ProductRepository.java` | **ek line jo oversell rokti hai** |
| 6 | `inventory-service/.../reservation/ReservationService.java` + `ReservationController.java` | idempotency, race ka loser winner ka jawab deta hai, cache evict |
| 7 | `shopflow-outbox/.../OutboxRelay.java` | SKIP LOCKED, at-least-once, cleanup |
| 8 | `payment-service/.../OrderConfirmedListener.java` | saga step 2, PK = eventId idempotency |
| 9 | `order-service/.../payment/PaymentEventListener.java` | saga step 3, compensation |
| 10 | `notification-service/.../OrderEventListener.java` + `KafkaErrorConfig.java` | duplicate skip, DLT |
| 11 | `order-service/src/test/.../OrderServiceApplicationTests.java` | har behaviour ka proof; WireMock, EmbeddedKafka, jwt() |
| 12 | `k8s/base/order-service/deployment.yaml` | har line ka comment: probes, resources, preStop, securityContext |
| 13 | `monitoring/alert-rules.yml` + `slo-rules.yml` + `tests/alerts_test.yml` | alert = code = test |
| 14 | `.github/workflows/shopflow.yml` | commit se prod tak ka raasta |

## "Kahan hai woh cheez?" (cheat sheet)

| Dhoondh rahe ho | Jagah |
|---|---|
| Sab commands | `make help` |
| Kya verified hai, kya nahi | `docs/STATE.md` §3 |
| Basic vs advanced approach comparison | `docs/STATE.md` §2 + wahan diye README |
| Alert aaye to kya karna | `docs/runbooks/<AlertName>.md` |
| Ek concept samajh nahi aaya | `docs/EASY-NOTES.md` (Ctrl+F) |
| Interview answer | `docs/interview-notes/README.md` rapid-fire → us topic ki file |
| Ye bug kyu tha / kaise fix hua | `docs/SELF-WALK.md` |
| Khud kuch banana hai | `docs/PRACTICE-LABS.md` |
| AWS pe kaise chalega (bina create kiye padhna) | `infra/terraform/aws/README.md`, `docs/interview-notes/09-aws-production.md` |
| Kubernetes ke bina kaise | `infra/ansible/README.md` |

## Teen galtiyan jo pehle din log karte hain

1. **Sab ek saath chalane ki koshish** (minikube + AWS + Ansible). Pehle sirf `make up`. Baaki baad mein.
2. **Advanced approach ko "sahi" samajhna.** Dono rakhe gaye hain kyunki dono sahi hain, alag situations mein. Matrix padho.
3. **Notes ratna, code na padhna.** Interview mein "in my project, file X does Y" hi bikta hai.
