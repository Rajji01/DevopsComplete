package com.shopflow.btree;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A B+tree — the read-optimised, update-in-place index at the heart of almost every relational database
 * (PostgreSQL, MySQL/InnoDB, Oracle, SQL Server) and the direct counterpart to the write-optimised
 * {@code shopflow-lsm} log-structured merge-tree. The canonical storage-interview question is "LSM vs
 * B-tree?", and this is the other half of the answer.
 *
 * <p><b>Why a B+tree and not a binary tree.</b> A balanced binary tree has height ~log₂(n); a B+tree packs
 * many keys per node (high <i>fanout</i>), so its height is ~log_fanout(n) — far shallower. Each node is
 * sized to one disk page / SSD block, so a lookup touches only a handful of pages (one per level) instead of
 * ~log₂(n) random reads. Shallow + page-aligned is exactly what makes it fast on storage, where a random I/O
 * is the dominant cost.
 *
 * <pre>
 *                     [ 13 | 30 ]                 internal nodes: keys route, no values
 *                    /     |     \
 *            [5|9]      [13|20]     [30|41]        leaves hold the values and are
 *              │           │           │           chained left→right for range scans
 *              └───────────┴───────────┘ ─────────▶
 * </pre>
 *
 * <p><b>B+tree vs plain B-tree.</b> In a B+tree <i>all</i> values live in the leaves; internal nodes hold
 * only separator keys to route. The leaves form a linked list, so a range scan finds the start leaf then
 * walks the chain — cheap ordered iteration, which is why SQL {@code ORDER BY} / {@code BETWEEN} love it.
 *
 * <p><b>Splits — the one subtlety.</b> Insert descends to a leaf and adds the key in sorted order. If a node
 * overflows it splits, and leaves and internals split differently:
 * <ul>
 *   <li><b>Leaf split: copy up.</b> The right half's smallest key is <i>copied</i> into the parent as a
 *       separator but <i>also stays</i> in the leaf (the value lives there).</li>
 *   <li><b>Internal split: push up.</b> The median key <i>moves</i> up into the parent and is removed from
 *       the child (internal keys are only routers, so there's no value to keep).</li>
 * </ul>
 * A split can cascade upward; when the root splits, a new root is created and the tree's height grows by one
 * — which is how a B+tree stays balanced without ever rebalancing like an AVL/red-black tree.
 *
 * <p>This implementation covers insert (with cascading splits + root growth), point {@link #get}, ordered
 * {@link #range} scans over the leaf chain, and a simple {@link #delete} (leaf removal without node
 * merging — a documented simplification; search/scan stay correct, space is just not reclaimed as
 * aggressively as a production B-tree's merge/borrow). Not thread-safe.
 *
 * @param <K> key type, ordered by {@link Comparable}
 * @param <V> value type
 */
public final class BPlusTree<K extends Comparable<K>, V> {

    private abstract static class Node<K> {
        final List<K> keys = new ArrayList<>();
    }

    private static final class Leaf<K, V> extends Node<K> {
        final List<V> values = new ArrayList<>();
        Leaf<K, V> next;    // right sibling, for range scans
    }

    private static final class Internal<K> extends Node<K> {
        final List<Node<K>> children = new ArrayList<>();
    }

    /** A node that split: the key to install in the parent, and the new right node. */
    private static final class Split<K> {
        final K key;
        final Node<K> right;
        Split(K key, Node<K> right) {
            this.key = key;
            this.right = right;
        }
    }

    private final int maxKeys;      // a node may hold up to this many keys; it splits on exceeding it
    private Node<K> root = new Leaf<>();
    private int height = 1;
    private int size;

    /** @param order maximum children per node (fanout); must be >= 3. Max keys per node is order-1. */
    public BPlusTree(int order) {
        if (order < 3) {
            throw new IllegalArgumentException("order (max fanout) must be >= 3");
        }
        this.maxKeys = order - 1;
    }

    public int size() {
        return size;
    }

    /** Height in levels (1 = a single leaf). Grows only when the root splits. */
    public int height() {
        return height;
    }

    public void put(K key, V value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("key and value must be non-null");
        }
        if (!get(key).isPresent()) {
            size++;
        }
        Split<K> split = insert(root, key, value);
        if (split != null) {                 // root split -> new root, height grows
            Internal<K> newRoot = new Internal<>();
            newRoot.keys.add(split.key);
            newRoot.children.add(root);
            newRoot.children.add(split.right);
            root = newRoot;
            height++;
        }
    }

    public Optional<V> get(K key) {
        Node<K> node = root;
        while (node instanceof Internal) {
            Internal<K> in = castInternal(node);
            node = in.children.get(childIndex(in, key));
        }
        Leaf<K, V> leaf = castLeaf(node);
        int i = indexOf(leaf.keys, key);
        return i >= 0 ? Optional.of(leaf.values.get(i)) : Optional.empty();
    }

    /** Ordered scan of all keys in [from, to) — the operation B+trees exist for. */
    public List<K> range(K from, K to) {
        List<K> out = new ArrayList<>();
        Node<K> node = root;
        while (node instanceof Internal) {
            Internal<K> in = castInternal(node);
            node = in.children.get(childIndex(in, from));
        }
        Leaf<K, V> leaf = castLeaf(node);
        while (leaf != null) {
            for (K k : leaf.keys) {
                if (k.compareTo(from) >= 0 && k.compareTo(to) < 0) {
                    out.add(k);
                } else if (k.compareTo(to) >= 0) {
                    return out;              // leaves are sorted + chained -> stop at the first key past `to`
                }
            }
            leaf = leaf.next;
        }
        return out;
    }

    /** Remove a key (simple leaf delete, no node merge). Returns true if the key was present. */
    public boolean delete(K key) {
        Node<K> node = root;
        while (node instanceof Internal) {
            Internal<K> in = castInternal(node);
            node = in.children.get(childIndex(in, key));
        }
        Leaf<K, V> leaf = castLeaf(node);
        int i = indexOf(leaf.keys, key);
        if (i < 0) {
            return false;
        }
        leaf.keys.remove(i);
        leaf.values.remove(i);
        size--;
        return true;
    }

    // --- internals ---------------------------------------------------------

    private Split<K> insert(Node<K> node, K key, V value) {
        if (node instanceof Leaf) {
            return insertLeaf(castLeaf(node), key, value);
        }
        Internal<K> in = castInternal(node);
        int ci = childIndex(in, key);
        Split<K> childSplit = insert(in.children.get(ci), key, value);
        if (childSplit == null) {
            return null;
        }
        // install the promoted/copied-up separator and the new right child
        in.keys.add(ci, childSplit.key);
        in.children.add(ci + 1, childSplit.right);
        if (in.keys.size() <= maxKeys) {
            return null;
        }
        return splitInternal(in);
    }

    private Split<K> insertLeaf(Leaf<K, V> leaf, K key, V value) {
        int i = 0;
        while (i < leaf.keys.size() && key.compareTo(leaf.keys.get(i)) > 0) {
            i++;
        }
        if (i < leaf.keys.size() && leaf.keys.get(i).compareTo(key) == 0) {
            leaf.values.set(i, value);       // overwrite, no structural change
            return null;
        }
        leaf.keys.add(i, key);
        leaf.values.add(i, value);
        if (leaf.keys.size() <= maxKeys) {
            return null;
        }
        // split: right half moves to a new leaf; its first key is COPIED up (stays in the leaf too)
        int mid = leaf.keys.size() / 2;
        Leaf<K, V> right = new Leaf<>();
        right.keys.addAll(leaf.keys.subList(mid, leaf.keys.size()));
        right.values.addAll(leaf.values.subList(mid, leaf.values.size()));
        leaf.keys.subList(mid, leaf.keys.size()).clear();
        leaf.values.subList(mid, leaf.values.size()).clear();
        right.next = leaf.next;
        leaf.next = right;
        return new Split<>(right.keys.get(0), right);
    }

    private Split<K> splitInternal(Internal<K> node) {
        int mid = node.keys.size() / 2;
        K promote = node.keys.get(mid);      // median is PUSHED up (removed from this node)
        Internal<K> right = new Internal<>();
        right.keys.addAll(node.keys.subList(mid + 1, node.keys.size()));
        right.children.addAll(node.children.subList(mid + 1, node.children.size()));
        node.keys.subList(mid, node.keys.size()).clear();
        node.children.subList(mid + 1, node.children.size()).clear();
        return new Split<>(promote, right);
    }

    /** Which child subtree a key routes to: keys.get(j) is the smallest key of children.get(j+1). */
    private int childIndex(Internal<K> in, K key) {
        int i = 0;
        while (i < in.keys.size() && key.compareTo(in.keys.get(i)) >= 0) {
            i++;
        }
        return i;
    }

    private int indexOf(List<K> keys, K key) {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).compareTo(key) == 0) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private Internal<K> castInternal(Node<K> n) {
        return (Internal<K>) n;
    }

    @SuppressWarnings("unchecked")
    private Leaf<K, V> castLeaf(Node<K> n) {
        return (Leaf<K, V>) n;
    }
}
