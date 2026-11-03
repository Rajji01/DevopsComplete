# 31 · JPA / Hibernate at scale — NOTES

> Pair: [`comprehension.md`](comprehension.md). ORM ka chhupa SQL/memory cost jo sirf prod data pe bite karta. ShopFlow 4 ko sahi dodge karta, 1 (OFFSET) known-limitation.

---

## KYA (one breath)
ORM easy cheez easy banata, **mehnga SQL/memory chhupa deta**. 5 traps: **OSIV** (conn held), **N+1** (lazy loop), **OFFSET deep-page** (skip-cost+count), **unbounded findAll** (OOM), **big-loop first-level cache**. Senior move: **generated SQL dekho**.

## EASY WAY
> **"Java innocent dikhta, SQL O(n) karta. Dev chhota data = sab fast = trap chhupa. Dekho: query-count rows ke saath badhe (N+1), OFFSET skip kare, findAll bound na ho."**

## 5 TRAPS (+ ShopFlow + detect)
| # | Trap | Chhupa cost | ShopFlow | Detect |
|---|------|-------------|----------|--------|
| 1 | **OSIV** (`open-in-view` default **true**) | session+**connection** poore request held; lazy in view | **`false`** (x4, sab service) | conn held time; pool pressure (D16) |
| 2 | **N+1** | lazy `@OneToMany` loop → 1+N queries | **flat entities** (koi relation) → none | **query-count ∝ rows** (SQL log) |
| 3 | **OFFSET pagination** | `OFFSET n` n rows scan+discard + `count(*)` | **OFFSET `Page`** (current limitation) | `EXPLAIN` deep OFFSET; HLD→keyset |
| 4 | **Unbounded `findAll()`** | saari rows memory+hydrate | products `findAll()` (small catalog) | big result → OOM/slow |
| 5 | **Big-loop 1st-level cache** | lakhs entities ek context = heap | relay **bounded 100** + `clear/flush` | heap grow in long txn |

## DIAGRAM — OFFSET vs keyset (deep page)
```
 OFFSET (page 100000, size 20):
   ... ORDER BY created_at DESC LIMIT 20 OFFSET 2000000
   DB: walk/count past 2,000,000 rows -> discard -> return 20   (O(n) skip)  + count(*) over all
 KEYSET / seek:
   ... WHERE created_at < :lastSeen ORDER BY created_at DESC LIMIT 20
   DB: index SEEK to :lastSeen -> read next 20   (O(log n + 20))   (no skip, no count)
   index idx_orders_customer_created (customer_id, created_at desc) = sorted -> seek works
   NOTE: index ordering helps keyset; OFFSET still must traverse skipped entries.
```

## DIAGRAM — N+1 (kyun prod-killer)
```
 list 10,000 orders:
   SELECT * FROM orders ...              <- 1 query
   for each order: order.getLines()      <- +10,000 lazy queries  = N+1
 dev (10 rows) = 11 queries (invisible); prod (10k) = 10,001 queries (DB melts)
 fix: join fetch / @EntityGraph (1 query) OR DTO projection (select only what view needs)
 ShopFlow: no @OneToMany -> nothing to lazily load -> no N+1
```

## KYU (key reasons)
- **OSIV off → short conn hold:** service method khatam = connection wapas pool mein; OSIV on = render tak held → pool jaldi khatam (D16 worse). ShopFlow off.
- **Keyset fast kyun:** index sorted, cursor pe **seek**; OFFSET index use karke bhi skipped entries **traverse** karta (skip free nahi).
- **N+1 avoid:** flat entity = koi relation = koi +N; warna `join fetch`/`@EntityGraph`/DTO.
- **findAll bound:** skew — p50 customer chhota, whale (B2B) laakhon → ek call OOM. Hamesha paginate.
- **Big loop:** persistence context har entity rakhta (1st-level cache) → flush+clear batches; `jdbc.batch_size` inserts ke liye.

## TRADE-OFF (OFFSET vs keyset — kab kya)
| | OFFSET | Keyset |
|---|--------|--------|
| random page jump | ✅ "page 500" | ✖ sirf next/prev cursor |
| exact total count | ✅ (`count(*)`, mehnga) | ✖ (usually drop) |
| deep-page speed | ✖ O(n) skip | ✅ O(log n+size) seek |
| best for | numbered pages + totals | **infinite scroll / load-more** (order history) |

## GOTCHAS (interview)
- **OSIV default ON** (Spring Boot) — turn off for services; connection-hygiene.
- **N+1 = query-count ∝ rows** — signature; `join fetch`/DTO, not lazy-in-loop.
- **OFFSET deep-page = scan+discard + count(*)** — keyset seek.
- **`findAll()` / unbounded collection = latent OOM** (whale customer).
- **Big loop → flush/clear + batch_size**; stateless session / streaming for huge.
- **OSIV off → `LazyInitializationException` if view touches unfetched** → fetch in service (DTO/join fetch). Good: forces explicit queries.

## UNDERSTANDING CHECK (khud bol)
1. OSIV on kya held rakhta, D16 pe asar? → *session+connection poore request; conn jaldi khatam, pool exhaust sooner.*
2. Index hai phir bhi OFFSET 1M slow kyun? → *index order deta, par skipped 1M entries traverse karni padti; seek nahi.*
3. ShopFlow mein N+1 kyun nahi? → *flat entities, koi `@OneToMany` nahi, load karne ko relation hi nahi.*
4. `findByCustomerId` bina Pageable — kab tootta? → *B2B whale customer (laakhon orders) → OOM/timeout; data-skew.*
5. Nightly job lakhs CONFIRMED load → OOM; fix? → *batch + flush/clear per chunk + index; unbounded context mat banao.*

## INTERVIEW LINE
> *"ORM mehnga SQL chhupa deta. ShopFlow `open-in-view: false` (connection request bhar held nahi — pool-friendly, D16 se juda), entities **flat** (koi `@OneToMany` → N+1 by design nahi). List abhi **OFFSET `Page`** — deep page pe `OFFSET` skipped rows scan+discard + `count(*)` mehnga; 50M pe HLD **keyset/seek** (index seek, no skip). `findAll()` unbounded = whale-customer OOM → hamesha paginate. Bade loop mein flush/clear batches (relay 100-bounded). Hamesha **generated SQL dekho** — query-count rows ke saath badhe to N+1."*

---
**Grounded:** `open-in-view: false` (4 services), no `@OneToMany`/`@ManyToOne` (flat `Order`), `OrderController`/`OrderService.list` OFFSET `Page` (`PageRequest.of`, `findByCustomerId(... Pageable)`), `ProductController.findAll()`, relay bounded-100 + `@Modifying(clearAutomatically, flushAutomatically)`, `INDEXING-HIBERNATE.md`, HLD §5 keyset. `[TRAP]` = N+1/unbounded (if you did it); OFFSET = real current limitation.
