# 31 · JPA / Hibernate at scale: the ORM traps that bite only in prod — comprehension

> Companion notes: [`notes.md`](notes.md). Grounded in ShopFlow's real choices: `*/application.yml` **`open-in-view: false`** (every service), **flat entities** (no `@OneToMany`/`@ManyToOne` — `Order` stores `sku`/`quantity` as columns), `OrderController`/`OrderService.list` **OFFSET `Page` pagination** (`PageRequest.of(page, size, Sort DESC createdAt)` + `findByCustomerId(... Pageable)`), `ProductController.findAll()`, the `@Modifying(clearAutomatically, flushAutomatically)` stock query. See also [`../INDEXING-HIBERNATE.md`](../../INDEXING-HIBERNATE.md), [29 silent-data](../29-silent-data-corruption-heisenbugs/), [30 replication](../30-replication-multi-instance-db/).
> **Honesty:** ShopFlow avoids most of these by design — cases marked **[TRAP]** are "what if you didn't"; the **OFFSET pagination** one is a *real current* limitation the HLD already flags for 50M orders.

---

## Scenario (read cold)

> *A review of the data-access layer: "The ORM makes easy things easy and hides the expensive things. Which Hibernate defaults quietly cost you at scale, and which has ShopFlow already turned off?" Read once, then answer.*

Hibernate/JPA is productive because it hides SQL — which is exactly why its costs are invisible until prod load. Five traps live here; ShopFlow sidesteps four by deliberate choices, and carries one as a known limitation.

**(1) Open-Session-In-View (OSIV) — on by default, and a quiet tax.** Spring Boot leaves `spring.jpa.open-in-view = true` by default: the Hibernate session stays open for the **whole web request**, so lazy associations can be loaded in the view/serialization layer. The hidden cost: the request **holds a DB connection from the pool until the response is rendered**, and lazy loads fire **outside** the service/transaction where you can reason about them. ShopFlow sets **`open-in-view: false`** in every service (with the comment "no lazy loading in the web layer") — connections return to the pool as soon as the service method ends, which matters directly to the pool ceiling of topic 26.

**(2) N+1 queries — the classic ORM prod-killer.** If an entity has a lazy `@OneToMany` (an order with its lines), iterating a list of N orders and touching each one's lines fires **1 query for the orders + N queries for the lines** — fine for 10 rows in dev, a storm for 10,000 in prod. ShopFlow dodges it structurally: its entities are **flat/denormalized** — `Order` stores `sku` and `quantity` as plain columns, **no relationship to load** — so there is no N+1 to trigger. **[TRAP]** Add a lazy collection and loop over it without a `join fetch`/`@EntityGraph`, and you get N+1.

**(3) OFFSET pagination — the deep-page cliff.** ShopFlow's order list uses Spring Data `Page`: `PageRequest.of(page, size, Sort.by(DESC, "createdAt"))` → SQL `... ORDER BY created_at DESC LIMIT :size OFFSET :page*:size`, **plus a `count(*)`** for the total. Two costs at scale: (a) a deep page (`page = 100000`) makes Postgres **scan and discard** all the skipped rows before returning yours — `OFFSET 2,000,000` reads two million rows to throw them away; (b) the `count(*)` over tens of millions is itself expensive on every page. In dev (few thousand rows) both are instant. This is a **real** ShopFlow limitation — the HLD flags it: for "order history, 50M orders," switch to **keyset / seek pagination** (`WHERE created_at < :lastSeen ORDER BY created_at DESC LIMIT :size`), which uses the `idx_orders_customer_created` index to **jump** instead of skip, and drop the exact count.

**(4) Unbounded `findAll()` — the query with no `LIMIT`.** `ProductController` does `findAll()` over the whole product table — correct while the catalog is small, but `findAll()` with no pagination is a **[TRAP]** on any table that can grow: it loads **every** row into memory (and hydrates every entity), so a table that quietly reaches millions turns one endpoint into an OOM / multi-second scan. The fix is to **always bound** a list query (paginate, or filter) — never `findAll()` on a table without a known small ceiling.

**(5) The first-level cache in a big loop.** Inside one transaction, Hibernate keeps every entity it touches in the **persistence context** (first-level cache). A loop that loads/updates **hundreds of thousands** of entities in one transaction accumulates them all in memory → ballooning heap and slowing dirty-checking. The pattern is **flush + clear in batches** (and `hibernate.jdbc.batch_size` for batched inserts/updates). ShopFlow's heaviest loop — the outbox relay — processes a **bounded batch of 100** per run (not the whole table) and its `@Modifying` stock query sets **`clearAutomatically`/`flushAutomatically`** so the context doesn't go stale; it never loads an unbounded set into one context.

The thread: **the ORM's convenience hides a SQL and a memory cost, and both scale with data you don't have in dev.** The senior move is to know which default is expensive (OSIV, lazy N+1, OFFSET, unbounded `findAll`, big-loop context) and to **see the generated SQL** — because the Java reads innocently while the SQL does something O(n) you didn't ask for.

---

## Questions

### A — Literal
1. What does `open-in-view: true` keep open for the whole request, and what resource does that pin? What has ShopFlow set it to?
2. Spell out the two SQL costs of `Page`/OFFSET pagination at a deep page, and the keyset alternative.
3. Why does ShopFlow have **no** N+1 problem today, structurally?

### B — Inference
4. OSIV being **off** interacts directly with the connection-pool ceiling of topic 26. Explain the link — how would OSIV **on** make the pool exhaustion of D16 *worse*?
5. The order list's `ORDER BY created_at DESC` is backed by `idx_orders_customer_created (customer_id, created_at desc)`. Why does that index make **keyset** pagination fast but **not** rescue deep **OFFSET** pagination?

### C — Trade-off
6. Keyset pagination is faster and O(1)-ish per page, but you give something up versus OFFSET. What are the two things OFFSET gives that keyset doesn't, and when do they actually matter?
7. OSIV off means you must fetch everything the response needs **inside** the service method (eager/`join fetch`/DTO projection). What's the cost of OSIV-off, and why is it still the right default for a service like this?

### D — Transfer / extension
8. **[TRAP]** A new "order detail" view adds a lazy `@OneToMany` list of status-history rows, and the list endpoint renders each order's latest history entry. In prod the list endpoint's query count explodes. Name the bug, the two standard fixes, and how you'd **detect** it from signals.
9. A nightly reconciliation loads **all** `CONFIRMED` orders (could be millions) in one `@Transactional` method and iterates them. It OOMs in prod, is fine in staging. Give the two independent causes (memory + query) and the fix pattern for each.

### E — Spot-the-flaw
10. To speed up the order-history page, a dev removes pagination and returns **all** of a customer's orders in one `findByCustomerId(customerId)` (no `Pageable`), "so the frontend can filter client-side." For most customers it's fine. For a few high-volume B2B customers the endpoint **times out and spikes memory**, intermittently. Explain exactly what went wrong, why it's customer-dependent, and the fix.

---

## Model answers

<details>
<summary>A — Literal</summary>

1. It keeps the **Hibernate session (persistence context) open for the entire web request** (through view rendering/serialization), which **pins a DB connection** from the pool for that whole duration (and allows lazy loads in the view). ShopFlow sets `open-in-view: **false**` in every service.
2. **(a)** `LIMIT :size OFFSET :n` makes the DB **read and discard** all `n` skipped rows before returning the page — O(n) at deep pages; **(b)** `Page` also issues a **`count(*)`** over the full (filtered) set for the total, expensive on huge tables. **Keyset/seek:** `WHERE (created_at) < :lastSeen ORDER BY created_at DESC LIMIT :size` — uses the index to **seek** to the cursor and read only `:size` rows, no skip, and you drop/approximate the total.
3. Its entities are **flat/denormalized** — `Order` holds `sku`, `quantity` etc. as **columns**, with **no `@OneToMany`/`@ManyToOne`** associations — so there's **nothing to lazily load per row**, hence no N+1 (there's no "+N" relationship to iterate).
</details>

<details>
<summary>B — Inference</summary>

4. With OSIV **on**, the connection is held **until the response is fully rendered**, not just until the service method returns — so each in-flight request occupies a pool connection for **longer** (serialization, slow clients, big payloads all extend the hold). Under load that means **more concurrent connections held for longer**, so the `pods × DB_POOL_SIZE` demand against `max_connections` is reached **sooner** — OSIV-on would make D16's connection exhaustion arrive at **lower** traffic. OSIV-off returns the connection the instant the service method (and its transaction) ends, keeping holds short. (ShopFlow off = pool-friendly.)
5. Keyset does `WHERE created_at < :lastSeen ORDER BY created_at DESC LIMIT :size`: the index is **sorted by `created_at desc`**, so the DB **seeks** directly to `:lastSeen` and reads the next `:size` entries — O(log n + size). Deep **OFFSET** still says "give me rows, skip the first 2,000,000": even using the same sorted index, the engine must **walk past** (count through) those 2,000,000 index entries before emitting yours — the index orders the rows but **can't skip** without traversing. So the index fixes *ordering/seeking*, not the *skip cost* OFFSET demands. (This is the whole reason to switch to keyset.)
</details>

<details>
<summary>C — Trade-off</summary>

6. OFFSET gives **(a) random page access** ("jump to page 500") and **(b) an exact total count / total pages**. Keyset only moves **relative to a cursor** (next/prev from the last seen row), so you can't jump to an arbitrary page number, and you typically **don't show an exact total**. They matter for a UI with numbered page links and "showing 1–20 of 4,312,118." For an **infinite-scroll / "load more"** feed (the common case, and what an order history usually is), neither matters — so keyset wins. Choose by the UI: numbered pages + exact totals → OFFSET (accept the cost, or cap depth); infinite scroll → keyset.
7. OSIV-off's cost: the service method must **explicitly fetch everything the response needs** (DTO projections, `join fetch`, or `@EntityGraph`) — a `LazyInitializationException` is thrown if the view touches an unfetched association, so you can't be lazy about it. That's **more upfront code**, but it's the right default because it (a) keeps **connection holds short** (pool-friendly, per Q4), (b) makes every query **explicit and reviewable** (no surprise lazy loads firing during JSON serialization), and (c) forces you to think about the query shape — turning hidden N+1s into visible, intentional fetches. The discipline is the point.
</details>

<details>
<summary>D — Transfer / extension</summary>

8. **[TRAP]** It's an **N+1**: 1 query loads the N orders, then rendering "latest history" triggers a **lazy load per order** → N more queries (and with OSIV off you'd instead get a `LazyInitializationException` in the view, which is its own tell). **Two fixes:** **(a)** `join fetch` / `@EntityGraph` to load orders **with** their history in **one** query; **(b)** a **DTO projection** that selects just the latest-history field via a single join/subquery (best for a list — don't hydrate full collections you only partly use). **Detect:** the per-request **query count** jumps with result size (visible in `hibernate.SQL` logs, `datasource` metrics, or an APM span count); `pg_stat_statements` shows the per-row query executed thousands of times; latency scales with page size. The signature is "**query count ∝ rows returned**."
9. **Memory cause:** loading **millions** of entities into **one persistence context** keeps them all in the first-level cache → heap blows (OOM); staging's small set fits. **Query cause:** `WHERE status='CONFIRMED'` may also be a big scan if not well-indexed, and returning all rows at once is a huge result set. **Fix pattern:** process in **bounded batches** — page/keyset through the CONFIRMED orders in chunks (e.g. 1,000), **`flush()` + `clear()`** the context between chunks (or use a stateless session / streaming), commit per batch, and ensure a usable index on the filter. Never load an unbounded set into one transaction/context. (Same shape as D19's "index + batching" for big writes.)
</details>

<details>
<summary>E — Spot-the-flaw</summary>

10. Removing `Pageable` makes `findByCustomerId` return **every** order for that customer in one query, loaded into memory and hydrated as entities. For most customers (few orders) it's trivially fine — which is why it passed review and works for the majority. For a few **high-volume B2B customers** with hundreds of thousands of orders, that one call **reads and materializes a massive result set**: a multi-second query + a heap spike (and with the frontend then holding it all, more memory). It's **customer-dependent** because the cost scales with **that customer's row count**, which is wildly skewed — the classic "works for p50, melts for the whale." The DB doesn't error; it just does O(rows-for-this-customer) work, so it looks fine until a big customer hits it. **Fix:** always **paginate** (keyset for an order history) — never return an unbounded per-entity collection to the client; if the frontend needs filtering, filter **server-side** with indexed predicates, not by shipping the whole table. (This is trap 4 "unbounded `findAll`" wearing a `findByCustomerId` costume.)
</details>

---

## Tod ke dekho — Local (ShopFlow ke choices + SQL dekho)

```bash
# (1) OSIV off everywhere (connection freed when the service method ends):
grep -rn "open-in-view" shopflow/*/src/main/resources/application.yml   # false (x4)

# (2) flat entities -> no relationships to N+1:
grep -rnE "@OneToMany|@ManyToOne|@OneToOne" shopflow --include=*.java || echo "none -> no N+1 by design"

# (3) OFFSET pagination today (the deep-page cliff at 50M) — see the generated SQL:
grep -n "PageRequest.of" shopflow/order-service/src/main/java/com/shopflow/order/order/OrderController.java
# turn on SQL logging and watch the LIMIT/OFFSET + count(*):
#   application.yml: logging.level.org.hibernate.SQL=DEBUG  (or spring.jpa.show-sql=true)
make up && TOKEN=$(make -s token)
curl -s "localhost:8081/api/v1/orders?page=0&size=20" -H "Authorization: Bearer $TOKEN" >/dev/null
docker compose -f shopflow/docker-compose.yml exec postgres psql -U orders -d orders -c \
  "explain analyze select * from orders where customer_id='alice' order by created_at desc limit 20 offset 1000000;"  # OFFSET scans+discards 1M

# (4) unbounded findAll on products (fine now, trap if catalog grows):
grep -n "findAll()" shopflow/inventory-service/src/main/java/com/shopflow/inventory/product/ProductController.java
```

**Proved:** ShopFlow is OSIV-off and relationship-free (no N+1), but its list uses **OFFSET `Page`** — `EXPLAIN` shows a deep `OFFSET` scanning+discarding rows, exactly the cliff the HLD flags; keyset would seek instead.

## Tod ke dekho — Online
Big-data pagination → [`ONLINE-LABS.md`](../ONLINE-LABS.md): seed millions of orders on managed PG, compare `LIMIT..OFFSET 1000000` vs keyset `WHERE created_at < :cursor` timings.

---

## Interview concepts is passage se
- **OSIV** (default on) — connection held for whole request; ShopFlow **off** (pool-friendly, links to D16).
- **N+1** — lazy association in a loop; avoid via `join fetch`/`@EntityGraph`/DTO; ShopFlow flat → none.
- **OFFSET vs keyset pagination** — skip-cost + `count(*)` vs seek; ShopFlow uses OFFSET (HLD flags keyset at 50M).
- **Unbounded `findAll()` / collection** — always bound a list query.
- **First-level cache in big loops** — flush/clear in batches; `jdbc.batch_size`; relay bounded-100.
- **See the generated SQL** — the Java hides an O(n) query; `hibernate.SQL`, `EXPLAIN`, `pg_stat_statements`.
- **Detection:** query-count ∝ rows (N+1), OFFSET scan in EXPLAIN, heap on big result sets.

**Interviewer's lens:** kya tu ORM ke **chhupe SQL/memory cost** jaanta — OSIV, N+1, OFFSET deep-page, unbounded findAll — aur **generated SQL dekhna** (query-count ∝ rows) jaanta; aur ki ShopFlow ne kya already sahi kiya (OSIV off, flat, bounded relay) vs kya limitation hai (OFFSET pagination).

> Interview line: *"ORM easy cheez easy banata, mehnga SQL chhupa deta. ShopFlow ne `open-in-view: false` rakha (connection request bhar held nahi — pool-friendly, D16 se juda), entities **flat** hain (koi `@OneToMany` nahi → N+1 by design nahi). List abhi **OFFSET `Page`** use karti — deep page pe `OFFSET` saari skipped rows scan+discard karta + `count(*)` mehnga; 50M orders pe HLD **keyset/seek** bolta (`WHERE created_at < :cursor`, index seek). `findAll()` bina bound = trap (bada customer pe OOM). Bade loop mein first-level cache flush/clear batches mein (relay 100-bounded). Aur hamesha **generated SQL dekho** — query-count rows ke saath badhe to N+1."*
