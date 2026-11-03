# 27 · Observability / SLI / incident-model — NOTES (kya / easy / kyu / kaise)

> Pair: [`comprehension.md`](comprehension.md). Trilogy: [25 correctness](../25-correctness-model-capstone/) · [26 scaling](../26-scaling-100x-bottlenecks/) · 27 observability (ye).

---

## KYA (one breath)
Checkout SLO = **"99.5% requests 1s ke andar succeed, 30 din"** — ek number mein **availability + latency**. SLI do hisson se: `bad = 5xx + slow-2xx` (histogram `le=1.0` bucket), denominator = **2xx+5xx (4xx exclude)**. Alert **multi-window multi-burn**. Incident mein **metric→trace→log** utro, guess mat karo.

## EASY WAY
> **"Slow bhi broken hai; client ki galti (4xx) hamari nahi; alert tabhi jab problem **real** (long window) **aur** **abhi** (short window) ho."**

## KYU (har design choice ka reason)
- **Slow counts as bad:** site "up" par 3s/checkout = user ke liye fail. Pure 5xx-ratio SLI ise 100% healthy dikhata — jhooth. Isliye `le=1.0` se slow-2xx ko bad ginते.
- **4xx denominator se bahar:** 400/409/422/429/404 = client/business, hamari unavailability nahi. Ginoge to service correctly-reject karne pe bhi "SLO miss" dikhega. (Test case 7: slow 429 burst, **no page** — yahi prove karta.)
- **Multi-window:** long (1h/6h) = **significance** (blip nahi), short (5m/30m) = **currency** (abhi ho raha → fix pe jaldi resolve). Dono `and` = real AND ongoing.
- **Signal hierarchy:** metric sasta+aggregate (what/where), trace per-request (kaunsा hop), log detail (kyun). Sirf logs = mehnga + slow + localize nahi.

## KAISE (layers — recording-rules.yml se slo-rules.yml tak)
```
http_server_requests_seconds_{count,bucket}   (micrometer, per uri/status/le)
        │
        ▼ recording-rules.yml
RED:  service:http_requests:rate5m  · service:http_errors:rate5m (5xx)
      service:http_error_ratio:rate5m · service:http_latency_p99:5m (histogram_quantile le)
        │
        ▼ SLI (checkout)
sli:checkout_requests = rate(status=~"[25].."})           # 2xx+5xx only (4xx out)
sli:checkout_bad      = rate(5xx) + ( rate(2xx) - rate(2xx, le="1.0") )   # errors + slow
sli:checkout_error_ratio:rate{5m,30m,1h,6h}
        │
        ▼ slo-rules.yml (multi-window multi-burn)
Fast : ratio1h  AND ratio5m  > 14.4*0.005 (7.2%)  for 2m  -> PAGE
Slow : ratio6h  AND ratio30m > 6*0.005    (3.0%)  for 15m -> PAGE
(1x over 3d -> TICKET)
```

## DIAGRAM — SLI numerator (ek checkout ka kya hota)
```
 POST /orders ──► 500/503  ───────────────► BAD   (availability miss)
             ──► 200 but >1s (outside le=1.0) ─► BAD   (latency miss)
             ──► 200 within 1s (in le=1.0) ───► GOOD
             ──► 400/409/422/429/404 ─────────► IGNORED (not in denominator)
 ratio = BAD / (GOOD+BAD)   ;  GOOD+BAD = 2xx+5xx only
```

## DIAGRAM — incident reasoning (utarna, guess nahi)
```
 PAGE: CheckoutSLOBurnRateFast
   │  which half moved?  (open sli:checkout_bad terms)
   ├─ 5xx term up ──► availability incident ─┐
   └─ slow-2xx up ──► latency incident ──────┤
                                             ▼
   METRIC  service:http_latency_p99:5m per application  -> which service
                                             ▼
   TRACE   Tempo: slow/failed span          -> which hop/dependency
                                             ▼
   USE/LOG Hikari active/pending; Loki by trace.id -> WHY (saturation/stacktrace)
                                             ▼
   FIX cause; watch SHORT window drop -> page resolves fast
```

## BURN-RATE MATH (yaad rakho)
| Alert | Windows | Threshold | = | Budget | Action |
|-------|---------|-----------|---|--------|--------|
| Fast | 1h **and** 5m | 14.4 × 0.005 | 7.2% | 2%/1h | PAGE (for 2m) |
| Slow | 6h **and** 30m | 6 × 0.005 | 3.0% | 5%/6h | PAGE (for 15m) |
| — | 3d | 1 × 0.005 | 0.5% | on-track exhaust | TICKET |
- `0.005 = 1 − SLO` (0.5% budget). `14.4` = burn-rate jo monthly budget ka 2% ek ghante mein jalaye.

## ALTERNATE (kis jagah kya)
| Choice | ShopFlow | Alternate | Kab alternate |
|--------|----------|-----------|---------------|
| SLI | availability+latency combined | separate avail-SLO + latency-SLO | jab alag budget/owner chahiye |
| latency-as-error | `le=1.0` bucket subtract | p99 threshold alert | quick dashboard; par budget-math weak |
| burn alert | multi-window multi-burn | single-threshold error-rate | simple svc; noisy/laggy |
| 4xx | excluded | include specific 4xx (e.g. 403 spike = attack) | security signal chahiye |
| signal | metric→trace→log | log-only | chhota svc; par scale pe mehnga |

## UNDERSTANDING CHECK (khud bol)
1. 200-at-3s budget jalata hai kya? kyun? → *haan; "within 1s" toota, user ne fail feel kiya; `le=1.0` ke bahar.*
2. 409 sold-out ka spike SLO burn karega? → *nahi; 4xx denominator se bahar.*
3. Sirf 1h window pe alert → 30-min incident ke baad kya? → *~50 min aur page karta rahega (1h average slow decays); 5m `and` ise jaldi resolve karता.*
4. 14.4×0.005 = kya aur kyun? → *7.2%; burn jo 2% budget/1h jalaye → page.*
5. Latency burn ho raha, 5xx flat — pehla step? → *per-service p99 dekho, phir trace span, phir pool/log.*

## INTERVIEW LINE
> *"SLO: 99.5% checkout 1s mein succeed, 30 din. SLI = bad(5xx **+** slow-2xx via `le=1.0`) / (2xx+5xx) — **4xx exclude** kyunki client/business ki galti hamari unavailability nahi (slow-429 burst bhi page nahi karta — test case isko lock karta). Multi-window multi-burn: 1h **and** 5m > 14.4×0.005 → page (long=real, short=abhi+jaldi-resolve). Fire pe metric→trace→log utarta hoon. Aur jis series pe alert depend karता wo prod mein exist karni chahiye warna alert chup dead — wahi `le=1.0` missing-bucket bug."*

---
**Grounded:** `monitoring/recording-rules.yml` (RED + sli_* multi-window), `monitoring/slo-rules.yml` (14.4/6/1 burn), `monitoring/tests/alerts_test.yml` (case 5 5xx→page, 6 slow→no-page threshold, 7 slow-429→**no page** = 4xx out), `monitoring/alert-rules.yml`, Tempo/Loki/Alloy. Topic 06 = SLI-missing-bucket bug; 13 = tracing; D03/D05 = live.
