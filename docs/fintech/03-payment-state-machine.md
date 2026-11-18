# 03 · Payment state machine — auth ≠ capture, aur illegal transition impossible (kya / kyu / kaise)

> Increment 4 slice 1 (Walk 114). payment-service pehle poora card flow ek `gateway.charge()` mein nipta deta tha → status seedha `CAPTURED`/`FAILED`. Ab `Payment` ek **guarded lifecycle state machine** hai aur gateway ke do asli steps hain: **authorize** (issuer ka funds check — decline yahin hota hai) aur **capture** (paisa yahan move hota hai). Code: `shopflow/payment-service/.../Payment.java`, `OrderConfirmedListener.java`, `PaymentGateway.java`.

---

## A. Auth ≠ capture — ek charge do calls hai

### Kya / kyu
Real card payment ek step nahi hai:
- **Authorize** = card pe **hold** lagao. Issuer check karta hai funds/limit hai ya nahi. **Decline yahin hota hai.** Paisa abhi move NAHI hua — sirf reserve hua.
- **Capture** = authorize kiye hue hold ka paisa **actually le lo**. Valid hold ko capture karna almost hamesha succeed karta hai.

shopflow authorize ke turant baad capture karta hai (immediate capture), par dono ko alag rakhne se:
1. **declined auth** (limit cross) aur **failed capture** (hold expire ho gaya) do alag outcomes ban jaate hain — ek `charge()` mein ye farq mar jaata tha.
2. **delayed capture** (authorize ab, capture jab item dispatch ho — Amazon/airline model) ek config change ban jaata hai, poora reshape nahi.

### Asli code (`PaymentGateway`)
```java
Result authorize(String orderRef, BigDecimal amount); // hold funds — decline point
Result capture(String orderRef, BigDecimal amount);   // take the held funds
Result refund(String orderRef, BigDecimal amount);
```
`FakePaymentGateway` mein card-limit ka decline ab `authorize` mein hai (wahi to issuer ka check hai), aur `capture` hamesha succeed karta hai. Pehle ye logic `charge` mein mila-jula tha.

Listener ab: `authorize` → agar approved to `AUTHORIZED` row persist → `capture` → `CAPTURED`, aur wahi purana `PaymentCaptured` event emit (order saga ko koi farq nahi padta — contract same). Declined auth → `FAILED` + `PaymentFailed`, bilkul pehle jaisa.

---

## B. Guarded state machine — illegal transition "unrepresentable"

### Kya / kyu
Pehle invariant "sirf CAPTURED payment refund ho sakti hai" ek `if` ke roop mein **listener** mein tha, entity mein nahi. Matlab koi doosra caller (ya out-of-order/replayed saga event) `markRefunded()` ko ek `FAILED` payment pe bula de to woh **chup-chaap corrupt** ho jaati. Invariant call-site pe tha, to sirf **us ek call-site** pe enforce hota tha.

### Asli seekh (yahi interview differentiator)
Legal graph ko entity ke andar daal diya, aur **har mutation ek hi `transitionTo` se jaata hai**:
```java
public enum Status {
    AUTHORIZED, CAPTURED, FAILED, REFUNDED;
    Set<Status> next() {
        return switch (this) {
            case AUTHORIZED      -> EnumSet.of(CAPTURED, FAILED); // capture, ya capture/void fail
            case CAPTURED        -> EnumSet.of(REFUNDED);         // paisa gaya → sirf wapas de sakte
            case FAILED, REFUNDED -> NONE;                         // terminal
        };
    }
    boolean canMoveTo(Status t) { return next().contains(t); }
}

private void transitionTo(Status target) {
    if (!status.canMoveTo(target)) throw new IllegalPaymentTransitionException(status, target);
    this.status = target;
}
```
`markRefunded()` (unguarded setter) **hata diya**. Ab sirf `capture()` / `failCapture(reason)` / `refund()` — teeno guarded transition. Ek replayed `PaymentRefunded` jo `FAILED` payment pe aaye → **throw**, silent rewrite nahi.

### Kyu entity mein, listener mein nahi
> **Call-site pe laga invariant sirf us call-site pe chalta hai. Entity ke andar daal do to har caller ke liye, hamesha chalta hai.** Tell yahi tha: refund-guard (`if status != CAPTURED`) aur capture-decision dono business code mein "aage kaun sa state legal hai" dobara-dobara derive kar rahe the, jabki entity ek aisa setter expose kar rahi thi jo in sab ko ignore karta tha.

Listener ab bhi `if (status != CAPTURED) return;` rakhta hai — par ab woh **expected idempotent cases** (duplicate `OrderRefundPending` jab already REFUNDED) ko graceful no-op karne ke liye hai; entity ka guard **programmer-error / out-of-order** ke liye backstop hai. Do layer, dono ka apna kaam.

### INITIATED/SETTLED kyu nahi (honest scoping)
Ledger ke `JournalEntryStatus` mein poora `INITIATED→AUTHORIZED→CAPTURED→SETTLED→RECONCILED` hai, par payment row pe maine sirf wahi states rakhe **jinke paas asli driver hai**:
- **INITIATED** persist nahi hota — auth synchronous hai, to pehli row jo likhti hai woh already `AUTHORIZED` (approved) ya `FAILED` (declined) hoti hai.
- **SETTLED / RECONCILED** ledger side pe rehte hain (money-movement record), is operational row pe nahi. SETTLED ko reconciliation-match se drive karna = increment 4b. Fake state nahi banaya — warna woh theatre hota.

---

## OSTTRA / post-trade defense line
> "Maine payment ko ek **guarded state machine** banaya: **authorize** (issuer funds check, decline yahin) aur **capture** (settlement of the hold) do alag gateway calls + do alag states. Har mutation `transitionTo` se jaata hai, to ek out-of-order ya replayed event ek terminal state (FAILED/REFUNDED) ko dobara nahi likh sakta — throw karta hai. Trade lifecycle mein yahi auth→capture→settlement ka separation hai; illegal transition block karna = state corruption ko unrepresentable banana. Delayed capture ab one-line change hai."

## Interview mein ye bol (3 lines)
1. **Auth ≠ capture:** ek charge do processor calls hai — authorize funds hold karta hai (decline yahin), capture paisa leta hai; alag rakhne se delayed capture aur sahi failure-attribution dono milte hain.
2. **Guarded transitions:** legal graph entity ke andar (`Status.next()`), har mutation `transitionTo` se — refund-a-FAILED / re-capture-a-REFUNDED jaisa illegal move throw karta hai, silently corrupt nahi karta.
3. **Invariant ki jagah:** call-site ka `if` sirf us call-site pe chalta hai; entity mein daal do to har caller ke liye hamesha — "we're careful" se "illegal is unrepresentable".

---
**Verify:** `cd shopflow && ./mvnw -q -pl payment-service test` → `PaymentStateMachineTest` (6 cases: happy path, AUTHORIZED→refund throws, failed-capture terminal, declined-auth terminal, re-capture throws, duplicate-refund throws) + embedded-Kafka tests jo unchanged outward behaviour (capture / card-limit decline / refund / declined-refund / redelivery dedup) assert karte hain. Schema change nahi — `status varchar(16)` `AUTHORIZED` ko fit karta hai.
