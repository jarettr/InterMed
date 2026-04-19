package org.intermed.core.registry;

import java.util.Map;

/**
 * Frozen identity-based object -> int lookup backed only by flat arrays.
 *
 * <p>The structure is used on the frozen registry path for raw-id lookups by
 * object identity. It intentionally relies on identity equality ({@code ==})
 * instead of {@link Object#equals(Object)}.
 */
final class FrozenIdentityIntLookup {

    private static final FrozenIdentityIntLookup EMPTY =
        new FrozenIdentityIntLookup(new Object[0], new int[0], 0);

    private final Object[] keys;
    private final int[] values;
    private final int mask;

    private FrozenIdentityIntLookup(Object[] keys, int[] values, int mask) {
        this.keys = keys;
        this.values = values;
        this.mask = mask;
    }

    static FrozenIdentityIntLookup empty() {
        return EMPTY;
    }

    static FrozenIdentityIntLookup build(Map<Object, Integer> entries) {
        if (entries == null || entries.isEmpty()) {
            return empty();
        }

        int capacity = 1;
        int target = Math.max(2, entries.size() * 2);
        while (capacity < target) {
            capacity <<= 1;
        }

        Object[] keys = new Object[capacity];
        int[] values = new int[capacity];
        int mask = capacity - 1;
        entries.forEach((key, value) -> insert(keys, values, mask, key, value));
        return new FrozenIdentityIntLookup(keys, values, mask);
    }

    int getOrDefault(Object key, int defaultValue) {
        if (key == null || keys.length == 0) {
            return defaultValue;
        }

        int slot = mix(System.identityHashCode(key)) & mask;
        while (true) {
            Object candidate = keys[slot];
            if (candidate == null) {
                return defaultValue;
            }
            if (candidate == key) {
                return values[slot];
            }
            slot = (slot + 1) & mask;
        }
    }

    boolean isEmpty() {
        return keys.length == 0;
    }

    private static void insert(Object[] keys, int[] values, int mask, Object key, Integer value) {
        if (key == null || value == null) {
            return;
        }

        int slot = mix(System.identityHashCode(key)) & mask;
        while (true) {
            Object existing = keys[slot];
            if (existing == null || existing == key) {
                keys[slot] = key;
                values[slot] = value;
                return;
            }
            slot = (slot + 1) & mask;
        }
    }

    private static int mix(int value) {
        int hash = value;
        hash ^= (hash >>> 16);
        hash *= 0x7feb352d;
        hash ^= (hash >>> 15);
        hash *= 0x846ca68b;
        hash ^= (hash >>> 16);
        return hash;
    }
}
