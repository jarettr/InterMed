package org.intermed.core.registry;

import org.intermed.core.security.CapabilityManager;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Registry virtualisation service (ТЗ 3.2.2, Requirement 3).
 *
 * <p>Canonical two-level mapping:
 * <pre>
 *   (modId, registryScope + namespacePath) -> globalId
 *   globalId                               -> namespacePath
 * </pre>
 *
 * <p>The object payload itself is stored in {@link VirtualRegistry}, which is
 * linked to the same global/raw ids through {@link #registerVirtualized}.
 */
public final class VirtualRegistryService {

    static final int GLOBAL_ID_OFFSET = 100_000;

    private static final ConcurrentHashMap<String, Integer> CANONICAL_IDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, String> GLOBAL_REVERSE_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, Integer>> MOD_LOCAL_MAP =
        new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, String> STORAGE_KEYS = new ConcurrentHashMap<>();

    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(GLOBAL_ID_OFFSET);

    private static volatile Map<String, Map<String, Integer>> frozenLocalMaps = null;
    private static volatile FrozenStringIntHashIndex frozenCanonicalIndex = FrozenStringIntHashIndex.empty();
    private static volatile FrozenModScopeIndex frozenModScopeIndex = FrozenModScopeIndex.empty();
    private static volatile String[] frozenOriginalIdsByRawId = new String[0];
    private static volatile int frozenCanonicalCount = 0;
    private static volatile int[] rawIdTable = null;
    private static volatile boolean frozen = false;
    private static final String REGISTRY_SCOPE_SEPARATOR = "@@";

    private VirtualRegistryService() {}

    public static int resolveVirtualId(String modId, String namespacePath, int originalId) {
        return resolveVirtualId(modId, "", namespacePath, originalId);
    }

    public static int resolveVirtualId(String modId, String registryScope, String namespacePath, int originalId) {
        String normalizedModId = normalizeModId(modId);
        String scopedNamespacePath = scopedNamespacePath(registryScope, namespacePath);
        ConcurrentHashMap<String, Integer> localMap =
            MOD_LOCAL_MAP.computeIfAbsent(normalizedModId, ignored -> new ConcurrentHashMap<>());

        Integer existing = localMap.get(scopedNamespacePath);
        if (existing != null) {
            return existing;
        }

        synchronized (VirtualRegistryService.class) {
            Integer resolved = localMap.get(scopedNamespacePath);
            if (resolved != null) {
                return resolved;
            }

            Integer canonicalId = CANONICAL_IDS.get(scopedNamespacePath);
            int globalId;
            if (canonicalId == null) {
                globalId = allocatePreferredGlobalId(namespacePath, originalId);
                CANONICAL_IDS.put(scopedNamespacePath, globalId);
            } else {
                globalId = allocateShardedGlobalId(namespacePath);
            }

            GLOBAL_REVERSE_MAP.putIfAbsent(globalId, namespacePath);
            STORAGE_KEYS.putIfAbsent(globalId, storageKey(globalId, scopedNamespacePath));
            localMap.put(scopedNamespacePath, globalId);
            return globalId;
        }
    }

    public static int resolveVirtualId(String namespacePath, int originalId) {
        return resolveVirtualId("unknown", namespacePath, originalId);
    }

    public static int registerVirtualized(String modId, String namespacePath, int originalId, Object value) {
        return registerVirtualized(modId, "", namespacePath, originalId, value);
    }

    public static int registerVirtualized(String modId,
                                          String registryScope,
                                          String namespacePath,
                                          int originalId,
                                          Object value) {
        String scopedNamespacePath = scopedNamespacePath(registryScope, namespacePath);
        int globalId = resolveVirtualId(modId, registryScope, namespacePath, originalId);
        if (value != null) {
            VirtualRegistry.register(storageKey(globalId, scopedNamespacePath), value, globalId);
            if (frozen) {
                refreshFrozenViews(false);
            }
        }
        return globalId;
    }

    public static int registerVirtualized(String namespacePath, int originalId, Object value) {
        return registerVirtualized(CapabilityManager.currentModIdOr("unknown"), namespacePath, originalId, value);
    }

    public static String resolveOriginalId(String modId, int globalId) {
        if (frozen) {
            String[] originalIds = frozenOriginalIdsByRawId;
            if (globalId >= 0 && globalId < originalIds.length) {
                String originalId = originalIds[globalId];
                if (originalId != null) {
                    return originalId;
                }
            }
        } else {
            String globalValue = GLOBAL_REVERSE_MAP.get(globalId);
            if (globalValue != null) {
                return globalValue;
            }
        }

        ConcurrentHashMap<String, Integer> localMap = MOD_LOCAL_MAP.get(normalizeModId(modId));
        if (localMap == null) {
            return null;
        }
        return localMap.entrySet().stream()
            .filter(entry -> entry.getValue() == globalId)
            .map(Map.Entry::getKey)
            .map(VirtualRegistryService::originalNamespacePath)
            .findFirst()
            .orElse(null);
    }

    public static Map<String, Integer> getLocalMap(String modId) {
        String normalizedModId = normalizeModId(modId);
        Map<String, Map<String, Integer>> localMaps = frozenLocalMaps;
        if (localMaps != null) {
            return localMaps.getOrDefault(normalizedModId, Map.of());
        }
        return MOD_LOCAL_MAP.getOrDefault(normalizedModId, new ConcurrentHashMap<>());
    }

    public static boolean isValidId(int globalId) {
        int[] table = rawIdTable;
        if (table != null) {
            return globalId >= 0 && globalId < table.length && table[globalId] == 1;
        }
        return GLOBAL_REVERSE_MAP.containsKey(globalId);
    }

    public static int lookupGlobalId(String namespacePath) {
        return lookupGlobalId(null, namespacePath);
    }

    public static int lookupGlobalId(String modId, String namespacePath) {
        return lookupGlobalId(modId, "", namespacePath);
    }

    public static int lookupGlobalId(String modId, String registryScope, String namespacePath) {
        String effectiveModId = effectiveModId(modId);
        String scopedNamespacePath = scopedNamespacePath(registryScope, namespacePath);
        if (frozen) {
            int id = frozenModScopeIndex.lookupExact(effectiveModId, scopedNamespacePath);
            if (id >= 0) {
                return id;
            }
            if (registryScope == null || registryScope.isBlank()) {
                int fallbackId = frozenModScopeIndex.lookupLegacy(effectiveModId, namespacePath);
                if (fallbackId >= 0) {
                    return fallbackId;
                }
            }
        } else {
            Map<String, Integer> localMap = MOD_LOCAL_MAP.get(effectiveModId);
            if (localMap != null) {
                Integer id = localMap.get(scopedNamespacePath);
                if (id != null) {
                    return id;
                }
                if (registryScope == null || registryScope.isBlank()) {
                    int fallbackId = legacyScopedLookup(localMap, namespacePath);
                    if (fallbackId >= 0) {
                        return fallbackId;
                    }
                }
            }
            Integer id = CANONICAL_IDS.get(scopedNamespacePath);
            return id != null ? id : -1;
        }

        if (frozen) {
            return frozenCanonicalIndex.getOrDefault(scopedNamespacePath, -1);
        }
        return -1;
    }

    public static Object lookupValue(String namespacePath) {
        return lookupValue(null, namespacePath);
    }

    public static Object lookupValue(String modId, Object key) {
        return lookupValue(modId, "", key);
    }

    public static Object lookupValue(String modId, Object registryCandidate, Object key) {
        return lookupValue(modId, RegistryIdentity.scopeOf(registryCandidate), key);
    }

    public static Object lookupValue(String modId, Object receiver, Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Object key = args[args.length - 1];
        if (key == null) {
            return null;
        }
        return lookupValue(modId, RegistryIdentity.scopeOfLookup(receiver, args), key);
    }

    public static Object lookupValue(String modId, String registryScope, Object key) {
        if (key == null) {
            return null;
        }
        String namespacePath = String.valueOf(key);
        int globalId = lookupGlobalId(modId, registryScope, namespacePath);
        return globalId >= 0 ? VirtualRegistry.getFastByRawId(globalId) : null;
    }

    public static Object lookupValueForCurrentMod(Object key) {
        return lookupValue(null, key);
    }

    public static Object lookupValueForCurrentMod(Object registryCandidate, Object key) {
        return lookupValue(null, registryCandidate, key);
    }

    public static Object lookupValueForCurrentMod(Object receiver, Object[] args) {
        return lookupValue(null, receiver, args);
    }

    public static Object lookupValueByGlobalId(int globalId) {
        return VirtualRegistry.getFastByRawId(globalId);
    }

    public static synchronized void freeze() {
        refreshFrozenViews(true);
    }

    /**
     * Returns the current frozen canonical registry snapshot encoded for the
     * multiplayer registry-sync handshake.
     *
     * <p>If the registry has not yet been frozen, this method performs the
     * freeze first so the returned snapshot always matches the hot-path MPHF
     * state used by runtime lookups.
     */
    public static synchronized byte[] buildRegistrySyncSnapshot() {
        if (!frozen) {
            refreshFrozenViews(true);
        }
        return RegistryTranslationMatrix.serialiseSnapshot(frozenCanonicalIndex);
    }

    public static boolean isFrozen() {
        return frozen;
    }

    public static void resetForTests() {
        CANONICAL_IDS.clear();
        GLOBAL_REVERSE_MAP.clear();
        MOD_LOCAL_MAP.clear();
        STORAGE_KEYS.clear();
        ID_GENERATOR.set(GLOBAL_ID_OFFSET);
        frozenLocalMaps = null;
        frozenCanonicalIndex = FrozenStringIntHashIndex.empty();
        frozenModScopeIndex = FrozenModScopeIndex.empty();
        frozenOriginalIdsByRawId = new String[0];
        frozenCanonicalCount = 0;
        rawIdTable = null;
        frozen = false;
        VirtualRegistry.resetForTests();
    }

    private static int allocatePreferredGlobalId(String namespacePath, int originalId) {
        if (originalId >= 0 && reserveGlobalId(originalId, namespacePath)) {
            return originalId;
        }
        return allocateShardedGlobalId(namespacePath);
    }

    private static int allocateShardedGlobalId(String namespacePath) {
        while (true) {
            int globalId = ID_GENERATOR.getAndIncrement();
            if (reserveGlobalId(globalId, namespacePath)) {
                return globalId;
            }
        }
    }

    private static boolean reserveGlobalId(int globalId, String namespacePath) {
        if (globalId < 0) {
            return false;
        }
        boolean reserved = GLOBAL_REVERSE_MAP.putIfAbsent(globalId, namespacePath) == null;
        if (reserved && globalId >= GLOBAL_ID_OFFSET) {
            ID_GENERATOR.updateAndGet(current -> Math.max(current, globalId + 1));
        }
        return reserved;
    }

    private static Map<String, Map<String, Integer>> snapshotLocalMaps() {
        Map<String, Map<String, Integer>> snapshot = new HashMap<>();
        MOD_LOCAL_MAP.forEach((modId, localMap) -> snapshot.put(modId, Map.copyOf(localMap)));
        return Map.copyOf(snapshot);
    }

    private static synchronized void refreshFrozenViews(boolean explicitFreeze) {
        Map<String, Map<String, Integer>> localSnapshot = snapshotLocalMaps();
        frozenLocalMaps = localSnapshot;
        frozenCanonicalIndex = FrozenStringIntHashIndex.build(CANONICAL_IDS);
        frozenModScopeIndex = FrozenModScopeIndex.build(localSnapshot);
        frozenOriginalIdsByRawId = buildOriginalIdTable();
        frozenCanonicalCount = CANONICAL_IDS.size();

        int maxId = GLOBAL_REVERSE_MAP.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        int size = Math.max(0, maxId + 1);
        int[] tbl = size == 0 ? new int[0] : new int[size];
        GLOBAL_REVERSE_MAP.keySet().forEach(id -> {
            if (id >= 0 && id < size) {
                tbl[id] = 1;
            }
        });
        rawIdTable = tbl;
        frozen = true;

        VirtualRegistry.freeze();

        if (explicitFreeze) {
            if (size > 0) {
                System.out.printf(
                    "[VirtualRegistryService] Frozen: %d virtual IDs, rawIdTable[0..%d].%n",
                    frozenCanonicalCount, size - 1
                );
            } else {
                System.out.printf(
                    "[VirtualRegistryService] Frozen: %d virtual IDs, rawIdTable empty.%n",
                    frozenCanonicalCount
                );
            }
            RegistryLinker.freeze();
        } else {
            System.out.printf(
                "[VirtualRegistryService] Refreshed frozen views after late registration. Entries=%d.%n",
                frozenCanonicalCount
            );
        }
    }

    private static String[] buildOriginalIdTable() {
        int maxId = GLOBAL_REVERSE_MAP.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        if (maxId < 0) {
            return new String[0];
        }
        String[] table = new String[maxId + 1];
        GLOBAL_REVERSE_MAP.forEach((globalId, originalId) -> {
            if (globalId >= 0 && globalId < table.length) {
                table[globalId] = originalId;
            }
        });
        return table;
    }

    private static String effectiveModId(String requestedModId) {
        if (requestedModId != null && !requestedModId.isBlank()) {
            return requestedModId;
        }
        // Fast path: if the registry is frozen and has no virtual entries, the
        // lookup will return null regardless of caller identity.  Skip the
        // expensive CapabilityManager stack walk that fires on every
        // Minecraft registry.get() call (entity render, item bake, etc.).
        if (frozen && frozenCanonicalCount == 0) {
            return "unknown";
        }
        return CapabilityManager.currentModIdOr("unknown");
    }

    private static String normalizeModId(String modId) {
        return (modId == null || modId.isBlank()) ? "unknown" : modId;
    }

    private static String scopedNamespacePath(String registryScope, String namespacePath) {
        if (namespacePath == null || namespacePath.isBlank()) {
            return "";
        }
        String normalizedScope = normalizeRegistryScope(registryScope);
        if (normalizedScope.isBlank()) {
            return namespacePath;
        }
        return normalizedScope + REGISTRY_SCOPE_SEPARATOR + namespacePath;
    }

    private static String originalNamespacePath(String scopedNamespacePath) {
        int separator = scopedNamespacePath.indexOf(REGISTRY_SCOPE_SEPARATOR);
        if (separator < 0) {
            return scopedNamespacePath;
        }
        return scopedNamespacePath.substring(separator + REGISTRY_SCOPE_SEPARATOR.length());
    }

    private static int legacyScopedLookup(Map<String, Integer> entries, String namespacePath) {
        if (entries == null || namespacePath == null || namespacePath.isBlank()) {
            return -1;
        }
        Integer resolved = null;
        for (Map.Entry<String, Integer> entry : entries.entrySet()) {
            if (!namespacePath.equals(originalNamespacePath(entry.getKey()))) {
                continue;
            }
            if (resolved != null && !resolved.equals(entry.getValue())) {
                return -1;
            }
            resolved = entry.getValue();
        }
        return resolved != null ? resolved : -1;
    }

    static boolean frozenCanonicalLookupIsMinimalPerfect() {
        return frozenCanonicalIndex.isMinimalPerfect();
    }

    static String frozenCanonicalLookupImplementationName() {
        return frozenCanonicalIndex.implementationName();
    }

    static String frozenLocalLookupImplementationName(String modId) {
        return frozenModScopeIndex.exactImplementationName(modId);
    }

    static String frozenLegacyLookupImplementationName(String modId) {
        return frozenModScopeIndex.legacyImplementationName(modId);
    }

    private record FrozenModLookup(FrozenStringIntHashIndex exactIndex,
                                   FrozenStringIntHashIndex legacyIndex) {

        private static FrozenModLookup build(Map<String, Integer> entries) {
            if (entries == null || entries.isEmpty()) {
                return new FrozenModLookup(FrozenStringIntHashIndex.empty(), FrozenStringIntHashIndex.empty());
            }

            return new FrozenModLookup(
                FrozenStringIntHashIndex.build(entries),
                FrozenStringIntHashIndex.build(buildLegacyEntries(entries))
            );
        }

        private int lookup(String scopedNamespacePath) {
            return exactIndex.getOrDefault(scopedNamespacePath, -1);
        }

        private int legacyLookup(String namespacePath) {
            return legacyIndex.getOrDefault(namespacePath, -1);
        }
    }

    private record FrozenModScopeIndex(FrozenStringIntHashIndex modIdIndex,
                                       FrozenModLookup[] lookups) {

        private static FrozenModScopeIndex empty() {
            return new FrozenModScopeIndex(FrozenStringIntHashIndex.empty(), new FrozenModLookup[0]);
        }

        private static FrozenModScopeIndex build(Map<String, Map<String, Integer>> snapshot) {
            if (snapshot == null || snapshot.isEmpty()) {
                return empty();
            }

            Map<String, Integer> modIdSlots = new HashMap<>();
            FrozenModLookup[] lookups = new FrozenModLookup[snapshot.size()];
            int cursor = 0;
            for (Map.Entry<String, Map<String, Integer>> entry : snapshot.entrySet()) {
                modIdSlots.put(entry.getKey(), cursor);
                lookups[cursor] = FrozenModLookup.build(entry.getValue());
                cursor++;
            }

            return new FrozenModScopeIndex(FrozenStringIntHashIndex.build(modIdSlots), lookups);
        }

        private int lookupExact(String modId, String scopedNamespacePath) {
            int slot = modIdIndex.getOrDefault(normalizeModId(modId), -1);
            if (slot < 0 || slot >= lookups.length) {
                return -1;
            }
            return lookups[slot].lookup(scopedNamespacePath);
        }

        private int lookupLegacy(String modId, String namespacePath) {
            int slot = modIdIndex.getOrDefault(normalizeModId(modId), -1);
            if (slot < 0 || slot >= lookups.length) {
                return -1;
            }
            return lookups[slot].legacyLookup(namespacePath);
        }

        private String exactImplementationName(String modId) {
            int slot = modIdIndex.getOrDefault(normalizeModId(modId), -1);
            if (slot < 0 || slot >= lookups.length) {
                return FrozenStringIntHashIndex.empty().implementationName();
            }
            return lookups[slot].exactIndex().implementationName();
        }

        private String legacyImplementationName(String modId) {
            int slot = modIdIndex.getOrDefault(normalizeModId(modId), -1);
            if (slot < 0 || slot >= lookups.length) {
                return FrozenStringIntHashIndex.empty().implementationName();
            }
            return lookups[slot].legacyIndex().implementationName();
        }
    }

    private static Map<String, Integer> buildLegacyEntries(Map<String, Integer> entries) {
        Map<String, Integer> legacy = new HashMap<>();
        Map<String, Boolean> ambiguous = new HashMap<>();
        for (Map.Entry<String, Integer> entry : entries.entrySet()) {
            String original = originalNamespacePath(entry.getKey());
            if (ambiguous.containsKey(original)) {
                continue;
            }
            Integer existing = legacy.get(original);
            if (existing == null) {
                legacy.put(original, entry.getValue());
            } else if (!existing.equals(entry.getValue())) {
                legacy.remove(original);
                ambiguous.put(original, Boolean.TRUE);
            }
        }
        return legacy;
    }

    private static String normalizeRegistryScope(String registryScope) {
        if (registryScope == null || registryScope.isBlank()) {
            return "";
        }
        return registryScope.trim().toLowerCase(Locale.ROOT);
    }

    private static String storageKey(int globalId, String scopedNamespacePath) {
        return scopedNamespacePath + "#" + globalId;
    }
}
