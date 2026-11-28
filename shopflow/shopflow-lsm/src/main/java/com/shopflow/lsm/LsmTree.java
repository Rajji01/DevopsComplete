package com.shopflow.lsm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A log-structured merge-tree (LSM-tree) mini engine — the write-optimised storage model behind RocksDB,
 * Cassandra, LevelDB, HBase and ScyllaDB. Modelled in memory so the <i>algorithm</i> is the focus (the real
 * thing persists segments as on-disk SSTables, but the memtable/segment/compaction mechanics are identical).
 *
 * <p><b>Why LSM instead of update-in-place (B-tree).</b> A B-tree does a random write per update — find the
 * page, modify it, write it back — which is slow on spinning disks and wearing on SSDs. An LSM turns every
 * write into an <i>append</i>: it goes to a sorted in-memory <b>memtable</b>, and when that fills it is
 * flushed <i>sequentially</i> as a new immutable <b>segment</b> (SSTable). Writes are therefore fast and
 * sequential; the cost is paid later, in the background, by compaction. This is the classic
 * <i>write-amplification-later for write-throughput-now</i> trade.
 *
 * <pre>
 *   put/delete ─▶ [ memtable (sorted, mutable) ]
 *                        │ fills up
 *                        ▼ flush (sequential)
 *                 [ seg_n ] [ seg_n-1 ] ... [ seg_0 ]      newest ───────────▶ oldest
 *                        │ too many segments
 *                        ▼ compaction: merge, keep newest per key, drop tombstones
 *                 [ one merged segment ]
 * </pre>
 *
 * <p><b>Read path.</b> A lookup checks the memtable first, then the segments from newest to oldest, and
 * <i>stops at the first one that holds the key</i> — because a newer write always shadows an older one. This
 * is why reads can be more expensive than a B-tree's (read amplification): a key that doesn't exist may have
 * to be looked for in every segment. Real engines keep a Bloom filter per segment (see
 * {@code shopflow-sketch}) to skip segments that cannot contain the key — the two libraries compose exactly
 * as they do in production.
 *
 * <p><b>Deletes are tombstones.</b> You can't delete from an immutable segment, so a delete writes a
 * <i>tombstone</i> marker that shadows any older value on read (the reader stops and reports "not found").
 * The space is only reclaimed later, when compaction merges the tombstone past the last older version and
 * drops both. That delayed reclamation is a real operational gotcha (Cassandra's "tombstone problem").
 *
 * <p><b>Compaction.</b> When segments pile up, they are merged: for each key the newest version wins, and
 * tombstones are purged. This bounds the number of segments a read must consult and reclaims the space held
 * by superseded values and deletes. (This is a simplified size-tiered "major" compaction into one segment;
 * LevelDB's leveled compaction is the same idea applied level by level.)
 *
 * <p>Not thread-safe; a real engine serialises writes and uses MVCC snapshots for reads. Sorted order (the
 * memtable and every segment are {@link TreeMap}s) is what makes both range scans and merge-compaction cheap.
 */
public final class LsmTree {

    /** A stored version of a key: either a value, or a tombstone (a delete marker). */
    private static final class Entry {
        final String value;     // null iff tombstone
        final boolean tombstone;
        private Entry(String value, boolean tombstone) {
            this.value = value;
            this.tombstone = tombstone;
        }
        static Entry of(String value) {
            return new Entry(value, false);
        }
        static Entry tombstone() {
            return new Entry(null, true);
        }
    }

    private final int memtableLimit;        // flush the memtable once it holds this many keys
    private final int compactionThreshold;  // compact once the engine holds this many segments

    private TreeMap<String, Entry> memtable = new TreeMap<>();
    // newest segment first (index 0); each segment is an immutable sorted run (an SSTable)
    private final List<TreeMap<String, Entry>> segments = new ArrayList<>();

    private long flushCount;
    private long compactionCount;

    /**
     * @param memtableLimit       flush to a new segment once the memtable holds this many keys (> 0)
     * @param compactionThreshold merge all segments once this many exist (> 1)
     */
    public LsmTree(int memtableLimit, int compactionThreshold) {
        if (memtableLimit < 1) {
            throw new IllegalArgumentException("memtableLimit must be >= 1");
        }
        if (compactionThreshold < 2) {
            throw new IllegalArgumentException("compactionThreshold must be >= 2");
        }
        this.memtableLimit = memtableLimit;
        this.compactionThreshold = compactionThreshold;
    }

    /** Write (or overwrite) a key. Goes to the memtable; may trigger a flush and then a compaction. */
    public void put(String key, String value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("key and value must be non-null");
        }
        memtable.put(key, Entry.of(value));
        maybeFlush();
    }

    /** Delete a key by writing a tombstone that shadows any older value until compaction reclaims it. */
    public void delete(String key) {
        if (key == null) {
            throw new IllegalArgumentException("key must be non-null");
        }
        memtable.put(key, Entry.tombstone());
        maybeFlush();
    }

    /** Point lookup: memtable, then segments newest→oldest, stopping at the first that holds the key. */
    public Optional<String> get(String key) {
        Entry e = memtable.get(key);
        if (e != null) {
            return e.tombstone ? Optional.empty() : Optional.of(e.value);
        }
        for (TreeMap<String, Entry> seg : segments) {   // index 0 = newest
            Entry s = seg.get(key);
            if (s != null) {
                return s.tombstone ? Optional.empty() : Optional.of(s.value);
            }
        }
        return Optional.empty();
    }

    /** Force the current memtable out to a new segment even if it isn't full (e.g. on shutdown). */
    public void flush() {
        if (memtable.isEmpty()) {
            return;
        }
        segments.add(0, memtable);          // newest at the front
        memtable = new TreeMap<>();
        flushCount++;
        maybeCompact();
    }

    public int segmentCount() {
        return segments.size();
    }

    public int memtableSize() {
        return memtable.size();
    }

    public long flushes() {
        return flushCount;
    }

    public long compactions() {
        return compactionCount;
    }

    // --- internals ---------------------------------------------------------

    private void maybeFlush() {
        if (memtable.size() >= memtableLimit) {
            flush();
        }
    }

    private void maybeCompact() {
        if (segments.size() >= compactionThreshold) {
            compact();
        }
    }

    /**
     * Merge every segment into one: newest version wins per key, tombstones are purged. Iterating oldest →
     * newest and overwriting makes "newest wins" fall out naturally.
     */
    private void compact() {
        TreeMap<String, Entry> merged = new TreeMap<>();
        for (int i = segments.size() - 1; i >= 0; i--) {    // oldest first, newer overwrites
            for (Map.Entry<String, Entry> kv : segments.get(i).entrySet()) {
                merged.put(kv.getKey(), kv.getValue());
            }
        }
        merged.values().removeIf(e -> e.tombstone);         // purge deletes; superseded values already gone
        segments.clear();
        if (!merged.isEmpty()) {
            segments.add(merged);
        }
        compactionCount++;
    }
}
