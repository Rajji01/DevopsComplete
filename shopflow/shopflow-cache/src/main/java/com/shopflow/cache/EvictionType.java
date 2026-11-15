package com.shopflow.cache;

/**
 * Which entry to drop when the cache is full. Configurable via {@code cache.eviction}.
 *
 *  LRU  — evict the Least Recently USED. Good default: assumes recently-touched keys are touched again
 *         (temporal locality). What Redis's allkeys-lru approximates.
 *  LFU  — evict the Least Frequently Used. Better when some keys are perennially hot and a burst of
 *         one-off keys would otherwise flush them out under LRU. Costlier to track.
 *  FIFO — evict the oldest INSERTED, ignoring use. Simplest, but can drop a hot key just because it is
 *         old — usually the wrong choice; included to show the contrast.
 */
public enum EvictionType {
    LRU,
    LFU,
    FIFO
}
