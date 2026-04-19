package org.intermed.core.vfs;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;

/**
 * Last-Write-Wins Element Set (LWW-Element-Set) CRDT for commutative set merging
 * (ТЗ 3.5.6).
 *
 * <h3>Semantics</h3>
 * Each element is independently tracked by its most recent add and remove
 * timestamp.  An element is considered <em>present</em> when its latest add
 * timestamp is strictly greater than its latest remove timestamp (add-wins tie
 * resolution is not used here; remove wins on ties for safety in server contexts).
 *
 * <h3>Merge</h3>
 * {@link #merge(LwwElementSet)} is commutative, associative, and idempotent —
 * the three requirements for a proper CRDT.  Two replicas on different servers
 * will converge to the same state when their LWW-Sets are merged, regardless of
 * message ordering.
 *
 * <h3>Usage</h3>
 * <pre>
 *   LwwElementSet&lt;String&gt; set = new LwwElementSet&lt;&gt;();
 *   set.add("minecraft:diamond", clock.now());
 *   set.add("minecraft:iron",   clock.now());
 *   set.remove("minecraft:iron", clock.now() + 1);
 *   set.contains("minecraft:diamond"); // true
 *   set.contains("minecraft:iron");    // false
 * </pre>
 *
 * @param <E> element type (must have a stable {@link #equals}/{@link #hashCode})
 */
public final class LwwElementSet<E> {

    /**
     * Internal per-element record keeping the last add and remove timestamps.
     * Both default to {@code Long.MIN_VALUE} (not present).
     */
    private static final class Entry {
        long addTs    = Long.MIN_VALUE;
        long removeTs = Long.MIN_VALUE;
    }

    /** Element → last-write-wins metadata. */
    private final Map<E, Entry> table;

    public LwwElementSet() {
        this.table = new HashMap<>();
    }

    /** Copy constructor — used internally by {@link #copy()}. */
    private LwwElementSet(Map<E, Entry> source) {
        this.table = new HashMap<>(source.size() * 2);
        for (Map.Entry<E, Entry> e : source.entrySet()) {
            Entry copy = new Entry();
            copy.addTs    = e.getValue().addTs;
            copy.removeTs = e.getValue().removeTs;
            this.table.put(e.getKey(), copy);
        }
    }

    // ── Mutation ──────────────────────────────────────────────────────────────

    /**
     * Records an add of {@code element} at {@code timestamp}.
     * Does nothing if the element's existing add timestamp is newer.
     */
    public void add(E element, long timestamp) {
        Entry entry = table.computeIfAbsent(element, k -> new Entry());
        if (timestamp > entry.addTs) {
            entry.addTs = timestamp;
        }
    }

    /**
     * Records a remove of {@code element} at {@code timestamp}.
     * Does nothing if the element's existing remove timestamp is newer.
     */
    public void remove(E element, long timestamp) {
        Entry entry = table.computeIfAbsent(element, k -> new Entry());
        if (timestamp > entry.removeTs) {
            entry.removeTs = timestamp;
        }
    }

    // ── Query ─────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if {@code element} is present in the set.
     * An element is present when {@code addTs > removeTs}.
     */
    public boolean contains(E element) {
        Entry entry = table.get(element);
        return entry != null && entry.addTs > entry.removeTs;
    }

    /**
     * Returns a snapshot of all currently present elements.
     * The returned set is a new, independent collection.
     */
    public Set<E> elements() {
        Set<E> result = new HashSet<>();
        for (Map.Entry<E, Entry> e : table.entrySet()) {
            if (e.getValue().addTs > e.getValue().removeTs) {
                result.add(e.getKey());
            }
        }
        return Collections.unmodifiableSet(result);
    }

    /** Returns the number of elements tracked (including tombstoned removes). */
    public int tableSize() {
        return table.size();
    }

    // ── CRDT merge ────────────────────────────────────────────────────────────

    /**
     * Merges {@code other} into this set in-place, taking the maximum timestamp
     * for each element's add and remove entries.
     *
     * <p>This operation is <b>commutative</b>, <b>associative</b>, and
     * <b>idempotent</b>.
     *
     * @param other  the remote replica to merge in; must not be null
     */
    public void merge(LwwElementSet<E> other) {
        for (Map.Entry<E, Entry> remote : other.table.entrySet()) {
            Entry local = table.computeIfAbsent(remote.getKey(), k -> new Entry());
            Entry r = remote.getValue();
            if (r.addTs > local.addTs)       local.addTs    = r.addTs;
            if (r.removeTs > local.removeTs) local.removeTs = r.removeTs;
        }
    }

    /**
     * Returns a deep copy of this set that shares no mutable state with the original.
     */
    public LwwElementSet<E> copy() {
        return new LwwElementSet<>(table);
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    /** Returns the last add timestamp for {@code element}, or {@code Long.MIN_VALUE}. */
    public long addTimestamp(E element) {
        Entry e = table.get(element);
        return e != null ? e.addTs : Long.MIN_VALUE;
    }

    /** Returns the last remove timestamp for {@code element}, or {@code Long.MIN_VALUE}. */
    public long removeTimestamp(E element) {
        Entry e = table.get(element);
        return e != null ? e.removeTs : Long.MIN_VALUE;
    }

    @Override
    public String toString() {
        return "LwwElementSet{size=" + tableSize() + ", present=" + elements().size() + "}";
    }
}
