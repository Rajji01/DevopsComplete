# 22 · Cognito in prod: auth-code + PKCE, short tokens, groups→roles — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `infra/terraform/aws/cognito.tf`, `order-service/security/JwtDecoderConfig.java` (aud/azp/client_id), `JwtRolesConverter.java` (`cognito:groups`). Pairs with [comp 10](../10-oauth2-jwt-idor/).

---

## Scenario (read cold)

> *Excerpt from ShopFlow's prod-auth design. Read once, then answer.*

In dev, **Keycloak** issues the JWTs order-service validates. In prod on AWS, **Amazon Cognito** does the same job — a managed identity provider so the team doesn't run Keycloak. The crucial thing: order-service is an **OAuth2 resource server** that only cares about **validating a JWT against its issuer's JWKS**, so swapping the IdP is mostly a **config change** (issuer URI + audience), not a code change — the same image runs with either.

Cognito is set up as a **user pool** (login with email, email auto-verified, **MFA optional**, a password policy of ≥12 chars with upper/lower/number). The **web client** is a **public client** (`generate_secret = false`) — a browser SPA can't keep a secret — so it uses the **authorization-code flow with PKCE** (`allowed_oauth_flows = ["code"]`), not the implicit flow and not a client secret. PKCE (Proof Key for Code Exchange) protects the code exchange for clients that can't hold a secret.

Token lifetimes are deliberate: **access token 15 minutes, id token 15 minutes, refresh token 30 days**. Short access tokens limit the damage if one leaks (it expires fast and can't be revoked mid-life); the long-lived refresh token lets the user stay logged in without re-entering credentials — the app silently gets a new access token every ~15 min.

**Roles**: Cognito **groups** map to the roles the app checks. order-service's `JwtRolesConverter` reads the **`cognito:groups`** claim (and falls back to Keycloak's `realm_access.roles`), turning each into a `ROLE_*` authority — so `hasRole("customer")` works the same regardless of IdP. The `JwtDecoderConfig` audience check accepts the client id from `aud`/`azp`/**`client_id`** because Cognito puts it in a different claim than Keycloak.

So the resource server is **IdP-agnostic by design**: validate the signature against the issuer's JWKS, check exp/iss/aud, map a group/role claim. Dev Keycloak and prod Cognito differ only in config and claim shapes, both of which the code already handles.

---

## Questions

### A — Literal
1. What issues JWTs in dev vs prod, and why is switching mostly a config change for order-service?
2. Why is the Cognito web client a **public client with PKCE** rather than one with a client secret?
3. What are the three token lifetimes, and what's the point of a short access token + long refresh token?

### B — Inference
4. order-service's `JwtRolesConverter` reads `cognito:groups` **or** `realm_access.roles`. Why does supporting both let the *same image* run in dev and prod?
5. The audience check accepts `aud` **or** `azp` **or** `client_id`. Why is that "or" necessary across Keycloak and Cognito (tie back to comp 10)?

### C — Trade-off
6. Managed Cognito vs self-hosted Keycloak for prod. What does Cognito buy, and what do you give up?
7. Access token 15 min, refresh 30 days. What's the trade-off of making the access token shorter (say 5 min) or longer (say 1 hour)?

### D — Transfer / extension
8. A stolen **access token** vs a stolen **refresh token** — which is worse, and what does each design choice (15-min access, revocable refresh) do about it?
9. To move prod from Keycloak-style validation to Cognito, list exactly what order-service needs changed — and confirm whether any **Java code** changes.

### E — Spot-the-flaw
10. To "make login simpler", a dev switches the Cognito client to `generate_secret = true` and the **implicit flow** (tokens returned straight in the browser URL). What two security properties did they lose, and why does a public SPA specifically need auth-code + PKCE?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **Dev: Keycloak; prod: Amazon Cognito.** order-service is an OAuth2 **resource server** — it only validates a JWT against the issuer's **JWKS** (+ exp/iss/aud) and maps a role claim. So switching IdP is a **config change** (issuer URI, audience) plus a claim-shape the code already handles; the same image runs with either.
2. Because a **browser SPA can't keep a secret** (anyone can read the JS). A public client (`generate_secret=false`) uses **authorization-code + PKCE**, where a per-request code-verifier proves the token request came from the same client that started the login — security without a stored secret.
3. **Access 15 min, id 15 min, refresh 30 days.** Short access tokens limit blast radius if one leaks (expires fast, can't be revoked mid-life); the long refresh token keeps the user logged in, silently minting fresh access tokens.
</details>

<details>
<summary>B — Inference</summary>

4. Because the **difference between the two IdPs is just the claim name** for roles (`cognito:groups` vs `realm_access.roles`). A converter that reads either produces the same `ROLE_*` authorities, so `hasRole("customer")` behaves identically — the app logic, security config, and image are unchanged; only the token's claim shape differs, and the converter absorbs that. One image, two environments.
5. Different IdPs put the **client id (the audience) in different claims**: Keycloak in `aud`/`azp`, Cognito access tokens in `client_id`. Accepting any of the three lets the same audience check validate tokens from both without branching; checking only `aud` would reject valid Cognito tokens (comp 10's confused-deputy defense, made portable).
</details>

<details>
<summary>C — Trade-off</summary>

6. **Cognito buys**: no IdP to run/patch/scale/back up (managed), AWS-native integration (IAM, groups), built-in MFA/password policy/hosted UI. **You give up**: control and portability — you're tied to AWS, customization is bounded by what Cognito exposes, and self-hosting Keycloak would give more flexibility (custom flows, themes, on-prem). ShopFlow keeps **both** (Keycloak dev, Cognito prod) precisely because the resource server is IdP-agnostic — you get managed prod without locking the app to it.
7. **Shorter access (5 min)**: smaller leak window, but more refresh round-trips (more load on Cognito, more chances for a refresh hiccup to interrupt UX). **Longer access (1 hour)**: fewer refreshes, smoother UX, but a leaked token is usable for an hour with no revocation. 15 min is a common balance — short enough that a leaked access token expires quickly, long enough to avoid constant refreshes.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. A stolen **refresh token is worse**: it's long-lived (30 days) and can mint **new access tokens repeatedly** — effectively the user's session. A stolen **access token** is bad but **self-limiting**: it expires in ~15 min and can't be refreshed on its own. The design reflects this: access tokens are **short** (so a leak is brief) and bearer-only, while the refresh token is longer-lived but **revocable server-side** (Cognito can invalidate it / rotate it), stored more carefully (httpOnly cookie / secure storage), and used only against the token endpoint. Protect the refresh token hardest.
9. Change `JWT_ISSUER_URI` to the Cognito user pool's issuer (so JWKS discovery + `iss` check target Cognito) and set `JWT_AUDIENCE` to the Cognito app client id. **No Java code changes**: `JwtDecoderConfig` already validates against whatever issuer is configured and checks `client_id` for the audience, and `JwtRolesConverter` already reads `cognito:groups`. The resource server was built IdP-agnostic, so prod auth is a config/overlay change (`application-aws.yml` / env), not a code change.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. They lost two things. **(1) PKCE / auth-code safety:** the **implicit flow returns tokens directly in the browser URL** (fragment), where they leak into history, logs, referrers, and extensions — and there's no code-verifier binding the token to the client. Auth-code + PKCE instead returns a one-time **code** that's exchanged server-to-token-endpoint with a verifier, so tokens never ride in the URL. **(2) A meaningless/dangerous secret:** `generate_secret=true` on a **public** SPA client means the "secret" ships in the browser JS where anyone can read it — it's not secret at all, giving a false sense of security. A public SPA **cannot** hold a secret, so the correct design is exactly what ShopFlow has: a **public client, no secret, authorization-code flow with PKCE**. The "simplification" reintroduced the implicit flow (deprecated for exactly these reasons) and a fake secret.
</details>

---

## Tod ke dekho — Local (render/validate; live needs AWS)

```bash
# Cognito is Terraform (prod). Validate + read the client settings without an AWS account:
cd infra/terraform/aws && terraform validate
grep -nE "generate_secret|allowed_oauth_flows|token_validity|cognito:groups|password_policy" cognito.tf
# the resource server handles BOTH IdPs in code — see the audience + roles handling:
grep -n "client_id\|azp\|aud" ../../../shopflow/order-service/src/main/java/com/shopflow/order/security/JwtDecoderConfig.java
grep -n "cognito:groups\|realm_access" ../../../shopflow/order-service/src/main/java/com/shopflow/order/security/JwtRolesConverter.java
# dev: test the real flow against Keycloak (same validation path):  make up && make token
```

**Proved:** the Cognito client is public (`generate_secret=false`) + auth-code; tokens are 15m/15m/30d; and the Java validator already reads `client_id`/`cognito:groups` — so prod auth is config, not code. A live Cognito login needs an AWS account (`terraform apply`).

## Tod ke dekho — Online
AWS chahiye → [`ONLINE-LABS.md`](../ONLINE-LABS.md). Dev-equivalent flow: Keycloak (`make up`, `make token`) — same resource-server validation.

---

## Interview concepts is passage se
- **Cognito (managed IdP) vs Keycloak (self-hosted)** — prod vs dev; resource server IdP-agnostic.
- **Authorization-code + PKCE for public clients** — no secret in the browser; not implicit.
- **Token lifetimes** — short access (15m, limits leak) + long revocable refresh (30d, UX).
- **Access vs refresh token theft** — refresh worse (long, mints tokens); protect it hardest.
- **Groups → roles** — `cognito:groups`/`realm_access.roles` → `ROLE_*` (same `hasRole`).
- **Audience across IdPs** — `aud`/`azp`/`client_id` (comp 10).
- **Config not code** — `JWT_ISSUER_URI`/`JWT_AUDIENCE` swap, no Java change.

**Interviewer's lens:** "prod mein auth kaise?" → managed Cognito, resource server config-swap; "SPA mein client secret?" → nahi, public client + PKCE + auth-code (implicit nahi); aur access-vs-refresh token theft ka farak.

> Interview line: *"Dev Keycloak, prod Cognito — order-service resource server hai, sirf issuer JWKS se JWT validate karta, to IdP swap config change hai (issuer+audience), code nahi. Cognito web client public hai (generate_secret false — SPA secret nahi rakh sakti), authorization-code + PKCE use karta (implicit nahi). Access token 15 min (leak ka window chhota), refresh 30 days revocable (UX). Groups → roles: JwtRolesConverter `cognito:groups` ya `realm_access.roles` padh ke ROLE_* banata, aur audience aud/azp/client_id dono IdP shapes handle karta — isliye ek hi image dono mein chalti."*
</content>
