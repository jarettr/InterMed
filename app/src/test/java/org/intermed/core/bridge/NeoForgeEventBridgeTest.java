package org.intermed.core.bridge;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;
import net.neoforged.neoforge.registries.ForgeRegistries;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.intermed.core.registry.VirtualRegistryService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class NeoForgeEventBridgeTest {

    @BeforeEach
    void reset() {
        NeoForgeEventBridge.resetForTests();
        FMLJavaModLoadingContext.reset();
        ForgeRegistries.reset();
        RegistryCache.clear();
        VirtualRegistryService.resetForTests();
    }

    @Test
    void bridgeClassLoadsWithoutDirectNeoForgeLinking() {
        assertDoesNotThrow(() ->
            Arrays.stream(NeoForgeEventBridge.class.getDeclaredMethods())
                .forEach(method -> method.getParameterTypes())
        );
    }

    @Test
    void registerHandlerGracefullyIgnoresUnknownEventShapes() {
        assertDoesNotThrow(() -> NeoForgeEventBridge.onRegister(new Object()));
    }

    @Test
    void sourceFileContainsNoDirectNeoForgeImports() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path source = root.resolve("src/main/java/org/intermed/core/bridge/NeoForgeEventBridge.java");
        if (!Files.exists(source)) {
            source = root.resolve("app/src/main/java/org/intermed/core/bridge/NeoForgeEventBridge.java");
        }

        String text = Files.readString(source);
        assertFalse(text.contains("import net.neoforged."),
            "Forge-only compilation must not depend on direct NeoForge imports");
    }

    @Test
    void registerIfAvailableAttachesRuntimeListenersAndHandlesRegisterEvent() {
        RegistryCache.BLOCKS.add(new RegistryCache.PendingEntry(new ResourceLocation("demo", "gear"), "payload"));

        assertTrue(NeoForgeEventBridge.registerIfAvailable());
        assertEquals(2, FMLJavaModLoadingContext.get().getModEventBus().listenerCount());

        FMLJavaModLoadingContext.get().getModEventBus().dispatch(new RegisterEvent("minecraft:block"));

        assertTrue(ForgeRegistries.BLOCKS.contains("demo:gear", "payload"));
        assertEquals("payload",
            VirtualRegistryService.lookupValue("unknown", "minecraft:block", "demo:gear"));
    }
}
