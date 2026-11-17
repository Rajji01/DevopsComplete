# Debugging drill — the settlement consumer that double-settles (idempotency done wrong)

Symptom → root cause → fix, for a buggy idempotent Kafka consumer. The two implementations and the tests
live in `shopflow/ledger-service/.../settlement/`:
- `BrokenSettlementConsumer` — the anti-pattern (reproduces S1/S2/S3)
- `SettlementConsumer` — the fix
- `SettlementScenarioTest` — proves both, side by side (run: `./mvnw -pl ledger-service test`)

## The code under review
```java
@Component
class SettlementConsumer {
    private final Set<String> processed = new HashSet<>();
    private final LedgerService ledger;

    @KafkaListener(topics = "settlements", concurrency = "3")
    public void onMessage(SettlementEvent e) {
        if (processed.contains(e.getTradeId())) return;          // already done?
        ledger.debit(e.getAccount(), e.getAmount());
        ledger.credit(e.getCounterparty(), e.getAmount());
        processed.add(e.getTradeId());
    }
}
```
Prod complaints: **S1** some trades settle twice after deploy/restart; **S2** moving from 1→2 pods made
duplicates worse; **S3** sometimes a debit happened with no matching credit.

---

## 1. What is it trying to do?
Consume each `settlements` message once and record the money movement — debit the payer, credit the
counterparty — while skipping trades it has already handled (idempotent consumption, because Kafka delivers
**at-least-once**, so the same trade can arrive more than once).

## 2. Root cause of each — all different
- **S1 (restart → double settle).** `processed` is an in-memory `HashSet`. A deploy/restart kills the
  process and the set with it. Kafka then redelivers the records whose offsets weren't committed; the fresh,
  empty set says "never seen it", so the trade settles **again**. The dedup memory didn't survive a restart —
  the one event it had to survive.
- **S2 (more pods → more duplicates).** Each pod has its **own** `HashSet`. A trade handled on pod A is
  unknown to pod B, so a partition rebalance/move lets B re-settle it. Dedup state that is local to one
  process can't dedup across many.
- **S3 (debit without credit).** `debit` and `credit` are **two separate transactions** (no surrounding
  one). The debit commits; if the credit then fails (bad account, pod killed between the two, a DB blip),
  the money has left one side and never reached the other. Not atomic.

> One sentence under all three: *the "have I done this trade?" truth is kept in volatile, per-process memory,
> and the two money moves aren't atomic.*

## 3. The hidden danger in `concurrency = "3"` + `HashSet`
Three listener threads call `onMessage` at once, and **`HashSet` is not thread-safe**. Two failures:
1. **Corruption** — concurrent `add`/`contains` can leave the set in a broken state (lost entries; on an
   unlucky resize, historically even a spinning CPU). Dedup silently stops being reliable.
2. **Check-then-act race** — even with a *thread-safe* set, `contains` → settle → `add` is not atomic: two
   threads carrying the **same** `tradeId` can both read "not present" before either adds, and both settle.
   A set lookup can't make "check and claim" one atomic step.

## 4. The fix — where the truth belongs
**In the database, inside one transaction.** Replace the `HashSet` with a `processed_settlement` table keyed
by `trade_id`, and wrap the whole handler in `@Transactional`:
- durable → survives restarts (**S1**), shared by all pods (**S2**);
- debit + credit + the dedup insert commit **all-or-nothing** → no half-settle (**S3**);
- the `trade_id` **PRIMARY KEY** makes "settle once" a constraint the DB enforces — two racing threads end
  with one row, the loser's insert violates the key and its transaction (debit+credit included) rolls back
  (**the concurrency hole**). Offsets commit only after the handler returns, so a crash just redelivers,
  which is now safe.

That is exactly `SettlementConsumer` + `SettlementLedger` here — and the same inbox pattern the project
already uses in `PaymentEventListener` / `LedgerPostingListener` (existsById fast-path + a unique-keyed
write in one transaction).

```
broken: HashSet (RAM, per-pod) + debit|credit (2 txns)
fixed : processed_settlement(trade_id PK) + debit+credit+insert (1 txn)   <- truth is durable, shared, atomic
```

## 5. A precise prompt to ask an AI (not a vague one)
> "I have a Spring Kafka `@KafkaListener` (concurrency=3, multiple pods) that settles trades by debiting one
> account and crediting another, and dedupes on `tradeId` using an in-memory `HashSet`. In production some
> trades settle twice after a restart, duplicates worsened going from 1 to 2 pods, and occasionally a debit
> has no matching credit. Explain the distinct root cause of each symptom, the thread-safety problem with a
> HashSet under concurrency=3, and rewrite it so dedup is durable and shared and the debit+credit are atomic
> — using Postgres (a `processed_settlement` table keyed by trade_id) and `@Transactional`, Spring Boot 3 /
> Java 21. Show the migration, the entity, and the listener, and note how offset commit interacts with it."

Why it's good: it states the stack, the exact symptoms, the constraint (Postgres + `@Transactional`), and
asks for the specific artifacts — so the answer is concrete and checkable, not a generic "use idempotency".

---

## Hit it LIVE (real API, not just the unit tests)
`SettlementConsumer` is a real `@KafkaListener(topics = "settlements")` (it takes a String payload and parses
it, ledger's convention, so it works over a real broker), and `SettlementDemoController` exposes REST so you
can drive both consumers with curl. Run the stack (`docker compose up`, ledger on `:8085`) and:

```sh
B=localhost:8085/api/settlement
# accounts
curl -s -XPOST $B/accounts -H 'Content-Type: application/json' -d '{"account":"ACC","balanceMinor":1000}'
curl -s -XPOST $B/accounts -H 'Content-Type: application/json' -d '{"account":"CP","balanceMinor":0}'
TRADE='{"tradeId":"T1","account":"ACC","counterparty":"CP","amountMinor":100,"currency":"INR"}'

# --- BROKEN ---
curl -s -XPOST $B/broken -H 'Content-Type: application/json' -d "$TRADE"; curl -s $B/accounts/ACC   # 900
# S1: restart the pod (docker compose restart ledger-service), then fire the SAME trade again:
curl -s -XPOST $B/broken -H 'Content-Type: application/json' -d "$TRADE"; curl -s $B/accounts/ACC   # 800 — double!
# S3: a counterparty that doesn't exist -> 409, but ACC is already debited (half-settle):
curl -i -XPOST $B/broken -H 'Content-Type: application/json' \
     -d '{"tradeId":"T2","account":"ACC","counterparty":"GHOST","amountMinor":100,"currency":"INR"}'
curl -s $B/accounts/ACC   # dropped again, with nothing credited to GHOST

# --- FIXED (reset ACC to 1000 first) ---
curl -s -XPOST $B/fixed -H 'Content-Type: application/json' -d "$TRADE"; curl -s $B/accounts/ACC   # 900
# replay / restart: same trade again -> still 900 (DB remembers)
curl -s -XPOST $B/fixed -H 'Content-Type: application/json' -d "$TRADE"; curl -s $B/accounts/ACC   # 900 — once
# S3 on fixed -> 409 AND ACC unchanged (atomic rollback):
curl -i -XPOST $B/fixed -H 'Content-Type: application/json' \
     -d '{"tradeId":"T3","account":"ACC","counterparty":"GHOST","amountMinor":100,"currency":"INR"}'
curl -s $B/accounts/ACC   # unchanged

# --- real Kafka path (fixed listener consumes async) ---
curl -s -XPOST $B/publish -H 'Content-Type: application/json' -d "$TRADE"; sleep 1; curl -s $B/accounts/ACC
```
- **S1 (restart)** needs a real restart (`docker compose restart ledger-service`) — that's the whole point:
  the broken HashSet dies, the fixed DB row survives.
- **S2 (two pods)** needs two instances (scale the service / run a second container) and firing the same
  trade at each; broken double-settles, fixed dedupes via the shared DB.
