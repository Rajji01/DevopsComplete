package com.shopflow.id;

/**
 * Generate a unique id. The whole module is about the trade-offs between the ways to do it:
 *   Snowflake  — 64-bit long, time-sortable, compact; needs a unique node id + a sane clock.
 *   ULID       — 128-bit string, time-sortable, no coordination at all; bigger than a long.
 *   UUIDv7     — the standardised time-ordered UUID; drop-in where a UUID is expected.
 *   Segment    — hand out blocks from a DB counter; dense + monotonic-ish; one round trip per block.
 */
public interface IdGenerator {

    String nextId();
}
