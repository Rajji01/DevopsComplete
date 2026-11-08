# Debugging drills — symptom in, root cause out

Comprehension rounds ([`../comprehension/`](../comprehension/)) sikhate hain *mechanism kaise kaam karta hai*. Ye series uska doosra aadha hai: **real-time debugging** — tujhe sirf **symptom** milta hai (ek alert, ek graph ka shape, ek log line, ek user complaint), aur tujhe **live diagnose** karna hai: pehle kya dekhun, har signal kya rule-in/rule-out karta, root cause kahan, fix kya, aur **kaise confirm** karun.

Yahi Google/Meta/Amazon ka **debugging / SRE round** hai, aur yahi on-call ki asli skill. Architect banne ke liye "design kar sakta hoon" kaafi nahi — "jab 2 baje raat ko toota, to 10 minute mein kahan hai bata sakta hoon" chahiye.

> Tip: Debugging guessing nahi hai. Har step pe ek **hypothesis** banao aur ek signal se usko **kaato ya pakko**. "Maine restart kar diya aur theek ho gaya" jawab nahi hai — "ye signal ne X ko rule out kiya, is liye Y" jawab hai.

## Debugging ka method (har drill mein yahi lagega)

1. **Blast radius pehchano.** Ek service? Ek endpoint? Ek pod? Saare users ya kuch? Ye hypothesis space ko 10x chhota kar deta hai.
2. **"Kya change hua?"** Deploy, config, traffic spike, ek dependency? Zyadatar incidents kisi change ke baad aate hain. Pehle wahi dekho.
3. **Request path bisect karo.** order → inventory (HTTP) → Kafka → payment → wapas. Flow ke beech mein check karke aadha-aadha kaato (binary search): "yahan tak sahi hai?"
4. **RED + USE padho.**
   - **RED** (har service): **R**ate (req/s), **E**rrors (5xx ratio), **D**uration (p99 latency). "Kya ye service error de raha ya slow hai?"
   - **USE** (har resource): **U**tilization, **S**aturation, **E**rrors. CPU/mem/pool/connections — "kya ye resource bhar gaya?"
5. **Differential diagnosis.** 2-3 candidate causes likho; har ek ki **ek alag prediction** hoti hai. Jo signal predictions ko alag karta hai, wahi dekho. Ek signal se ek hypothesis maaro.
6. **Root cause confirm karo, phir fix.** "Restart se theek hoga" ≠ root cause. Jab tak ye na bata pao "X hone se Y hua", aur ek signal se X dikha na do, tum guess kar rahe ho.
7. **Blast-radius-safe fix.** Reversible pehle (scale, restart, rollback, feature-flag). Jahan paisa/consistency ka risk ho, wahan socho (dekho comprehension topic 04: har limbo auto-cancel nahi hota).

> Mantra: **blast radius → kya change hua → path bisect → RED/USE → differential → confirm → safe fix.**

## Kaise practice karo

1. Drill ka **Page/symptom** padho. `<details>` kholne se **pehle** likho: "main kya kya check karunga aur kis order mein, aur kyu."
2. Phir **Signals** wala section dekho (jo tu dekh sakta tha). Apni list se milao — tu sahi signal maang raha tha?
3. **Walkthrough** kholo — diagnostic tree + root cause + fix. Apni reasoning se milao.
4. **The pattern** yaad rakho — har drill ek transferable debugging heuristic deta hai.

## Index

| # | Page (symptom) | Root cause (chhupa) | Skill |
|---|---|---|---|
| D01 | [Orders pile up in CONFIRMED, PAID flat](D01-orders-stuck-confirmed.md) | consumer lag on payment-service | Kafka / async |
| D02 | [`POST /orders` still 201, but nothing downstream happens](D02-201-but-silent-downstream.md) | Kafka down / outbox relay stuck | async / outbox |
| D03 | [Checkout p99 spikes, 5xx stays normal](D03-latency-spike-no-errors.md) | slow dependency → pool/thread saturation | latency / resources |
| D04 | [Pods restart in a loop, exit 137](D04-crashloop-oom.md) | OOMKilled (heap vs limit) | k8s / memory |
| D05 | [The outage happened but the SLO alert never fired](D05-alert-that-didnt-fire.md) | missing histogram bucket (monitor the monitor) | observability |
| D06 | [Flash sale sold 5 of 3 units](D06-oversold-flash-sale.md) | non-atomic stock decrement / cache in write path | concurrency |
| D07 | [Two confirmation emails (did charges double?)](D07-duplicate-emails-charges.md) | idempotency gap: dedupe recorded after the side effect | idempotency / Kafka |
| D08 | [Lag on ONE partition, the other two fine](D08-one-partition-stalled.md) | poison pill + a DLT topic that didn't exist | Kafka / partitions |
| D09 | [Every request 401 after the IdP migration](D09-401-after-idp-change.md) | wrong `JWT_ISSUER_URI` (auth vs authz layer) | security / JWT |
| D10 | [Redis went down, the database caught fire](D10-redis-down-thundering-herd.md) | fail-open cache → DB stampede (capacity, not errors) | caching / capacity |
| D11 | [inventory blipped, order-service has zero endpoints](D11-readiness-cascade.md) | readiness coupled to a downstream → cascade | Kubernetes / health |
| D12 | [The DLT has 9,000 records and nobody noticed](D12-dlt-filling-up.md) | growing DLT, zero lag — silent loss, no alert | async / schema drift |
| D13 | [The deploy's been "in progress" 20 min — stuck?](D13-rollout-stuck-or-paused.md) | paused-by-design vs auto-aborted vs genuinely stuck | progressive delivery |
| D14 | [Every deploy throws a few seconds of 502s](D14-deploy-drops-requests.md) | removed `preStop` → SIGTERM-vs-deregistration race | k8s / zero-downtime |
| D15 | [The autoscaler flaps up and down all day](D15-hpa-flapping.md) | removed scaleDown stabilization window + twitchy CPU | k8s / autoscaling |
| D16 | [Inventory says 5 units, the warehouse has 2](D16-phantom-stock.md) | double release under concurrency (read-guard, no @Version) = phantom stock | concurrency / race-idempotency |

**Status:** D01–D16 built; har drill ke ant mein ek **"Reproduce / test (apne haath se)"** block hai — symptom-induce command → observe → fix (docker-compose/kubectl/make se grounded; D03/D04/D14/D15 advanced hain to approximate/observe note). Aage (ideas): a `FAILED`-order storm the reconciler can't drain, Keycloak/Postgres connection-pool exhaustion, a Terraform drift surprise.

## In sab ka rishta
- **Mechanism samajhna** → [`../comprehension/`](../comprehension/) (har drill wahan ke topic se link karta).
- **Haath se todna** → [`../LABS.md`](../LABS.md) (break/fix labs).
- **Alert aaya to runbook** → [`../runbooks/`](../runbooks/).
- **Authoritative state** → [`../STATE.md`](../STATE.md).
Ye series inko jodti hai: symptom se root cause tak **sochna**.
</content>
