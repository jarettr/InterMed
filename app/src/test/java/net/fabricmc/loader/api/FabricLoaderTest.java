package net.fabricmc.loader.api;

import com.google.gson.JsonObject;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.intermed.core.bridge.BridgeRuntime;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.metadata.ModPlatform;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.metadata.RuntimeModIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FabricLoaderTest {

    @AfterEach
    void tearDown() {
        RuntimeModIndex.clear();
        BridgeRuntime.reset();
        System.clearProperty("runtime.game.dir");
        System.clearProperty("runtime.config.dir");
        System.clearProperty("runtime.mods.dir");
        System.clearProperty("runtime.env");
        RuntimeConfig.resetForTests();
    }

    @Test
    void delegatesLoadedModsToRuntimeIndex() {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("id", "demo_mod");
        manifest.addProperty("version", "1.0.0");
        manifest.add("depends", new JsonObject());
        manifest.add("entrypoints", new JsonObject());

        RuntimeModIndex.register(new NormalizedModMetadata(
            "demo_mod",
            "1.0.0",
            null,
            ModPlatform.FABRIC,
            manifest,
            Map.of()
        ));

        FabricLoader loader = FabricLoader.getInstance();
        assertTrue(loader.isModLoaded("demo_mod"));
        assertTrue(loader.isModLoaded("fabric-api"));
        assertFalse(loader.isModLoaded("missing_mod"));
        assertEquals("demo_mod", loader.getModContainer("demo_mod").orElseThrow().getMetadata().getId());
        assertEquals("1.0.0", loader.getModContainer("demo_mod").orElseThrow().getMetadata().getVersion().getFriendlyString());
    }

    @Test
    void exposesFabricMetadataCustomValues() {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("id", "metadata_mod");
        manifest.addProperty("version", "3.0.0");
        JsonObject custom = new JsonObject();
        custom.addProperty("fabric-renderer-api-v1:contains_renderer", true);
        manifest.add("custom", custom);
        manifest.add("depends", new JsonObject());
        manifest.add("entrypoints", new JsonObject());

        RuntimeModIndex.register(new NormalizedModMetadata(
            "metadata_mod",
            "3.0.0",
            null,
            ModPlatform.FABRIC,
            manifest,
            Map.of()
        ));

        var metadata = FabricLoader.getInstance().getModContainer("metadata_mod").orElseThrow().getMetadata();
        assertTrue(metadata.containsCustomValue("fabric-renderer-api-v1:contains_renderer"));
        assertTrue(metadata.getCustomValue("fabric-renderer-api-v1:contains_renderer").getAsBoolean());
    }

    @Test
    void enumeratesAllRuntimeAndPlatformMods() {
        JsonObject fabricApiManifest = new JsonObject();
        fabricApiManifest.addProperty("id", "fabric-api");
        fabricApiManifest.addProperty("version", "0.92.3");
        fabricApiManifest.add("depends", new JsonObject());
        fabricApiManifest.add("entrypoints", new JsonObject());

        JsonObject indigoManifest = new JsonObject();
        indigoManifest.addProperty("id", "fabric-renderer-indigo");
        indigoManifest.addProperty("version", "1.5.2");
        indigoManifest.add("depends", new JsonObject());
        indigoManifest.add("entrypoints", new JsonObject());

        RuntimeModIndex.register(new NormalizedModMetadata(
            "fabric-renderer-indigo",
            "1.5.2",
            null,
            ModPlatform.FABRIC,
            indigoManifest,
            Map.of()
        ));
        RuntimeModIndex.register(new NormalizedModMetadata(
            "fabric-api",
            "0.92.3",
            null,
            ModPlatform.FABRIC,
            fabricApiManifest,
            Map.of()
        ));

        Collection<ModContainer> containers = FabricLoader.getInstance().getAllMods();
        List<String> ids = containers.stream()
            .map(container -> container.getMetadata().getId())
            .toList();

        assertTrue(ids.contains("fabric-renderer-indigo"));
        assertTrue(ids.contains("fabric-api"));
        assertTrue(ids.contains("fabricloader"));
        assertEquals(ids.size(), ids.stream().distinct().count());
    }

    @Test
    void exposesRegisteredEntrypointsThroughLoaderApi() {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("id", "entrypoint_mod");
        manifest.addProperty("version", "2.0.0");
        JsonObject entrypoints = new JsonObject();
        entrypoints.add("main", new com.google.gson.JsonArray());
        manifest.add("depends", new JsonObject());
        manifest.add("entrypoints", entrypoints);

        RuntimeModIndex.register(new NormalizedModMetadata(
            "entrypoint_mod",
            "2.0.0",
            null,
            ModPlatform.FABRIC,
            manifest,
            Map.of()
        ));

        DemoEntrypoint entrypoint = new DemoEntrypoint();
        BridgeRuntime.registerEntrypoint("entrypoint_mod", "main", DemoEntrypoint.class.getName(), entrypoint);

        FabricLoader loader = FabricLoader.getInstance();
        assertEquals(List.of(entrypoint), loader.getEntrypoints("main", ModInitializer.class));

        List<EntrypointContainer<ModInitializer>> containers = loader.getEntrypointContainers("main", ModInitializer.class);
        assertEquals(1, containers.size());
        assertSame(entrypoint, containers.get(0).getEntrypoint());
        assertEquals("entrypoint_mod", containers.get(0).getProvider().getMetadata().getId());
    }

    @Test
    void exposesConfiguredDirectories() {
        System.setProperty("runtime.game.dir", "/tmp/intermed-game");
        System.setProperty("runtime.config.dir", "config-live");
        System.setProperty("runtime.env", "server");
        RuntimeConfig.reload();

        FabricLoader loader = FabricLoader.getInstance();
        assertEquals(Path.of("/tmp/intermed-game"), loader.getGameDir());
        assertEquals(Path.of("/tmp/intermed-game", "config-live"), loader.getConfigDir());
        assertEquals(EnvType.SERVER, loader.getEnvironmentType());
    }

    private static final class DemoEntrypoint implements ModInitializer {
        @Override
        public void onInitialize() {
        }
    }
}
