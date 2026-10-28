# 10 · JWTs, audience, and the 404-not-403 trick — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `order-service/…/security/SecurityConfig.java`, `JwtDecoderConfig.java` (audience), `order/Caller.java`, `OrderService.get/list` (IDOR).

---

## Scenario (read cold)

> *Excerpt from ShopFlow's API-security design. Read once, then answer.*

order-service is an **OAuth2 resource server**: it never sees passwords and keeps **no session**. It only validates **JWTs** issued by the identity provider (Keycloak locally, Cognito in AWS). Because there's no session or cookie, CSRF protection is disabled (there's no cookie to forge) and the session policy is `STATELESS`.

**Validating a token** means: fetch the issuer's public keys (**JWKS**) — lazily, via a `SupplierJwtDecoder`, so order-service still *starts* when the IdP is briefly down — pick the key by `kid`, verify the **RS256** signature, and check `exp` and `iss`. RS256 (asymmetric) is used so services hold only **public** keys; there's no shared secret to leak across services as there would be with HS256.

But signature + expiry + issuer is **not enough**. A token the same issuer minted for a *different* API would still pass all three checks. So when an audience is configured, order-service also pins it: the token is accepted only if **any of** `aud`, `azp`, or `client_id` equals the configured audience (Keycloak and Cognito put the client id in different claims). An empty audience config means "not checked" (local dev only).

**Authorization** is role-based, from the token's roles claim mapped to `ROLE_*`: `GET /api/v1/orders/**` needs `customer` **or** `support`; the write endpoints need `customer`; everything else is `denyAll`. Actuator and Swagger are `permitAll` (kept off the public internet by NetworkPolicy/ingress, not by tokens).

Finally, **object-level** authorization (the classic **IDOR** — Insecure Direct Object Reference — bug): a customer must not read another customer's order just by changing the id in the URL. Each order stores the JWT **subject** (`customerId`). `OrderService.get` does `findById(id).filter(caller::mayAccess)` where `mayAccess` is "owner **or** support"; a non-owner falls through to **`OrderNotFoundException` → 404**. The list endpoint is scoped with `findByCustomerId` unless the caller is support. The deliberate choice: a foreign order returns **404, not 403**. A `403` would *confirm the id exists*, letting an attacker enumerate sequential ids; the **same 404** as a truly missing order reveals nothing.

---

## Questions

### A — Literal
1. Why is CSRF protection disabled here, and why is that safe?
2. Beyond signature, expiry and issuer, what extra claim does order-service check when configured, and which three claim names can carry it?
3. What role(s) can read an order via `GET`, and what role is required to write one?

### B — Inference
4. A valid, unexpired token from the correct issuer is rejected anyway. Give the most likely reason from the passage, and what real attack it stops.
5. Why does the service use a **lazy** `SupplierJwtDecoder` for JWKS instead of fetching keys at startup — what failure does that tolerate?

### C — Trade-off
6. RS256 (asymmetric) vs HS256 (symmetric) for signing: what does RS256 cost, and what concrete operational problem does it remove in a multi-service system?
7. Returning `404` for a foreign order is "less honest" than `403`. What security property does the 404 buy, and what's the small usability/debugging cost you accept?

### D — Transfer / extension
8. A new `support`-only endpoint `GET /api/v1/orders/{id}/audit` is added. Using the existing model, what two layers of check must be in place so a `customer` token can't reach it, and which one is the "defense in depth" backstop?
9. The team moves from Keycloak to Cognito. The passage says the client id lands in a different claim. Explain why the audience check was written to accept **any of** `aud`/`azp`/`client_id`, and what would break if it only checked `aud`.

### E — Spot-the-flaw
10. A dev adds a fast path: if the JWT's `sub` matches the order's `customerId`, skip the DB ownership `filter` "to save a query," and otherwise do the full check. A week later, support users can't see customer orders and an audit finds some customers *can* read others'. What did removing/weakening the `filter` break, and why is "owner **or** support, enforced in one place" the correct shape?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. There are **no cookies/sessions** (stateless JWT in the `Authorization` header), so there is **no cookie for a malicious site to ride** — CSRF attacks rely on the browser auto-sending a session cookie. With bearer tokens that aren't auto-sent cross-site, the CSRF surface doesn't exist, so disabling it is safe (and necessary to avoid blocking legitimate stateless calls).
2. The **audience** — accepted if **any of** `aud`, `azp`, or `client_id` equals the configured value.
3. `GET` needs **`customer` or `support`**; writes (POST/DELETE) need **`customer`**.
</details>

<details>
<summary>B — Inference</summary>

4. The **audience** didn't match — the token was minted by the right issuer but for a **different API/client**. This stops the "**confused deputy / token reuse**" attack where a token legitimately issued for service X is replayed against order-service; signature/exp/iss all pass, only the audience check catches it.
5. Keys are fetched **on first use**, so order-service **starts even if the IdP is down** at boot (it only needs JWKS when the first token arrives). Fetching at startup would make the IdP a hard **startup dependency** — a Keycloak/Cognito blip during a deploy would prevent order-service pods from starting at all.
</details>

<details>
<summary>C — Trade-off</summary>

6. **Cost of RS256:** asymmetric crypto is heavier than HMAC, and you must distribute/rotate public keys (handled by JWKS). **Problem it removes:** with HS256 every service would need the **shared secret** to verify tokens — that secret is now copied across many services (and maybe the IdP), so any one leak compromises all, and rotation means coordinating every service at once. With RS256 only the IdP holds the **private** key; services hold only **public** keys, so there's no shared secret to leak or synchronise.
7. The `404` buys **non-enumerability**: an attacker can't tell "exists but not yours" (`403`) from "doesn't exist" (`404`), so they can't walk sequential ids to map which orders exist. The cost: a legitimate owner who mistypes an id, and a developer debugging, both get a generic `404` instead of a more informative `403` — slightly worse ergonomics for materially better security.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **(1)** URL/role authorization in `SecurityConfig`: a matcher requiring `hasRole("support")` on `…/audit` (blocks a `customer` token at the filter chain). **(2)** Method/object-level check in the service (e.g. `@PreAuthorize`/`Caller.support()` guard) so even if a route is misconfigured, the handler itself refuses a non-support caller. The **service-level check is the defense-in-depth backstop** — URL matchers are easy to get wrong (ordering, wildcards), so the business logic should not *rely* on the matcher alone.
9. Because **different IdPs put the client id in different claims** — Keycloak in `aud`/`azp`, Cognito access tokens in `client_id`. Accepting **any of** them makes the same code work across both providers without branching. If it only checked `aud`, **Cognito tokens would be rejected** (their audience lives in `client_id`), breaking auth entirely after the migration even though the tokens are valid.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. The `filter(caller::mayAccess)` encodes **"owner OR support"** in one place. The "fast path" that matches only `sub == customerId` **drops the support branch** (so support users, who are *not* the owner, now fail the fast path and may be mishandled) and, depending on how the "otherwise" path was rewritten, can **weaken the owner check** (e.g. comparing the wrong field, or skipping it when a condition is met) — letting some customers read others' orders (the IDOR returns). The correct shape is a **single, centralized authorization predicate** (`mayAccess` = owner or support) applied uniformly on `get`/`cancel`/`list`; scattering or special-casing it is exactly how object-level auth bugs creep in. The "saved query" is a false economy against a data-exposure vulnerability.
</details>

---

## Tod ke dekho — Local (docker compose)

Goal: see audience/role enforcement and the IDOR 404.

```bash
make up
TOKEN_ALICE=$(make -s token)   # alice (customer)
TOKEN_BOB=$(curl -s -X POST http://localhost:8180/realms/shopflow/protocol/openid-connect/token \
  -d grant_type=password -d client_id=shopflow-web -d username=bob -d password=bob | jq -r .access_token)

# 1) no token -> 401
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/v1/orders            # 401

# 2) alice creates an order
ID=$(curl -s -X POST http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN_ALICE" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"sku":"PIXEL-9","quantity":1}' | jq -r .id)

# 3) IDOR: bob tries to read alice's order by id -> 404 (NOT 403: id existence hidden)
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/v1/orders/$ID -H "Authorization: Bearer $TOKEN_BOB"   # 404
# alice reads her own -> 200
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/v1/orders/$ID -H "Authorization: Bearer $TOKEN_ALICE" # 200

# 4) list is scoped: bob sees only his own orders, not alice's
curl -s http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN_BOB" | jq '.content | length'

# 5) tampered/garbage token -> 401 (signature fails)
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/api/v1/orders -H "Authorization: Bearer not.a.jwt"   # 401
```

**Proved:** 401 without a valid signed token; cross-customer read is a **404** (not 403); list is owner-scoped. Audience: set `JWT_AUDIENCE` on order-service to a wrong value and watch valid tokens get rejected (confused-deputy defense).

## Tod ke dekho — Online
[`ONLINE-LABS.md`](../ONLINE-LABS.md) **section A (Codespaces)** — Keycloak + order-service chahiye.

---

## Interview concepts is passage se
- **OAuth2 resource server / stateless JWT** — no session, CSRF off (no cookie).
- **JWT validation steps** — JWKS by `kid`, RS256 sig, `exp`, `iss`, **`aud`**.
- **Audience pinning** — confused-deputy / token-reuse ko rokta (sig+exp+iss kaafi nahi).
- **RS256 vs HS256** — public-key verify, no shared secret to leak/rotate.
- **Lazy JWKS (SupplierJwtDecoder)** — IdP down pe bhi service start.
- **RBAC vs object-level auth** — role matcher + per-object owner check.
- **IDOR + 404-not-403** — id enumeration rokna.
- **Defense in depth** — URL matcher + method/service check dono.

**Interviewer's lens:** "sig+exp+iss ke baad aur kya" (audience!) aur "403 kyu nahi, 404 kyu" — ye do security-round ke signature questions hain.

> Interview line: *"order-service OAuth2 resource server hai — stateless JWT, no session, CSRF off. Validation: issuer JWKS se RS256 signature, exp, iss — aur audience bhi (aud/azp/client_id == configured), warna doosre API ka token bhi pass kar jaata (confused deputy). RS256 isliye ki services ke paas sirf public key ho, shared secret nahi. Roles se RBAC. IDOR ke liye har order JWT sub store karta; non-owner ko 404 (403 nahi, warna id enumerate ho jaaye). JWKS lazy, to IdP down pe bhi service start ho."*
</content>
