# shopflow-btree — a B+tree, the other half of "LSM vs B-tree"

The read-optimised, update-in-place index behind **PostgreSQL, MySQL/InnoDB, Oracle, SQL Server** — and the
deliberate counterpart to [`shopflow-lsm`](../shopflow-lsm/README.md)'s write-optimised log-structured
merge-tree. Together they answer the single most common storage-interview question. Pure Java, fully
unit-tested.

> Plain library like `shopflow-outbox` — never boots.

---

## Why high fanout
A balanced *binary* tree has height ~log₂(n): 30 random reads for a billion keys. A B+tree packs many keys
per node (**fanout** = children per node), so height is ~log_fanout(n) — 3–4 levels for a billion keys. Each
node is sized to a disk page / SSD block, so a lookup is a handful of page reads, not 30 random I/Os. Shallow
+ page-aligned is the whole point on storage, where a random I/O dominates cost.

```
                  [ 13 | 30 ]              internal nodes: separator keys only, they route
                 /     |     \
         [5|9]      [13|20]     [30|41]     leaves hold the values, chained left→right
           │           │           │
           └───────────┴───────────┴──────▶   range scan walks the chain
```

## B+tree vs plain B-tree
All values live in the **leaves**; internal nodes hold only separators. The leaves are a **linked list**, so
a range scan (`WHERE x BETWEEN …`, `ORDER BY`) finds the start leaf and walks the chain — cheap ordered
iteration. (A classic B-tree stores values in internal nodes too, which makes range scans awkward.)

## The one subtlety: splits
Insert descends to a leaf and adds the key in order. On overflow a node splits, and the two kinds split
*differently*:
- **Leaf split → copy up.** The right half's smallest key is **copied** into the parent as a separator but
  also **stays** in the leaf (the value is there).
- **Internal split → push up.** The median key **moves** up into the parent and is **removed** from the child
  (internal keys are routers, no value to keep).

A split can cascade to the parent; when the **root** splits, a new root is made and the height grows by one.
That is how a B+tree stays balanced — it grows at the *root*, never needing AVL/red-black rotations.

## B-tree vs LSM (the interview answer)
| | B+tree (`shopflow-btree`) | LSM-tree (`shopflow-lsm`) |
|---|---|---|
| Write | update **in place**, random I/O | **append** to memtable, sequential flush |
| Read | **1 path**, ~log_fanout(n) | check memtable + every segment (read amp) |
| Space | pages ~⅔ full | compaction debt + tombstones |
| Best for | read-heavy, range scans, OLTP | write-heavy, ingest, time-series |
| Used by | PostgreSQL, InnoDB, Oracle | RocksDB, Cassandra, LevelDB, HBase |

Rule of thumb: **reads and ranges → B-tree; heavy writes → LSM.** Many systems offer both (MySQL InnoDB is a
B+tree; MyRocks is an LSM).

## Use it
```java
var idx = new BPlusTree<Integer, String>(/* order = max fanout */ 128);
idx.put(42, "{...}");
idx.put(42, "{...v2}");             // overwrite in place
Optional<String> v = idx.get(42);
List<Integer> page = idx.range(100, 200);   // ordered scan of [100, 200)
idx.delete(42);
```

## Where it fits in shopflow
It's the index type under every relational table the services use (order/inventory/payment/ledger on
Postgres) — a primary key or a `@Column(index=true)` is a B+tree, and understanding its height and range-scan
behaviour is what the `shopflow` JPA-at-scale notes (comprehension 31: keyset vs OFFSET pagination, the index
cliff) are really about. Read it alongside `shopflow-lsm` to reason about *why* a given store was chosen.

## Tests
`BPlusTreeTest`: put/get + overwrite-doesn't-grow-size; 20 sequential inserts cascade splits and grow height
while every key stays findable; 50 **shuffled** inserts still range-scan in sorted order (full scan, a window,
an empty window); delete removes a key once and leaves the rest findable and the range intact; bad order /
null args rejected. A standalone JDK-17 harness adds a **2000-key shuffled stress test** verifying a fully
ordered 0..1999 scan.
