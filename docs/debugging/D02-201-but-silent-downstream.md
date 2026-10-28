# D02 · `POST /orders` still returns 201, but nothing downstream happens

> Skill: async / outbox debugging. Related mechanism: [`../comprehension/01-outbox-dual-write/`](../comprehension/01-outbox-dual-write/).

---

## Page (symptom)

Support escalates: customers placed orders (they have a 201 and an order id), but **no confirmation emails went out** and **nothing got charged** — for the last ~8 minutes. Yet the API looks totally healthy: 201s flowing, readiness UP, no 5xx, p99 normal.

A minute later: **`OutboxBacklogGrowing`** alert fires.

**Before the details: where do you look first, and why is "the API is healthy" a clue, not a comfort?**

---

## What you can see (signals)

<details>
<summary>Signals available</summary>

- order-service: 201s normal, readiness UP, no 5xx, latency normal. Orders exist in the DB.
- `outbox_unpublished` gauge: **climbing steeply** (hundreds and rising).
- notification-service and payment-service: idle — their consume rate dropped to zero ~8 min ago; both pods healthy.
- Kafka: the broker pod shows `CrashLoopBackOff` / not Ready. `kafka-topics --list` times out.
- order-service logs: repeating `could not publish outbox event … will retry: … Broker may not be available`.
- No app deploy recently; a cluster node was drained ~8 min ago.
</details>

---

## Your move

1. The write path returns 201 but downstream is silent. Given how ShopFlow persists events, **why is the order safe even though nothing downstream ran?** What exactly is stuck?
2. Which single gauge tells you "the publish leg is down, not the write leg," and why does it climb while the API stays green?
3. Two hypotheses: Kafka is down, **or** the outbox relay thread is stuck. What signal separates them?
4. Once you fix the broker, do you need to replay anything or re-POST the orders? Why / why not?
5. If this had been "the relay thread is stuck" instead, what would be different in the signals, and what's the fix?

---

## Walkthrough

<details>
<summary>Diagnostic tree → root cause → fix</summary>

**Why the API is green is the clue.** ShopFlow uses a **transactional outbox**: `POST /orders` writes the order row **and** an `outbox_event` row in one Postgres transaction, returns 201, and a separate `OutboxRelay` publishes to Kafka asynchronously. So the API can be perfectly healthy (DB is fine) while the **publish-to-Kafka leg** is completely dead. "Healthy API + silent downstream" is the *signature* of an async-publish failure, not a reason to relax.

**One gauge nails it.** `outbox_unpublished` (count of `outbox_event` rows with `published_at IS NULL`) is climbing → rows are being written but not published. That's the publish leg down. The API stays green because publishing isn't on the request path — it's the whole point of the outbox.

**Differential (Q3): Kafka down vs relay stuck.**
- **Kafka down:** relay logs show `could not publish … Broker may not be available`; `kafka-topics --list` fails; the Kafka pod is not Ready. *(All true here.)*
- **Relay thread stuck/dead:** Kafka is healthy and reachable, but `outbox_unpublished` still climbs and the relay logs show **nothing** (no publish attempts at all) — the scheduler stopped. You'd confirm by checking whether the relay is even running (thread dump / last "published N" log).

Here the signals point squarely at **Kafka down** (CrashLoop broker, `kafka-topics` times out, relay *is* trying and failing). The node drain 8 min ago ("what changed") took the single-node broker with it.

**Root cause:** the Kafka broker went down (node drained), so the outbox relay can't publish; events pile up as unpublished rows. Orders are **safe and durable** — they're committed in Postgres with their outbox rows intact.

**Fix:**
1. Bring Kafka back (reschedule/replace the broker pod; in prod this is where RF≥3 / MSK multi-AZ would have avoided the outage — a single-node broker is the real design smell).
2. The relay resumes on its next 1s tick and **drains the backlog automatically** — it publishes every `published_at IS NULL` row in order.

**Replay? (Q4) No.** Nothing to re-POST, nothing to manually replay. The events were never lost — they're sitting in the outbox table precisely so a broker outage is survivable. Delivery is **at-least-once**, so when the relay drains, consumers may see each event (and since they're idempotent via `processed_event`/PK, duplicates are no-ops). The backlog drains, emails send, charges happen, `outbox_unpublished` returns to 0.

**If it had been a stuck relay (Q5):** Kafka would be healthy, relay logs silent (no attempts), `outbox_unpublished` still climbing. Fix = restart the order-service pod(s) to restart the scheduler, and investigate why the `@Scheduled` task died (an unhandled error in a prior run, a blocked thread). Same backlog, different culprit — which is why you separate "can't publish" (Kafka) from "not even trying" (relay).
</details>

---

## The pattern

- **Healthy sync path + dead async effects = look at the queue/relay between them**, not at the API. The 201 only proves the first DB write committed.
- **One backlog gauge is worth a thousand guesses.** An `unpublished`/`lag`/`queue depth` metric that climbs localizes the failure to the publish/consume leg instantly.
- **"Can't do X" vs "not trying X"** are different root causes with different fixes — the relay failing to publish (Kafka down) vs the relay not running (scheduler dead). Separate them with "is it even attempting?"
- **Durable-by-design means no heroics.** When the pattern (outbox) is there, recovery is "fix the dependency, the backlog drains" — not re-processing or data recovery.

> Grounded in: `shopflow-outbox/OutboxRelay` (relay + `outbox_unpublished` gauge), `OrderService.saveWithEvent` (order + outbox in one txn). Alert `OutboxBacklogGrowing`.
</content>
