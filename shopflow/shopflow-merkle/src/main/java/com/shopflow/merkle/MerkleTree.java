package com.shopflow.merkle;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Merkle (hash) tree: hash the leaves, then each parent = hash(left || right) up to a single ROOT hash. Two
 * powers fall out of that:
 *  • TAMPER-EVIDENCE — change any leaf and the root changes, so one small hash certifies a huge dataset
 *    (Git commits, Bitcoin blocks, container image layers all do this).
 *  • cheap PROOFS & DIFFS — prove a leaf is included with just log₂(n) sibling hashes, and find WHICH leaf
 *    two datasets differ on by walking down the differing branch — O(log n) comparisons, not O(n). That is
 *    exactly how Dynamo/Cassandra anti-entropy repairs replicas without shipping all the data.
 *
 * Domain separation (a leaf is hashed with a 0x00 prefix, an internal node with 0x01) stops an attacker
 * from passing an internal node off as a leaf — the second-preimage defence from Certificate Transparency.
 * Odd levels duplicate the last node (Bitcoin style).
 */
public final class MerkleTree {

    /** One step of an inclusion proof: a sibling hash and whether it sits to the RIGHT of the running hash. */
    public record ProofStep(byte[] hash, boolean siblingOnRight) {}

    private static final byte[] LEAF_PREFIX = {0x00};
    private static final byte[] NODE_PREFIX = {0x01};

    private final List<List<byte[]>> levels; // levels.get(0) = leaf hashes; last level = [root]

    private MerkleTree(List<List<byte[]>> levels) {
        this.levels = levels;
    }

    public static MerkleTree of(List<byte[]> data) {
        if (data.isEmpty()) {
            throw new IllegalArgumentException("need at least one leaf");
        }
        List<byte[]> leaves = new ArrayList<>();
        for (byte[] d : data) {
            leaves.add(hashLeaf(d));
        }
        List<List<byte[]>> levels = new ArrayList<>();
        levels.add(leaves);
        List<byte[]> current = leaves;
        while (current.size() > 1) {
            List<byte[]> parents = new ArrayList<>();
            for (int i = 0; i < current.size(); i += 2) {
                byte[] left = current.get(i);
                byte[] right = (i + 1 < current.size()) ? current.get(i + 1) : current.get(i); // dup last if odd
                parents.add(hashNode(left, right));
            }
            levels.add(parents);
            current = parents;
        }
        return new MerkleTree(levels);
    }

    public byte[] root() {
        return levels.get(levels.size() - 1).get(0).clone();
    }

    public String rootHex() {
        return hex(root());
    }

    public int leafCount() {
        return levels.get(0).size();
    }

    /** The O(log n) sibling path proving the leaf at {@code leafIndex} is part of this tree. */
    public List<ProofStep> proof(int leafIndex) {
        if (leafIndex < 0 || leafIndex >= leafCount()) {
            throw new IndexOutOfBoundsException("leafIndex " + leafIndex);
        }
        List<ProofStep> steps = new ArrayList<>();
        int idx = leafIndex;
        for (int level = 0; level < levels.size() - 1; level++) {
            List<byte[]> nodes = levels.get(level);
            boolean idxIsLeft = (idx % 2 == 0);
            int siblingIdx = idxIsLeft ? idx + 1 : idx - 1;
            byte[] sibling = siblingIdx < nodes.size() ? nodes.get(siblingIdx) : nodes.get(idx); // odd dup
            steps.add(new ProofStep(sibling, idxIsLeft)); // if we are the left child, the sibling is on the right
            idx /= 2;
        }
        return steps;
    }

    /** Recompute the root from a leaf + its proof and check it matches {@code expectedRoot}. */
    public static boolean verify(byte[] leafData, List<ProofStep> proof, byte[] expectedRoot) {
        byte[] h = hashLeaf(leafData);
        for (ProofStep step : proof) {
            h = step.siblingOnRight() ? hashNode(h, step.hash()) : hashNode(step.hash(), h);
        }
        return Arrays.equals(h, expectedRoot);
    }

    /**
     * The index of the first leaf that differs from {@code other} (same leaf count), or -1 if the trees are
     * identical. Walks DOWN the differing branch, so it costs O(log n) comparisons — the anti-entropy trick.
     */
    public int firstDifferingLeaf(MerkleTree other) {
        if (leafCount() != other.leafCount()) {
            throw new IllegalArgumentException("trees must have the same leaf count");
        }
        if (Arrays.equals(root(), other.root())) {
            return -1;
        }
        int idx = 0; // the differing node at the current level (root differs, established above)
        for (int level = levels.size() - 1; level >= 1; level--) {
            List<byte[]> mine = levels.get(level - 1);
            List<byte[]> theirs = other.levels.get(level - 1);
            int left = idx * 2;
            int right = idx * 2 + 1;
            if (right >= mine.size() || !Arrays.equals(mine.get(left), theirs.get(left))) {
                idx = left;   // left child differs (or is the only child)
            } else {
                idx = right;  // left matches, so the difference is on the right
            }
        }
        return idx;
    }

    private static byte[] hashLeaf(byte[] data) {
        return sha256(LEAF_PREFIX, data);
    }

    private static byte[] hashNode(byte[] left, byte[] right) {
        return sha256(NODE_PREFIX, left, right);
    }

    private static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) {
                md.update(p);
            }
            return md.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
