# 02 · Inbox pattern + Reconciliation — ledger ko saga se jodna (kya / kyu / kaise)

> Increment 2 + 3 ka teaching note, **asli code** ke saath (`ledger-service/.../saga/` aur `.../recon/`). Tracker: [`README.md`](README.md). Outbox/CDC/idempotent-consumer base: [`../comprehension/01-outbox-dual-write/LIVE-WALKTHROUGH.md`](../comprehension/01-outbox-dual-write/LIVE-WALKTHROUGH.md).

Do cheezein ek saath: (A) payment event aaya → ledger mein **double-entry** post karo, exactly once — **inbox pattern**. (B) Roz ledger ko bahar ki **settlement statement** se milao — **reconciliation**.

---

## A. Inbox pattern — "idempotent consumer", par bina alag inbox table ke

### Kya / kyu
Outbox ne producer side solve kiya: DB commit + event publish atomic (dual-write problem). **Inbox** consumer side hai: Kafka **at-least-once** deliver karta hai, toh ek hi event do baar aa sakta hai (rebalance, retry, redelivery). Agar consumer har delivery pe paisa post kar de → **double post**. Inbox = "ye event pehle process ho chuka? to skip."

Classic inbox = ek `processed_event(event_id PK)` table: `existsById` → skip, warna process + `save(eventId)` **ek transaction mein**.

### Asli seekh (yahi interview differentiator)
ShopFlow mein maine `processed_event` table **nahi** banayi — kyunki zaroorat hi nahi thi:

> `journal_entry.idempotency_key` already **UNIQUE** hai, aur usmein source `eventId` jaata hai. Toh ledger ka apna `record()` **khud hi inbox hai** — redelivered event ko wahi unique key dobara post hone se rokti hai.

```java
// LedgerPostingListener.onPaymentEvent  (NOT @Transactional — jaan-bujh ke, neeche dekh)
switch (event.type()) {
    case "PaymentCaptured" -> post(event, DEBIT);   // cash aaya:  DR CASH / CR SALES_REVENUE
    case "PaymentRefunded" -> post(event, CREDIT);  // cash gaya:  ulta
    default -> { }  // PaymentFailed/RefundFailed: paisa move hi nahi hua → post kuch nahi
}

// post(...) andar:
ledger.record(event.eventId().toString(),  // <-- eventId = idempotencyKey = inbox guard
              event.orderRef(),             // reference (recon isi se group karta)
              "INR", List.of(DR CASH amount, CR SALES_REVENUE amount));
```
`record()` andar: `findByIdempotencyKey(eventId)` → mila to wahi return (no double post); nahi mila to insert. Redelivery → unique key → exactly once.

**Kab alag inbox table chahiye?** Jab consumer ka effect ek **keyed DB write na ho** — jaise email bhejna (notification-service): wahan `processed_event` sahi hai, kyunki email ko "unique key se dedupe" nahi kar sakte. Jab business write mein hi natural idempotency key hai (ledger entry), **wahi write inbox ban jaata** — ek extra table ko sync mein rakhne ki zaroorat nahi.

### @Transactional kyu NAHI listener pe (Walk 99 trap)
`record()` jaan-bujh ke non-transactional hai: entry+postings `TransactionTemplate` mein likhta, aur unique-key race recovery (`catch DataIntegrityViolationException` → winner re-fetch) us transaction ke **bahar**. Agar listener ko `@Transactional` bana dete, to `record()` ka write outer tx mein enlist ho jaata → concurrent-duplicate ka constraint violation **outer tx ko rollback-only** mark kar deta → recovery re-fetch commit pe `UnexpectedRollbackException` se mar jaata. Listener non-transactional rakha → recovery bachi. (Isliye alag inbox table hota to usko @Transactional chahiye hota = wahi trap wapas.)

### Double-entry refresher
```
PaymentCaptured 749.99  →  DR CASH 74999        (ASSET ↑ debit se)
                           CR SALES_REVENUE 74999 (REVENUE ↑ credit se)
signed sum = +74999 + (-74999) = 0  → balanced. Har entry balance → sabhi balances ka sum hamesha 0.
```
`Money.fromMajor(749.99, "INR")` = 74999 paise, **HALF_EVEN (banker's rounding)** — rounding decision ek baar, boundary pe, jahan decimal andar aata. Kabhi float nahi.

---

## B. Reconciliation — "books tie out karte hain?"

### Kya / kyu
Ledger humari **apni** kahani hai (humne jo events consume kiye). Settlement statement = **bahar ki sachchai** (PSP/bank ne asal mein kya settle kiya). Dono ko milana = reconciliation. Post-trade / payments ki roti-roti: *"har paisa jo move hua, dono taraf dikhe."*

Mismatch = paisa **kho nahi gaya, par unexplained** — aur unexplained hi woh jagah hai jahan asli loss chhupta hai. Isliye ye ek **same-day control** hai.

### Kaise (asli code)
```java
// ReconciliationService.reconcile(statement):
Map<String,Long> ledgerNet = ledgerNetCashByReference();  // CASH postings ka signed sum, ref-wise
Map<String,Long> statementNet = statement ka ref-wise sum;
// net-zero ref (capture + poora refund) dono taraf se drop — settle karne ko kuch nahi

for (ref : union(ledgerNet, statementNet)) {
    dono mein hai, equal      → matched++
    dono mein hai, alag        → AMOUNT_MISMATCH
    sirf statement mein         → MISSING_IN_LEDGER     (PSP ne settle kiya, humne book nahi kiya)
    sirf ledger mein            → MISSING_IN_STATEMENT   (humne book kiya, statement mein nahi)
}
```
Teen **exception buckets** — har ek alag action: (1) MISSING_IN_LEDGER → shayad event DLT pe gira, replay karo; (2) MISSING_IN_STATEMENT → timing (agle statement pe aayega) ya humne galat book kiya; (3) MISMATCH → partial/duplicate/galat amount.

### Metric + alert + ACL
- Gauges: `ledger_recon_matched / unmatched / mismatch` (last run ka result).
- Alert `LedgerReconExceptions` (ticket): `max(unmatched)+max(mismatch) > 0` → runbook `docs/runbooks/LedgerReconExceptions.md`. Raw-gauge check (rate window nahi), `max()` across pods.
- `SettlementStatementSource` = **Anti-Corruption Layer** seam: recon domain `SettlementLine` bolta; adapter (CSV/S3/PSP API/MT940) bahar ki shape ko translate karta — bahar ka format ledger mein leak nahi hota. Default empty source → scheduler **skip** karta (fresh deploy pe jhoothe MISSING_IN_STATEMENT storm nahi).

### Ledger balance DERIVED hai (stored nahi)
`ledgerNetCashByReference()` CASH ke postings ka signed sum leta — koi stored balance column nahi jo drift kare. "Balance = postings ka sum" property reconciliation ki neev hai: audit + recon dono isi se possible.

---

## Interview mein ye bol (3 lines)
1. **Inbox:** "At-least-once delivery pe consumer idempotent hona chahiye. Mere ledger mein alag inbox table nahi — journal entry ka unique `idempotency_key` (= source eventId) hi inbox hai, double-post rokta hai. Alag table sirf tab jab effect keyed DB write na ho, jaise email."
2. **Double-entry + money:** "Har payment event = balanced DR/CR, immutable postings, balance derived (stored nahi). Paise minor units (paise) long mein, HALF_EVEN rounding boundary pe — kabhi float nahi."
3. **Reconciliation:** "Internal ledger vs external settlement statement, per reference, teen exception buckets. Mismatch = unexplained, not lost — same-day control, alert + runbook. External feed ACL seam ke peeche, taaki PSP format ledger mein leak na ho."

> Next: increment 4 — payment state machine (auth ≠ capture ≠ settlement, illegal transitions blocked) + FX; increment 5 — ledger ko k8s/CI deploy pipeline mein.
