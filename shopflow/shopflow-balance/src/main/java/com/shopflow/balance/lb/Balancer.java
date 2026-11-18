package com.shopflow.balance.lb;

/**
 * Picks the next backend for a request. Stateless pickers (round-robin, weighted) implement this directly;
 * connection-aware pickers (least-connections, power-of-two) track in-flight work and expose acquire/release
 * instead, since "which is least loaded" only means something if you tell them when a request finishes.
 */
public interface Balancer<N> {

    N next();
}
