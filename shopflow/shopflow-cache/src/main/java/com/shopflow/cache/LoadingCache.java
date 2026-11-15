package com.shopflow.cache;

import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

import com.shopflow.cache.eviction.EvictionPolicy;

/**
 * The strategy layer over a {@link BoundedLocalCache}: it owns the loader and the write policy, so callers
 * just ask {@code get(key)} / {@code put(key, value)}. Three prod-hard behaviours live here:
 *
 *  • STAMPEDE / thundering herd — when a hot key misses, N concurrent callers would all hammer the DB with
 *    the same load. SINGLE-FLIGHT collapses them: the first caller loads, the rest wait on the same future.
 *  • CACHE PENETRATION — repeated lookups of a key that does not exist bypass the cache and pound the DB.
 *    NEGATIVE CACHING stores the "not found" for a short TTL so those misses are absorbed too.
 *  • WRITE consistency — {@link WritePolicy} decides what a write does to the cache vs the system of record.
 *
 * Cache-aside vs read-through: {@code get()} here is read-through (the cache loads on miss). The cache-aside
 * shape — caller does {@code getIfPresent()} then loads-and-{@code put()}s itself — is the same effect with
 * the orchestration in the caller; {@code getIfPresent()} is provided for it.
 *
 * (Owns an in-memory store directly; the distributed version swaps in a Redis-backed {@link CacheStore} —
 * see the README. Single-flight is then per-pod; cross-pod dedup needs a short lock, same idea as the
 * shopflow-ratelimit Lua locks.)
 */
public final class LoadingCache<K, V> {

    private record Holder<V>(V value) {} // a present Holder with a null value == a negatively-cached miss

    private final CacheStore<K, Holder<V>> store;
    private final Function<K, V> loader;      // the system-of-record read (returns null = not found)
    private final BiConsumer<K, V> writer;    // the system-of-record write; null = writes touch only the cache
    private final WritePolicy writePolicy;
    private final long ttlMillis;
    private final long negativeTtlMillis;     // <= 0 disables negative caching
    private final Executor writeExecutor;     // used only by WRITE_BEHIND

    private final ConcurrentHashMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();
    private final Queue<Map.Entry<K, V>> writeQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong loadCount = new AtomicLong();

    public LoadingCache(int capacity, EvictionPolicy<K> eviction, LongSupplier clock, double jitterFraction,
                        Function<K, V> loader, BiConsumer<K, V> writer, WritePolicy writePolicy,
                        long ttlMillis, long negativeTtlMillis, Executor writeExecutor) {
        this.store = new BoundedLocalCache<>(capacity, eviction, clock, jitterFraction);
        this.loader = loader;
        this.writer = writer;
        this.writePolicy = writePolicy;
        this.ttlMillis = ttlMillis;
        this.negativeTtlMillis = negativeTtlMillis;
        this.writeExecutor = writeExecutor;
    }

    /** Read-through: cache hit, negative-cache hit (→ empty), or single-flight load on miss. */
    public Optional<V> get(K key) {
        Optional<Holder<V>> cached = store.getIfPresent(key);
        if (cached.isPresent()) {
            return Optional.ofNullable(cached.get().value()); // Holder(null) → empty, no DB hit
        }
        return Optional.ofNullable(singleFlightLoad(key));
    }

    /** Raw peek, no load — the cache-aside building block (caller loads + puts on empty). */
    public Optional<V> getIfPresent(K key) {
        return store.getIfPresent(key).map(Holder::value);
    }

    private V singleFlightLoad(K key) {
        CompletableFuture<V> mine = new CompletableFuture<>();
        CompletableFuture<V> running = inFlight.putIfAbsent(key, mine);
        if (running != null) {
            return running.join(); // another thread is already loading this exact key — share its result
        }
        try {
            loadCount.incrementAndGet();
            V value = loader.apply(key);
            if (value == null) {
                if (negativeTtlMillis > 0) {
                    store.put(key, new Holder<>(null), negativeTtlMillis); // absorb repeated misses
                }
            } else {
                store.put(key, new Holder<>(value), ttlMillis);
            }
            mine.complete(value);
            return value;
        } catch (RuntimeException ex) {
            mine.completeExceptionally(ex); // waiters see the same failure (join throws CompletionException)
            throw ex;
        } finally {
            inFlight.remove(key, mine);
        }
    }

    public void put(K key, V value) {
        switch (writePolicy) {
            case WRITE_THROUGH -> {
                writeToSystemOfRecord(key, value);                 // SoR first...
                store.put(key, new Holder<>(value), ttlMillis);    // ...then cache: always consistent
            }
            case WRITE_BEHIND -> {
                store.put(key, new Holder<>(value), ttlMillis);    // cache now...
                writeQueue.add(Map.entry(key, value));             // ...SoR later, async
                writeExecutor.execute(this::flush);
            }
            case WRITE_AROUND, INVALIDATE -> {
                writeToSystemOfRecord(key, value);
                store.invalidate(key);                             // next read reloads a fresh value
            }
        }
    }

    public void invalidate(K key) {
        store.invalidate(key);
    }

    /** Drain queued WRITE_BEHIND writes to the system of record. (A crash before this loses them — the
     *  write-behind trade-off; a real one needs a durable queue, not an in-memory one.) */
    public void flush() {
        Map.Entry<K, V> e;
        while ((e = writeQueue.poll()) != null) {
            writeToSystemOfRecord(e.getKey(), e.getValue());
        }
    }

    public CacheStats stats() {
        CacheStats s = store.stats();
        return new CacheStats(s.hits(), s.misses(), loadCount.get(), s.evictions());
    }

    private void writeToSystemOfRecord(K key, V value) {
        if (writer != null) {
            writer.accept(key, value);
        }
    }
}
