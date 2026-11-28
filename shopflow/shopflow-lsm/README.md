# shopflow-lsm — a log-structured merge-tree, built small

The write-optimised storage engine behind **RocksDB, Cassandra, LevelDB, HBase, ScyllaDB** — the answer to
the interview question "how does a database that takes a million writes a second actually store them?".
Pure Java, in-memory (so the *algorithm* is the focus), fully unit-tested.

> Plain library like `shopflow-outbox` — never boots.

---

## The idea: turn every write into an append
A B-tree updates **in place** — find the page, modify it, write it back — one random write per update. Random
writes are slow on disk and wear SSDs. An LSM-tree makes every write an **append** instead:

```
put/delete ─▶ [ memtable ]  sorted, in RAM, mutable
                   │ fills up
                   ▼ flush, one sequential write
            [ seg_n ] [ seg_n-1 ] ... [ seg_0 ]   immutable sorted segments (SSTables), newest ─▶ oldest
                   │ too many segments
                   ▼ compaction (background)
            [ one merged segment ]   newest version per key, tombstones purged
```

Writes are fast and sequential; the cost is deferred to **compaction**, which runs in the background. That is
the whole trade: **write-amplification-later in exchange for write-throughput-now.**

## The three mechanics
- **Memtable** — a sorted map in memory (a skiplist in LevelDB; a `TreeMap` here). Writes land here. When it
  hits `memtableLimit` keys it's flushed, as one sequential write, into a new immutable segment.
- **Read path** — check the memtable, then segments **newest → oldest, stopping at the first hit**, because a
  newer write always shadows an older one. The downside is *read amplification*: a key that doesn't exist may
  be searched for in every segment. Real engines keep a **Bloom filter per segment**
  (`shopflow-sketch`) to skip segments that can't contain the key — the two libraries compose exactly as in
  production.
- **Compaction** — when segments pile up, merge them: newest version per key wins, tombstones are purged.
  Bounds how many segments a read touches and reclaims dead space. (Here: a simplified size-tiered "major"
  compaction into one segment; LevelDB's leveled compaction is the same idea applied level by level.)

## Deletes are tombstones (a real gotcha)
You can't erase a key from an immutable segment, so `delete` writes a **tombstone** — a marker that shadows
any older value, so a reader stops and reports "not found". The space the key and its tombstone occupy is
reclaimed **only later**, when compaction merges the tombstone past the last older version. Deferred
reclamation is Cassandra's infamous "tombstone problem": delete-heavy workloads bloat until compaction
catches up, and range scans slow down wading through tombstones.

```
delete x:   memtable/segment gets  x -> <tombstone>     get(x) => empty (stops at the tombstone)
compaction: merges x=1 (old) and x=<tombstone> (new) -> both dropped, space reclaimed
```

## Use it
```java
var lsm = new LsmTree(/* memtableLimit */ 1024, /* compactionThreshold */ 4);
lsm.put("user:42", "{...}");
lsm.put("user:42", "{...v2}");         // overwrite -> newest wins on read
Optional<String> v = lsm.get("user:42");
lsm.delete("user:42");                 // writes a tombstone
lsm.get("user:42");                    // Optional.empty()
lsm.flush();                           // force the memtable out (e.g. on shutdown)
```

## Where it fits in shopflow
It's the storage model under the infrastructure the services already lean on: **Kafka**'s log segments,
**Cassandra/Scylla**-style stores, and RocksDB (which backs Kafka Streams' state stores and Flink). It also
rhymes with this repo's own patterns — `shopflow-wal` is the durability log an LSM writes before the memtable,
and the **transactional outbox** is itself an append-then-compact (relay-then-delete) log. Pair it with a
`shopflow-sketch` Bloom filter and you have the real read path.

## Tests
`LsmTreeTest`: memtable overwrite (newest wins) + missing-key empty; filling the memtable flushes a segment
and reads scan newest→oldest (newer segment shadows older); delete writes a tombstone that shadows an older
value until a later write resurrects the key; compaction merges segments keeping the newest version per key;
compaction purges tombstones (the key is fully reclaimed); bad config / null args rejected. Mirrored by a
standalone JDK-17 harness.
