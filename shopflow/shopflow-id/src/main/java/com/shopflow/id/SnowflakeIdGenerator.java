package com.shopflow.id;

import java.util.function.LongSupplier;

/**
 * Twitter Snowflake: a 64-bit, roughly-time-ordered id packed as
 *
 *   [ 1 unused ][ 41-bit ms since a custom epoch ][ 10-bit node id ][ 12-bit per-ms sequence ]
 *
 * Why the layout: the timestamp on top makes ids sortable by creation time (great as a DB primary key —
 * inserts hit the end of the B-tree, no page splits); the node id lets many generators run without
 * coordinating; the 12-bit sequence allows 4096 ids per node per millisecond.
 *
 * Two real hazards it must handle, both here:
 *  • sequence exhausted within a millisecond → spin to the next millisecond (never reuse a number).
 *  • the clock moving BACKWARDS (NTP step) → ids could collide or go back in time, so we refuse rather
 *    than risk a duplicate. (A production variant may wait a few ms if the drift is tiny.)
 */
public final class SnowflakeIdGenerator implements IdGenerator {

    private static final int NODE_BITS = 10;
    private static final int SEQ_BITS = 12;
    private static final long MAX_NODE = (1L << NODE_BITS) - 1;   // 1023
    private static final long MAX_SEQ = (1L << SEQ_BITS) - 1;     // 4095

    private final long epoch;      // custom epoch in ms (e.g. 2020-01-01) so the 41 bits last ~69 years
    private final long nodeId;
    private final LongSupplier clock;

    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(long nodeId, long epochMillis, LongSupplier clock) {
        if (nodeId < 0 || nodeId > MAX_NODE) {
            throw new IllegalArgumentException("nodeId must be 0.." + MAX_NODE);
        }
        this.nodeId = nodeId;
        this.epoch = epochMillis;
        this.clock = clock;
    }

    public synchronized long nextLong() {
        long now = clock.getAsLong();
        if (now < lastTimestamp) {
            throw new IllegalStateException("clock moved backwards by " + (lastTimestamp - now)
                    + "ms; refusing to generate a possibly-duplicate id");
        }
        if (now == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQ;
            if (sequence == 0) {
                now = waitForNextMillis(lastTimestamp); // this ms is full, roll to the next
            }
        } else {
            sequence = 0; // fresh millisecond, restart the sequence
        }
        lastTimestamp = now;
        return ((now - epoch) << (NODE_BITS + SEQ_BITS)) | (nodeId << SEQ_BITS) | sequence;
    }

    @Override
    public String nextId() {
        return Long.toString(nextLong());
    }

    private long waitForNextMillis(long last) {
        long t = clock.getAsLong();
        while (t <= last) {
            t = clock.getAsLong();
        }
        return t;
    }
}
