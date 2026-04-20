package org.intermed.core.cache;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class AOTCacheManagerTest {

    @BeforeAll
    static void init() {
        AOTCacheManager.initialize();
    }

    @BeforeEach
    void resetState() {
        AOTCacheManager.resetForTests();
    }

    @Test
    void testCacheMissReturnsNull() {
        byte[] result = AOTCacheManager.getCachedClass("com.example.NonExistent", "deadbeef");
        assertNull(result, "Cache miss should return null");
    }

    @Test
    void testSaveAndRetrieve() {
        String className = "com.example.TestClass_" + System.nanoTime();
        String hash = "abc123";
        byte[] data = new byte[]{0x01, 0x02, 0x03, (byte) 0xCA, (byte) 0xFE};

        AOTCacheManager.saveToCache(className, hash, data);

        byte[] retrieved = AOTCacheManager.getCachedClass(className, hash);
        assertNotNull(retrieved, "Saved entry should be retrievable");
        assertArrayEquals(data, retrieved, "Retrieved bytes must match saved bytes");
        assertTrue(Files.exists(AOTCacheManager.metadataPathForTesting(className, hash)));
    }

    @Test
    void testDifferentHashGivesCacheMiss() {
        String className = "com.example.HashTest_" + System.nanoTime();
        byte[] data = new byte[]{0x0A, 0x0B};

        AOTCacheManager.saveToCache(className, "hash_v1", data);

        byte[] wrongHash = AOTCacheManager.getCachedClass(className, "hash_v2");
        assertNull(wrongHash, "Different hash should be a cache miss (cache invalidation)");
    }

    @Test
    void testSameKeyReturnsSameBytes() {
        String className = "com.example.SameKey_" + System.nanoTime();
        String hash = "stablehash";
        byte[] data = {1, 2, 3};

        AOTCacheManager.saveToCache(className, hash, data);

        byte[] first = AOTCacheManager.getCachedClass(className, hash);
        byte[] second = AOTCacheManager.getCachedClass(className, hash);
        assertArrayEquals(first, second, "Two reads of the same key must return equal byte arrays");
    }

    @Test
    void testExtraMetadataRoundTripsAndIsVisibleOnCacheHit() throws Exception {
        String className = "com.example.Metadata_" + System.nanoTime();
        String hash = "metadataHash";
        byte[] data = {0x01, 0x23, 0x45};

        AOTCacheManager.saveToCache(className, hash, data, Map.of(
            "cache.kind", "PASS_THROUGH",
            "resolution.summary", "modified=0,added=0"
        ));

        AOTCacheManager.CachedClass cachedClass = AOTCacheManager.getCachedEntry(className, hash);
        assertNotNull(cachedClass);
        assertEquals("PASS_THROUGH", cachedClass.metadata().extraMetadata().get("cache.kind"));
        assertEquals("modified=0,added=0", cachedClass.metadata().extraMetadata().get("resolution.summary"));

        Properties properties = new Properties();
        try (var input = Files.newInputStream(AOTCacheManager.metadataPathForTesting(className, hash))) {
            properties.load(input);
        }
        assertEquals("PASS_THROUGH", properties.getProperty("extra.cache.kind"));
        assertEquals("modified=0,added=0", properties.getProperty("extra.resolution.summary"));
    }

    @Test
    void testSavingNewVersionPrunesOlderCacheEntryForSameClass() {
        String className = "com.example.Pruned_" + System.nanoTime();
        byte[] oldData = {0x0A};
        byte[] newData = {0x0B};

        AOTCacheManager.saveToCache(className, "oldHash", oldData);
        Path oldMetadata = AOTCacheManager.metadataPathForTesting(className, "oldHash");
        Path oldClass = AOTCacheManager.classPathForTesting(className, "oldHash");
        assertTrue(Files.exists(oldMetadata));
        assertTrue(Files.exists(oldClass));

        AOTCacheManager.saveToCache(className, "newHash", newData);

        assertFalse(Files.exists(oldMetadata));
        assertFalse(Files.exists(oldClass));
        assertNull(AOTCacheManager.getCachedClass(className, "oldHash"));
        assertArrayEquals(newData, AOTCacheManager.getCachedClass(className, "newHash"));
    }

    @Test
    void testTamperedMetadataInvalidatesEntry() throws Exception {
        String className = "com.example.TamperedMetadata_" + System.nanoTime();
        String hash = "metaHash";
        byte[] data = {9, 8, 7, 6};

        AOTCacheManager.saveToCache(className, hash, data);

        Path metadataFile = AOTCacheManager.metadataPathForTesting(className, hash);
        Properties properties = new Properties();
        try (var input = Files.newInputStream(metadataFile)) {
            properties.load(input);
        }
        properties.setProperty("inputHash", "mutated");
        try (var output = Files.newOutputStream(metadataFile)) {
            properties.store(output, "tampered");
        }

        assertNull(AOTCacheManager.getCachedClass(className, hash));
        assertFalse(Files.exists(metadataFile));
        assertFalse(Files.exists(AOTCacheManager.classPathForTesting(className, hash)));
    }

    @Test
    void testTamperedBytecodeChecksumInvalidatesEntry() throws Exception {
        String className = "com.example.TamperedBytecode_" + System.nanoTime();
        String hash = "byteHash";
        byte[] data = {1, 3, 3, 7};

        AOTCacheManager.saveToCache(className, hash, data);

        Path classFile = AOTCacheManager.classPathForTesting(className, hash);
        Files.write(classFile, new byte[]{0x00, 0x01});

        assertNull(AOTCacheManager.getCachedClass(className, hash));
        assertFalse(Files.exists(classFile));
        assertFalse(Files.exists(AOTCacheManager.metadataPathForTesting(className, hash)));
    }

    @Test
    void runtimeComponentFingerprintChangesWhenComponentSetChanges() {
        String one = AOTCacheManager.runtimeComponentFingerprint(AOTCacheManager.class);
        String two = AOTCacheManager.runtimeComponentFingerprint(AOTCacheManager.class, AOTCacheManagerTest.class);

        assertNotNull(one);
        assertNotNull(two);
        assertNotEquals(one, two);
    }

    /**
     * Warm-start budget gate: repeated cache lookups must stay well below the
     * absolute per-lookup limit that allows overall startup overhead to stay
     * within 1.5x of native startup time.
     *
     * <p>The test saves {@value #WARM_ENTRIES} entries (each {@value #ENTRY_BYTES} bytes)
     * then reads them in a tight loop.  The per-lookup budget is intentionally loose
     * (configurable via {@code intermed.budget.cache.maxNanosPerLookup}) so that it
     * does not create false positives on CI but still catches catastrophic regressions.
     */
    private static final int WARM_ENTRIES = 20;
    private static final int ENTRY_BYTES  = 4096;

    @Test
    void warmCacheLookupStaysWithinStartupBudget() {
        byte[] payload = new byte[ENTRY_BYTES];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0xFF);
        }

        String[] classNames = new String[WARM_ENTRIES];
        String[] hashes     = new String[WARM_ENTRIES];
        for (int i = 0; i < WARM_ENTRIES; i++) {
            classNames[i] = "com.example.WarmClass" + i + "_" + System.nanoTime();
            hashes[i]     = AOTCacheManager.sha256(classNames[i]);
            AOTCacheManager.saveToCache(classNames[i], hashes[i], payload);
        }

        // Warm-up pass (excluded from timing).
        for (int i = 0; i < WARM_ENTRIES; i++) {
            assertNotNull(AOTCacheManager.getCachedClass(classNames[i], hashes[i]));
        }

        // Timed pass.
        long started = System.nanoTime();
        for (int rep = 0; rep < 10; rep++) {
            for (int i = 0; i < WARM_ENTRIES; i++) {
                byte[] result = AOTCacheManager.getCachedClass(classNames[i], hashes[i]);
                assertNotNull(result, "Warm cache must not miss entry " + i + " on rep " + rep);
            }
        }
        long totalNanos = System.nanoTime() - started;

        long totalLookups   = (long) 10 * WARM_ENTRIES;
        double nanosPerLookup = totalNanos / (double) totalLookups;
        double budget = Double.parseDouble(
            System.getProperty("intermed.budget.cache.maxNanosPerLookup", "5000000"));

        assertTrue(nanosPerLookup <= budget,
            "AOT cache warm-lookup exceeded startup budget: "
                + String.format("%.0f", nanosPerLookup) + " ns/lookup (budget=" + budget + " ns)");
    }
}
