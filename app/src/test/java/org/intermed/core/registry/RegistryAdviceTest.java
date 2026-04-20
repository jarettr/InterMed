package org.intermed.core.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RegistryAdviceTest {

    @BeforeEach
    void reset() {
        VirtualRegistryService.resetForTests();
        RegistryLinker.resetForTests();
    }

    @Test
    void registerAdviceUsesReceiverRegistryScopeForInstanceCalls() {
        Object payload = "block-payload";
        ScopedRegistry registry = new ScopedRegistry("minecraft:block");

        RegistryRegisterAdvice.onRegisterEnter(registry, new Object[]{"shared:gear", payload});

        assertSame(payload, VirtualRegistryService.lookupValue("unknown", "minecraft:block", "shared:gear"));
    }

    @Test
    void registerAdviceUsesFirstArgumentRegistryScopeForStaticCalls() {
        Object payload = "item-payload";
        ScopedRegistry registry = new ScopedRegistry("minecraft:item");

        RegistryRegisterAdvice.onRegisterEnter(null, new Object[]{registry, "shared:gear", payload});

        assertSame(payload, VirtualRegistryService.lookupValue("unknown", "minecraft:item", "shared:gear"));
    }

    @Test
    void interceptRegisterShardsSameIdAcrossRegistryScopes() {
        ScopedRegistry blockRegistry = new ScopedRegistry("minecraft:block");
        ScopedRegistry itemRegistry = new ScopedRegistry("minecraft:item");

        RegistryLinker.interceptRegister("scoped_mod", blockRegistry, "shared:gear", "block");
        RegistryLinker.interceptRegister("scoped_mod", itemRegistry, "shared:gear", "item");

        int blockId = VirtualRegistryService.lookupGlobalId("scoped_mod", "minecraft:block", "shared:gear");
        int itemId = VirtualRegistryService.lookupGlobalId("scoped_mod", "minecraft:item", "shared:gear");

        assertNotEquals(blockId, itemId);
        assertEquals("block", VirtualRegistryService.lookupValue("scoped_mod", "minecraft:block", "shared:gear"));
        assertEquals("item", VirtualRegistryService.lookupValue("scoped_mod", "minecraft:item", "shared:gear"));
    }

    private static final class ScopedRegistry {
        private final ScopedKey registryKey;

        private ScopedRegistry(String scope) {
            this.registryKey = new ScopedKey(scope);
        }

        public ScopedKey getRegistryKey() {
            return registryKey;
        }
    }

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
