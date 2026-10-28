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

## Interview mein ye bolo
*"Overselling lost-update race hai. Main check aur decrement ek atomic SQL mein karta hoon — `update … set quantity = quantity - :qty where quantity >= :qty`; DB row lock concurrent buyers ko serialise karta aur WHERE stock dobara check karta, rows-affected 0 matlab out-of-stock. reserve idempotent hai (orderRef pe). Front pe 50/s/pod rate limiter (429). Guarantee ko k6 test `orders_confirmed == 3` threshold se CI mein lock kiya — 300 buyers, 3 units. Stock decision kabhi cache se nahi."*

> Related: [`../03-idempotency-three-ways/`](../03-idempotency-three-ways/) (reserve idempotent), [`../07-k8s-readiness-cascade/`](../07-k8s-readiness-cascade/) (per-pod scaling).
</content>
