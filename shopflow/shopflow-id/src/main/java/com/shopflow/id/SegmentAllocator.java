package com.shopflow.id;

/**
 * Hands out non-overlapping blocks of ids for a named sequence. In prod this is one atomic DB statement
 * ({@code UPDATE id_segment SET next = next + :size WHERE name = :name RETURNING next}) — ONE round trip
 * buys a whole block, so the per-id cost is near zero and generators hand ids out locally from the block.
 */
public interface SegmentAllocator {

    /** Reserve {@code size} ids for {@code name} and return the block's start; the block is {@code [start, start+size)}. */
    long allocate(String name, int size);
}
