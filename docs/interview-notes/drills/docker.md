# Docker — interview drill set (ShopFlow edition)

> **Ye kya hai:** ek **question-only drill sheet** — basic se prod tak. Study notes `../04-docker.md` mein hain; ye wo set hai jise **ek-ek karke mock-interview** ki tarah drill karna: padho → 2–4 sentence bolo → notes se check.
>
> **Tags:** `[C]` = core · `[SF]` = ShopFlow se defend · `[P]` = prod / failure scenario.

## ShopFlow mein Docker kahan (grounding)

- **Per-service Dockerfiles** — multi-stage build (`mvnw` se jar banao, phir chhoti runtime image mein copy).
- **compose:** `shopflow/docker-compose.yml` — har service `build:` + `image: shopflow/<svc>:local`, Postgres 16, Kafka 4.1 (KRaft), Redis, Keycloak, Prometheus/Grafana/Tempo/Loki.
- **Healthchecks:** har service pe `wget -qO- http://localhost:<port>/actuator/health/readiness`.
- **Non-root runtime:** container `runAsUser 10001`, `readOnlyRootFilesystem`, `/tmp` emptyDir — (k8s/Helm layer pe enforce; image bhi non-root).
- **depends_on + condition:** `redis: condition: service_healthy` type wiring.

---

## TIER 1 — Fundamentals
1. Container vs VM — asal farq (kernel share) kya hai? `[C]`
2. Image vs container vs layer — teeno ko ek line mein. `[C]`
3. Docker image **layers** kaise bante, aur caching kaise kaam karti? `[C]`
4. `CMD` vs `ENTRYPOINT` — farq aur kab kaun? `[C]`
5. `COPY` vs `ADD` — kaun prefer aur kyun? `[C]`
6. `EXPOSE` sach mein port kholta hai? `[C]`
7. ShopFlow ka ek service container ban ke run hone tak ka flow batao. `[SF]`

## TIER 2 — Dockerfile craft (yahan senior dikhte ho)
8. **Multi-stage build** kya hai aur ShopFlow mein kyun? (build tools final image se bahar) `[SF][C]`
9. Layer ordering se build cache kaise maximize karte ho (deps pehle, source baad)? ShopFlow Maven ke liye kaise? `[SF][P]`
10. Image size kam karne ke tareeke? (slim/distroless base, multi-stage, `.dockerignore`) `[P]`
11. `.dockerignore` kyun zaroori? Na ho to kya? `[P]`
12. Container ko **non-root** kyun chalate ho? ShopFlow kaunsa user (10001) aur kyun? `[SF][P]`
13. **Read-only root filesystem** + `/tmp` writable — kyun aur kaise? `[SF][P]`
14. `HEALTHCHECK` kya karta? ShopFlow mein readiness pe kyun point karta? `[SF]`

## TIER 3 — JVM-in-container (Java-specific, bahut poocha jaata)
15. Container memory limit aur JVM heap — purana bug kya tha, ab kaise theek (`MaxRAMPercentage` / container-aware JVM)? `[P][C]`
16. Container ko kitni CPU? JVM GC threads / pool sizing pe asar? `[P]`
17. OOMKilled container — kaise pata chalega ye JVM heap tha ya native/limit? `[P]`

## TIER 4 — Runtime, networking, storage
18. Container ka data persist kaise? **volume vs bind mount** — kab kaun? `[C]`
19. Docker networking — ek container dusre ko naam se kaise dhoondta (compose DNS)? ShopFlow mein `INVENTORY_URL=http://inventory-service:8082` kaise resolve hota? `[SF][C]`
20. `depends_on` sirf start-order deta hai, readiness nahi — ShopFlow ne `condition: service_healthy` se kaise fix kiya? `[SF][P]`
21. Container stop hone pe graceful shutdown — `SIGTERM` vs `SIGKILL`, PID 1 problem kya? `[P]`
22. Secrets ko image mein `ENV`/`ARG` se daalna kyun galat? Sahi tareeka? `[P]`

## TIER 5 — Prod / failure scenarios
23. Image build CI pe kaam karti, prod pe fail — reproducibility kaise ensure karte (pinned tags, digest, lockfiles)? `[P]`
24. `latest` tag production mein kyun mana? `[P]`
25. Container baar-baar restart ho raha (CrashLoopBackOff jaisa) — compose/docker level pe kaise debug? `[P]`
26. Image mein vulnerability — scanning kaise (Trivy/Grype), base image patching strategy? `[P]`
27. Build cache poisoning / supply chain — `signed-by` repos, pinned base, provenance — kyun? `[P]`
28. Ek container disk/CPU/mem kha raha baaki ko bhukha maar raha — limits kaise? `[P]`

---

### Drill log
| # | Topic | Attempted | Confident |
|---|---|---|---|
| 1–7 | Fundamentals | ☐ | ☐ |
| 8–14 | Dockerfile craft | ☐ | ☐ |
| 15–17 | JVM-in-container | ☐ | ☐ |
| 18–22 | Runtime/net/storage | ☐ | ☐ |
| 23–28 | Prod failures | ☐ | ☐ |

> Tip: JVM-in-container (15–17) aur non-root/read-only (12–13) — ye do cluster interview mein "kitna deep jaanta hai" test karte hain. Inhe pakka karo.
