# 03 · Three kinds of idempotency — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in: `OrderService.placeOrder` (Idempotency-Key), `PaymentEventListener.onPaymentEvent` (`processed_event`), `OrderConfirmedListener.charge` (payment PK = eventId) and `refund` (by state).

---

## Scenario (read cold)

> *Excerpt from ShopFlow's "why we don't double-charge" notes. Read once, then answer.*

Retries are everywhere in a distributed system: a client whose request times out presses the button again; Kafka redelivers a record when a consumer restarts before committing its offset. "Idempotent" means **doing the same operation twice has the same effect as doing it once.** ShopFlow enforces it in **three different ways**, each suited to a different situation.

**(1) The client-facing write — an `Idempotency-Key` header.** `POST /orders` accepts an `Idempotency-Key`. `placeOrder` looks up any existing order with that key: if found, it returns that same order instead of creating a second one. There is a twist — if the stored order belongs to a **different customer**, it does *not* replay it; it throws a conflict (422), because one user must never receive another user's order just because their keys collided. The key is chosen by the **client** and lets a retried HTTP call be safe.

**(2) The Kafka consumer — a `processed_event` dedupe table.** order-service's `PaymentEventListener` consumes payment events. Delivery is at-least-once, so the same `eventId` can arrive twice. Before acting, it checks `processed_event.existsById(eventId)`; if seen, it skips. After handling, it saves the `eventId` into `processed_event`, **in the same transaction** as the state change. The id here is produced by the **upstream event**, not the client.

**(3) The payment write — the business row's primary key *is* the event id.** payment-service charges on `OrderConfirmed`. Rather than a separate dedupe table, the `payment` table's **primary key is the `OrderConfirmed` eventId**. A redelivery hits `existsById` and returns early; a genuine race (two threads, same event) hits the **primary-key constraint** and one insert fails. Charge + payment row + outgoing event are one local transaction, so "charged but no record" is impossible.

And a fourth, subtler case that is **not** a table at all: the **refund** leg is idempotent **by state**. There's no new row to key on, so payment-service refunds only a payment whose status is still `CAPTURED`; a redelivery finds it `REFUNDED` and does nothing. The *current state* is the guard.

The rule the team repeats: **at-least-once delivery is a given, so every consumer and every externally-triggered write must be idempotent — choose the mechanism that already has a natural key.**

---

## Questions

### A — Literal
1. Who chooses the id in each of the three mechanisms — the client, or the upstream event?
2. When two `POST /orders` arrive with the **same** `Idempotency-Key` but from **different customers**, what does ShopFlow do, and why?
3. In mechanism (2), why must the `processed_event` save happen **in the same transaction** as the state change?

### B — Inference
4. Mechanism (3) relies on a primary-key constraint instead of a "check then insert." What failure does the PK catch that an `existsById` check alone would miss?
5. The refund leg uses "idempotent by state" with no dedupe table. What property of the refund operation makes a table unnecessary — and when would that trick stop working?

### C — Trade-off
6. Why not just use **one** mechanism everywhere — e.g. a `processed_event` table for all four cases? What does each of the other mechanisms give that the generic table doesn't?
7. The `Idempotency-Key` is optional on `POST /orders`. What's the risk of making it optional, and what's the argument for not forcing it?

### D — Transfer / extension
8. A new `inventory-service` endpoint `reserve(orderRef, sku, qty)` can be called twice by the order-service retrying. Using the passage's "natural key" rule, which of the three mechanisms fits best and what is the key? (Hint: a reservation already has a unique business id.)
9. You add an **email** side effect in notification-service. At-least-once means it could fire twice. The passage's mechanisms dedupe a *database write* — what extra problem does an email have that a DB insert doesn't, and how does dedupe-before-send help only partially?

### E — Spot-the-flaw
10. A dev makes the consumer idempotent like this: handle the event, commit the Kafka offset, **then** insert into `processed_event` in a separate transaction. Under a crash between the offset commit and the insert, what goes wrong on redelivery — and how does ShopFlow's "same transaction" ordering avoid it?

---

## Model answers

<details>
<summary>A — Literal</summary>

1. **(1) Idempotency-Key:** the **client** chooses it. **(2) processed_event:** the **upstream event** carries the `eventId`. **(3) payment PK:** the **upstream `OrderConfirmed` event's** id (reused as the payment row's primary key).
2. It throws a **conflict (422)** and does **not** replay the stored order. Replaying would hand one customer another customer's order just because their keys happened to collide — a data-leak/correctness bug. Ownership is checked (`isOwnedBy(customerId)`) before any replay.
3. So that "I acted" and "I recorded that I acted" commit atomically. If they were separate, a crash between them would either re-process the event (acted-but-not-recorded) or skip a never-handled event (recorded-but-not-acted). One transaction makes the dedupe exactly match the side effect.
</details>

<details>
<summary>B — Inference</summary>

4. A **concurrent race**: two threads processing the *same* event at the same time can both pass `existsById` (neither has inserted yet) and both proceed — a check-then-act TOCTOU gap. The **primary-key constraint** is enforced by the DB at insert time, so the second insert fails no matter the timing. `existsById` handles the common sequential redelivery cheaply; the PK is the hard backstop.
5. The refund is a **one-way state transition on an already-existing row** (`CAPTURED → REFUNDED`), so the row's own status is a natural idempotency key — refund only if `CAPTURED`, and after that every retry is a no-op. It stops working the moment the operation isn't a single all-or-nothing transition on one row — e.g. **partial** refunds, where the same payment can be refunded multiple valid times, so "is it CAPTURED?" no longer uniquely identifies "have I done *this* refund?"
</details>

<details>
<summary>C — Trade-off</summary>

6. Each mechanism uses a key that **already exists** in its context, so it adds the least state:
   - **Idempotency-Key** works at the HTTP edge *before* any event exists — a `processed_event` table can't dedupe a client retry that never became an event yet, and it must also encode **ownership**.
   - **payment PK = eventId** reuses the row you're already inserting — a separate table would be redundant state and a second write.
   - **by-state refund** has **no new row at all** to key a table on; the status is free.
   A single generic table would force an artificial id where a natural one exists, and still couldn't cover the pre-event (client) case.
7. **Risk:** without a key, a client that retries a timed-out `POST /orders` can create duplicate orders — the safety is opt-in. **Argument for optional:** not every caller needs it (a GET-then-POST flow, or an at-most-once producer), and forcing it complicates simple clients; the *server* still enforces it correctly when a key is supplied. The pragmatic stance: document it and have serious clients always send one.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. Mechanism **(3): the business row's natural unique key.** A reservation is uniquely identified by `orderRef`, so make `orderRef` a **unique key on the reservation row** (or look it up first and return the existing one). A second `reserve` with the same `orderRef` returns the existing reservation (or hits the unique constraint) — no double decrement. No extra dedupe table needed, exactly like payment. (This is what ShopFlow's `ReservationService.reserve` does: `findByOrderRef` first, return if present.)
9. An email is an **external side effect that can't be rolled back** — once sent, it's sent; it isn't part of your DB transaction. Dedupe-before-send (`processed_event`) stops the *second* delivery from sending a *second* email, which covers normal redelivery. But it only helps **partially**: if the service crashes **after** sending the email but **before** committing the dedupe row, redelivery will send again — you can't make "send email" and "record sent" atomic across two systems (same dual-write problem). You minimize it (idempotent provider keys, send-last-commit-first ordering), you don't eliminate it.
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. If the offset is committed first and the crash happens before the `processed_event` insert, then on restart Kafka **won't redeliver** (offset already advanced) — but the dedupe row was never written. Worse, the common variant — insert `processed_event` in a *separate* transaction from the state change — means a redelivery can find **no** dedupe row and **re-apply** the side effect, or find the row but the state change didn't commit. ShopFlow puts the dedupe insert and the state change in the **same `@Transactional`** method and lets Kafka commit the offset only after that method returns, so "handled" and "recorded handled" are atomic and the offset reflects both.
</details>

---

## Tod ke dekho — Local (docker compose)

```bash
make up && TOKEN=$(make -s token)

# (1) Idempotency-Key: same key twice -> ONE order (201 then 200), same id
KEY=$(uuidgen)
A=$(curl -si -X POST http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d '{"sku":"PIXEL-9","quantity":1}')
B=$(curl -si -X POST http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d '{"sku":"PIXEL-9","quantity":1}')
echo "$A" | grep HTTP   # 201 Created
echo "$B" | grep HTTP   # 200 OK  (replay, NOT a new order)
echo "$A$B" | grep -o '"id":[0-9]*' | sort -u   # same id -> only one order created

# (1b) same key, DIFFERENT user (bob) -> 422 conflict, never replays alice's order
TOKEN_BOB=$(curl -s -X POST http://localhost:8180/realms/shopflow/protocol/openid-connect/token \
  -d grant_type=password -d client_id=shopflow-web -d username=bob -d password=bob | jq -r .access_token)
curl -si -X POST http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN_BOB" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" -d '{"sku":"PIXEL-9","quantity":1}' | grep HTTP  # 422

# (2)/(3) dedupe: place an order, let payment capture, inspect dedupe rows
ID=$(curl -s -X POST http://localhost:8081/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" -d '{"sku":"AIRPODS-PRO","quantity":1}' | jq -r .id)
sleep 3
docker compose -f shopflow/docker-compose.yml exec postgres psql -U orders   -d orders   -c "select event_id from processed_event order by event_id desc limit 3;"
docker compose -f shopflow/docker-compose.yml exec postgres psql -U payments -d payments -c "select id, order_ref, status from payment order by id desc limit 3;"  # id == OrderConfirmed eventId
```

**Proved:** mechanism (1) collapses a client retry to one order and refuses a cross-user replay; (2)/(3) leave a dedupe fingerprint (`processed_event` row, payment PK) that makes a Kafka redelivery a no-op.

## Tod ke dekho — Online
[`ONLINE-LABS.md`](../ONLINE-LABS.md) **section A (Codespaces)** — Kafka+services chahiye.

---

## Interview concepts is passage se
- **Idempotency** definition aur at-least-once ke saath uski majboori.
- **Idempotency-Key** (client-chosen) + **ownership check** (cross-user replay = bug).
- **Dedupe table (`processed_event`)** by eventId, **same transaction** as side effect.
- **Natural key = PK** (payment id = eventId) → PK constraint catches the race `existsById` misses.
- **Idempotent-by-state** (no table, status guard) aur uski limit (partial refunds).
- **Email/external side effect** fully idempotent nahi ho sakta (dual-write).

**Interviewer's lens:** "sab jagah ek hi mechanism kyu nahi" ka jawab — **natural key** jo already exist karti hai use karo. Race vs sequential redelivery ka farak, aur same-transaction ordering.

> Interview line: *"At-least-once given hai, isliye har consumer/externally-triggered write idempotent hona chahiye. Hum teen tarah use karte hain: client Idempotency-Key (ownership ke saath), Kafka consumer `processed_event` dedupe same-txn mein, aur payment row ka PK hi eventId — jahan natural key pehle se ho wahi use karo. Refund by-state idempotent hai, koi table nahi."*
</content>
