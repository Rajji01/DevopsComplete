package com.shopflow.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

/** Lock semantics against the in-memory store: mutual exclusion, owner-only release, fencing, expiry, wait. */
class DistributedLockTest {

    private final AtomicLong now = new AtomicLong(0);
    private final LongSupplier clock = now::get;

    @Test
    void onlyOneHolderAtATime() {
        var lock = new DistributedLock(new InMemoryLockStore(clock));
        Optional<LockToken> a = lock.tryAcquire("k", Duration.ofSeconds(10));
        assertThat(a).isPresent();
        assertThat(lock.tryAcquire("k", Duration.ofSeconds(10))).isEmpty();   // already held
        assertThat(lock.release(a.get())).isTrue();
        assertThat(lock.tryAcquire("k", Duration.ofSeconds(10))).isPresent(); // free again
    }

    @Test
    void releaseOnlyWorksForTheOwner() {
        var lock = new DistributedLock(new InMemoryLockStore(clock));
        LockToken a = lock.tryAcquire("k", Duration.ofSeconds(10)).orElseThrow();
        LockToken forged = new LockToken("k", "not-the-owner", 99, 0);
        assertThat(lock.release(forged)).isFalse();   // can't release a lock you don't own
        assertThat(lock.release(a)).isTrue();
    }

    @Test
    void fenceTokenStrictlyIncreasesAcrossAcquisitions() {
        var lock = new DistributedLock(new InMemoryLockStore(clock));
        LockToken a = lock.tryAcquire("k", Duration.ofSeconds(10)).orElseThrow();
        lock.release(a);
        LockToken b = lock.tryAcquire("k", Duration.ofSeconds(10)).orElseThrow();
        assertThat(b.fenceToken()).isGreaterThan(a.fenceToken());
    }

    @Test
    void expiredLockCanBeStolenAndTheFenceBumps() {
        var store = new InMemoryLockStore(clock);
        LockStore.AcquireResult a = store.acquire("k", "A", 1000);
        assertThat(a.acquired()).isTrue();
        assertThat(store.acquire("k", "B", 1000).acquired()).isFalse(); // A still holds it
        now.set(1000);                                                   // A's TTL elapses
        LockStore.AcquireResult b = store.acquire("k", "B", 1000);
        assertThat(b.acquired()).isTrue();
        assertThat(b.fenceToken()).isGreaterThan(a.fenceToken());        // stale A carries a lower fence
    }

    @Test
    void waitsUntilTheHolderReleases() throws Exception {
        var lock = new DistributedLock(new InMemoryLockStore(clock));
        LockToken held = lock.tryAcquire("k", Duration.ofSeconds(10)).orElseThrow();
        Thread releaser = new Thread(() -> {
            sleep(100);
            lock.release(held);
        });
        releaser.start();
        Optional<LockToken> got = lock.tryAcquire("k",
                Duration.ofSeconds(10), Duration.ofSeconds(2), Duration.ofMillis(20));
        releaser.join();
        assertThat(got).isPresent(); // acquired once the holder let go, within the wait budget
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
