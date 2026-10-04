# 11 — Security, Caching, Performance & Tracing (Interview Notes)

> Source of truth in this repo:
> - Security: `shopflow/order-service/src/main/java/com/shopflow/order/security/SecurityConfig.java`, `JwtRolesConverter.java`; `application.yml` (`spring.security.oauth2.resourceserver.jwt.issuer-uri`); Keycloak realm `shopflow/k8s/base/keycloak/shopflow-realm.json`; compose `keycloak` service; `infra/terraform/aws/cognito.tf`; tests using `SecurityMockMvcRequestPostProcessors.jwt()`
> - Caching: `inventory-service` — `InventoryServiceApplication` (`@EnableCaching`), `ProductController.get` (`@Cacheable`), `ReservationService` (`@CacheEvict`), `application.yml` (`spring.cache.type=${CACHE_TYPE:simple}`, Redis TTL 60s), compose/k8s `redis`, test `productReadIsCachedAndEvictedOnReserve`
> - Performance: `@RateLimiter(name = "orders")` on `OrderController.create`, `resilience4j.ratelimiter` config, `GlobalExceptionHandler.handleRateLimited` (429), test `rateLimitExceededIs429`; `spring.threads.virtual.enabled: true` in all three services; Hikari pool sizes
> - Tracing: parent `pom.xml` (`micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`), `management.tracing.*` / `management.otlp.tracing.endpoint`, `spring.kafka.*.observation-enabled`, `docker-compose.yml` (Tempo, Loki, Alloy, Grafana), `monitoring/tempo.yml`, `monitoring/alloy.river`, `monitoring/grafana/provisioning/datasources/datasources.yml`
>
> Honest scope: only **order-service** validates JWTs (inventory and notification have no Spring Security; inventory is protected by NetworkPolicy and is not routed by the Ingress for `/reservations`). The rate limiter is **per pod**, not distributed. Say these out loud before the interviewer finds them.

---

## 0. Cheat table

| Topic | ShopFlow value | Where |
|---|---|---|
| Auth model | OAuth2 **resource server**, JWT bearer tokens, **stateless**, CSRF disabled | `SecurityConfig` |
| Issuer | `${JWT_ISSUER_URI:http://localhost:8180/realms/shopflow}` → Keycloak locally; Cognito pool URL in prod (`shopflow-endpoints` ConfigMap) | `application.yml`, overlays |
| Validation | signature via issuer's **JWKS** (auto-discovered from `issuer-uri`), `exp`, `iss`; algorithm RS256 (asymmetric) | Spring Security defaults |
| Roles | `realm_access.roles` (Keycloak) **or** `cognito:groups` (Cognito) → `ROLE_<name>` authorities | `JwtRolesConverter` |
| Rules | `/actuator/**`, swagger → `permitAll`; `GET /api/v1/orders/**` → `customer` or `support`; other `/api/v1/orders/**` → `customer`; everything else `denyAll` | `SecurityConfig` |
| Realm | realm `shopflow`, roles `customer`, `support`; users `alice`/`alice` (customer), `bob`/`bob` (support); public client `shopflow-web` (auth code + direct grant), access token lifetime 900s | `shopflow-realm.json` |
| Test auth | `jwt().authorities(new SimpleGrantedAuthority("ROLE_customer"))`; `@WebMvcTest` imports `SecurityConfig` + `JwtRolesConverter`, `@MockitoBean JwtDecoder` | both order test classes |
| Cache | `@Cacheable(cacheNames = "products", key = "#sku")` on a **DTO**; `@CacheEvict(key = "#sku")` in `reserve`, `allEntries = true` in `release` | `ProductController`, `ReservationService` |
| Cache backend | `CACHE_TYPE=simple` (ConcurrentHashMap) by default/tests; `redis` in compose/K8s; TTL 60s, key prefix `inventory:` | `application.yml` |
| Redis | `redis:7.4-alpine --maxmemory 64mb --maxmemory-policy allkeys-lru`; prod ElastiCache (TLS) | compose, `k8s/base/redis`, `elasticache.tf` |
| Rate limit | 50 order creations / 1s / pod, `timeout-duration: 0` → immediate **429** ProblemDetail "Too many requests" | `application.yml`, `GlobalExceptionHandler` |
| Threads | `spring.threads.virtual.enabled: true` (Tomcat + `@Scheduled` + Kafka listener run on virtual threads) | all `application.yml` |
| DB pool | Hikari `maximum-pool-size` 10 default, `DB_POOL_SIZE=5` in K8s (10 pods × 5 × 2 services = 100 < `max_connections=200`) | `application.yml`, deployments |
| Tracing | `management.tracing.sampling.probability: ${TRACING_SAMPLE:1.0}`, OTLP HTTP to `${OTLP_ENDPOINT:http://localhost:4318/v1/traces}`; disabled in tests (`management.otlp.tracing.export.enabled: false`) | `application.yml`, `application-test.yml` |
| Backends | Tempo 2.8.1 (traces), Loki 3.5.3 (logs via Alloy 1.10 tailing Docker stdout), Grafana 12.1.1 with traces↔logs links | compose + `monitoring/` |

---

# PART A — SECURITY

## A1. OAuth2 / OIDC flows

```
Authorization Code + PKCE (users: SPA / mobile / server-rendered)
  Browser ──▶ Keycloak /auth?response_type=code&code_challenge=S256(verifier) ──▶ login page
  Browser ◀── redirect ?code=XYZ
  App     ──▶ /token  grant_type=authorization_code&code=XYZ&code_verifier=verifier
  App     ◀── {access_token (JWT), id_token (OIDC), refresh_token}
  App     ──▶ GET /api/v1/orders   Authorization: Bearer <access_token>   ──▶ order-service validates

Client Credentials (service-to-service, no user)
  order-service ──▶ /token  grant_type=client_credentials&client_id&client_secret (or mTLS / private_key_jwt)
                ◀── {access_token with scope inventory:reserve}
                ──▶ POST inventory /api/v1/reservations  Bearer <token>
```

| Flow | Use when | Notes |
|---|---|---|
| **Authorization Code + PKCE** | any app with a human user (SPA, mobile, web) | PKCE replaces client secret for public clients; implicit flow is deprecated |
| **Client Credentials** | machine-to-machine | no user context; scopes describe the caller's permissions |
| **Refresh token** | get a new access token without re-login | rotate refresh tokens; short access tokens (Keycloak realm here: 900s) |
| **Resource Owner Password (direct grant)** | legacy / local testing only | ShopFlow's README uses it to get a token with `curl` (`grant_type=password`, client `shopflow-web`, `directAccessGrantsEnabled: true`). Never for real users: the app sees the password. |
| **Device code** | TVs/CLIs | — |

OIDC = OAuth2 + identity: adds the **`id_token`** (who the user is) and `/userinfo`, and the **discovery document** `/.well-known/openid-configuration` which Spring uses to find the **JWKS URL** from `issuer-uri`.

### ShopFlow's identity providers

| | Keycloak (compose / minikube) | Amazon Cognito (prod, `cognito.tf`) |
|---|---|---|
| Issuer | `http://localhost:8180/realms/shopflow` (compose), `http://keycloak.shopflow.local/realms/shopflow` (K8s `KC_HOSTNAME`) | `https://cognito-idp.ap-south-1.amazonaws.com/<pool-id>` |
| Roles claim | `realm_access.roles: ["customer"]` | `cognito:groups: ["customer"]` (groups `customer`, `support` created in Terraform) |
| Client | public `shopflow-web`, redirect `http://localhost:*`, standard flow + direct grant | user pool client `web`, `allowed_oauth_flows = ["code"]`, SRP + refresh |
| Config | realm JSON imported at start (`--import-realm`), mounted from a ConfigMap (`keycloak-realm`, `configMapGenerator`) | managed |
| In K8s | Deployment + `startupProbe` on `/realms/shopflow`, admin password from Secret `keycloak-admin`, reachable only from ingress-nginx and order-service (JWKS) per NetworkPolicy | prod overlay deletes Keycloak and adds egress `0.0.0.0/0:443` for the Cognito JWKS |

Same image, different `JWT_ISSUER_URI` — that is the whole point of `JwtRolesConverter` supporting both claim layouts.

Compose gotcha worth telling: tokens carry `iss = http://localhost:8180/...` because the browser/curl talks to Keycloak via `localhost`; order-service runs in a container where `localhost` is itself. The compose file sets `KC_HOSTNAME: http://localhost:8180` and gives order-service `extra_hosts: ["localhost:host-gateway"]` so the container's `localhost` resolves to the Docker host and the JWKS fetch at the issuer URL works. In K8s both sides use the same hostname (`keycloak.shopflow.local`), so no trick is needed.

## A2. JWT — structure and validation

```
eyJhbGciOiJSUzI1NiIsImtpZCI6IjEyMyJ9 . eyJpc3MiOiJodHRwOi8vLi4uIiwic3ViIjoiYWxpY2UiLCJleHAiOjE3Li4ufQ . <signature>
      header (alg, kid, typ)              payload (claims)                                  RSA-SHA256 over header.payload
```

Claims you must know: `iss` (issuer), `sub` (subject = user id), `aud` (audience), `exp`/`iat`/`nbf` (times), `azp` (authorized party / client), `scope`, plus provider-specific ones (`realm_access`, `cognito:groups`, `preferred_username`, `email`).

What Spring's resource server does with `issuer-uri` set:
1. At startup (lazily on first request) fetch `<issuer>/.well-known/openid-configuration` → read `jwks_uri`.
2. On each request: parse the bearer token, pick the key by **`kid`** from the cached JWKS, verify the **RS256** signature, validate `exp` (with 60s clock skew), validate `iss` equals the configured issuer. **`aud` is not validated by default** — add `JwtValidators`/`spring.security.oauth2.resourceserver.jwt.audiences` in a real deployment (honest gap).
3. Convert to an `Authentication`: ShopFlow's `JwtRolesConverter` builds `JwtAuthenticationToken(jwt, authorities, jwt.getSubject())` with `ROLE_` prefixed authorities.

**Key rotation**: the IdP publishes several keys in the JWKS with different `kid`s; new tokens are signed with the new key while old tokens still validate against the old one until they expire. Spring's `NimbusJwtDecoder` refetches the JWKS when it sees an unknown `kid` (with rate limiting), so rotation needs no restart.

**Why RS256 (asymmetric), not HS256 (shared secret)?** With HS256 every resource server holds the *same* secret that can also *mint* tokens — a leak from one service compromises all; distributing/rotating the secret is painful. With RS256 only the IdP has the private key; services only need the public JWKS. Also RS256 lets you accept tokens from an issuer you do not control (Cognito). Reject `alg: none` and never let the token choose the algorithm (Nimbus pins it).

**JWT pros/cons**: stateless validation and horizontal scaling; but hard to revoke (keep access tokens short — 15 min here — and revoke refresh tokens), payload is only base64 (no secrets in claims), size on every request. Opaque tokens + introspection are the alternative when revocation matters more than latency.

## A3. Resource server vs authorization server; stateless; CSRF

- **Authorization server** (Keycloak/Cognito): authenticates users, issues tokens, manages clients, keys, consent.
- **Resource server** (order-service): never sees passwords, never issues tokens, only **validates** and **authorizes**. Dependency `spring-boot-starter-oauth2-resource-server`.
- `SessionCreationPolicy.STATELESS`: no `JSESSIONID`, no server-side session, every request carries the token → any pod can serve any request (HPA-friendly), nothing to replicate.
- **CSRF disabled** — the reasoning, not just the line: CSRF attacks ride on **credentials the browser attaches automatically** (cookies, basic auth). ShopFlow authenticates with a bearer token the client must add explicitly in a header, so a cross-site form post cannot carry it. No cookie → no CSRF surface. If you ever store the JWT in a cookie, you must re-enable CSRF protection (or use `SameSite`).
- `permitAll` for `/actuator/**` and swagger: kubelet probes and Prometheus do not carry tokens. Exposure is controlled at the network layer instead: the Ingress only routes `/api/v1/orders` and `/api/v1/products`, and NetworkPolicies let only `ingress-nginx` and `monitoring` reach the pods. For a public deployment also move actuator to a separate management port.
- `anyRequest().denyAll()`: a new controller is **closed by default** until a rule is written — fail-safe.

## A4. Roles vs scopes vs permissions; method security

| Concept | Means | Where it lives | Spring check |
|---|---|---|---|
| **Role** | who the user *is* (customer, support, admin) | IdP (realm roles / Cognito groups) | `hasRole("customer")` ⇔ authority `ROLE_customer` |
| **Scope** | what the *client app* was allowed to ask for (`orders:write`) | token `scope` claim, granted per client | `hasAuthority("SCOPE_orders:write")` (default converter prefixes `SCOPE_`) |
| **Permission** | fine-grained action on a resource ("cancel order 42 if you own it") | application logic / policy engine (OPA, Keycloak authz) | `@PreAuthorize("@orderAuth.owns(#id)")` |

ShopFlow uses **roles**: `GET /api/v1/orders/**` → `hasAnyRole("customer", "support")`, writes (`POST`, `DELETE`) → `hasRole("customer")`. Test `rejectsRequestsWithoutAValidToken`: no token → **401**, support posting → **403**, liveness without token → 200. 401 = "who are you?" (missing/invalid token), 403 = "I know you, you may not".

`@EnableMethodSecurity` is on `SecurityConfig`, so `@PreAuthorize` works, but **no method is annotated yet** — the rules are URL-based. The natural next step is ownership: today any `customer` can read or cancel *any* order (honest gap). Fix: store `sub` on the order at creation and check `authentication.name == order.ownerSub` in the service or with `@PostAuthorize`.

`JwtRolesConverter` replaces Spring's default `JwtGrantedAuthoritiesConverter` (which only reads `scope`/`scp`) because Keycloak/Cognito put roles in nested/custom claims; the `ROLE_` prefix is what `hasRole()` expects.

## A5. Service-to-service authentication

| Option | How | Pros | Cons | ShopFlow |
|---|---|---|---|---|
| **NetworkPolicy** (L3/L4) | only pods with label `order-service` may reach inventory:8082 | zero code, enforced by CNI | no identity proof (any process in that pod), no encryption, needs Calico/Cilium | **used** (`inventory-service-ingress`) |
| **Client credentials JWT** | order-service fetches a token with scope `inventory:reserve`, inventory validates it | app-level identity, works across clusters | token fetching/caching code, IdP dependency on the hot path | not yet |
| **mTLS / service mesh** (Istio, Linkerd) | sidecars exchange certificates (SPIFFE ids), `AuthorizationPolicy` says who may call what | encryption + identity + L7 policy, zero app code, auto rotation | operational complexity, sidecar overhead | not yet |
| **Token propagation** | forward the user's JWT downstream | end-user context preserved | audience/scope mismatch, long chains | not yet |
| **AWS IAM** | IRSA role → MSK IAM auth, Secrets Manager | no secrets to manage | AWS-only | prod design (`msk.tf`, `irsa.tf`) |

Say: *"Defense in depth: NetworkPolicy today, and I would add client-credentials tokens or a mesh so inventory also verifies **who** is calling, not just from which pod."*

## A6. Secrets handling

- Never in git / images / logs. ShopFlow: DB passwords via Secret `shopflow-db` (dev: `secretGenerator` with throwaway values; prod: `ExternalSecret` from AWS Secrets Manager via ESO + IRSA), Keycloak admin password via Secret `keycloak-admin`, GHCR via `GITHUB_TOKEN`, cloud via **OIDC** (`ci-oidc.tf`) — no long-lived keys in CI.
- Env vars vs mounted files: env vars leak via `kubectl describe`/crash dumps; files + `readOnlyRootFilesystem` are safer. Rotation: ESO refresh interval + rolling restart (hash suffix / Reloader).
- `management.endpoints.web.exposure.include: health,info,prometheus` only — `env`/`configprops`/`heapdump` would leak secrets.
- Tokens are never logged; ProblemDetail responses return generic messages for downstream/DB errors (no stack traces).

## A7. OWASP API Security Top-10 — mapped to ShopFlow

| Risk | ShopFlow status |
|---|---|
| API1 Broken object-level authorization | **gap**: any `customer` can `GET/DELETE` any order id → add ownership check |
| API2 Broken authentication | JWT validated (signature, exp, iss); short-lived tokens; direct grant only for local testing |
| API3 Broken object property level auth | DTO records (`OrderResponse`) — entities and `version`/internal fields are never exposed |
| API4 Unrestricted resource consumption | `@RateLimiter` 50 rps/pod → 429; pagination `size ≤ 100`; `quantity ≤ 100`; timeouts on downstream calls; memory limits |
| API5 Broken function-level authorization | method-based rules: writes need `customer`, `support` is read-only; `denyAll` default |
| API6 Unrestricted access to business flows | `Idempotency-Key`, rate limit; a bot buying all stock would still need a waiting room/captcha (gap) |
| API7 SSRF | no user-supplied URLs are fetched |
| API8 Security misconfiguration | `ddl-auto: validate`, least actuator exposure, non-root, read-only FS, dropped caps, seccomp, `automountServiceAccountToken: false`; swagger is `permitAll` (restrict in prod) |
| API9 Improper inventory management | OpenAPI (`springdoc`) documents every endpoint; `/api/v1` versioned |
| API10 Unsafe consumption of APIs | inventory responses mapped by status; Kafka payload parsed with tolerant reader; poison pills → DLT |

Classic OWASP web items still apply: injection (JPA parameters, no string concatenation), Bean Validation on input, no secrets in error bodies.

## A8. Security headers & TLS

Spring Security adds defaults: `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `X-XSS-Protection: 0`, and `Strict-Transport-Security` on HTTPS. For a pure JSON API also set a restrictive `Content-Security-Policy` and `Referrer-Policy`; CORS only for the real front-end origin. TLS terminates at the Ingress (cert-manager, `shopflow-tls`) / ALB (ACM) — inside the cluster a mesh would add mTLS.

## A9. Supply chain: dependency and image scanning

- **Trivy** in both pipelines (`shopflow.yml` and `Jenkinsfile`): `--severity HIGH,CRITICAL --ignore-unfixed --exit-code 1` **before** push; `ignore-unfixed` avoids blocking on CVEs without a patch.
- **Dependabot** (`.github/dependabot.yml`): weekly Maven (Spring grouped), Docker, GitHub Actions, Terraform updates.
- Images: `eclipse-temurin:21-jre-alpine` (JRE only), pinned tags, non-root UID 10001; layered jar so a dependency CVE fix is a small layer.
- Would add: SBOM (`syft`/CycloneDX), image signing (`cosign`) + admission policy (Kyverno), `trivy config`/`checkov` for Terraform and K8s YAML, secret scanning (gitleaks).

---

# PART B — CACHING

## B1. Patterns

| Pattern | Read | Write | Pros / cons |
|---|---|---|---|
| **Cache-aside** (ShopFlow) | app checks cache → miss → DB → put | app writes DB and **evicts** (or updates) cache | simple, cache failure = just misses; risk of stale data between write and evict |
| Read-through | cache loads from DB itself (loader) | — | app code simpler; needs a cache that supports loaders (Caffeine `LoadingCache`) |
| Write-through | — | write to cache, cache writes DB synchronously | always consistent, slower writes |
| Write-behind | — | write to cache, flush to DB async | fastest writes; data loss if cache dies, ordering issues |
| Refresh-ahead | cache refreshes hot keys before expiry | — | hides miss latency; wasted work for cold keys |

### ShopFlow's cache-aside, line by line

```java
@EnableCaching                          // InventoryServiceApplication — turns on the @Cacheable proxy
@Cacheable(cacheNames = "products", key = "#sku")   // ProductController.get → caches ProductResponse (DTO, not entity)
@CacheEvict(cacheNames = "products", key = "#sku")  // ReservationService.reserve — stock changed for this sku
@CacheEvict(cacheNames = "products", allEntries = true) // ReservationService.release — sku known only after lookup; rare
```
```yaml
spring.cache.type: ${CACHE_TYPE:simple}   # simple = ConcurrentHashMap per pod (tests, bare local run); redis in compose/K8s
spring.cache.redis.time-to-live: 60s      # safety net if an eviction is ever missed
spring.cache.redis.key-prefix: "inventory:"
spring.data.redis.host/port: ${REDIS_HOST}/${REDIS_PORT}
management.health.redis.enabled: ${REDIS_HEALTH:false}   # true only when CACHE_TYPE=redis
```

Why each decision:
- **Evict, don't update** on write: `decrementStock` is a bulk `UPDATE ... WHERE quantity >= :qty`; the service never loads the new quantity, so it cannot "put" a correct value. Evicting forces the next reader to fetch the truth. Updating the cache from app memory would also race with concurrent reservations (two pods write different "latest" values). Rule: *writes go to the source of truth; cache is only a read accelerator.*
- **Cache the DTO** (`ProductResponse` record), not the JPA entity: entities carry lazy proxies and a session; a cached detached entity leaks into other requests and can trigger `LazyInitializationException` or be accidentally re-attached and flushed.
- **Never decide stock from the cache**: the reservation path reads/updates Postgres atomically; the cache serves only the catalog `GET`. A 60s-stale quantity on a product page is acceptable; an oversell is not.
- **TTL as a safety net**: eviction can be missed (a new write path, a Redis blip during evict) — TTL bounds the staleness to 60s.
- **`allEntries = true` on release**: `release(orderRef)` only learns the SKU after loading the reservation; a precise `@CacheEvict(key = "#result...")` is not possible for a `void` method. Releases are rare, so flushing the whole `products` cache is acceptable — mention the alternative (evict manually via `CacheManager` inside the method).
- **Test**: `productReadIsCachedAndEvictedOnReserve` — GET caches (`cacheManager.getCache("products").get("PIXEL-9")` not null), `reserve` evicts (null), next GET shows `quantity - 1`.

Honest implementation gap to know (it will come up if you demo with Redis): Spring Boot's `RedisCacheManager` defaults to **JDK serialization** for values, which requires `Serializable`. `ProductResponse` is a plain record and does not implement it, and there is no `RedisCacheConfiguration` bean with a JSON serializer. So with `CACHE_TYPE=redis` the first cache put would fail with a `SerializationException`; tests pass because they run with `simple`. The fix is one bean: `RedisCacheConfiguration.defaultCacheConfig().serializeValuesWith(SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()))` (or `implements Serializable`). Knowing this is exactly the "Spring Cache pitfall" interviewers probe.

## B2. Spring Cache abstraction pitfalls

1. **Self-invocation**: `@Cacheable` is a proxy; `this.get(sku)` inside the same class bypasses it (same rule as `@Transactional`). ShopFlow's annotation is on the controller method, which Spring MVC calls **through the proxy**, so it works. Placing it on the controller is unusual — the common place is a service method; be ready to say so.
2. **Key design**: default key = all method parameters; use `key = "#sku"` explicitly. Keys must be stable and bounded (SKU, not a whole request object). Prefix per service (`inventory:`) to share one Redis.
3. **Null / exceptions**: `null` results are cached by default (`unless = "#result == null"` to avoid); exceptions are **not** cached — `ProductNotFoundException` → 404 is not cached, so a missing SKU hits the DB every time (negative caching would need a sentinel).
4. **Serialization** (Redis): JDK serialization is brittle across versions; prefer JSON (`GenericJackson2JsonRedisSerializer`) or Protobuf; records need `Serializable` for JDK.
5. **Conditional**: `condition = "#quantity > 0"`, `sync = true` to coalesce concurrent misses for the same key in one JVM (stampede protection).
6. **Transactions**: `@CacheEvict` runs when the method returns — *before* the surrounding transaction commits. A concurrent reader can re-populate the cache with the **old** value between evict and commit. Mitigation: `@CacheEvict(beforeInvocation = false)` + a `TransactionAwareCacheManagerProxy`, or evict after commit (`@TransactionalEventListener(AFTER_COMMIT)`). ShopFlow's TTL (60s) bounds the damage.
7. **`type: simple` across pods**: each pod has its own map → a reserve on pod A does not evict pod B's copy. That is why compose/K8s set `CACHE_TYPE=redis` (shared).

## B3. Redis essentials

| Data structure | Use |
|---|---|
| String | cache values (`SET inventory:products::PIXEL-9 <bytes> EX 60`), counters (`INCR`), distributed locks (`SET k v NX PX`) |
| Hash | object fields (`HSET product:PIXEL-9 qty 10`) |
| List | queues (`LPUSH`/`BRPOP`) |
| Set / Sorted set | tags, leaderboards, **sliding-window rate limiting** (`ZADD` timestamps, `ZREMRANGEBYSCORE`) |
| Streams | Kafka-lite log with consumer groups |
| HyperLogLog / Bitmap | unique counts, flags |

- **Eviction policies** (`maxmemory-policy`): `noeviction` (errors when full), `allkeys-lru` (ShopFlow: pure cache, evict anything), `volatile-lru` (only keys with TTL), `allkeys-lfu`, `volatile-ttl`. Rule: cache → `allkeys-lru/lfu`; cache + must-keep data → `volatile-*` and set TTLs; store → `noeviction`.
- **TTL vs eviction**: TTL = time-based expiry per key (`EXPIRE`, lazy + active sampling); eviction = memory-pressure-based removal. ShopFlow uses both: 60s TTL and `maxmemory 64mb allkeys-lru`.
- **Persistence**: RDB snapshots / AOF — off or irrelevant for a cache (the K8s Redis is a Deployment with no volume: "losing it only costs cache misses").
- **Single-threaded command execution** → avoid `KEYS *`, big `SMEMBERS`, huge values; use `SCAN`, pipelining.
- Prod: ElastiCache Redis 7.1, `transit_encryption_enabled = true`, in private subnets; Cluster mode for sharding; replicas for read scale/HA.

## B4. Stampede, consistency, hot keys, local vs distributed

- **Cache stampede / thundering herd**: a hot key expires → hundreds of requests miss at once → DB spike. Fixes: `sync = true` (per-JVM coalescing), **single-flight / lock** (`SET lock NX PX`), **jittered TTL** (60s ± 10%), **stale-while-revalidate** / probabilistic early refresh, request collapsing at the gateway, or never expire + explicit evict.
- **Consistency**: cache-aside + evict gives *eventual* consistency with a small window (evict-before-commit race, replication lag). For stock, ShopFlow sidesteps the problem: reservation decisions never use the cache.
- **Hot keys**: one SKU (flash sale) hammers one Redis shard → local L1 cache (Caffeine, ~1s TTL) in front of Redis (L2), key replication (`sku#1..N` suffixes), or serve the product page from a CDN.
- **Local (Caffeine) vs distributed (Redis)**: Caffeine is in-process (ns latency, no network, no serialization, size/TTL/weight-based eviction, W-TinyLFU) but per pod and lost on restart; Redis is shared, survives pod restarts, supports TTL/eviction policies centrally, but adds ~1ms network + serialization + a dependency. Common: two-level cache. `type: simple` in ShopFlow is a Caffeine-less `ConcurrentHashMap` with **no eviction and no TTL** — fine for tests, never for prod (unbounded growth).
- **Cache metrics**: `cache_gets_total{result="hit|miss"}`, `cache_puts_total`, `cache_evictions_total` via Micrometer `CacheMetricsRegistrar` (auto for Redis/Caffeine; the simple cache exposes limited stats). Alert on hit ratio collapse.

---

# PART C — PERFORMANCE

## C1. Rate limiting

| Algorithm | Idea | Burst | Memory |
|---|---|---|---|
| **Token bucket** | bucket of `b` tokens refilled at `r`/s; a request takes one | allows bursts up to `b` | O(1) per key |
| Leaky bucket | queue drains at fixed rate | smooths bursts (queues them) | O(queue) |
| Fixed window | counter per minute | 2× burst at window edges | O(1) |
| Sliding window log | timestamps per request (Redis ZSET) | exact | O(requests) |
| Sliding window counter | weighted previous + current window | approx, cheap | O(1) |

Resilience4j's `RateLimiter` is a **fixed-window permission counter**: `limit-for-period: 50` permits per `limit-refresh-period: 1s`; `timeout-duration: 0` means a caller that finds no permit fails immediately with `RequestNotPermitted` instead of queueing → `GlobalExceptionHandler.handleRateLimited` → **429 ProblemDetail "Too many requests"**. Should also send `Retry-After` (gap).

Where to enforce:

| Layer | Pros | Cons | ShopFlow |
|---|---|---|---|
| **Gateway / Ingress** (`nginx.ingress.kubernetes.io/limit-rps`, Spring Cloud Gateway `RequestRateLimiter`, Kong, AWS WAF/API Gateway) | protects everything, per client IP/API key/user, before any app work | coarse; shared limit needs Redis | not yet (next step) |
| **Service, in-memory** (Resilience4j, Bucket4j local) | protects *this* pod's DB pool and downstream; zero infra | **per pod**: 50 rps × N pods total; no per-user fairness | **used** on `OrderController.create` |
| **Service, Redis-backed** (Bucket4j + Redis, Lua token bucket) | global limit per user/tenant across pods | adds a Redis round trip to the hot path | not yet |

Why limit `POST /orders` specifically: every order costs a DB insert, an HTTP call to inventory (with retries), another DB write and an outbox row — the most expensive endpoint; a burst would exhaust the Hikari pool and trip inventory. Reads are cheap and unlimited. The test `rateLimitExceededIs429` is a `@WebMvcTest` slice: the mocked service throws `RequestNotPermitted.createRequestNotPermitted(RateLimiter.ofDefaults("orders"))` and the advice maps it to 429 — it tests the **mapping**, not the limiter itself (honest).

**Rate limiter vs bulkhead vs circuit breaker**: rate = requests per *time*; bulkhead = *concurrent* calls to a dependency (`@Bulkhead max-concurrent-calls: 20` would keep a hung inventory from occupying every request thread — not used, less critical with virtual threads but the DB pool is still finite); breaker = stop calling a *failing* dependency.

## C2. Virtual threads (Java 21)

- A virtual thread is a cheap, JVM-scheduled thread **mounted** on a small pool of carrier (platform) threads. When it blocks on IO (JDBC socket, HTTP, `Future.get`), it **unmounts** and the carrier runs another virtual thread. Millions are possible; stack lives on the heap.
- `spring.threads.virtual.enabled: true` (all three services) makes Tomcat handle each request on a virtual thread, and also `@Scheduled` tasks (the outbox relay) and the Kafka listener container threads. Effect: a hung inventory call no longer eats one of 200 Tomcat threads — thread exhaustion stops being the first bottleneck; the **DB pool** (10 or 5) becomes the real limiter, which is why it is sized explicitly.
- **Pinning**: a virtual thread cannot unmount while inside a `synchronized` block or native frame → it pins the carrier. Java 21: affects old JDBC drivers / libraries using `synchronized` (fixed in JDK 24, JEP 491). Detect with `-Djdk.tracePinnedThreads=full` or JFR `jdk.VirtualThreadPinned`. Postgres JDBC ≥ 42.6 and Hikari are virtual-thread friendly.
- **When NOT to use**: CPU-bound work (no benefit, use a bounded platform pool), code relying on `ThreadLocal` heavy caching (each virtual thread gets its own → memory), tasks needing thread-pool **limiting** as a backpressure mechanism (virtual threads are unlimited — protect pools with semaphores/bulkheads), or libraries that pin heavily.
- Interview one-liner: *"Virtual threads give the scalability of reactive code with blocking-style code; they don't make anything faster, they let you wait cheaply."*

## C3. Connection pool sizing and JVM in containers

- Hikari rule of thumb: `pool = cores × 2 + effective_spindles` → a service rarely needs more than 10–20 per pod; more connections = more Postgres context switching. ShopFlow: default 10, K8s `DB_POOL_SIZE=5` so that 10 pods × 5 × 2 services = 100 < `max_connections=200` (compose/StatefulSet) — in prod RDS, use **RDS Proxy / PgBouncer**. Watch `hikaricp_connections_pending` and `hikaricp_connections_timeout_total`; `connection-timeout` default 30s is too long for an API — set 2–5s so a saturated pool fails fast.
- Hold connections briefly: no `@Transactional` around HTTP (`OrderService`), `open-in-view: false`, short `TransactionTemplate` blocks.
- JVM: `-XX:MaxRAMPercentage=75` of the 512Mi limit ≈ 384 MiB heap; `-XX:+ExitOnOutOfMemoryError` → crash and restart; **no CPU limit** (throttling slows GC/JIT/startup); G1 default; for small heaps consider `-XX:+UseSerialGC`/`ParallelGC`; `-Xss` matters less with virtual threads. Startup: CDS/AppCDS, Spring AOT, or CRaC for faster scale-out.

## C4. Load testing with k6 — flash-sale script

```javascript
// k6 run -e TOKEN=$TOKEN -e BASE=http://localhost:8081 flash-sale.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const confirmed = new Counter('orders_confirmed');
const rejected  = new Counter('orders_rejected');
const limited   = new Rate('rate_limited_429');
const createLatency = new Trend('create_latency', true);

export const options = {
  scenarios: {
    flash_sale: {                       // 500 shoppers hit "buy" within 30s, then taper
      executor: 'ramping-arrival-rate',
      startRate: 10, timeUnit: '1s',
      preAllocatedVUs: 100, maxVUs: 500,
      stages: [
        { target: 200, duration: '30s' },   // burst: well above 50 rps/pod -> expect 429s
        { target: 50,  duration: '1m'  },
        { target: 0,   duration: '15s' },
      ],
    },
  },
  thresholds: {
    http_req_failed:   ['rate<0.01'],            // 5xx only (429 is handled below, see responseCallback)
    create_latency:    ['p(95)<800', 'p(99)<1500'],
    rate_limited_429:  ['rate<0.5'],              // more than half limited => add pods or raise the limit
  },
};
// treat 429 as an expected, non-failed response so http_req_failed measures real errors
http.setResponseCallback(http.expectedStatuses({ min: 200, max: 299 }, 429));

export default function () {
  const idem = `${__VU}-${__ITER}-${Date.now()}`;            // unique per attempt; reuse to test idempotent replay
  const res = http.post(`${__ENV.BASE}/api/v1/orders`,
    JSON.stringify({ sku: 'PS5-SLIM', quantity: 1 }),
    { headers: { 'Content-Type': 'application/json',
                 'Authorization': `Bearer ${__ENV.TOKEN}`,
                 'Idempotency-Key': idem } });

  createLatency.add(res.timings.duration);
  limited.add(res.status === 429);
  if (res.status === 201) {
    const status = res.json('status');
    if (status === 'CONFIRMED') confirmed.add(1);
    if (status === 'REJECTED')  rejected.add(1);               // out of stock: correct, not an error
  }
  check(res, { 'no 5xx': r => r.status < 500 });
  sleep(0.1);
}
```
What to look at while it runs (Grafana): p99 of `http_server_requests_seconds` for `POST /api/v1/orders`, `orders_total{status}` (CONFIRMED must stop exactly at the seeded stock — the oversell check at scale), `inventory_reservations_rejected_total`, `hikaricp_connections_pending`, 429 ratio, `outbox_unpublished` (relay keeping up?), `kafka_consumer_fetch_manager_records_lag_max` on notification-service, JVM heap/GC. Run it against compose first (`TOKEN` from the Keycloak `curl` in the README), then against minikube with 2–3 pods to see the per-pod limit multiply.

## C5. Profiling, N+1, indexes, p99 tactics

- **JFR** (built-in, low overhead): `jcmd <pid> JFR.start duration=60s filename=/tmp/rec.jfr` → open in JDK Mission Control; events for GC, allocation, locks, `VirtualThreadPinned`, socket IO. Works in the container (needs a writable `/tmp` — ShopFlow mounts an `emptyDir` there).
- **async-profiler**: sampling CPU/alloc/lock profiler producing **flame graphs** (`asprof -d 30 -e cpu -f /tmp/flame.html <pid>`); no safepoint bias. Needs `perf_event` access in the container (`securityContext` capabilities `SYS_ADMIN`/`perf_event_paranoid`) — do it on a debug pod, not in prod by default.
- Heap: `jcmd GC.heap_dump` → Eclipse MAT; threads: `jcmd Thread.print` / `jstack`.
- **N+1**: 1 query for N parents + N lazy queries for children. Spot with `spring.jpa.show-sql` / p6spy / Hibernate statistics (`hibernate.generate_statistics`) or the trace waterfall (dozens of tiny JDBC spans). Fix: `JOIN FETCH` / `@EntityGraph`, `@BatchSize`, DTO projections; `open-in-view: false` (ShopFlow) surfaces it as an exception instead of hiding it.
- **Index basics**: B-tree for equality/range (`idx_orders_created_at (created_at desc)` backs the newest-first listing; `idx_outbox_pending (published_at, created_at)` backs the relay query; unique indexes on `order_ref`, `idempotency_key`, `sku`). `EXPLAIN (ANALYZE, BUFFERS)` → look for Seq Scan on big tables, high `rows` estimates vs actual. Composite index column order = equality columns first, then range/sort. Partial indexes for "pending" queues (Postgres only, noted in `V2__outbox.sql`). Too many indexes slow writes.
- **p99 tactics** (what moves tail latency): timeouts + retries with budget (ShopFlow 1s/2s, 3 attempts), **hedged requests** for idempotent reads, caching the hot read path (products), connection pool warm-up and right sizing, avoid GC pauses (heap headroom, fewer allocations), virtual threads so queueing does not happen at the thread layer, remove sync fan-out (notification moved to Kafka), keep-alive/HTTP2 to downstreams, pagination caps (`size ≤ 100`), DB indexes, pre-computed read models. Measure with histograms (`percentiles-histogram`) and `histogram_quantile(0.99, ...)`, never averages.

---

# PART D — DISTRIBUTED TRACING

## D1. Concepts

```
trace 4bf92f3577b34da6a3ce929d0e0e4736  (one POST /api/v1/orders)
├─ order-service   http.server  POST /api/v1/orders                      310 ms
│  ├─ jdbc         INSERT orders (PENDING)                                 3 ms
│  ├─ http.client  POST inventory-service /api/v1/reservations           240 ms   ← traceparent header
│  │   └─ inventory-service http.server POST /api/v1/reservations        230 ms
│  │       ├─ cache  products evict
│  │       └─ jdbc   UPDATE product ... WHERE quantity >= ?              220 ms   ← row-lock wait on hot SKU
│  ├─ jdbc         UPDATE orders + INSERT outbox_event                     5 ms
│  └─ (1s later, separate scheduled span, linked by the same trace via Kafka headers)
│     order-service  kafka.producer  orders.events send                    4 ms
│        └─ notification-service kafka.consumer orders.events process     12 ms
│              └─ jdbc  INSERT processed_event
```
- **Span** = one timed operation with attributes (`http.route`, `db.statement`, status), **trace** = tree of spans sharing a `traceId`; `parentSpanId` builds the tree.
- **Context propagation**: HTTP → W3C `traceparent: 00-<traceId>-<spanId>-<flags>` (+ `tracestate`); older systems use **B3** (`X-B3-TraceId`, `X-B3-SpanId`, or single `b3` header). Spring Boot 3 defaults to W3C and can also accept B3 (`management.tracing.propagation.consume`). Kafka → the same headers inside the record (`observation-enabled: true` on `KafkaTemplate` and the listener container).
- **Baggage**: key/values that travel with the context (e.g. `tenantId`) — use sparingly, it goes over the wire on every hop.

## D2. Micrometer Tracing vs OTel SDK vs OTel Java agent

| | Micrometer Tracing + OTel bridge (**ShopFlow**) | OpenTelemetry SDK/API directly | OpenTelemetry Java **agent** |
|---|---|---|---|
| How | Spring's Observation API creates spans; `micrometer-tracing-bridge-otel` maps them to OTel; `opentelemetry-exporter-otlp` ships them | code against `io.opentelemetry.api`, Spring Boot OTel starter | `-javaagent:opentelemetry-javaagent.jar`, bytecode instrumentation, zero code |
| Instruments | Spring MVC, `RestClient` (built from the auto-configured builder — `InventoryClient` does), JDBC (via datasource proxy libs), Spring Kafka, `@Scheduled`, `@Observed` | what you write + instrumentation libs | 100+ libraries automatically |
| Metrics + traces consistency | same Observation produces both the `http.server.requests` timer and the span → identical tags, **exemplars** possible | separate | agent can export metrics too |
| Control | fine (custom `Observation`s, filters) | finest | config via env (`OTEL_*`) |
| Cost | dependency only | more code | startup time, memory, version coupling |
| Fits | Spring Boot 3 apps (recommended) | non-Spring / library authors | legacy apps you cannot change, polyglot fleets |

ShopFlow config: `management.tracing.sampling.probability: ${TRACING_SAMPLE:1.0}` (100% locally; 0.1 typical in prod), `management.otlp.tracing.endpoint: ${OTLP_ENDPOINT:http://localhost:4318/v1/traces}` (OTLP/HTTP; compose: `http://tempo:4318/v1/traces`; K8s: `http://tempo.monitoring:4318/v1/traces` from the `shopflow-endpoints` ConfigMap; prod: an **ADOT / OTel Collector** at `otel-collector.monitoring:4318` forwarding to X-Ray or Tempo). Tests set `management.otlp.tracing.export.enabled: false` so no exporter thread tries to reach `localhost:4318`. NetworkPolicy egress rules allow port 4318 to the `monitoring` namespace for all three services.

## D3. Sampling

- **Head-based** (ShopFlow, `probability`): decided at the root span, propagated in the `traceparent` flags so all services agree. Cheap, predictable volume; may drop the one slow/error trace you wanted.
- **Tail-based** (in the Collector): buffer complete traces, keep all errors + slow ones + 1% of the rest. Needs a Collector with memory and consistent routing (load-balancing exporter by traceId).
- **Rate-limiting** samplers (N traces/s) for bursty traffic. Always sample 100% in dev/staging.
- Why not 100% in prod: storage and network cost, and exporter CPU — 1.0 at 1000 rps is 1000 traces/s × ~10 spans.

## D4. Correlation: traces ↔ logs ↔ metrics (as configured)

```
Alloy (docker discovery) ──tails container stdout──▶ Loki              Grafana datasources.yml
   label service=<compose service name>                                 ┌────────────────────────────┐
Services (ECS JSON logs: LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs,         │ Tempo  tracesToLogsV2 →Loki │  span → "logs for this trace"
   fields trace.id / span.id)                                          │        filterByTraceID     │
Services ──OTLP/HTTP 4318──▶ Tempo (local blocks + WAL in /var/tempo)   │ Loki   derivedFields:      │  log line → click trace.id → Tempo
Prometheus ◀──scrape /actuator/prometheus── Services                    │   regex "trace\.id":"(\w+)"│
                                                                        │   datasourceUid: tempo     │
                                                                        └────────────────────────────┘
```
- **Logs → trace**: Boot's structured logging (ECS) includes `trace.id`/`span.id` automatically when a tracer is present; the Loki datasource's `derivedFields` regex turns that field into a link to Tempo. Compose now sets `LOGGING_STRUCTURED_FORMAT_CONSOLE: ecs` for all services (`x-service-env`), so local logs are JSON too.
- **Trace → logs**: Tempo's `tracesToLogsV2` with `filterByTraceID: true` runs a Loki query `{...} |= "<traceId>"` for the span's time range.
- **Metrics → trace**: exemplars — histogram buckets carry a sample traceId; Prometheus needs `--enable-feature=exemplar-storage` and Micrometer `management.prometheus.metrics.export` exemplars (not configured yet — honest gap). Also Tempo's **metrics generator** can derive RED metrics from spans (service graphs).
- The debugging loop to describe: alert `HighP99Latency` → Grafana panel → exemplar/trace → Tempo waterfall shows `UPDATE product` span is 220ms → click to Loki → `"reserved 1 x PS5-SLIM for order ..."` lines → row-lock contention on a hot SKU.

## D5. Backends

| | Grafana Tempo (ShopFlow) | Jaeger | AWS X-Ray |
|---|---|---|---|
| Storage | object storage (S3/GCS) or local; index-free, search via TraceQL | Cassandra/Elasticsearch/Badger; full indexing | managed |
| Cost | very cheap at scale | heavier | per trace recorded/retrieved |
| Query | TraceQL (`{ span.http.route = "/api/v1/orders" && duration > 500ms }`) | tag search | service map, filter expressions |
| Integration | Grafana native, links to Loki/Prometheus | own UI (or Grafana) | CloudWatch; via **ADOT Collector** from OTLP (X-Ray needs its own trace-id format, the collector converts) |
| ShopFlow | compose `tempo:2.8.1`, config `monitoring/tempo.yml` (OTLP http 4318 + grpc 4317, local backend) | alternative | prod option behind `otel-collector.monitoring` |

---

## E. Interview Q&A (28)

**Q1. How is order-service secured?**
It is an OAuth2 resource server: stateless, CSRF off, every `/api/v1/orders` request needs a bearer JWT issued by the configured issuer (Keycloak locally, Cognito in prod). Spring fetches the issuer's JWKS, verifies the RS256 signature, `exp` and `iss`, and my `JwtRolesConverter` maps `realm_access.roles` or `cognito:groups` to `ROLE_*`. Reads need `customer` or `support`, writes need `customer`; actuator/swagger are open but network-restricted; anything else is denied.

**Q2. Authorization code + PKCE vs client credentials?**
Code+PKCE when a human logs in (browser/mobile; PKCE protects public clients without a secret). Client credentials when a service calls a service with no user; the token's scopes describe the service's rights.

**Q3. Where does Spring get the public key?**
From `issuer-uri` → `/.well-known/openid-configuration` → `jwks_uri`; keys are cached and re-fetched on an unknown `kid`, which is how key rotation works without restarts.

**Q4. Why RS256 and not HS256?**
Asymmetric: only the IdP holds the private key; services hold public keys, so a compromised service cannot forge tokens and keys can rotate centrally. HS256 would spread a shared secret across all services.

**Q5. Why disable CSRF?**
CSRF exploits credentials the browser sends automatically (cookies). We use a bearer token in a header with no session cookie, so there is nothing to ride on. If tokens moved to cookies, I would re-enable it.

**Q6. 401 vs 403?**
401: not authenticated (missing/invalid/expired token) — the test posts without a token. 403: authenticated but not allowed — `support` posting an order.

**Q7. Role vs scope?**
Role = what the user is (from the IdP); scope = what the client application was allowed to request on the user's behalf. Fine-grained ownership is a permission check in code (`@PreAuthorize`), which ShopFlow does not do yet — any customer can read any order. I would store `sub` on the order and check it.

**Q8. How do you test security without Keycloak?**
`spring-security-test`: `mockMvc.perform(post(...).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_customer"))))` injects an authenticated JWT; in the `@WebMvcTest` slice I `@Import` `SecurityConfig` + `JwtRolesConverter` and `@MockitoBean` the `JwtDecoder` so the context starts without an issuer.

**Q9. Service-to-service auth today?**
NetworkPolicy: only pods labelled `order-service` reach inventory:8082, and `/api/v1/reservations` is not routed by the Ingress. It is L3/L4 only — no caller identity or encryption — so the next step is client-credentials tokens or a mesh with mTLS.

**Q10. How do you keep secrets out of git?**
Kubernetes Secrets generated in dev, `ExternalSecret` from Secrets Manager (ESO + IRSA) in prod; GHCR via `GITHUB_TOKEN`; AWS via OIDC; only `health,info,prometheus` actuator endpoints; Trivy + Dependabot for supply-chain.

**Q11. Which OWASP API risk is your biggest gap?**
API1 broken object-level authorization — ownership is not enforced. Second: `aud` is not validated, so a token minted for another API of the same issuer would be accepted.

**Q12. Cache-aside vs write-through?**
Cache-aside: the app reads through the cache and on writes updates the DB then evicts — simple, tolerant to cache outages, briefly stale. Write-through: writes go to the cache which writes the DB synchronously — consistent but slower and the cache becomes critical. ShopFlow is cache-aside with evict.

**Q13. Why evict instead of update the cached product?**
The stock change is a conditional bulk UPDATE; the app never knows the resulting quantity, and two pods updating the cache would race. Evicting and letting the next read fetch the truth is simpler and correct; the 60s TTL bounds any missed eviction.

**Q14. Why cache the DTO and not the entity?**
Entities are tied to a persistence context (lazy proxies, dirty checking); a cached entity would be shared across requests and could throw `LazyInitializationException` or be re-attached accidentally. A record is immutable and serialisable.

**Q15. What happens to the cache when inventory has 3 pods?**
With `type: simple` each pod has its own map → stale reads on the other pods. That is why compose/K8s set `CACHE_TYPE=redis`: one shared cache, evictions visible everywhere. (And then the serializer must be configured — see the honest gap in B1.)

**Q16. Cache stampede — what and how to prevent?**
Many concurrent misses on the same expired hot key hammer the DB. Prevent with `sync = true`, a lock/single-flight, jittered TTLs, stale-while-revalidate, or explicit invalidation instead of expiry.

**Q17. Redis eviction policy for a cache?**
`allkeys-lru` (or `allkeys-lfu`) with `maxmemory` — ShopFlow runs `--maxmemory 64mb --maxmemory-policy allkeys-lru`. `noeviction` is for Redis-as-store; `volatile-*` when mixing cache and must-keep keys.

**Q18. Caffeine vs Redis?**
Caffeine is in-process (fastest, no serialization, W-TinyLFU) but per pod; Redis is shared and survives restarts but costs a network hop and a dependency. Often both: tiny L1 Caffeine in front of L2 Redis for hot keys.

**Q19. Which rate-limiting algorithm does Resilience4j use and what are its limits?**
A fixed-window permit counter: 50 permits per 1s refresh, `timeout-duration: 0` → immediate `RequestNotPermitted` → 429. Limits: per pod (N pods = N×50), no per-user fairness, window-edge bursts. For global per-user limits I would use the gateway or Bucket4j with Redis.

**Q20. Why rate-limit only order creation?**
It is the expensive, write-heavy path (DB + inventory HTTP with retries + outbox). Protecting it protects the DB pool and the downstream; reads are cheap.

**Q21. Virtual threads — benefit and pitfalls?**
Blocking IO no longer occupies a scarce platform thread, so thread exhaustion disappears and throughput under slow downstreams improves; code stays simple. Pitfalls: pinning in `synchronized` (JDK 21), no implicit concurrency limit (protect pools with bulkheads), no gain for CPU-bound work, `ThreadLocal` memory.

**Q22. How do you size the Hikari pool?**
Small: ~10 per pod; total across pods under the DB's `max_connections` with headroom. ShopFlow: `DB_POOL_SIZE=5` × 10 pods × 2 services = 100 < 200. At scale use PgBouncer/RDS Proxy. Fail fast with a short `connection-timeout`.

**Q23. How would you load-test the flash sale?**
k6 with a ramping-arrival-rate scenario (script above), thresholds on p95/p99 and 5xx, 429 treated as expected; watch `orders_total{status}` to prove no oversell, pool pending, outbox backlog, consumer lag and GC.

**Q24. JFR vs async-profiler?**
JFR: built in, always safe to run, great for GC/locks/IO/pinning. async-profiler: sampling CPU/allocation flame graphs without safepoint bias, needs perf access. Use JFR first in prod, async-profiler for CPU hot spots.

**Q25. Explain a trace through your system.**
HTTP span in order-service → child JDBC spans → `RestClient` span carrying `traceparent` → inventory server span and its `UPDATE` span → back → outbox insert; one second later the relay's producer span and notification's consumer span share the same traceId via Kafka headers. Tempo renders the waterfall; a click opens the Loki logs for that trace.

**Q26. Micrometer Tracing vs the OTel agent?**
Micrometer is Spring-native: the same Observation yields the metric and the span, so tags match and exemplars are possible; fine control via code. The agent needs no code and instruments everything, but adds startup cost and version coupling. Spring Boot 3 projects usually pick Micrometer + OTel bridge (ShopFlow).

**Q27. What is sampling and what did you set?**
Recording a fraction of traces. 1.0 locally (`TRACING_SAMPLE`), 0.1 typical in prod; head-based in the app, or tail-based in a Collector to keep all errors and slow traces.

**Q28. How do traces and logs connect?**
Boot puts `trace.id`/`span.id` into the ECS JSON log fields; Loki's derived field links them to Tempo, and Tempo's traces-to-logs runs the reverse query. Alloy ships container stdout to Loki with the compose service name as a label.

---

## F. Scenarios

**S1. After deploying to minikube every `POST /orders` returns 401, but curl with a Keycloak token worked in compose.**
`iss` mismatch: compose tokens say `http://localhost:8180/realms/shopflow`, K8s expects `http://keycloak.shopflow.local/realms/shopflow` (`shopflow-endpoints` ConfigMap) — get the token from the K8s Keycloak, not the compose one. Other causes: JWKS unreachable (order-service egress NetworkPolicy must allow keycloak:8180 — it does; `KC_HOSTNAME` wrong), clock skew > 60s (`exp`), token expired (900s), or `JWT_ISSUER_URI` env not wired. Debug: decode the token at jwt.io (never paste prod tokens), compare `iss`, check order-service logs for `JwtValidationException`.

**S2. Security review: "support staff can cancel orders".**
Check the rule order: `GET /api/v1/orders/**` → `customer|support` comes **before** `/api/v1/orders/**` → `customer`; `DELETE` is not GET, so it falls to the second rule and `support` gets 403 (tested). If someone reordered the matchers so the broad rule came first, both roles would pass — matcher order matters; keep the test.

**S3. Product page shows stock 3 but the reservation says "insufficient stock".**
Expected and acceptable: the page reads the cache (≤ 60s stale, or a missed eviction), the reservation uses the atomic DB update. Explain the design: never decide from cache. If staleness is too long, check evictions happen (metrics `cache_evictions_total`), that all pods share Redis (`CACHE_TYPE`), and shorten the TTL.

**S4. Switching `CACHE_TYPE=redis` in compose made `GET /products/{sku}` return 500.**
`SerializationException: DefaultSerializer requires a Serializable payload` — `ProductResponse` is a record without `Serializable` and the cache manager uses JDK serialization by default. Fix with a `RedisCacheConfiguration` bean using `GenericJackson2JsonRedisSerializer` (or implement `Serializable`); add a test profile that runs the cache test against Testcontainers Redis so the gap cannot return.

**S5. Flash sale: hundreds of 429s, customers complain, but the service is healthy.**
Working as designed per pod, but the limit (50 rps/pod) may be too low or unevenly spread (keep-alive connections pin clients to pods). Options: raise `limit-for-period`, scale pods (limit multiplies), move the limit to the Ingress/gateway for a global, per-user rule, add `Retry-After`, and put a waiting-room/queue in front for the sale. Verify the DB pool and inventory were not the real bottleneck (`hikaricp_connections_pending`).

**S6. p99 of `POST /orders` jumped to 2s after enabling virtual threads.**
Virtual threads removed the Tomcat thread ceiling, so more requests now reach the DB pool concurrently and wait there (`connection-timeout`), or pin on a `synchronized` block in an old driver. Check `hikaricp_connections_pending`, JFR `jdk.VirtualThreadPinned`, and downstream saturation; add a bulkhead/semaphore around the inventory call, right-size the pool, update the driver.

**S7. The Tempo waterfall shows order-service's HTTP span but the inventory span is missing (two separate traces).**
Propagation broken: the HTTP client was not built from the auto-configured `RestClient.Builder` (a plain `RestClient.create()` has no observation), or inventory lacks the tracing dependencies (they are in the parent pom, so check the image was rebuilt), or an Ingress/proxy strips `traceparent`. For Kafka hops, confirm `observation-enabled: true` on both template and listener. Verify with `curl -v` through the chain and look for `traceparent` in request logs.

**S8. Logs in Loki have no `trace.id`.**
Structured logging not active (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` missing in that environment), or no tracer on the classpath (then MDC has no trace id), or the Loki `derivedFields` regex does not match the field layout (dotted `"trace.id"` vs nested `trace: {id}` differs by Boot version). Check a raw log line first, then the datasource regex.

---

## G. Common mistakes

- HS256 shared secrets across services; accepting `alg: none`; skipping `iss`/`aud` checks; multi-hour access tokens.
- Validating the JWT only at the gateway and trusting internal traffic blindly (zero trust says every service validates).
- Storing JWTs in `localStorage` for SPAs (XSS) without discussion of the trade-off vs httpOnly cookies + CSRF.
- Exposing `/actuator/env`, `/heapdump`; leaking stack traces in error bodies.
- Caching JPA entities; caching with unbounded `ConcurrentHashMap` in prod; no TTL; updating the cache from stale app memory instead of evicting; deciding stock from the cache.
- Forgetting that `@Cacheable`/`@RateLimiter`/`@Transactional` are proxies (self-invocation does nothing).
- Rate limiting per pod and calling it a "global limit"; no `Retry-After`; 429 counted as an error in SLOs.
- Thread pools of 500 to "fix" latency instead of finding the bottleneck; pool sizes larger than `max_connections`.
- `MaxRAMPercentage=95`; CPU limits that throttle GC; no startup probe.
- 100% trace sampling in prod; `traceparent` lost by a hand-built HTTP client; metrics with `orderId` labels (cardinality).
- Load tests without thresholds or without watching the business invariant (no oversell).

> Tip: Security round me "maine JWT lagaya" se zyada impressive hai "JWT lagaya, lekin ownership check aur `aud` validation abhi gap hai, aur yeh fix karunga". Honest gaps + plan = senior signal.
