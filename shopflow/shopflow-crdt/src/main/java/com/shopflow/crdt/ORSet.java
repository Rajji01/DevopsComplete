package com.shopflow.crdt;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Observed-Remove Set (OR-Set): a state-based CRDT set where concurrent add and remove of the same element
 * resolve to ADD-WINS, without coordination. The naive "two grow-only sets, one of adds one of removes"
 * (2P-Set) can never re-add a removed element; the OR-Set fixes that by tagging every add with a UNIQUE id.
 *
 * <h3>How add-wins falls out of the tags</h3>
 * {@code add(e)} records a fresh unique tag for {@code e}. {@code remove(e)} tombstones exactly the tags it
 * can SEE right now (the "observed" in the name). An element is present iff it has at least one add-tag that
 * is not tombstoned. So if replica A removes {@code e} (tombstoning the tags it has seen) while replica B
 * concurrently adds {@code e} again (a brand-new tag A never saw), the merge keeps B's new tag alive →
 * {@code e} is present. Add wins over a concurrent remove, which is the intuitive outcome ("someone just
 * added it back"). A remove only cancels the adds that happened-before it.
 *
 * <h3>Merge</h3>
 * {@link #mergeFrom} is the union of the add-tag sets and the union of the tombstone sets — set union is
 * commutative, associative and idempotent, so the OR-Set converges like any CvRDT. Cost: tombstones
 * accumulate (production OR-Sets compact them with version vectors; this teaching version keeps them).
 *
 * <p>Uses: a shopping cart replicated across devices, a collaborative tag/label set, a presence set — any
 * "set that several replicas edit offline and reconcile" where you want add-wins and no lost re-adds.
 *
 * <p>Not thread-safe: one writer per replica instance, or guard externally.
 *
 * @param <E> element type; used as a map key, so it must have sane {@code equals}/{@code hashCode}.
 */
public final class ORSet<E> {

    private final Map<E, Set<String>> addTags = new HashMap<>();   // element -> unique tags of its adds
    private final Map<E, Set<String>> tombstones = new HashMap<>(); // element -> add-tags that were removed
    private final Supplier<String> tagGenerator;

    public ORSet() {
        this(() -> UUID.randomUUID().toString());
    }

    /** Test/determinism hook: inject the unique-tag source (must still be globally unique across replicas). */
    public ORSet(Supplier<String> tagGenerator) {
        this.tagGenerator = tagGenerator;
    }

    /** Add {@code e} with a fresh unique tag. Re-adding after a remove is fine — it is a new, un-tombstoned tag. */
    public void add(E e) {
        addTags.computeIfAbsent(e, k -> new HashSet<>()).add(tagGenerator.get());
    }

    /** Remove {@code e} by tombstoning every add-tag this replica has OBSERVED for it so far. */
    public void remove(E e) {
        Set<String> observed = addTags.get(e);
        if (observed != null && !observed.isEmpty()) {
            tombstones.computeIfAbsent(e, k -> new HashSet<>()).addAll(observed);
        }
    }

    /** Present iff {@code e} has at least one add-tag that has not been tombstoned. */
    public boolean contains(E e) {
        Set<String> tags = addTags.get(e);
        if (tags == null) {
            return false;
        }
        Set<String> removed = tombstones.getOrDefault(e, Set.of());
        for (String tag : tags) {
            if (!removed.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    /** The live elements (those with at least one un-tombstoned add-tag). */
    public Set<E> elements() {
        Set<E> live = new HashSet<>();
        for (E e : addTags.keySet()) {
            if (contains(e)) {
                live.add(e);
            }
        }
        return live;
    }

    /** Converge with another replica: union the add-tags and union the tombstones, element by element. */
    public void mergeFrom(ORSet<E> other) {
        other.addTags.forEach((e, tags) ->
                addTags.computeIfAbsent(e, k -> new HashSet<>()).addAll(tags));
        other.tombstones.forEach((e, tags) ->
                tombstones.computeIfAbsent(e, k -> new HashSet<>()).addAll(tags));
    }
}
