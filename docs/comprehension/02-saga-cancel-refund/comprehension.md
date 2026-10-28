# 02 · Cancelling a paid order: a refund saga — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `order-service/…/OrderService.java` (`cancel`, `onPaymentFailed`, `onPaymentRefunded`), `payment-service/…/OrderConfirmedListener.java` (`charge`, `refund`), `FakePaymentGateway.java`.

---

## Scenario (read cold)

> *Excerpt from ShopFlow's order lifecycle notes. Read once, then answer.*

ShopFlow has no central "order manager" that drives the whole flow. Each service reacts to the previous step's event — **choreography**. Placing an order reserves stock over REST (the customer needs an answer now); everything after that travels over Kafka.

When stock is reserved, the order is `CONFIRMED` and an `OrderConfirmed` event goes out. payment-service listens, charges the card through a `PaymentGateway`, writes a `payment` row and a `PaymentCaptured` (or `PaymentFailed`) event in **one local transaction**, and publishes it. order-service listens back: `PaymentCaptured` → `PAID`; `PaymentFailed` → release the stock and `CANCELLED`. Only orders still in `CONFIRMED` are moved, so a duplicate event is a no-op.

Now the hard case: **the customer cancels an order that is already `PAID`.** You cannot just delete the order or release the stock — the money has already left the customer's card. Deleting would leave them charged for nothing. So cancelling a paid order is **not a delete; it is a refund saga**:

```
PAID ──cancel──▶ REFUND_PENDING ──(OrderRefundPending)──▶ payment-service refunds the CAPTURED payment
                                                           ──(PaymentRefunded)──▶ order-service releases stock ──▶ REFUNDED
```

The order goes to `REFUND_PENDING` and emits `OrderRefundPending`. payment-service refunds the captured payment and emits `PaymentRefunded`. **Only then** does order-service release the stock and mark the order `REFUNDED`. The sequence is deliberate: **money back first, stock after.** If the refund fails, the saga simply doesn't advance — the stock is never released for an order the customer is still owed money on, so a failed refund can never "leak" stock.

Idempotency on the refund leg is **by state**, not by event id. payment-service looks the payment up by `orderRef` and refunds it **only if its status is `CAPTURED`**; a redelivered `OrderRefundPending` finds it already `REFUNDED` and does nothing. order-service's `onPaymentRefunded` only acts on an order still in `REFUND_PENDING`. There is no new row to key a dedupe table on, so the *current state* is the guard.

Compare this with the earlier compensation: when a **payment fails** on a fresh order (`CONFIRMED`, money never captured), order-service just releases the stock and goes straight to `CANCELLED` — no refund, because nothing was charged. The branch you take depends entirely on **whether money has moved yet**.

---

## Questions

### A — Literal
1. Which step of the flow is orchestrated over REST, and which steps are choreographed over Kafka?
2. In the refund saga, what is released **first** and what happens **after** — and what is the stated reason for that order?
3. On the refund leg, what exactly makes a redelivered `OrderRefundPending` safe (i.e. not refund twice)?

### B — Inference
4. A customer cancels a `CONFIRMED` order (stock reserved, card **not** charged) and, separately, a `PAID` order. Why does the first become `CANCELLED` immediately while the second must go through `REFUND_PENDING`?
5. The refund saga releases stock only after `PaymentRefunded`. Describe the concrete bad outcome that "release stock first, then refund" would allow if the refund then failed.

### C — Trade-off
6. The whole flow is choreography (no central orchestrator). Give one real advantage and one real cost of that versus a central orchestrator that calls each service.
7. Refund idempotency is "by state" (look up by `orderRef`, act only if `CAPTURED`) instead of a `processed_event` dedupe table keyed by `eventId`. Why is "by state" a reasonable choice **here** specifically?

### D — Transfer / extension
8. The fake gateway always approves refunds. In reality a refund can be declined by the processor. Using the saga's own shape, where would you add a `RefundFailed` path, and what new state/alert would you introduce so nothing is lost?
9. Product asks for **partial** refunds (refund 1 of 2 items). What breaks in the current "look up the payment by `orderRef` and refund it if CAPTURED" logic, and what would the model need to track instead?

### E — Spot-the-flaw
10. A dev "optimizes" cancel of a PAID order to: release stock immediately, emit `OrderRefundPending`, mark `REFUNDED`, and let payment-service refund asynchronously. Tests pass. What production bug has been introduced, and which invariant from the passage did it violate?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **Orchestrated / REST:** reserving stock (order-service calls inventory-service synchronously, because the customer needs the reserve/reject answer immediately). **Choreographed / Kafka:** payment (payment-service reacts to `OrderConfirmed`) and the order's reaction to `PaymentCaptured`/`PaymentFailed`/`PaymentRefunded`.
2. **First:** the money is refunded (payment-service refunds the CAPTURED payment → `PaymentRefunded`). **After:** order-service releases the stock and marks `REFUNDED`. Reason: **money back first, stock after**, so a failed refund never releases stock for an order the customer is still owed money on.
3. payment-service refunds **only if the payment's status is `CAPTURED`**; a redelivery finds it already `REFUNDED` and does nothing (and order-service's `onPaymentRefunded` only acts while the order is `REFUND_PENDING`). The *state* is the guard — no double refund.
</details>

<details>
<summary>B — Inference</summary>

4. The branch depends on **whether money has moved**. A `CONFIRMED` order was never charged (payment happens after confirm), so cancelling just reverses the one thing that happened — the stock reservation — and goes straight to `CANCELLED`. A `PAID` order **has** a captured payment, so you cannot finish the cancel until that money is returned; hence the intermediate `REFUND_PENDING` state and the refund saga.
5. If you released stock first and the refund then failed, the item would go back on sale (someone else could buy it) **while the original customer is still charged and holds no product and no money**. You'd owe them a refund with the stock already gone — exactly the "leak" the money-first ordering prevents.
</details>

<details>
<summary>C — Trade-off</summary>

6. **Advantage:** loose coupling and resilience — each service only knows the events it cares about; payment-service can be down and catch up from the topic without order-service orchestrating retries, and adding a consumer (e.g. analytics) needs no change to the producer. **Cost:** no single place shows the whole flow — the logic is spread across listeners and status guards, so understanding/debugging "where is this order stuck?" needs distributed tracing. (ShopFlow answers this with trace propagation through HTTP + Kafka headers.)
7. Because the refund already has a **natural, persistent, idempotent key: the payment row's status.** There's no *new* record created per refund attempt to dedupe on, but there **is** an existing row (`payment`, keyed by the original `OrderConfirmed` eventId) whose `CAPTURED → REFUNDED` transition is one-way. "Act only if CAPTURED" makes every redelivery after the first a no-op for free — a separate dedupe table would be redundant state.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. Mirror the capture path. payment-service's `refund` would, on a declined refund, write the payment as `REFUND_FAILED` (not `REFUNDED`) and emit a `RefundFailed` event. order-service keeps the order in `REFUND_PENDING` (does **not** advance to `REFUNDED`, so stock stays held) and the stuck-`REFUND_PENDING` gauge already catches it → an alert pages a human (and/or a retry with backoff against the processor). Nothing is lost: no money implies no stock release, and the limbo is visible. (This is an open item in `docs/STATE.md` §4.)
9. "Refund **the** payment for this `orderRef` if CAPTURED" assumes one all-or-nothing payment per order. Partial refunds need to track **amount refunded vs amount captured** (and probably per-line-item), so the payment can be `PARTIALLY_REFUNDED`, and idempotency must key on *which* refund (a refund id / line), not just the payment's binary status. The state guard alone is no longer enough.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. It releases stock **before** the money is actually returned, and even marks `REFUNDED` while the refund is only *requested*. If payment-service's refund then fails (processor declines, pod crash), the item is already back on sale and the order says `REFUNDED`, but **the customer was never refunded** — they're charged, product-less, and the system shows the order as fully settled. It violates the passage's core invariant: **money back first, stock after**, so a failed refund can never leak stock. Tests passed only because the fake gateway always approves refunds, hiding the failure branch.
</details>

---

## Tod ke dekho — Local (docker compose)

Goal 1: see the happy saga (`CONFIRMED → PAID`). Goal 2: **force a payment failure** and watch the compensation (`release + CANCELLED`). Goal 3: cancel a PAID order and watch the **refund saga**.

`FakePaymentGateway` approves up to a limit of **100000**. `PS5-SLIM` = 54990, so **quantity 2 = 109980 > 100000 → declined.** That's our lever.

```bash
make up && TOKEN=$(make -s token)
ORDER() { curl -s -X POST http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d "$1"; }
GET()  { curl -s http://localhost:8081/api/v1/orders/$1 -H "Authorization: Bearer $TOKEN" | jq '.status'; }

# 1) HAPPY: qty 1 (54990 < limit) -> CONFIRMED, then payment captures -> PAID
ID=$(ORDER '{"sku":"PS5-SLIM","quantity":1}' | jq -r '.id'); sleep 3; GET $ID      # -> "PAID"

# 2) PAYMENT FAILS: qty 2 (109980 > limit) -> CONFIRMED, then PaymentFailed -> release + CANCELLED
IDF=$(ORDER '{"sku":"PS5-SLIM","quantity":2}' | jq -r '.id'); sleep 3; GET $IDF     # -> "CANCELLED"
docker compose -f shopflow/docker-compose.yml logs --tail=5 payment-service         # "declined: card limit exceeded"

# 3) REFUND SAGA: cancel the PAID order from step 1
curl -s -X DELETE http://localhost:8081/api/v1/orders/$ID -H "Authorization: Bearer $TOKEN" | jq '.status'  # -> "REFUND_PENDING"
sleep 3; GET $ID                                                                     # -> "REFUNDED"
docker compose -f shopflow/docker-compose.yml logs --tail=5 payment-service          # "refunded order ... amount ..."
```

**What you proved:** the branch taken depends on whether money moved — step 2 (never captured) goes straight to CANCELLED; step 3 (captured) must pass through REFUND_PENDING and only reaches REFUNDED after the money is back.

> Break harder: in step 3, `docker compose stop payment-service` **before** the DELETE, cancel → order sits `REFUND_PENDING` (stock still held — correct!), then `start payment-service` → it drains and reaches `REFUNDED`. That's the "money-first" invariant protecting you live.

## Tod ke dekho — Online
Kafka chahiye → [`ONLINE-LABS.md`](../ONLINE-LABS.md) **section A (Codespaces)**: wahi commands browser mein.

---

## Interview concepts is passage se
- **Saga pattern** — distributed transaction ki jagah local transactions + compensations.
- **Choreography vs orchestration** — event-react vs central driver; coupling vs visibility.
- **Compensating transaction** — PaymentFailed → release stock; cancel PAID → refund.
- **Ordering of compensation ("money-first")** — kis cheez ko pehle undo karna safe hai.
- **Idempotency by state** — status transition hi dedupe; alag table ki zarurat nahi.
- **State machine design** — kaunsa transition kab allowed (CONFIRMED vs PAID cancel alag).

**Interviewer's lens:** "whether money moved" ke hisaab se branch choose karna, aur "release-first" ka failure khud spell karna. Agar tu `RefundFailed`/partial-refund extension bhi bol de, senior signal.

> Interview line: *"PAID order cancel delete nahi, refund saga hai: PAID→REFUND_PENDING→(payment refunds)→REFUNDED. Money pehle wapis, stock baad mein — isliye fail hua refund kabhi stock leak nahi karta. Idempotency state se hai: payment sirf CAPTURED hone pe refund hota hai."*
</content>
