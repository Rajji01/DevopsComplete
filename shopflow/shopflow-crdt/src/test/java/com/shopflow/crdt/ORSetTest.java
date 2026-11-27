package com.shopflow.crdt;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

class ORSetTest {

    /** Deterministic, globally-unique tags for the test ("r1-0", "r1-1", …) instead of random UUIDs. */
    private static Supplier<String> tags(String replica) {
        AtomicLong seq = new AtomicLong();
        return () -> replica + "-" + seq.getAndIncrement();
    }

    @Test
    void addThenRemoveMakesAbsent() {
        var s = new ORSet<String>(tags("r1"));
        s.add("x");
        assertThat(s.contains("x")).isTrue();
        s.remove("x");
        assertThat(s.contains("x")).isFalse();
        assertThat(s.elements()).isEmpty();
    }

    @Test
    void reAddingAfterRemoveBringsItBack() {
        // the 2P-Set bug this fixes: a plain add-set/remove-set could never re-add a removed element
        var s = new ORSet<String>(tags("r1"));
        s.add("x");
        s.remove("x");
        s.add("x");                       // a fresh, un-tombstoned tag
        assertThat(s.contains("x")).isTrue();
    }

    @Test
    void concurrentAddWinsOverRemove() {
        // r1 and r2 both start with {x}. r1 removes x (tombstoning the tag it has seen); r2, concurrently,
        // adds x AGAIN with a brand-new tag r1 never observed. After the merge, x is PRESENT: the new add
        // survives because r1's remove could only cancel the tags it had observed.
        var shared = new ORSet<String>(tags("seed"));
        shared.add("x");

        var r1 = new ORSet<String>(tags("r1"));
        r1.mergeFrom(shared);
        var r2 = new ORSet<String>(tags("r2"));
        r2.mergeFrom(shared);

        r1.remove("x");   // tombstones the seed tag
        r2.add("x");      // new tag r1 never saw

        r1.mergeFrom(r2);
        r2.mergeFrom(r1);
        assertThat(r1.contains("x")).isTrue();  // add-wins
        assertThat(r2.contains("x")).isTrue();
    }

    @Test
    void mergeConvergesRegardlessOfDirection() {
        var a = new ORSet<String>(tags("a"));
        var b = new ORSet<String>(tags("b"));
        a.add("apple");
        a.add("pear");
        b.add("banana");
        b.add("apple");   // independent add of the same element on another replica

        var aThenB = new ORSet<String>(tags("x"));
        aThenB.mergeFrom(a);
        aThenB.mergeFrom(b);

        var bThenA = new ORSet<String>(tags("y"));
        bThenA.mergeFrom(b);
        bThenA.mergeFrom(a);

        assertThat(aThenB.elements()).containsExactlyInAnyOrder("apple", "pear", "banana");
        assertThat(bThenA.elements()).isEqualTo(aThenB.elements());
    }

    @Test
    void mergeIsIdempotent() {
        var a = new ORSet<String>(tags("a"));
        a.add("x");
        var b = new ORSet<String>(tags("b"));
        b.add("y");
        a.mergeFrom(b);
        var once = a.elements();
        a.mergeFrom(b);
        a.mergeFrom(b);
        assertThat(a.elements()).isEqualTo(once).containsExactlyInAnyOrder("x", "y");
    }
}
