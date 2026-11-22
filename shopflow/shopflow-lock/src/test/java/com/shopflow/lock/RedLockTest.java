package com.shopflow.lock;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

/** Redlock quorum over three independent in-memory "nodes". */
class RedLockTest {

    private final AtomicLong now = new AtomicLong(0);
    private final LongSupplier clock = now::get;

    private List<LockStore> threeNodes() {
        return List.of(new InMemoryLockStore(clock), new InMemoryLockStore(clock), new InMemoryLockStore(clock));
    }

    @Test
    void acquiresWhenAMajorityAgree() {
        var redlock = new RedLock(threeNodes());
        assertThat(redlock.tryAcquire("k", Duration.ofSeconds(10))).isPresent();
    }

    @Test
    void aSecondClientCannotAcquireWhileHeld() {
        List<LockStore> nodes = threeNodes();
        var client1 = new RedLock(nodes);
        var client2 = new RedLock(nodes);
        assertThat(client1.tryAcquire("k", Duration.ofSeconds(10))).isPresent();
        assertThat(client2.tryAcquire("k", Duration.ofSeconds(10))).isEmpty(); // client1 holds all 3
    }

    @Test
    void failsWithoutAMajorityAndReleasesTheMinorityItGot() {
        var n1 = new InMemoryLockStore(clock);
        var n2 = new InMemoryLockStore(clock);
        var n3 = new InMemoryLockStore(clock);
        n1.acquire("k", "rival", 10_000); // a rival already holds 2 of the 3 nodes
        n2.acquire("k", "rival", 10_000);

        var redlock = new RedLock(List.of(n1, n2, n3));
        assertThat(redlock.tryAcquire("k", Duration.ofSeconds(10))).isEmpty(); // only got n3 (1 < quorum 2)

        // and it must have released n3 — it never sits on a minority
        assertThat(n3.acquire("k", "rival", 10_000).acquired()).isTrue();
    }

    @Test
    void failsWhenAcquiringTheQuorumOutlastsTheTtl() {
        // Each node's acquire costs 5s of wall-clock. By the time a majority of three nodes is locked, >10s
        // has passed — more than the 10s TTL — so the lock has already expired on the first node(s). A bare
        // majority is present, but there is no validity left: Redlock MUST reject this, or two clients could
        // believe they hold the same lock. (Before the validity check this returned a "held" lock.)
        var slow = List.<LockStore>of(
                new SlowStore(new InMemoryLockStore(clock), now, 5_000),
                new SlowStore(new InMemoryLockStore(clock), now, 5_000),
                new SlowStore(new InMemoryLockStore(clock), now, 5_000));
        var redlock = new RedLock(slow, () -> "tok", clock);
        assertThat(redlock.tryAcquire("k", Duration.ofSeconds(10))).isEmpty();
    }

    @Test
    void stillAcquiresWhenAcquisitionIsWellInsideTheTtl() {
        // A little acquisition cost (3 nodes × 1s = 3s against a 10s TTL) still leaves plenty of validity.
        var slow = List.<LockStore>of(
                new SlowStore(new InMemoryLockStore(clock), now, 1_000),
                new SlowStore(new InMemoryLockStore(clock), now, 1_000),
                new SlowStore(new InMemoryLockStore(clock), now, 1_000));
        var redlock = new RedLock(slow, () -> "tok", clock);
        assertThat(redlock.tryAcquire("k", Duration.ofSeconds(10))).isPresent();
    }

    /** A store that advances the shared clock by {@code costMillis} on each acquire, to model slow nodes. */
    private static final class SlowStore implements LockStore {
        private final LockStore delegate;
        private final AtomicLong clock;
        private final long costMillis;

        SlowStore(LockStore delegate, AtomicLong clock, long costMillis) {
            this.delegate = delegate;
            this.clock = clock;
            this.costMillis = costMillis;
        }

        @Override
        public AcquireResult acquire(String key, String token, long ttlMillis) {
            AcquireResult r = delegate.acquire(key, token, ttlMillis); // happens at the current time...
            clock.addAndGet(costMillis);                               // ...then time passes
            return r;
        }

        @Override
        public boolean release(String key, String token) {
            return delegate.release(key, token);
        }

        @Override
        public boolean extend(String key, String token, long ttlMillis) {
            return delegate.extend(key, token, ttlMillis);
        }
    }
}
