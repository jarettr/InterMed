package org.intermed.core.registry;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fast in-memory object store for registry virtualisation (ТЗ 3.2.2, Requirement 3).
 *
 * <p>The registry keeps three linked views of the same data:
 * <ul>
 *   <li>namespace key -> object slot</li>
 *   <li>global/raw id -> object slot</li>
 *   <li>object instance -> global/raw id</li>
 * </ul>
 *
 * <p>After {@link #freeze()} the namespace and raw-id views are serviced by flat
 * arrays so hot-path reads no longer depend on concurrent hash maps.
 */
public final class VirtualRegistry {

    private static final ConcurrentHashMap<String, Object> REGISTRY_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Integer> KEY_TO_SLOT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, String> SLOT_TO_KEY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, Integer> RAW_ID_TO_SLOT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Object, Integer> OBJECT_TO_RAW_ID = new ConcurrentHashMap<>();

    private static volatile FrozenStringIntHashIndex FROZEN_KEY_TO_SLOT = FrozenStringIntHashIndex.empty();
    private static volatile Object[] FROZEN_TABLE = null;
    private static volatile int[] FROZEN_RAW_ID_TO_SLOT = null;
    private static volatile FrozenIdentityIntLookup FROZEN_OBJECT_TO_RAW_ID = FrozenIdentityIntLookup.empty();
    private static volatile boolean FROZEN = false;
    /**
     * Monotonically increasing slot counter.
     *
     * <p>Plain {@code int} (not {@code volatile}, not {@code AtomicInteger}) is
     * intentional: every write path ({@link #register} and {@link #resetForTests})
     * is {@code synchronized} on {@link VirtualRegistry}.class, which provides the
     * required happens-before guarantee between the write and any subsequent
     * {@code synchronized} read.  No unsynchronized path increments this field.
     */
    private static int nextSlot = 0;

    static final MethodHandle GET_FAST_BY_ID_HANDLE;
    static final MethodHandle GET_FAST_HANDLE;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            GET_FAST_BY_ID_HANDLE = lookup.findStatic(VirtualRegistry.class,
                "getFastById", MethodType.methodType(Object.class, int.class));
            GET_FAST_HANDLE = lookup.findStatic(VirtualRegistry.class,
                "getFast", MethodType.methodType(Object.class, Object.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private VirtualRegistry() {}

    public static synchronized int register(String key, Object value) {
        return register(key, value, -1);
    }

    public static synchronized int register(String key, Object value, int rawId) {
        Object previous = REGISTRY_MAP.put(key, value);
        int slot = KEY_TO_SLOT.computeIfAbsent(key, ignored -> nextSlot++);
        SLOT_TO_KEY.putIfAbsent(slot, key);

        if (previous != null && previous != value) {
            OBJECT_TO_RAW_ID.remove(previous);
        }
        if (rawId >= 0 && value != null) {
            RAW_ID_TO_SLOT.put(rawId, slot);
            OBJECT_TO_RAW_ID.put(value, rawId);
        }
        if (FROZEN) {
            rebuildFrozenTables();
        }
        return slot;
    }

    public static synchronized void freeze() {
        rebuildFrozenTables();
        System.out.printf("[VirtualRegistry] Frozen: %d entries in flat table.%n", FROZEN_TABLE.length);
    }

    public static boolean isFrozen() {
        return FROZEN;
    }

    public static Object getFastById(int slot) {
        Object[] table = FROZEN_TABLE;
        if (table != null && slot >= 0 && slot < table.length) {
            return table[slot];
        }
        String key = SLOT_TO_KEY.get(slot);
        return key != null ? REGISTRY_MAP.get(key) : null;
    }

    public static Object getFastByRawId(int rawId) {
        int[] rawIdTable = FROZEN_RAW_ID_TO_SLOT;
        if (rawIdTable != null && rawId >= 0 && rawId < rawIdTable.length) {
            int encodedSlot = rawIdTable[rawId];
            return encodedSlot == 0 ? null : getFastById(encodedSlot - 1);
        }
        Integer slot = RAW_ID_TO_SLOT.get(rawId);
        return slot != null ? getFastById(slot) : null;
    }

    public static Object getFast(String key) {
        Object[] table = FROZEN_TABLE;
        if (table != null) {
            int slot = FROZEN_KEY_TO_SLOT.getOrDefault(key, -1);
            if (slot >= 0 && slot < table.length) {
                return table[slot];
            }
            return null;
        }
        return REGISTRY_MAP.get(key);
    }

    public static Object getFast(Object key) {
        return key == null ? null : getFast(String.valueOf(key));
    }

    public static int getRawIdFast(Object value) {
        if (value == null) {
            return -1;
        }
        FrozenIdentityIntLookup frozenObjectIds = FROZEN_OBJECT_TO_RAW_ID;
        if (!frozenObjectIds.isEmpty()) {
            return frozenObjectIds.getOrDefault(value, -1);
        }
        Integer rawId = OBJECT_TO_RAW_ID.get(value);
        return rawId != null ? rawId : -1;
    }

    public static int slotOf(String key) {
        if (FROZEN) {
            return FROZEN_KEY_TO_SLOT.getOrDefault(key, -1);
        }
        Integer slot = KEY_TO_SLOT.get(key);
        return slot != null ? slot : -1;
    }

    public static Object redirectRegister(Object registry, Object key, Object value) {
        if (key != null && value != null) {
            register(String.valueOf(key), value);
        }
        return value;
    }

    public static void resetForTests() {
        synchronized (VirtualRegistry.class) {
            REGISTRY_MAP.clear();
            KEY_TO_SLOT.clear();
            SLOT_TO_KEY.clear();
            RAW_ID_TO_SLOT.clear();
            OBJECT_TO_RAW_ID.clear();
            nextSlot = 0;
            FROZEN_KEY_TO_SLOT = FrozenStringIntHashIndex.empty();
            FROZEN_TABLE = null;
            FROZEN_RAW_ID_TO_SLOT = null;
            FROZEN_OBJECT_TO_RAW_ID = FrozenIdentityIntLookup.empty();
            FROZEN = false;
        }
    }

    private static void rebuildFrozenTables() {
        Object[] table = new Object[nextSlot];
        KEY_TO_SLOT.forEach((key, slot) -> table[slot] = REGISTRY_MAP.get(key));
        FROZEN_KEY_TO_SLOT = FrozenStringIntHashIndex.build(KEY_TO_SLOT);
        FROZEN_TABLE = table;
        FROZEN_OBJECT_TO_RAW_ID = FrozenIdentityIntLookup.build(OBJECT_TO_RAW_ID);

        int maxRawId = RAW_ID_TO_SLOT.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        if (maxRawId >= 0) {
            int[] rawIdTable = new int[maxRawId + 1];
            RAW_ID_TO_SLOT.forEach((rawId, slot) -> {
                if (rawId >= 0 && rawId < rawIdTable.length) {
                    rawIdTable[rawId] = slot + 1;
                }
            });
            FROZEN_RAW_ID_TO_SLOT = rawIdTable;
        } else {
            FROZEN_RAW_ID_TO_SLOT = null;
        }

        FROZEN = true;
    }

    static boolean frozenKeyLookupIsMinimalPerfect() {
        return FROZEN_KEY_TO_SLOT.isMinimalPerfect();
    }

    static String frozenKeyLookupImplementationName() {
        return FROZEN_KEY_TO_SLOT.implementationName();
    }
}
