package com.shopflow.merkle;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class MerkleTreeTest {

    private static List<byte[]> leaves(String... items) {
        List<byte[]> l = new ArrayList<>();
        for (String s : items) {
            l.add(s.getBytes(UTF_8));
        }
        return l;
    }

    @Test
    void sameDataGivesSameRootAndOneChangeFlipsIt() {
        var a = MerkleTree.of(leaves("a", "b", "c", "d"));
        var b = MerkleTree.of(leaves("a", "b", "c", "d"));
        assertThat(a.rootHex()).isEqualTo(b.rootHex());

        var changed = MerkleTree.of(leaves("a", "b", "X", "d"));
        assertThat(changed.rootHex()).isNotEqualTo(a.rootHex()); // tamper-evident
    }

    @Test
    void inclusionProofVerifiesForTheRealLeafAndFailsForAWrongOne() {
        var tree = MerkleTree.of(leaves("a", "b", "c", "d"));
        var proof = tree.proof(2); // leaf "c"
        assertThat(MerkleTree.verify("c".getBytes(UTF_8), proof, tree.root())).isTrue();
        assertThat(MerkleTree.verify("not-c".getBytes(UTF_8), proof, tree.root())).isFalse();
    }

    @Test
    void aProofDoesNotVerifyAgainstADifferentRoot() {
        var tree = MerkleTree.of(leaves("a", "b", "c", "d"));
        var proof = tree.proof(2);
        var other = MerkleTree.of(leaves("a", "b", "c", "e"));
        assertThat(MerkleTree.verify("c".getBytes(UTF_8), proof, other.root())).isFalse();
    }

    @Test
    void findsTheSingleDifferingLeafInLogComparisons() {
        var mine = MerkleTree.of(leaves("a", "b", "c", "d", "e", "f", "g", "h"));
        var theirs = MerkleTree.of(leaves("a", "b", "c", "d", "e", "Z", "g", "h"));
        assertThat(mine.firstDifferingLeaf(theirs)).isEqualTo(5);

        var identical = MerkleTree.of(leaves("a", "b", "c", "d", "e", "f", "g", "h"));
        assertThat(mine.firstDifferingLeaf(identical)).isEqualTo(-1);
    }
}
