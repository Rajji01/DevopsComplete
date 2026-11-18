# LedgerReconExceptions (ticket)

**Fires when:** the daily ledger reconciliation (`ReconciliationService`, increment 3; 04:00 UTC) found one or more business references where the ledger's net cash movement and the external settlement statement (PSP/bank) **do not tie out** — `ledger_recon_unmatched + ledger_recon_mismatch > 0`.

> Not a page: no money is lost and nothing is customer-facing-down. But every exception is money that moved on one side without the other — **unexplained, not reconciled**. In payments/post-trade this is the control that must be cleared the same day; an unworked recon break is how a real loss hides.

## The three buckets (each is a different action)

| Bucket (`ReconciliationReport.Bucket`) | Meaning | First action |
|---|---|---|
| `MISSING_IN_LEDGER` | the statement shows a settlement the ledger never booked (`ledgerMinor=0`) | did a `PaymentCaptured`/`PaymentRefunded` event never reach ledger-service? Check `kafka_dead_lettered_total` / `payments.events.DLT`, and the ledger consumer lag. Replay the event → the entry posts. |
| `MISSING_IN_STATEMENT` | the ledger booked an entry the statement does not show (`statementMinor=0`) | timing (the settlement lands on a later statement — expected for same-day captures) **or** we booked something the PSP never settled. Re-check on the next statement before escalating. |
| `AMOUNT_MISMATCH` | both sides have the reference but the net amounts differ | a partial capture/refund, a duplicate, or a wrong amount. Compare the ledger entries for the reference against the statement line. |

## Investigate

```sh
# the warn log from the run lists every discrepancy with ledger vs statement minor units
kn logs deploy/ledger-service --since=25h | grep -iE "reconciliation|recon " | tail -40

# the ledger's side for a reference (orderRef / trade id): its entries and their net cash
#   GET /api/v1/journal-entries?reference=<ref>   (ledger-service REST)
# the statement side: the PSP/bank line for the same reference (the settlement feed / CSV)
```

## Resolve

1. **`MISSING_IN_LEDGER` from a dropped event** → replay the payment event from `payments.events.DLT` (consumers are idempotent — the journal entry's unique `idempotency_key` dedupes, so replay is safe). The entry posts, next run ties out.
2. **`MISSING_IN_STATEMENT` that is just timing** → no action; it matches once the settlement appears on the next statement. If it persists across statements, treat as a real break (we booked an unsettled movement — investigate the capture).
3. **`AMOUNT_MISMATCH`** → find which side is wrong. If the ledger is wrong, **correct with a new reversing/adjusting entry, never an edit** (postings are append-only); if the statement is wrong, raise it with the PSP.
4. If an exception is an accepted known difference (e.g. a fee booked elsewhere), record it so it is explained, not silently ignored.

**Verify:** after the fix, the next reconciliation run (or a manual trigger) drives `ledger_recon_unmatched` and `ledger_recon_mismatch` back to 0 and the alert resolves. Post-mortem if the cause was lost events (why did the DLT fill?) — tune the consumer/retry budget, not the alert threshold.

**Design note:** the default `SettlementStatementSource` is empty (no PSP feed wired yet), and the scheduler **skips** when the statement is empty — so this alert cannot fire on a fresh deploy from "no feed". It fires only when a real statement is being reconciled.
