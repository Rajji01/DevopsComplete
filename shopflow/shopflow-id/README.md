# shopflow-id — distributed unique id generation

Auto-increment columns don't work once you have many writers / shards (every node would need to coordinate
the next number). These are the four standard ways to mint unique ids without that bottleneck, each a real
trade-off. Pure Java, clock-injected, fully unit-tested.

> Plain library like `shopflow-outbox` — never boots, no infra.

---

## The four, and when to reach for each
| Generator | Shape | Time-sortable? | Coordination | Notes |
|---|---|---|---|---|
| **Snowflake** | 64-bit `long` | yes | needs a unique **node id** + a monotonic clock | compact; great DB key; `[41 ms][10 node][12 seq]` = 4096 ids/node/ms |
| **ULID** | 26-char string (128-bit) | yes | **none** | `[48 ms][80 random]`, Crockford base32; sortable with zero setup |
| **UUIDv7** | `UUID` (128-bit) | yes | **none** | RFC 9562; drop-in where a `UUID` is expected |
| **Segment (Hi/Lo)** | dense `long` | no (per-generator monotonic) | one DB block at a time | one round trip per *block*, not per id; gaps on restart are fine |

**Why sortable ids matter** (the thing interviewers probe): a random id (UUIDv4) as a primary key scatters
inserts all over the B-tree → page splits, cache misses, write amplification. A **time-prefixed** id
(Snowflake/ULID/UUIDv7) makes every insert land at the *end* of the index — sequential, cheap. That single
property is why all three put the timestamp in the high bits.

## The two hazards Snowflake must handle (and does, here)
- **Sequence exhausted in a millisecond** (> 4096 ids) → spin to the next ms; never reuse a number.
- **Clock moves backwards** (NTP step) → refuse to generate, because a lower timestamp could collide with
  an already-issued id. (A tolerant variant waits a few ms if the drift is tiny.)

## Use it
```java
IdGenerator snow = new SnowflakeIdGenerator(/*nodeId*/ 7, /*epoch*/ 1577836800000L, System::currentTimeMillis);
long orderId = ((SnowflakeIdGenerator) snow).nextLong();

IdGenerator ulid = new UlidGenerator();            // no args, no coordination
String id = ulid.nextId();                         // e.g. 01HQ3K7P8E5X...  (sorts by time)

UUID u = new Uuidv7Generator().next();             // standard time-ordered UUID

// Segment: in prod pass a DB-backed SegmentAllocator; here the in-memory one
var seg = new SegmentIdGenerator(new InMemorySegmentAllocator(), "orders", 1000);
long next = seg.nextLong();
```

## Where it fits in shopflow
`orderRef` is the natural home: today it's a UUID. A ULID or Snowflake `orderRef` would be **sortable by
creation time** — a better primary key (ordered inserts) and it doubles as a rough "when" without a separate
column. The Segment generator mirrors how a classic `SEQUENCE`/ticket server hands out ids, useful when ids
must be dense (e.g. human-facing invoice numbers).

## Tests
`SnowflakeIdGeneratorTest` (uniqueness + monotonic within a ms, sequence reset on a new ms, **refuses a
backwards clock**, node-id bits), `UlidGeneratorTest` (26 Crockford chars, later-ts sorts after, monotonic
within a ms), `Uuidv7GeneratorTest` (version 7 + timestamp bits), `SegmentIdGeneratorTest` (contiguous +
refill, two generators never overlap). All deterministic via an injected clock + seeded `Random`.
