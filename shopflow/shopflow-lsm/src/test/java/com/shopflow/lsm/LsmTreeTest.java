package com.shopflow.lsm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LsmTreeTest {

    @Test
    void memtableWritesAreReadBack_andNewestWins() {
        var lsm = new LsmTree(100, 100);
        lsm.put("a", "1");
        lsm.put("a", "2");                      // overwrite in the memtable
        assertThat(lsm.get("a")).contains("2");
        assertThat(lsm.get("missing")).isEmpty();
    }

    @Test
    void fillingTheMemtableFlushesToASegment_andReadsScanNewestToOldest() {
        var lsm = new LsmTree(2, 100);          // flush every 2 keys
        lsm.put("a", "1");
        lsm.put("b", "1");                      // memtable full -> flush -> segment {a=1,b=1}
        lsm.put("a", "2");
        lsm.put("c", "1");                      // flush -> newer segment {a=2,c=1}
        assertThat(lsm.segmentCount()).isEqualTo(2);
        assertThat(lsm.memtableSize()).isZero();
        assertThat(lsm.get("a")).contains("2"); // newest segment wins
        assertThat(lsm.get("b")).contains("1"); // only in the older segment
        assertThat(lsm.get("c")).contains("1");
    }

    @Test
    void deleteWritesATombstoneThatShadowsAnOlderValue_untilOverwritten() {
        var lsm = new LsmTree(2, 100);
        lsm.put("x", "1");
        lsm.put("y", "1");                      // flush -> {x=1,y=1}
        lsm.delete("x");
        lsm.put("z", "1");                      // flush -> {x=<tombstone>,z=1}
        assertThat(lsm.get("x")).isEmpty();     // tombstone shadows the older x=1
        assertThat(lsm.get("y")).contains("1");
        lsm.put("x", "2");                      // a later write resurrects the key
        assertThat(lsm.get("x")).contains("2");
    }

    @Test
    void compactionMergesSegmentsKeepingTheNewestVersionPerKey() {
        var lsm = new LsmTree(1, 3);            // one key per segment; compact at 3 segments
        lsm.put("a", "1");                      // seg {a=1}
        lsm.put("a", "2");                      // seg {a=2}
        lsm.put("b", "1");                      // seg {b=1} -> 3 segments -> compaction
        assertThat(lsm.compactions()).isEqualTo(1);
        assertThat(lsm.segmentCount()).isEqualTo(1);     // merged into one
        assertThat(lsm.get("a")).contains("2");          // newest version survived
        assertThat(lsm.get("b")).contains("1");
    }

    @Test
    void compactionPurgesTombstones() {
        var lsm = new LsmTree(1, 3);
        lsm.put("k", "1");                      // seg {k=1}
        lsm.delete("k");                        // seg {k=<tombstone>}
        lsm.put("m", "1");                      // seg {m=1} -> compaction
        assertThat(lsm.compactions()).isEqualTo(1);
        assertThat(lsm.get("k")).isEmpty();     // key fully gone
        assertThat(lsm.get("m")).contains("1");
        assertThat(lsm.segmentCount()).isEqualTo(1);     // only {m=1} remains; k+tombstone reclaimed
    }

    @Test
    void rejectsBadConfigAndNullArgs() {
        assertThatThrownBy(() -> new LsmTree(0, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LsmTree(1, 1)).isInstanceOf(IllegalArgumentException.class);
        var lsm = new LsmTree(10, 3);
        assertThatThrownBy(() -> lsm.put("k", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> lsm.delete(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
