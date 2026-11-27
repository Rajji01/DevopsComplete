# shopflow-wal — write-ahead log

The durability primitive under databases, Kafka, and the **transactional-outbox** pattern this project
already uses: write the change to an append-only log and flush it to disk BEFORE acknowledging it, so a
crash is survived by replaying the log. Sequential appends are fast; random-access state is rebuilt on
recovery.

> Plain library like `shopflow-outbox` — never boots. Pure Java (`java.nio`), fully unit-tested.

---

## What it does
- **`append(payload)`** — frames the record `[len][lsn][crc32][payload]`, writes it at the end, and
  `fsync`s (`FileChannel.force`) before returning its LSN. Durable the moment `append` returns.
- **`replay()`** — reads records in order and **stops at the first torn or CRC-failing frame**. That point
  is the crash boundary: everything before it was fully flushed; nothing after it is trusted. You never
  half-apply the record that was mid-write when the power cut.
- **recovery on open** — finds that boundary, **truncates the torn tail**, and resumes appending after the
  last good record; LSNs continue across reopens.

```
why "stop at the first bad frame" is the whole point:
  ...[ rec N-1  ✓ ][ rec N  (half-written, crash here) ]
  replay returns rec 0..N-1 and DROPS the partial rec N.
  the CRC over (lsn+payload) is what detects the torn write a short read alone would miss.
```

## Use it
```java
try (var wal = new WriteAheadLog(Path.of("data/wal.log"))) {
    long lsn = wal.append(serialize(event));   // durable (fsync'd) before this returns
    // ... apply to in-memory state ...
}
// after a crash, on restart:
try (var wal = new WriteAheadLog(Path.of("data/wal.log"))) {
    for (var r : wal.replay()) apply(r.payload()); // rebuild state from the durable prefix
}
```

## Where it fits in shopflow
This *is* the idea behind `shopflow-outbox`: the outbox table is a WAL in a database — the event is written
in the same transaction as the business change (so it's durable before anyone sees the result), then a relay
replays it to Kafka. Same with a DB's redo log and Kafka's partition log. Seeing the pattern in ~150 lines of
`FileChannel.force` + CRC framing is the point: "append-then-fsync, replay the valid prefix on recovery" is
the shape of durability everywhere in the stack.

Teaching scope: one segment, no checkpoint/compaction (a real WAL periodically folds the log into a snapshot
and truncates — the natural next step). Append/replay are synchronized.

## Tests
`WriteAheadLogTest` (append/replay round-trip in order with LSNs; records survive a reopen and LSNs continue;
a torn tail appended after a clean close is discarded on recovery and a fresh append lands cleanly; a flipped
byte in a committed record fails its CRC so replay stops at that record). Uses a JUnit `@TempDir`.
