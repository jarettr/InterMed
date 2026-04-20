package org.intermed.core.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VirtualRegistryServiceTest {

    @BeforeEach
    void reset() {
        VirtualRegistryService.resetForTests();
        RegistryLinker.resetForTests();
    }

    @Test
    void testConsistencyForSameKey() {
        int first  = VirtualRegistryService.resolveVirtualId("minecraft:stone", -1);
        int second = VirtualRegistryService.resolveVirtualId("minecraft:stone", -1);
        assertEquals(first, second, "Same key must always produce the same ID");
    }

    @Test
    void testUniquenessForDifferentKeys() {
        int id1 = VirtualRegistryService.resolveVirtualId("mod1:itemA", -1);
        int id2 = VirtualRegistryService.resolveVirtualId("mod2:itemB", -1);
        assertNotEquals(id1, id2, "Different keys must produce different IDs");
        assertTrue(id1 >= 100_000 && id2 >= 100_000, "IDs must start from safe range 100 000+");
    }

    @Test
    void testTwoLevelMapping() {
        int idA = VirtualRegistryService.resolveVirtualId("modA", "modA:block_ore", -1);
        int idB = VirtualRegistryService.resolveVirtualId("modB", "modB:block_ore", -1);

        // Each mod gets its own local map
        assertNotEquals(idA, idB, "Two mods registering different paths must get different IDs");

        assertEquals(idA, VirtualRegistryService.getLocalMap("modA").get("modA:block_ore"));
        assertEquals(idB, VirtualRegistryService.getLocalMap("modB").get("modB:block_ore"));
    }

    @Test
    void testReverseResolution() {
        VirtualRegistryService.resolveVirtualId("modR", "modR:reverse_item", -1);
        int globalId = VirtualRegistryService.getLocalMap("modR").get("modR:reverse_item");
        String resolved = VirtualRegistryService.resolveOriginalId("modR", globalId);
        assertEquals("modR:reverse_item", resolved);
    }

    @Test
    void testCrossModCollisionPrevention() {
        Object payloadX = "payload-x";
        Object payloadY = "payload-y";

        int idX = VirtualRegistryService.resolveVirtualId("modX", "shared:diamond", -1);
        int idY = VirtualRegistryService.resolveVirtualId("modY", "shared:diamond", -1);

        VirtualRegistryService.registerVirtualized("modX", "shared:diamond", -1, payloadX);
        VirtualRegistryService.registerVirtualized("modY", "shared:diamond", -1, payloadY);

        assertNotEquals(idX, idY, "Conflicting namespace paths from different mods must be sharded");
        assertEquals(idX, VirtualRegistryService.lookupGlobalId("modX", "shared:diamond"));
        assertEquals(idY, VirtualRegistryService.lookupGlobalId("modY", "shared:diamond"));
        assertSame(payloadX, VirtualRegistryService.lookupValue("modX", "shared:diamond"));
        assertSame(payloadY, VirtualRegistryService.lookupValue("modY", "shared:diamond"));
    }

    @Test
    void testSafeOriginalIdIsPreservedWhenAvailable() {
        int id = VirtualRegistryService.resolveVirtualId("modOriginal", "modOriginal:block", 120_123);
        assertEquals(120_123, id);
        assertEquals("modOriginal:block", VirtualRegistryService.resolveOriginalId("modOriginal", id));
    }

    @Test
    void preservesVanillaSizedOriginalRawIdsWhenTheyAreConflictFree() {
        Object payload = "vanilla-sized";
        int id = VirtualRegistryService.registerVirtualized("vanilla_mod", "vanilla_mod:block", 42, payload);

        assertEquals(42, id);
        assertSame(payload, VirtualRegistryService.lookupValueByGlobalId(42));
        assertTrue(VirtualRegistryService.isValidId(42));

        VirtualRegistryService.freeze();

        assertTrue(VirtualRegistryService.isValidId(42));
        assertSame(payload, VirtualRegistryService.lookupValueByGlobalId(42));
    }

    @Test
    void shardsOnlyWhenThePreferredOriginalRawIdIsAlreadyTaken() {
        int preserved = VirtualRegistryService.resolveVirtualId("modA", "modA:item", 7);
        int sharded = VirtualRegistryService.resolveVirtualId("modB", "modB:item", 7);

        assertEquals(7, preserved);
        assertNotEquals(7, sharded);
        assertTrue(sharded >= VirtualRegistryService.GLOBAL_ID_OFFSET);
    }

    @Test
    void shardedAllocatorSkipsAlreadyPreservedHighIds() {
        int preserved = VirtualRegistryService.resolveVirtualId("modA", "modA:item", VirtualRegistryService.GLOBAL_ID_OFFSET);
        int sharded = VirtualRegistryService.resolveVirtualId("modB", "modB:item", -1);

        assertEquals(VirtualRegistryService.GLOBAL_ID_OFFSET, preserved);
        assertNotEquals(preserved, sharded);
        assertTrue(sharded > preserved);
    }

    @Test
    void registerVirtualizedLinksObjectStoreToGlobalIds() {
        Object payload = new Object();
        int globalId = VirtualRegistryService.registerVirtualized("modPayload", "modPayload:item", -1, payload);

        assertSame(payload, VirtualRegistryService.lookupValue("modPayload:item"));
        assertSame(payload, VirtualRegistryService.lookupValueByGlobalId(globalId));
    }

    @Test
    void separatesEntriesWithSameIdAcrossDifferentRegistryScopes() {
        Object block = "block-payload";
        Object item = "item-payload";

        int blockId = VirtualRegistryService.registerVirtualized(
            "scopedMod",
            "minecraft:block",
            "shared:gear",
            -1,
            block
        );
        int itemId = VirtualRegistryService.registerVirtualized(
            "scopedMod",
            "minecraft:item",
            "shared:gear",
            -1,
            item
        );

        assertNotEquals(blockId, itemId, "Equal IDs in different registries must not collide");
        assertSame(block, VirtualRegistryService.lookupValue("scopedMod", "minecraft:block", "shared:gear"));
        assertSame(item, VirtualRegistryService.lookupValue("scopedMod", "minecraft:item", "shared:gear"));
        assertEquals("shared:gear", VirtualRegistryService.resolveOriginalId("scopedMod", blockId));
        assertEquals("shared:gear", VirtualRegistryService.resolveOriginalId("scopedMod", itemId));
    }

    @Test
    void keepsLegacyModScopedLookupWhenEntryIsUnambiguous() {
        Object payload = "single-scope";
        int globalId = VirtualRegistryService.registerVirtualized(
            "legacyMod",
            "minecraft:block",
            "legacy:item",
            -1,
            payload
        );

        assertEquals(globalId, VirtualRegistryService.lookupGlobalId("legacyMod", "legacy:item"));
        assertSame(payload, VirtualRegistryService.lookupValue("legacyMod", "legacy:item"));
    }

    @Test
    void conflictingLegacyLookupRemainsDisabledAcrossMultipleScopesAfterFreeze() {
        VirtualRegistryService.registerVirtualized(
            "ambiguousMod",
            "minecraft:block",
            "shared:gear",
            -1,
            "block"
        );
        VirtualRegistryService.registerVirtualized(
            "ambiguousMod",
            "minecraft:item",
            "shared:gear",
            -1,
            "item"
        );
        VirtualRegistryService.registerVirtualized(
            "ambiguousMod",
            "minecraft:entity_type",
            "shared:gear",
            -1,
            "entity"
        );

        VirtualRegistryService.freeze();

        assertEquals(-1, VirtualRegistryService.lookupGlobalId("ambiguousMod", "shared:gear"));
    }

    @Test
    void lateRegistrationsRefreshFrozenViews() {
        VirtualRegistryService.freeze();

        Object payload = "late-payload";
        int globalId = VirtualRegistryService.registerVirtualized(
            "lateMod",
            "minecraft:block",
            "late:block",
            -1,
            payload
        );

        assertTrue(VirtualRegistryService.isFrozen());
        assertEquals(globalId, VirtualRegistryService.lookupGlobalId("lateMod", "minecraft:block", "late:block"));
        assertSame(payload, VirtualRegistryService.lookupValue("lateMod", "minecraft:block", "late:block"));
        assertSame(payload, VirtualRegistryService.lookupValueByGlobalId(globalId));
    }

    @Test
    void frozenCollisionHeavyLookupsRemainCorrectAcrossRegistryScopes() {
        String[] keys = {
            "AaAa",
            "BBBB",
            "AaBB",
            "BBAa"
        };

        for (int i = 0; i < keys.length; i++) {
            VirtualRegistryService.registerVirtualized(
                "collisionMod",
                "minecraft:block",
                keys[i],
                -1,
                "block-" + i
            );
            VirtualRegistryService.registerVirtualized(
                "collisionMod",
                "minecraft:item",
                keys[i],
                -1,
                "item-" + i
            );
        }

        VirtualRegistryService.freeze();

        for (int i = 0; i < keys.length; i++) {
            assertEquals(
                VirtualRegistryService.lookupGlobalId("collisionMod", "minecraft:block", keys[i]),
                VirtualRegistryService.lookupGlobalId("collisionMod", "minecraft:block", keys[i])
            );
            assertEquals(
                "block-" + i,
                VirtualRegistryService.lookupValue("collisionMod", "minecraft:block", keys[i])
            );
            assertEquals(
                "item-" + i,
                VirtualRegistryService.lookupValue("collisionMod", "minecraft:item", keys[i])
            );
        }
    }

    @Test
    void frozenServiceCompilesMinimalPerfectCanonicalAndLocalIndexes() {
        int globalId = VirtualRegistryService.registerVirtualized(
            "compiledMod",
            "minecraft:block",
            "compiled:block",
            -1,
            "payload"
        );

        VirtualRegistryService.freeze();

        assertTrue(VirtualRegistryService.frozenCanonicalLookupIsMinimalPerfect());
        assertTrue(VirtualRegistryService.frozenCanonicalLookupImplementationName().endsWith("CompiledFrozenStringIntLookup"));
        assertTrue(VirtualRegistryService.frozenLocalLookupImplementationName("compiledMod")
            .endsWith("CompiledFrozenStringIntLookup"));
        assertTrue(VirtualRegistryService.frozenLegacyLookupImplementationName("compiledMod")
            .endsWith("CompiledFrozenStringIntLookup"));
        assertEquals(globalId, VirtualRegistryService.lookupGlobalId("compiledMod", "compiled:block"));
        assertEquals("compiled:block", VirtualRegistryService.resolveOriginalId("compiledMod", globalId));
    }

    @Test
    void lookupValueDoesNotSilentlyFallbackToDirectRegistryStorage() {
        VirtualRegistry.register("shadow:key", "shadow-value");

        assertNull(
            VirtualRegistryService.lookupValue("unknown", "shadow:key"),
            "Registry virtualization must not bypass id mapping through a silent direct-store fallback"
        );
    }

    @Test
    void lookupValueUsesFacadeContextArgumentsToRecoverRegistryScope() {
        Object block = "block-payload";
        Object item = "item-payload";
        RegistryFacade facade = new RegistryFacade();

        VirtualRegistryService.registerVirtualized("scopedMod", "minecraft:block", "shared:gear", -1, block);
        VirtualRegistryService.registerVirtualized("scopedMod", "minecraft:item", "shared:gear", -1, item);

        assertSame(
            block,
            VirtualRegistryService.lookupValue(
                "scopedMod",
                facade,
                new Object[]{new ScopedKey("minecraft:block"), "shared:gear"}
            )
        );
        assertSame(
            item,
            VirtualRegistryService.lookupValue(
                "scopedMod",
                facade,
                new Object[]{new ScopedKey("minecraft:item"), "shared:gear"}
            )
        );
    }

    @Test
    void registryIdentityIgnoresOpaqueObjectIdentityFallbacks() {
        assertEquals("", RegistryIdentity.scopeOf(new Object()));
        assertEquals("", RegistryIdentity.scopeOfLookup(new Object(), new Object[]{"shared:gear"}));
    }

    private static final class RegistryFacade {}

    private static final class ScopedKey {
        private final String scope;

        private ScopedKey(String scope) {
            this.scope = scope;
        }

        public String location() {
            return scope;
        }
    }
}
