package com.shopflow.cache;

import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

import com.shopflow.cache.eviction.EvictionPolicy;
import com.shopflow.cache.eviction.FifoEvictionPolicy;
import com.shopflow.cache.eviction.LfuEvictionPolicy;
import com.shopflow.cache.eviction.LruEvictionPolicy;

/**
 * Builds a configured {@link LoadingCache}. The one place the eviction policies are chosen from config —
 * everything else (read/write strategy, TTL, stampede defences) is wired by {@link LoadingCache} itself.
 */
public final class CacheFactory {

    private CacheFactory() {}

    public static <K, V> LoadingCache<K, V> create(CacheProperties p, LongSupplier clock,
                                                   Function<K, V> loader, BiConsumer<K, V> writer,
                                                   Executor writeExecutor) {
        EvictionPolicy<K> eviction = switch (p.eviction()) {
            case LRU -> new LruEvictionPolicy<>();
            case LFU -> new LfuEvictionPolicy<>();
            case FIFO -> new FifoEvictionPolicy<>();
        };
        return new LoadingCache<>(p.capacity(), eviction, clock, p.jitterFraction(), loader, writer,
                p.writePolicy(), p.ttlMillis(), p.negativeTtlMillis(), writeExecutor);
    }

    /** Convenience: a read-only (no writer) read-through cache on the system clock, writes run inline. */
    public static <K, V> LoadingCache<K, V> readThrough(CacheProperties p, Function<K, V> loader) {
        return create(p, System::currentTimeMillis, loader, null, Runnable::run);
    }
}
