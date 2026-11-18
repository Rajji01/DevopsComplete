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
}
