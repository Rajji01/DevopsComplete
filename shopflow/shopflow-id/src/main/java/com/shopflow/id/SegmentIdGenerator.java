package com.shopflow.id;

/**
 * Segment (a.k.a. Hi/Lo) id generator: grab a block from the {@link SegmentAllocator}, then hand ids out
 * locally until it runs dry, then grab the next block. Dense, monotonic-per-generator ids with a DB round
 * trip only once per {@code blockSize} ids — not per id.
 *
 * Trade-offs to know: ids are NOT globally time-sortable the way Snowflake/ULID are, and a restart wastes
 * the rest of the current block (gaps) — which is fine, ids only need to be unique, not gap-free. Bigger
 * blocks = fewer DB hits but more waste on restart.
 */
public final class SegmentIdGenerator implements IdGenerator {

    private final SegmentAllocator allocator;
    private final String name;
    private final int blockSize;

    private long cursor = 0;
    private long end = 0; // [cursor, end) is the block we currently own; empty when cursor == end

    public SegmentIdGenerator(SegmentAllocator allocator, String name, int blockSize) {
        if (blockSize <= 0) {
            throw new IllegalArgumentException("blockSize must be positive");
        }
        this.allocator = allocator;
        this.name = name;
        this.blockSize = blockSize;
    }

    public synchronized long nextLong() {
        if (cursor >= end) {                              // block exhausted (or first call) -> fetch another
            long start = allocator.allocate(name, blockSize);
            cursor = start;
            end = start + blockSize;
        }
        return cursor++;
    }

    @Override
    public String nextId() {
        return Long.toString(nextLong());
    }
}
