package org.intermed.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.lifecycle.LifecycleManager;
import org.intermed.core.metadata.ModPlatform;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.registry.VirtualRegistryService;
import org.intermed.core.remapping.InterMedRemapper;
import org.intermed.core.sandbox.PolyglotSandboxManager;
import org.intermed.core.sandbox.SandboxExecutionResult;
import org.intermed.core.sandbox.SandboxMode;
import org.intermed.core.sandbox.SandboxPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("soak")
class RuntimeStabilitySoakTest {

    @BeforeEach
    void setUp() {
        VirtualRegistryService.resetForTests();
        PolyglotSandboxManager.resetForTests();
        InterMedRemapper.clearCaches();
        LifecycleManager.DICTIONARY.clear();
        LifecycleManager.DICTIONARY.addClass("net/minecraft/class_42", "net/minecraft/server/level/ServerPlayer");
        InterMedRemapper.installDictionary(LifecycleManager.DICTIONARY);
        System.setProperty("sandbox.native.fallback.enabled", "true");
        RuntimeConfig.reload();
    }

    @AfterEach
    void tearDown() {
        VirtualRegistryService.resetForTests();
        PolyglotSandboxManager.resetForTests();
        InterMedRemapper.clearCaches();
        LifecycleManager.DICTIONARY.clear();
        System.clearProperty("sandbox.native.fallback.enabled");
        RuntimeConfig.resetForTests();
    }

    @Test
    void writesRuntimeStabilityReport() throws Exception {
        NormalizedModMetadata metadata = hotPathSandboxMetadata();

        long started = System.nanoTime();
        int lastGlobalId = -1;
        for (int i = 0; i < 1_000; i++) {
            String key = "soak:item_" + i;
            String payload = "payload-" + i;
            lastGlobalId = VirtualRegistryService.registerVirtualized(
                "soak_mod",
                "minecraft:block",
                key,
                i,
                payload
            );
            assertEquals(payload, VirtualRegistryService.lookupValue("soak_mod", "minecraft:block", key));

            String remapped = InterMedRemapper.translateRuntimeString("owner=net.minecraft.class_42");
            assertTrue(remapped.contains("ServerPlayer"));

            SandboxPlan plan = PolyglotSandboxManager.registerPlan(metadata);
            assertEquals(SandboxMode.NATIVE, plan.effectiveMode());
            assertTrue(plan.hotPath());

            SandboxExecutionResult result = PolyglotSandboxManager.executeSandboxedEntrypoint(
                metadata,
                "main-" + i,
                "demo.hot.EntryPoint",
                "init"
            );
            assertEquals(SandboxMode.NATIVE, result.effectiveMode());

            if (i % 250 == 249) {
                VirtualRegistryService.freeze();
            }
        }
        long elapsedNanos = System.nanoTime() - started;

        String report = """
            runtime_stability:
              iterations: 1000
              nanos_total: %d
              nanos_per_iteration: %.2f
              final_global_id: %d
              sandbox_mode: native
            """.formatted(
            elapsedNanos,
            elapsedNanos / 1_000.0d,
            lastGlobalId
        );

        Path outputDir = resolveOutputDir();
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("runtime-stability.txt"), report);
    }

    private static NormalizedModMetadata hotPathSandboxMetadata() {
        JsonObject manifest = new JsonObject();
        JsonArray mixins = new JsonArray();
        mixins.add("soak.mixins.json");
        manifest.add("mixins", mixins);

        JsonObject sandbox = new JsonObject();
        sandbox.addProperty("mode", "espresso");
        sandbox.addProperty("allowNativeFallback", true);
        manifest.add("intermed:sandbox", sandbox);

        return new NormalizedModMetadata(
            "soak_mod",
            "1.0.0",
            null,
            ModPlatform.FABRIC,
            manifest,
            Map.of()
        );
    }

    private static Path resolveOutputDir() {
        String configured = System.getProperty("intermed.soak.outputDir");
        if (configured == null || configured.isBlank()) {
            return Path.of("build", "reports", "soak");
        }
        return Path.of(configured);
    }
}
