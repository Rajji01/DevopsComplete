# 11 · Kafka: poison pills, DLT, lag, ordering — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `KafkaErrorConfig.java`, `order-service application.yml` (consumer), `PaymentEventListener`.

## Kya (one line)
Kafka partition ke andar order deta hai; ek "poison pill" record poore partition ko block kar sakti hai, isliye retry-with-backoff phir **dead-letter topic (.DLT)** pe daal ke aage badho; ordering ke liye `orderRef` key; health = **consumer lag**; DB/email ke saath "exactly-once" nahi, **at-least-once + idempotent consumer**.

## Easy way (Hinglish analogy)
Ek hi line (partition) mein log khade hain, counter ek-ek ko serve karta ( order). Ek banda aisa aa gaya jiska kaam **kabhi** nahi hota (poison pill) — agar counter uspe atka rahe to **poori line ruk jaayegi**. Solution: 2-3 baar koshish, phir usko **side wale "complaints desk" (DLT)** pe bhej do aur line aage badha do. Baad mein koi manually uski problem dekhega. **Lag** = line kitni lambi ho gayi (kitne log pending). Agar counter band (consumer down) → line badhti jaayegi → kaam ruka.

## Kyu (ye problem solve karta hai)
- **Head-of-line blocking**: ek bad record poore partition ko rok sakta → live orders ruk jaayein. DLT isko quarantine karke flow bachata.
- **Offset safety**: auto-commit se crash pe event skip ho sakta (lost). Manual commit after success → re-deliver (at-least-once), jo idempotent consumer handle karta.
- **Ordering**: related events same key → same partition → order maintained. Key galat chuni to ordering tooti.
- **Lag** = business health; nazar na rakho to orders CONFIRMED stuck, revenue delayed.
- **Exactly-once** DB/email ke saath possible nahi → at-least-once + idempotency hi real answer.

## Kaise (step by step)
1. **KafkaErrorConfig**: `DefaultErrorHandler(DeadLetterPublishingRecoverer(template), ExponentialBackOff(500ms, ×2, maxElapsed 10s))`. Retry 10s tak; phir record → `<topic>.DLT`, offset commit, partition aage.
2. **Consumer config** (`application.yml`): `enable-auto-commit:false` (offset success ke baad), `isolation.level:read_committed`, `auto-offset-reset:earliest`, `listener.observation-enabled:true` (trace).
3. **Keying**: producer `orderRef` key use karta (outbox `aggregateId`) → ek order ke events ek partition.
4. **Consumer idempotent**: `processed_event` dedupe (topic 03).
5. **Lag alert**: `KafkaConsumerLagHigh`; payment-service group lag = orders CONFIRMED unpaid.

## Flow diagram (record lifecycle)
```
 topic partition (ordered): [r1][r2][BAD][r4]...
        consumer (group) processes in order
             │ r1, r2 ok (offset commits after success)
             ▼
            BAD fails → retry 500ms,1s,2s... (≤10s)
             │ still failing
             ▼
   DeadLetterPublishingRecoverer → payments.events.DLT   (commit past BAD)
             │
            r4, r5 ... keep flowing   (partition NOT blocked)
   DLT → alert + human triage + replay (fix bug/data → re-publish to main topic)
```

## Lag-reduction decision diagram
```
 lag climbing?
   1) poison-pill retry loop burning time?  → fix / DLT it           (check FIRST)
   2) processing slow?                       → batch DB, drop slow calls
   3) genuinely under-parallel?              → add partitions, scale consumers to match
                                               (consumers ≤ partitions; extra sit idle)
   note: changing partition count breaks key→partition ordering mapping
```

## Alternate approaches (kab kaunsa)
| Choice | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Retry→DLT** *(ShopFlow)* | DefaultErrorHandler + DLT | partition flows, quarantine | DLT must be watched/replayed | default |
| **Infinite retry (no DLT)** | block till success | no "loss" | poison pill blocks forever | kabhi nahi |
| **Skip on error (swallow)** | try/catch return | "runs" | **silent data loss**, no retry | kabhi nahi |
| **At-least-once + idempotent** *(ShopFlow)* | manual commit + dedupe | achievable, safe | duplicates possible | DB/email loop |
| **Exactly-once (EOS)** | idempotent producer + txn | no dup | **only Kafka→Kafka** | pure stream pipelines |

## Understanding check
1. Poison pill partition ke saath kya karti, DLT kaise bachata?
2. `enable-auto-commit:false` kyu — kaunsa failure avoid hota?
3. 3 partitions pe 6 consumers kyu bekaar?
4. Ordering ke liye key kaunsi, partition-count change kya torta?
5. Listener mein exception swallow karne se kya do cheezein tootein?

## Interview mein ye bolo
*"Kafka partition ke andar order deta, ek consumer per partition. Poison pill (hamesha fail hone wala record) poore partition ko block kar sakta tha, isliye DefaultErrorHandler exp-backoff se retry karta (10s cap) phir DeadLetterPublishingRecoverer usay `.DLT` pe daal ke offset commit kar deta — partition chalti rehti, human baad mein DLT dekhta/replay karta. Offsets manual commit (auto-commit off) + read_committed → at-least-once, consumer processed_event se idempotent. Ordering orderRef key se; partition count badlo to mapping tooti. Lag business delay hai — payment group lag = orders CONFIRMED unpaid; lag kam karne ka order: consumers≤partitions, faster processing, phir partitions — par pehle poison-loop check. Exactly-once DB/email ke saath nahi hota."*

> Related: [`../03-idempotency-three-ways/`](../03-idempotency-three-ways/) (dedupe), [`../01-outbox-dual-write/`](../01-outbox-dual-write/) (producer side), [`../04-order-limbo-reconciler/`](../04-order-limbo-reconciler/) (CONFIRMED stuck).
</content>
