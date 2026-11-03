# D09 · Every request is 401 right after the identity-provider migration

> Skill: auth / JWT debugging. Related mechanism: [`../comprehension/10-oauth2-jwt-idor/`](../comprehension/10-oauth2-jwt-idor/).

---

## Page (symptom)

The team migrated the identity provider (Keycloak → a new Keycloak realm / Cognito) over the weekend. Monday morning: **every** authenticated call to order-service returns **401 Unauthorized**. Login itself works — users get a token from the new IdP just fine — but order-service rejects it. Reads, writes, everyone, all 401. The `/actuator/health` endpoints are fine (they're `permitAll`).

**Before details: a blanket 401 after an IdP change has a short list of causes. What are the JWT validation checks, and which failures produce 401 vs 403?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- A fresh token from the new IdP, decoded at jwt.io, looks valid: not expired, well-formed, signed.
- order-service logs on every request: `An error occurred while attempting to decode the Jwt: The iss claim is not valid` (or on some deploys: `Couldn't retrieve remote JWK set … connection refused`).
- The token's `iss` claim is the **new** issuer URL; order-service's `JWT_ISSUER_URI` env still points at the **old** Keycloak URL.
- 401, not 403 — the request never reaches an authorization (role) check.
- `JWT_AUDIENCE` is set; the token carries the client id in `aud` (Keycloak) or `client_id` (Cognito).
</details>

---

## Your move

1. List the checks order-service runs on a JWT (it's an OAuth2 resource server). For each, does a failure give **401** or **403**?
2. The log says `iss claim is not valid`. Translate that to the exact config/deploy mistake, given the token's `iss` vs the service's `JWT_ISSUER_URI`.
3. Why is this a **401** and not a **403**, and why does that distinction tell you *where* to look?
4. On another pod the log is `Couldn't retrieve remote JWK set`. Same symptom (401), different cause — what is it, and does ShopFlow's lazy `SupplierJwtDecoder` help or hurt here?
5. After you fix the issuer, a *subset* of users can authenticate (200) but get **403** on writes. That's a different bug — what changed between IdPs to cause it?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**The checks and their status codes (Q1).** order-service validates a JWT by: fetching the issuer's **JWKS** (public keys), picking the key by `kid`, verifying the **RS256 signature**, then checking **`exp`**, **`iss`**, and (when configured) the **audience** (`aud`/`azp`/`client_id`). **Any** of these failing → the token is invalid → **401** (authentication failed). Only *after* a token is accepted do role checks run; failing those → **403** (authenticated but not allowed). So 401 = "I don't believe this token"; 403 = "I believe it, but you can't do this."

**The config mistake (Q2, root cause).** The token's `iss` is the **new** issuer; order-service is configured (`JWT_ISSUER_URI`) with the **old** issuer, so the `iss` check fails for every token — and, because the issuer URI also drives JWKS discovery, the service is fetching keys from the dead old issuer too. The migration updated the IdP and the login flow but **not order-service's `JWT_ISSUER_URI`** (and, in prod, the matching `JWT_AUDIENCE`). One env var, every request down.

**Why 401 localizes it (Q3).** A blanket **401** means the failure is in **authentication** — the token itself is being rejected — so you look at the **resource server's token config** (issuer, JWKS, audience, clock), *not* at roles, scopes, or the business logic. If it had been **403**, you'd look at role mapping instead. The status code is a signpost: 401 → "who issued this and do I trust it?", 403 → "what is this user allowed to do?". Reading the code first saves you from debugging the wrong layer.

**The JWKS variant (Q4).** `Couldn't retrieve remote JWK set` means order-service can't reach the issuer's `/.well-known/jwks` endpoint (wrong URL after the migration, a NetworkPolicy, or the new IdP not yet reachable from the cluster). Without keys it can't verify any signature → 401. ShopFlow's **lazy `SupplierJwtDecoder`** means the service still **started** (it only fetches JWKS on the first token, not at boot) — which **helps** availability (an IdP blip at deploy time doesn't crash the pod) but **doesn't fix** a genuinely wrong/unreachable issuer URL; it just defers the failure from startup to the first request. Here it slightly *hurt* diagnosis: the pod looked healthy (Ready) while being unable to authenticate anyone.

**Fix:** set `JWT_ISSUER_URI` (and `JWT_AUDIENCE`) to the **new** issuer, redeploy. Verify: the issuer's JWKS is reachable from the pod, a fresh token now returns 200. (Prevention: issuer/audience should be config per environment, and a smoke test that calls a protected endpoint with a real token belongs in the migration runbook.)

**The follow-on 403 (Q5).** Different layer entirely. Authentication now works (200), but **writes need the `customer` role**, and the role claim moved: Keycloak puts roles in `realm_access.roles`, Cognito in `cognito:groups`. If the `JwtRolesConverter` still reads only the Keycloak claim, users authenticate but map to **no roles** → `denyAll`/`hasRole("customer")` fails → **403**. Fix the converter to read the new IdP's group/role claim. (This is why 401 and 403 are *different* bugs: the first is "trust the token", the second is "map its authorities".)
</details>

---

## The pattern

- **Blanket 401 = authentication layer; blanket 403 = authorization layer.** The status code tells you which half of security to open first — don't debug roles when the token is being rejected.
- **A resource server trusts exactly one issuer.** After any IdP move, `JWT_ISSUER_URI` (+ audience) is the first thing to check; the `iss`/JWKS error names it for you.
- **Lazy JWKS keeps you *up* but can hide *wrong*.** A pod that starts and reports Ready can still authenticate nobody if the issuer URL is wrong — "Ready" isn't "working". Smoke-test a protected call.
- **Auth has layers that fail independently.** Fixing the issuer (401) can reveal a role-mapping bug (403); they're different claims (`iss` vs `realm_access.roles`/`cognito:groups`) and different fixes.
- **Migrations need a protected-endpoint smoke test**, not just "login returns a token."

> Grounded in: `security/JwtDecoderConfig` (issuer-driven JWKS, `aud`/`azp`/`client_id` audience, `SupplierJwtDecoder` lazy), `security/SecurityConfig` (401 vs 403 matchers), `JwtRolesConverter` (realm roles → `ROLE_*`), `application.yml` `JWT_ISSUER_URI`/`JWT_AUDIENCE`.
</content>
