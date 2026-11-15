package com.shopflow.skiplist;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Skip list: an ordered set that gets balanced-tree performance (O(log n) search/insert/delete, O(log n + k)
 * range scans) from RANDOMNESS instead of rotations. Think of a sorted linked list with express lanes: each
 * node is promoted to the next level with probability p (½ here), so level L has ~n/2^L nodes and a search
 * drops down the lanes, skipping most of the list.
 *
 * The key property: correctness NEVER depends on the coin flips — the list is always sorted and every
 * operation returns the right answer; the randomness only affects how FAST it is. That's why it's far
 * simpler than a red-black tree (no rotation cases) and why Redis uses it for sorted sets (ZSET) and
 * LevelDB/RocksDB for memtables. The Random is injected so tests are deterministic.
 */
public final class SkipList<T extends Comparable<T>> {

    private static final int MAX_LEVEL = 32;
    private static final double P = 0.5;

    private static final class Node<T> {
        final T value;
        final Node<T>[] next;

        @SuppressWarnings("unchecked")
        Node(T value, int level) {
            this.value = value;
            this.next = (Node<T>[]) new Node[level];
        }
    }

    private final Node<T> head = new Node<>(null, MAX_LEVEL); // sentinel; its value is never compared
    private final Random random;
    private int levels = 1;
    private int size = 0;

    public SkipList() {
        this(new Random());
    }

    public SkipList(Random random) {
        this.random = random;
    }

    public boolean contains(T value) {
        Node<T> x = head;
        for (int i = levels - 1; i >= 0; i--) {
            while (x.next[i] != null && x.next[i].value.compareTo(value) < 0) {
                x = x.next[i];
            }
        }
        x = x.next[0];
        return x != null && x.value.compareTo(value) == 0;
    }

    public boolean add(T value) {
        @SuppressWarnings("unchecked")
        Node<T>[] update = (Node<T>[]) new Node[MAX_LEVEL];
        Node<T> x = descend(value, update);
        Node<T> next = x.next[0];
        if (next != null && next.value.compareTo(value) == 0) {
            return false; // already present (a set, no duplicates)
        }
        int lvl = randomLevel();
        if (lvl > levels) {
            for (int i = levels; i < lvl; i++) {
                update[i] = head;
            }
            levels = lvl;
        }
        Node<T> node = new Node<>(value, lvl);
        for (int i = 0; i < lvl; i++) {
            node.next[i] = update[i].next[i];
            update[i].next[i] = node;
        }
        size++;
        return true;
    }

    public boolean remove(T value) {
        @SuppressWarnings("unchecked")
        Node<T>[] update = (Node<T>[]) new Node[MAX_LEVEL];
        descend(value, update);
        Node<T> target = update[0].next[0];
        if (target == null || target.value.compareTo(value) != 0) {
            return false;
        }
        for (int i = 0; i < levels; i++) {
            if (update[i].next[i] == target) {
                update[i].next[i] = target.next[i];
            }
        }
        while (levels > 1 && head.next[levels - 1] == null) {
            levels--; // drop now-empty express lanes
        }
        size--;
        return true;
    }

    /** All values in ascending order. */
    public List<T> toList() {
        List<T> out = new ArrayList<>(size);
        for (Node<T> x = head.next[0]; x != null; x = x.next[0]) {
            out.add(x.value);
        }
        return out;
    }

    /** Values in [from, to], inclusive, ascending — the skip list's reason to exist over a hash set. */
    public List<T> range(T from, T to) {
        List<T> out = new ArrayList<>();
        Node<T> x = head;
        for (int i = levels - 1; i >= 0; i--) {
            while (x.next[i] != null && x.next[i].value.compareTo(from) < 0) {
                x = x.next[i];
            }
        }
        for (x = x.next[0]; x != null && x.value.compareTo(to) <= 0; x = x.next[0]) {
            out.add(x.value);
        }
        return out;
    }

    public T first() {
        return head.next[0] == null ? null : head.next[0].value;
    }

    public int size() {
        return size;
    }

    // walk down the lanes, recording the last node before `value` at each level into `update`
    private Node<T> descend(T value, Node<T>[] update) {
        Node<T> x = head;
        for (int i = levels - 1; i >= 0; i--) {
            while (x.next[i] != null && x.next[i].value.compareTo(value) < 0) {
                x = x.next[i];
            }
            update[i] = x;
        }
        return x;
    }

    private int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL && random.nextDouble() < P) {
            lvl++;
        }
        return lvl;
    }
}
