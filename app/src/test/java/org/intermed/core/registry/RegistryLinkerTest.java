package org.intermed.core.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryLinkerTest {

    @BeforeEach
    void reset() {
        VirtualRegistryService.resetForTests();
        RegistryLinker.resetForTests();
    }

    @Test
    void freezeRetargetsPreviouslyObservedCallSiteByKey() throws Throwable {
        VirtualRegistryService.registerVirtualized("demo_mod", "demo:key", -1, "value-1");

        CallSite site = RegistryLinker.bootstrapGet(
            MethodHandles.lookup(),
            "registryGet",
            MethodType.methodType(Object.class, Object.class)
        );

        Object beforeFreeze = site.dynamicInvoker().invokeExact((Object) "demo:key");
        assertEquals("value-1", beforeFreeze);

        VirtualRegistry.freeze();
        RegistryLinker.freeze();

        Object afterFreeze = site.dynamicInvoker().invokeExact((Object) "demo:key");
        assertEquals("value-1", afterFreeze);
        int lookupsAfterWarmup = RegistryLinker.frozenDynamicLookupCount();

        Object hot = site.dynamicInvoker().invokeExact((Object) "demo:key");
        assertEquals("value-1", hot);
        assertEquals(lookupsAfterWarmup, RegistryLinker.frozenDynamicLookupCount(),
            "Frozen hot path must not repeat dynamic id lookups for the same key");
    }

    @Test
    void frozenSlowPathSelfPatchesCallSiteOnFirstObservedHit() throws Throwable {
        VirtualRegistryService.registerVirtualized("demo_mod", "demo:late", -1, "value-2");

        CallSite site = RegistryLinker.bootstrapGet(
            MethodHandles.lookup(),
            "registryGet",
            MethodType.methodType(Object.class, Object.class)
        );

        VirtualRegistry.freeze();

        Object first = site.dynamicInvoker().invokeExact((Object) "demo:late");
        Object second = site.dynamicInvoker().invokeExact((Object) "demo:late");

        assertEquals("value-2", first);
        assertEquals("value-2", second);
        assertEquals(1, RegistryLinker.frozenDynamicLookupCount(),
            "Once the call site is patched, repeated hits for the same key must stay monomorphic");
    }

    @Test
    void optionalLookupSitesPreserveOptionalSemanticsAcrossFreeze() throws Throwable {
        VirtualRegistryService.registerVirtualized("demo_mod", "demo:optional", -1, "value-3");

        CallSite site = RegistryLinker.bootstrapGet(
            MethodHandles.lookup(),
            "registryGet",
            MethodType.methodType(Optional.class, Object.class)
        );

        Optional<?> beforeFreeze = (Optional<?>) site.dynamicInvoker().invokeExact((Object) "demo:optional");
        assertTrue(beforeFreeze.isPresent());
        assertEquals("value-3", beforeFreeze.orElseThrow());

        VirtualRegistry.freeze();
        RegistryLinker.freeze();

        Optional<?> afterFreeze = (Optional<?>) site.dynamicInvoker().invokeExact((Object) "demo:optional");
        assertTrue(afterFreeze.isPresent());
        assertEquals("value-3", afterFreeze.orElseThrow());
    }

    @Test
    void rawIdLookupSitesReturnVirtualGlobalIdsAcrossFreeze() throws Throwable {
        Object value = new Object();
        int rawId = VirtualRegistryService.registerVirtualized("raw_mod", "demo:raw", -1, value);

        CallSite site = RegistryLinker.bootstrapGet(
            MethodHandles.lookup(),
            "registryGet",
            MethodType.methodType(int.class, Object.class)
        );

        int beforeFreeze = (int) site.dynamicInvoker().invokeExact((Object) value);
        assertEquals(rawId, beforeFreeze);

        VirtualRegistryService.freeze();

        int afterFreeze = (int) site.dynamicInvoker().invokeExact((Object) value);
        assertEquals(rawId, afterFreeze);
    }

    @Test
    void registryScopedLookupSitesKeepBlockAndItemNamespacesSeparated() throws Throwable {
        VirtualRegistryService.registerVirtualized("scoped_mod", "minecraft:block", "shared:gear", -1, "block-value");
        VirtualRegistryService.registerVirtualized("scoped_mod", "minecraft:item", "shared:gear", -1, "item-value");

        CallSite site = RegistryLinker.bootstrapGet(
            MethodHandles.lookup(),
            "registryGet",
            MethodType.methodType(Object.class, ScopedRegistry.class, Object.class)
        );

        Object block = site.dynamicInvoker().invokeExact(new ScopedRegistry("minecraft:block"), (Object) "shared:gear");
        Object item = site.dynamicInvoker().invokeExact(new ScopedRegistry("minecraft:item"), (Object) "shared:gear");

        assertEquals("block-value", block);
        assertEquals("item-value", item);
    }

    @Test
    void frozenScopedFastPathAvoidsRepeatedDynamicLookupsForHotRegistryKey() throws Throwable {
        VirtualRegistryService.registerVirtualized("scoped_mod", "minecraft:block", "shared:gear", -1, "block-value");

        CallSite site = RegistryLinker.bootstrapGet(
            MethodHandles.lookup(),
            "registryGet",
            MethodType.methodType(Object.class, ScopedRegistry.class, Object.class)
        );

        VirtualRegistryService.freeze();

        Object first = site.dynamicInvoker().invokeExact(new ScopedRegistry("minecraft:block"), (Object) "shared:gear");
        int afterFirst = RegistryLinker.frozenDynamicLookupCount();
        Object second = site.dynamicInvoker().invokeExact(new ScopedRegistry("minecraft:block"), (Object) "shared:gear");

        assertEquals("block-value", first);
        assertEquals("block-value", second);
        assertEquals(afterFirst, RegistryLinker.frozenDynamicLookupCount());
    }

    @Test
    void registerBootstrapDropsExtraArgumentsForExtendedRegisterSignatures() throws Throwable {
        Object payload = new Object();
        CallSite site = RegistryLinker.bootstrapRegister(
            MethodHandles.lookup(),
            "register",
            MethodType.methodType(Object.class, ScopedRegistry.class, Object.class, Object.class, Object.class)
        );

        Object result = site.dynamicInvoker().invokeExact(
            new ScopedRegistry("minecraft:block"),
            (Object) "demo:gear",
            payload,
            (Object) "lifecycle"
        );

        assertEquals(payload, result);
        assertEquals(
            payload,
            VirtualRegistryService.lookupValue("unknown", "minecraft:block", "demo:gear")
        );
    }

    private static final class ScopedRegistry {
        private final String scope;

        private ScopedRegistry(String scope) {
            this.scope = scope;
        }

        public ScopedKey getRegistryKey() {
            return new ScopedKey(scope);
        }

        @Override
        public String toString() {
            return "ScopedRegistry[" + scope + "]";
        }
    }

    private static final class ScopedKey {
        private final String scope;

        private ScopedKey(String scope) {
            this.scope = scope;
        }

        @Override
        public String toString() {
            return scope;
        }
    }
}
