package org.intermed.core.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenStringIntHashIndexTest {

    @BeforeEach
    void reset() {
        VirtualRegistry.resetForTests();
    }

    @Test
    void buildsFrozenIndexForHashCollisionHeavyStringSet() {
        Map<String, Integer> entries = new LinkedHashMap<>();
        String[] keys = collisionKeys();
        for (int i = 0; i < keys.length; i++) {
            entries.put(keys[i], i + 10);
        }

        FrozenStringIntHashIndex index = FrozenStringIntHashIndex.build(entries);

        assertEquals(entries.size(), index.size());
        assertEquals(entries.size(), index.slotCount());
        assertTrue(index.isMinimalPerfect());
        assertTrue(index.usesGeneratedLookup());
        assertTrue(index.implementationName().endsWith("CompiledFrozenStringIntLookup"));
        for (int i = 0; i < keys.length; i++) {
            assertEquals(i + 10, index.getOrDefault(keys[i], -1), keys[i]);
        }
        assertEquals(-1, index.getOrDefault("missing:key", -1));
    }

    @Test
    void virtualRegistryUsesFrozenHashIndexAfterFreeze() {
        String[] keys = collisionKeys();
        for (int i = 0; i < keys.length; i++) {
            VirtualRegistry.register(keys[i], "payload-" + i, 1000 + i);
        }

        VirtualRegistry.freeze();

        assertTrue(VirtualRegistry.frozenKeyLookupIsMinimalPerfect());
        assertTrue(VirtualRegistry.frozenKeyLookupImplementationName().endsWith("CompiledFrozenStringIntLookup"));

        for (int i = 0; i < keys.length; i++) {
            assertEquals("payload-" + i, VirtualRegistry.getFast(keys[i]), keys[i]);
            assertNotEquals(-1, VirtualRegistry.slotOf(keys[i]), keys[i]);
            assertEquals("payload-" + i, VirtualRegistry.getFastByRawId(1000 + i), keys[i]);
        }
        assertNull(VirtualRegistry.getFast("missing:key"));
    }

    @Test
    void frozenIdentityLookupUsesObjectIdentityForRawIdPath() {
        Object payload = new String("same-text");
        Object equalButDifferent = new String("same-text");

        VirtualRegistry.register("identity:key", payload, 321);
        VirtualRegistry.freeze();

        assertEquals(321, VirtualRegistry.getRawIdFast(payload));
        assertEquals(-1, VirtualRegistry.getRawIdFast(equalButDifferent));
    }

    private static String[] collisionKeys() {
        return new String[] {
            "Aa",
            "BB",
            "AaAa",
            "BBBB",
            "AaBB",
            "BBAa",
            "AaAaAa",
            "AaAaBB",
            "AaBBAa",
            "BBAaAa"
        };
    }
}
