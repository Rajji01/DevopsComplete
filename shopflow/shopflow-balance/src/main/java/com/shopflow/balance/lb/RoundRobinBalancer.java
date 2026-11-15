package com.shopflow.balance.lb;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robin: hand out backends in a strict cycle. Fair and dead simple when every backend is equal and
 * every request costs about the same. Its blind spot: it ignores actual load, so one slow backend still
 * gets its full 1/N share. {@code floorMod} keeps it correct even after the counter overflows int range.
 */
public final class RoundRobinBalancer<N> implements Balancer<N> {

    private final List<N> nodes;
    private final AtomicInteger counter = new AtomicInteger();

    public RoundRobinBalancer(List<N> nodes) {
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("need at least one node");
        }
        this.nodes = List.copyOf(nodes);
    }

    @Override
    public N next() {
        return nodes.get(Math.floorMod(counter.getAndIncrement(), nodes.size()));
    }
}
