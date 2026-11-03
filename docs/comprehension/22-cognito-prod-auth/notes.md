# 22 · Cognito prod auth (vs Keycloak) — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `infra/terraform/aws/cognito.tf`, `security/JwtDecoderConfig.java`, `JwtRolesConverter.java`. Pairs with [comp 10](../10-oauth2-jwt-idor/).

## Kya (one line)
Prod mein Keycloak ki jagah **Amazon Cognito** JWT issue karta; order-service resource server hai (sirf issuer JWKS se validate), to IdP swap **config change** hai (issuer+audience), code nahi — Cognito web client public hai (**auth-code + PKCE**, no secret), access token 15min/refresh 30d, groups→roles `cognito:groups`.

## Easy way (Hinglish analogy)
Building ke do security-guard vendors: **dev = Keycloak** (apna banda), **prod = Cognito** (agency ka managed banda). Darwaze ka scanner (order-service) sirf **ID card asli hai?** check karta — kisne banaya farak nahi, bas card ka format (claim) thoda alag, scanner dono samajhta. **SPA = browser** = koi locker nahi, to "secret" rakh nahi sakta → **PKCE** (ek one-time parchi) se saabit karo tum wahi ho. **Access card 15 min** (kho gaya to jaldi expire), **refresh pass 30 din** (baar-baar login na karna pade, par ise sabse sambhal ke).

## Kyu (ye problem solve karta hai)
- Prod mein apna IdP (Keycloak) run/patch/scale/backup karna jhanjhat — managed Cognito wo hata deta.
- Resource server ko IdP-agnostic banaya (issuer JWKS validate), to dev↔prod ek hi image — sirf config/claim-shape badalta (converter absorb karta).
- SPA secret nahi rakh sakti → PKCE + auth-code (implicit flow deprecated: token URL mein leak).
- Short access token leak-window chhota rakhta; long refresh UX deta par zyada risk → use sabse sambhalo.

## Kaise (step by step)
1. **User pool** (`cognito.tf`): email login, auto-verify, MFA optional, password ≥12 (upper/lower/number).
2. **Web client**: `generate_secret=false` (public) → `allowed_oauth_flows=["code"]` (+ PKCE), scopes openid/email/profile, callback/logout URLs, SRP+refresh auth.
3. **Token validity**: access 15m, id 15m, refresh 30d.
4. **Resource server** (no code change): `JWT_ISSUER_URI`=Cognito issuer → JWKS+iss; `JWT_AUDIENCE`=client id → `JwtDecoderConfig` checks `aud`/`azp`/**`client_id`**.
5. **Roles**: `JwtRolesConverter` `cognito:groups` (else `realm_access.roles`) → `ROLE_*`.
6. **Hosted UI**: Cognito domain (login page).

## Flow diagram (login → API call, prod)
```
 browser SPA ──auth-code + PKCE──► Cognito hosted UI (login)
            ◄── one-time code ──
 SPA ──code + PKCE verifier──► Cognito token endpoint ──► access(15m) + id(15m) + refresh(30d)
 SPA ──Authorization: Bearer access──► order-service (resource server)
            validate: Cognito JWKS (RS256) + exp + iss + aud(client_id) ; cognito:groups → ROLE_*
 every ~15m: SPA ──refresh──► Cognito ──► new access   (no re-login)
```

## Dev vs prod diagram
```
 concern         dev (Keycloak)              prod (Cognito)
 ───────────────────────────────────────────────────────────
 who runs it     you (self-hosted)           AWS (managed)
 roles claim     realm_access.roles          cognito:groups        } JwtRolesConverter both
 audience claim  aud / azp                   client_id             } JwtDecoderConfig both
 order-service   SAME image, config only (JWT_ISSUER_URI / JWT_AUDIENCE)
```

## Alternate approaches (kab kaunsa)
| Choice | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Cognito (managed)** *(prod)* | user pool + public client | no ops, AWS-native, MFA/UI | AWS lock-in, limited custom | AWS prod |
| **Keycloak (self-hosted)** *(dev)* | run it | full control, portable, custom flows | you operate it | on-prem / custom |
| **auth-code + PKCE** *(SPA)* | code + verifier | no secret, token not in URL | one extra step | public clients |
| **implicit flow** | token in URL | — | **deprecated**, token leaks | kabhi nahi |
| **client-credentials** | service-to-service | no user | not for user login | M2M |

## Understanding check
1. Dev/prod kaun JWT deta, swap config kyu (code nahi)?
2. Web client public + PKCE kyu (secret kyu nahi)?
3. Access 15m / refresh 30d — kyu aise?
4. `cognito:groups`/`realm_access.roles` dono support se same image kaise?
5. Access vs refresh token theft — kaunsa bura, kyu?

## Interview mein ye bolo
*"Dev Keycloak, prod Cognito — order-service resource server, sirf issuer JWKS se JWT validate karta, to IdP swap config change hai (issuer+audience), code nahi. Cognito web client public hai (generate_secret false — SPA secret nahi rakh sakti), authorization-code + PKCE use karta (implicit nahi, warna token URL mein leak). Access token 15 min (leak window chhota), refresh 30 days revocable (UX). Groups→roles: JwtRolesConverter `cognito:groups` ya `realm_access.roles` padh ke ROLE_* banata, audience aud/azp/client_id dono IdP shapes handle karta — isliye ek hi image dono mein. Refresh token sabse sambhalne wali cheez (long-lived, naye access mint karta)."*

> Related: [`../10-oauth2-jwt-idor/`](../10-oauth2-jwt-idor/) (JWT validation + IDOR), [`../20-eks-irsa-least-privilege/`](../20-eks-irsa-least-privilege/) (OIDC/STS). Code: `security/application-aws.yml` (prod profile).
</content>
