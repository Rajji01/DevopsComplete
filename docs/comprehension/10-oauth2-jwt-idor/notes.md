# 10 · OAuth2 JWT, audience & IDOR — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `security/SecurityConfig.java`, `JwtDecoderConfig.java`, `order/Caller.java`, `OrderService.get/list`.

## Kya (one line)
order-service stateless OAuth2 resource server hai: har request pe JWT verify karta (JWKS→RS256 sig, exp, iss, **aud**), role se RBAC lagata, aur per-object ownership check karta — doosre ka order maangne pe **404 (403 nahi)** deta taaki id enumerate na ho.

## Easy way (Hinglish analogy)
Office entry socho:
- **JWT** = ID card jisme hologram (signature), expiry date, aur "kis office ka" (issuer) likha.
- **Audience check** = card asli hai par **doosre office** ka — to andar mat aane do (sig/expiry/office sahi hone pe bhi).
- **RS256** = hologram banane wali machine (private key) sirf HR ke paas; guards ke paas sirf "hologram asli hai?" check karne wala stamp (public key) — secret kisi ke paas nahi.
- **RBAC** = card pe "visitor/manager" likha — kuch darwaze sirf manager ke liye.
- **IDOR + 404** = tum apni locker key se doosre ki locker kholne jao — guard "ye locker **hai hi nahi**" bolta (404), "hai par teri nahi" (403) nahi — warna tum number badal badal ke pata laga lete kaun-si locker exist karti.

## Kyu (ye problem solve karta hai)
- Sirf signature+expiry+issuer **kaafi nahi**: same issuer ka doosre API ke liye bana token bhi pass kar jaata → **confused deputy / token reuse**. Audience pin isko rokta.
- **HS256** mein har service ko shared secret chahiye → ek leak = sab compromise; rotation nightmare. **RS256** se sirf IdP ke paas private key.
- **IDOR**: role check kaafi nahi — "customer" hone se tum *koi bhi* order nahi dekh sakte, sirf apna. Per-object owner check chahiye. 403 vs 404 se id-enumeration rokte.

## Kaise (step by step)
1. **SecurityConfig**: `STATELESS`, CSRF off (no cookie). `/actuator/**`,`/swagger**` permitAll; `GET /api/v1/orders/**` → `customer`|`support`; writes → `customer`; else `denyAll`. `oauth2ResourceServer.jwt` + `JwtRolesConverter` (roles → `ROLE_*`).
2. **JwtDecoderConfig**: `SupplierJwtDecoder` (lazy JWKS → IdP down pe bhi start). Default validator = sig(JWKS,`kid`,RS256)+exp+iss; agar `jwt.audience` set → `DelegatingOAuth2TokenValidator(withIssuer, audienceValidator)`. `audienceValidator`: `aud` contains OR `azp`== OR `client_id`== audience.
3. **Caller**: `customerId` = JWT `sub`; `support` = `ROLE_support` present. `mayAccess(order)` = support || order owned-by customerId.
4. **IDOR** (`OrderService`): `get` → `findById.filter(mayAccess).orElseThrow(OrderNotFound)` → **404**. `list` → support? `findAll` : `findByCustomerId`. Cross-user Idempotency-Key → 422.

## Flow diagram (request → decision)
```
 request + Bearer JWT
     │
 SecurityFilterChain
     ├─ no/invalid token ─────────────► 401 (signature/exp/iss/aud fail)
     ├─ valid token, wrong role ──────► 403 (denyAll / role mismatch)
     └─ valid token, right role
             │
        Controller → OrderService.get(id, Caller)
             │ findById(id)
             ├─ not found ─────────────► 404
             ├─ found, mayAccess? (owner|support) yes ─► 200
             └─ found, NOT owner/support ─────────────► 404  (IDOR: NOT 403)
```

## JWT validation diagram
```
 JWKS (issuer public keys) ──kid──► pick key
                                      │
 token ─► verify RS256 signature ─► check exp ─► check iss ─► check aud (aud|azp|client_id == configured)
          (any fail → 401)                                     (empty config → skip, dev only)
```

## Alternate approaches (kab kaunsa)
| Choice | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Stateless JWT resource server** *(ShopFlow)* | verify token per request | scalable, no session store | token revocation hard | microservices |
| **Session + cookie** | server session | easy revoke | sticky/shared session store, CSRF | monolith |
| **HS256 signing** | shared secret | fast | secret copied everywhere | single trusted service |
| **RS256 signing** *(ShopFlow)* | IdP private, services public | no shared secret | key distribution (JWKS) | multi-service |
| **403 for foreign object** | honest error | clearer UX | **id enumeration** | internal/non-sensitive |
| **404 for foreign object** *(ShopFlow)* | hide existence | non-enumerable | less informative | user data |

## Understanding check
1. CSRF yahan off kyu, aur safe kyu?
2. Sig+exp+iss ke baad aur kya, teen claim names?
3. RS256 kaunsi operational dikkat hataata (HS256 ke muqable)?
4. Foreign order pe 404 kyu, 403 nahi?
5. JWKS lazy kyu — kaunsa failure tolerate hota?

## Interview mein ye bolo
*"order-service stateless OAuth2 resource server hai — no session, CSRF off. Token validation: issuer JWKS se `kid` pick, RS256 signature, exp, iss — aur audience bhi (aud/azp/client_id == configured), warna same issuer ka doosre API ka token pass kar jaata (confused deputy). RS256 isliye services ke paas sirf public key ho. RBAC roles se. IDOR ke liye har order JWT `sub` store karta aur `mayAccess` (owner|support) check hota; non-owner ko 404 deta — 403 id ki maujoodgi confirm kar deta aur enumerate ho jaata. JWKS lazy hai to IdP down pe bhi service start ho jaati."*

> Related: [`../03-idempotency-three-ways/`](../03-idempotency-three-ways/) (cross-user Idempotency-Key 422), [`../08-supply-chain-signing/`](../08-supply-chain-signing/) (identity/trust).
</content>
