# 05 · Overselling & concurrency — concept notes

> Comprehension round: [`comprehension.md`](comprehension.md). Code: `ProductRepository.decrementStock`, `ReservationService.reserve`, `OrderController @RateLimiter`, `loadtest/flash-sale.js`.

## Kya (one line)
Check aur decrement ko **ek atomic SQL** mein karo (`update … set quantity = quantity - :qty where quantity >= :qty`) — DB row lock concurrent buyers ko serialise karta hai, isliye 3 stock pe 300 buyers mein bhi exactly 3 confirm hote hain, kabhi oversell nahi.

## Easy way (Hinglish analogy)
Ek hi kursi hai, 300 log baithna chahte hain. **Galat:** sab pehle dekhte hain "kursi khali hai?" (haan) phir sab baith jaate — do log ek kursi pe. **Sahi:** kursi pe ek **turnstile (lock)** laga do jo ek baar mein ek hi ko andar jaane de, aur andar jaate waqt hi check kare "khali hai?". Jo pehla gaya kursi le gaya; baaki ko turnstile pe hi "full" mil gaya. Turnstile = **DB row lock**; "khali hai?" check turnstile ke andar = `where quantity >= :qty`.

## Kyu (ye problem solve karta hai)
Naive `read → check-in-Java → write` mein do thread same purana value padh lete hain, dono check pass, dono likh dete — **lost update / oversell**. Check aur write ke beech ka gap hi dushman hai. Us gap ko mitao: dono ko **ek atomic, locked statement** bana do. Tab koi interleaving oversell nahi kar sakta.
Flash sale (high contention, single row) ke liye yahi sabse clean aur fast hai — na alag SELECT, na app lock, na retry-loop thrash.

## Kaise (step by step)
1. `ReservationService.reserve` (`@Transactional`): pehle `findByOrderRef` → mila to wahi return (**idempotent**, double-decrement nahi).
2. `productRepository.decrementStock(sku, qty)` → `update product set quantity = quantity - :qty where sku=:sku and quantity >= :qty`. Rows affected:
   - `1` → decrement hua → reservation row insert (usi txn; insert fail → stock rollback).
   - `0` → `existsBySku`? nahi → `ProductNotFoundException`; haan → `InsufficientStockException` (409) + rejected counter.
3. **Front defences:** `@RateLimiter("orders")` 50/s/pod → excess 429 (reads unlimited). `release` → `incrementStock` (idempotent compensation).
4. **Guarantee as test:** k6 `flash-sale.js` 300 buyers / 3 units → threshold `orders_confirmed: count==3`, `http_req_failed<0.01` (409/429 ko failure nahi ginta), browse p99<300ms, order p95<800ms. CI gate.

## Flow diagram (race vs atomic)
```
 NAIVE (oversell):
   T1 read q=3 ─┐
   T2 read q=3 ─┤ both see 3
   T1 check ok, write 2
   T2 check ok, write 2     ← 2 sold for 1 unit (LOST UPDATE)

 ATOMIC (ShopFlow):
   UPDATE product SET quantity=quantity-1 WHERE sku=? AND quantity>=1
        │   row lock on product row (DB serialises)
        ├─ rows=1 → reserved (reservation insert, same txn)
        └─ rows=0 → out of stock → 409   (no gap to exploit)
   300 buyers ─► serialised on the row ─► exactly 3 get rows=1, 297 get rows=0
```

## Defence-in-depth diagram
```
 300 buyers
     │
 @RateLimiter 50/s/pod ── excess ─► 429 (shed load, not an error)
     │ allowed
 order-service ──reserve(orderRef)──► inventory-service
                                        │ findByOrderRef? → existing (idempotent)
                                        │ atomic decrementStock (row lock + WHERE guard)
                                        └─ rows 0 → 409 ;  rows 1 → reserved
 k6 threshold orders_confirmed==count 3  ── gates CI (overselling can't merge)
```

## Code + dry run (samajh pakki)

**Asli code:**
```java
// ProductRepository — check + decrement EK atomic statement (row lock + WHERE re-check)
@Modifying(clearAutomatically = true, flushAutomatically = true)
@Query("update Product p set p.quantity = p.quantity - :qty " +
       "where p.sku = :sku and p.quantity >= :qty")
int decrementStock(@Param("sku") String sku, @Param("qty") int qty);  // rows: 1=hua, 0=out/unknown

// ReservationService.reserve — idempotent + atomic
@Transactional
public Reservation reserve(String orderRef, String sku, int quantity) {
    var existing = reservationRepository.findByOrderRef(orderRef);
    if (existing.isPresent()) return existing.get();          // idempotent: retry = wahi reservation
    if (productRepository.decrementStock(sku, quantity) == 0) {  // ATOMIC
        if (!productRepository.existsBySku(sku)) throw new ProductNotFoundException(sku);
        rejectedCounter.increment();
        throw new InsufficientStockException(sku, quantity);  // → 409
    }
    return reservationRepository.save(new Reservation(orderRef, sku, quantity));
}
```

**Dry run — do buyer, stock = 1, bilkul ek saath (`decrementStock("PS5",1)`):**
```
 DB row: PS5 quantity=1
 Buyer A  ─┐                         Buyer B ─┐
           ▼                                   ▼
 UPDATE ... set qty=qty-1 where qty>=1   UPDATE ... set qty=qty-1 where qty>=1
   │ DB row LOCK A ne liya (serialize)     │ A ke commit tak WAIT
   │ WHERE 1>=1 true → qty=0, rows=1        │
   │ COMMIT, lock chhoda                    ▼ ab chalta: WHERE 0>=1 FALSE → rows=0
   ▼                                        └→ InsufficientStockException → 409
 A: rows=1 → reservation save → CONFIRMED
 B: rows=0 → 409 REJECTED
 → quantity ab 0, KABHI -1 nahi. Exactly ek jeeta.
```
**Naive (galat) version ka dry run — oversell:**
```
 A read qty=1 ─┐  B read qty=1 ─┐   (dono purana 1 dekhte — gap!)
 A check 1>=1 ok, write 0        B check 1>=1 ok, write 0
 → DONO reserved, stock 1 ka 2 bika (LOST UPDATE)
```
**Seekh:** check aur write ke beech ka gap hi dushman; atomic UPDATE wo gap mitata, DB row-lock serialize karta.

## Alternate approaches (kab kaunsa)
| Approach | Kaise | Pros | Cons | Kab |
|---|---|---|---|---|
| **Read-check-write in app** | findBySku → if → save | simple | **oversells** under concurrency | kabhi nahi |
| **Atomic conditional UPDATE** *(ShopFlow)* | one statement, WHERE guard | fast, short lock, no race | single-row ops only | counters/stock decrement |
| **`SELECT … FOR UPDATE` + update** | pessimistic lock then write | flexible multi-step logic | 2 statements, longer lock | complex update needing reads |
| **`@Version` optimistic + retry** | version check, retry on conflict | good when conflicts rare | thrashes under high contention | low-contention entities |
| **Reserve via distributed lock (Redis)** | lock key per sku | cross-row/global coordination | extra infra, lock pitfalls | multi-resource atomicity |

## Understanding check
1. Naive code ka exact 2-thread interleaving jo oversell karta?
2. Mutual exclusion aata kahan se hai (app lock nahi to kya)?
3. k6 409/429 ko failure kyu nahi ginta?
4. `SELECT FOR UPDATE`/`@Version` ke muqable conditional UPDATE kab behtar?
5. Rate limiter "per pod" kya bound nahi karta, global ke liye kya?

## Test karo (UI + Postman + curl)
- **React UI:** `lab-console.html` → **Oversell** panel (concurrency). **Postman:** Lab 2 (Collection Runner ×50 iterations).
- **curl** (fresh stack, PS5 stock=3; 20 parallel reserves → exactly 3×201, baaki 409):
```bash
{ for i in $(seq 1 20); do
    curl -s -X POST http://localhost:8082/api/v1/reservations -H 'Content-Type: application/json' \
      -d "{\"orderRef\":\"r-$i\",\"sku\":\"PS5-SLIM\",\"quantity\":1}" -o /dev/null -w "%{http_code}\n" &
  done; wait; } | sort | uniq -c   # -> 3× 201, 17× 409, kabhi oversell nahi
# ya k6 flash-sale (300 buyers / 3 units):  make loadtest TOKEN=$TOKEN   -> orders_confirmed == 3
```

## Interview mein ye bolo
*"Overselling lost-update race hai. Main check aur decrement ek atomic SQL mein karta hoon — `update … set quantity = quantity - :qty where quantity >= :qty`; DB row lock concurrent buyers ko serialise karta aur WHERE stock dobara check karta, rows-affected 0 matlab out-of-stock. reserve idempotent hai (orderRef pe). Front pe 50/s/pod rate limiter (429). Guarantee ko k6 test `orders_confirmed == 3` threshold se CI mein lock kiya — 300 buyers, 3 units. Stock decision kabhi cache se nahi."*

> Related: [`../03-idempotency-three-ways/`](../03-idempotency-three-ways/) (reserve idempotent), [`../07-k8s-readiness-cascade/`](../07-k8s-readiness-cascade/) (per-pod scaling).
</content>
