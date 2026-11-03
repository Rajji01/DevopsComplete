# D07 · Customers got two confirmation emails (and we fear double charges)

> Skill: idempotency / exactly-once debugging. Related mechanism: [`../comprehension/03-idempotency-three-ways/`](../comprehension/03-idempotency-three-ways/), [`../comprehension/11-kafka-dlt-lag/`](../comprehension/11-kafka-dlt-lag/).

---

## Page (symptom)

Support tickets: a batch of customers received **two identical "order confirmed" emails** a few seconds apart. Finance is nervous — if emails doubled, did **charges** double too? The spike lines up with a **consumer group rebalance** earlier (a notification-service pod was restarted during a deploy).

**Before details: at-least-once delivery means duplicates are expected. So the bug isn't "a duplicate arrived" — what is it?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- notification-service sent 2 emails for the same `orderRef`/`eventId`; logs show the **same `eventId` processed twice**, a few seconds apart, around a rebalance.
- payment-service: exactly **one** `payment` row per order (its PK is the `eventId`); `payments_duplicates_total` incremented but **no second charge**.
- order-service: one `PAID` transition per order; `processed_event` has the eventId once.
- notification-service was redeployed; a pod was killed mid-batch → consumer group rebalanced → some offsets were **re-delivered** (normal at-least-once behaviour).
- notification-service dedupe: it has a `processed_event` table, but the recent "batch email" feature **sends the email first, then records the eventId** — in **separate** steps.
</details>

---

## Your move

1. Duplicates are normal under at-least-once. So precisely which **invariant** did notification-service violate that payment-service and order-service did not?
2. payment-service got the same duplicate delivery but did **not** double-charge. What makes it immune, and why is that stronger than a dedupe table?
3. notification-service "sends email, then records eventId." Walk the crash/rebalance interleaving that sends two emails.
4. Can you make "send email" and "record processed" perfectly atomic? If not, what's the best achievable guarantee and how do you get closest?
5. Finance's question: were charges doubled? Point to the one signal that answers it definitively.

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**The real bug (Q1).** At-least-once means Kafka **will** redeliver on a rebalance/restart — that's a feature, not a fault. The contract is: **every consumer must be idempotent** so a redelivery is a no-op. notification-service violated **idempotency of its side effect**: it processed the same `eventId` twice and acted both times. The duplicate delivery is expected; the **double action** is the defect.

**Why payment-service is immune (Q2).** The `payment` table's **primary key is the `eventId`**. A redelivered `OrderConfirmed` either hits `existsById` (skip) or, in a race, hits the **PK constraint** on insert — so a second charge is *structurally impossible*, enforced by the database, not by hopeful code. That's stronger than a dedupe table because the uniqueness is the same write you're already doing; there's no window where "check" and "act" diverge.

**The interleaving (Q3).** notification-service does: (1) send email, (2) insert `eventId` into `processed_event` — **not in one transaction**. On a rebalance, the pod is revoked **after step 1 but before step 2 commits** (or before the Kafka offset commits). The partition is reassigned, the record is **redelivered**, the new owner checks `processed_event` → the eventId **isn't there** (step 2 never committed) → it **sends the email again**. Two emails, one eventId. The dedupe existed but was **recorded too late**.

**Root cause:** notification-service's new batch path records the dedupe **after** the side effect and in a **separate** step, so a crash/rebalance between "sent" and "recorded" re-sends. The dedupe-before-or-after ordering, and the lack of a single transaction, is the bug.

**Can you make it atomic? (Q4).** Not perfectly — "send an email" is an **external side effect** you can't enroll in a DB transaction (the dual-write problem again). Best achievable: **check-then-record in one DB transaction around the handler, and commit the Kafka offset only after that transaction** (manual ack, exactly how order-service does it) — so "recorded processed" and "state change" are atomic and the offset reflects both. The email still has a tiny residual window (crash after send, before commit), which you minimize by (a) recording intent *before* send and sending with the provider's **idempotency key**, or (b) accepting the rare duplicate as the cost of an un-rollback-able side effect. The point: get the dedupe **into the same transaction** and gate the offset on it.

**Fix:**
1. Move notification-service's `processed_event` insert into the **same transaction** as processing, with the dedupe **check at the top**, and don't commit the offset until that transaction commits (match order-service's `@Transactional` consumer).
2. If the email provider supports it, pass an **idempotency key** (the `eventId`) so even a residual re-send is deduped provider-side.

**Finance's answer (Q5):** **No double charge.** The definitive signal is payment-service's **one `payment` row per order** (PK = eventId) and `payments_duplicates_total` incrementing *without* a second row — the DB constraint proves the redelivery was a no-op on money. Only the email path (which lacked the same guarantee) doubled.
</details>

---

## The pattern

- **Under at-least-once, a duplicate *delivery* is normal; a duplicate *effect* is the bug.** Don't chase "why did Kafka redeliver" — chase "why wasn't the consumer idempotent."
- **Dedupe must be recorded in the same transaction as the effect, and the offset committed after.** Record-after-act (or in a separate txn) re-opens the exact window you were closing.
- **A uniqueness constraint (PK = eventId) beats a dedupe table** when the business write can carry the key — the DB enforces it with no check-then-act gap.
- **External side effects (email, SMS, third-party calls) can't be made transactional.** Minimize with provider idempotency keys + record-intent-first; accept the residual.
- **Prove the scary question with a constraint-backed signal.** "One row per eventId, enforced by a PK" is a definitive "no double charge."

> Grounded in: `notification-service` + `order-service` (`processed_event` dedupe), `payment-service/OrderConfirmedListener` (PK = eventId, `payments_duplicates`), the dual-write reasoning from comprehension topic 01.
</content>
