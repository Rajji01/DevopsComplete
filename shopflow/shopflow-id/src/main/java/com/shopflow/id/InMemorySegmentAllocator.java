package com.shopflow.id;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory stand-in for the DB counter — used by tests and single-node runs. {@code getAndAdd} is the
 * atomic equivalent of the DB's {@code UPDATE ... SET next = next + size RETURNING}, so concurrent
 * generators never get overlapping blocks.
 */
public final class InMemorySegmentAllocator implements SegmentAllocator {

    private final ConcurrentHashMap<String, AtomicLong> cursors = new ConcurrentHashMap<>();

    @Override
    public long allocate(String name, int size) {
        return cursors.computeIfAbsent(name, k -> new AtomicLong(0)).getAndAdd(size);
    }
}
