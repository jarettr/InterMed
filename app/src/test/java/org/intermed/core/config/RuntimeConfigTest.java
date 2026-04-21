package org.intermed.core.config;

import net.fabricmc.api.EnvType;
import org.intermed.core.security.CapabilityManager;
import org.intermed.core.security.SecurityPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Tag("strict-security")
class RuntimeConfigTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("aot.cache.enabled");
        System.clearProperty("security.strict.mode");
        System.clearProperty("security.legacy.broad.permissions.enabled");
        System.clearProperty("mixin.ast.reclaim.enabled");
        System.clearProperty("mixin.conflict.policy");
        System.clearProperty("observability.ewma.threshold.ms");
        System.clearProperty("resolver.allow.fallback");
        System.clearProperty("sandbox.espresso.enabled");
        System.clearProperty("sandbox.wasm.enabled");
        System.clearProperty("sandbox.native.fallback.enabled");
        System.clearProperty("sandbox.shared.region.bytes");
        System.clearProperty("sandbox.shared.region.pool.max");
        System.clearProperty("classloader.dynamic.weak.edges.enabled");
        System.clearProperty("vfs.enabled");
        System.clearProperty("vfs.conflict.policy");
        System.clearProperty("vfs.priority.mods");
        System.clearProperty("vfs.cache.dir");
        System.clearProperty("runtime.env");
        System.clearProperty("intermed.env");
        System.clearProperty("fabric.env");
        System.clearProperty("runtime.game.dir");
        System.clearProperty("runtime.config.dir");
        System.clearProperty("runtime.mods.dir");
        System.clearProperty("intermed.modsDir");
        SecurityPolicy.resetForTests();
        CapabilityManager.resetForTests();
        RuntimeConfig.resetForTests();
    }

    @Test
    void loadsDefaultsFromResource() {
        RuntimeConfig.resetForTests();
        RuntimeConfig config = RuntimeConfig.get();
        assertTrue(config.isAotCacheEnabled());
        assertTrue(config.isSecurityStrictMode());
        assertFalse(config.isLegacyBroadPermissionDefaultsEnabled());
        assertTrue(config.isMixinAstReclaimEnabled());
        assertEquals("bridge", config.getMixinConflictPolicy());
        assertEquals(55.0d, config.getObservabilityEwmaThresholdMs());
        assertFalse(config.isResolverFallbackEnabled());
        assertTrue(config.isEspressoSandboxEnabled());
        assertTrue(config.isWasmSandboxEnabled());
        assertFalse(config.isNativeSandboxFallbackEnabled());
        assertEquals(4096, config.getSandboxSharedRegionBytes());
        assertEquals(32, config.getSandboxSharedRegionPoolMax());
        assertTrue(config.isClassloaderDynamicWeakEdgesEnabled());
        assertTrue(config.isVfsEnabled());
        assertEquals("merge_then_priority", config.getVfsConflictPolicy());
        assertTrue(config.getVfsPriorityOrder().isEmpty());
        assertEquals(EnvType.CLIENT, config.getEnvironmentType());
    }

    @Test
    void systemPropertiesOverrideDefaults() {
        System.setProperty("aot.cache.enabled", "false");
        System.setProperty("security.strict.mode", "false");
        System.setProperty("security.legacy.broad.permissions.enabled", "true");
        System.setProperty("mixin.ast.reclaim.enabled", "false");
        System.setProperty("mixin.conflict.policy", "overwrite");
        System.setProperty("observability.ewma.threshold.ms", "42.5");
        System.setProperty("sandbox.espresso.enabled", "false");
        System.setProperty("sandbox.wasm.enabled", "false");
        System.setProperty("sandbox.native.fallback.enabled", "true");
        System.setProperty("sandbox.shared.region.bytes", "8192");
        System.setProperty("sandbox.shared.region.pool.max", "8");
        System.setProperty("classloader.dynamic.weak.edges.enabled", "false");
        System.setProperty("vfs.enabled", "false");
        System.setProperty("vfs.conflict.policy", "priority");
        System.setProperty("vfs.priority.mods", "preferred_mod,second_mod");
        RuntimeConfig.reload();

        RuntimeConfig config = RuntimeConfig.get();
        assertFalse(config.isAotCacheEnabled());
        assertFalse(config.isSecurityStrictMode());
        assertTrue(config.isLegacyBroadPermissionDefaultsEnabled());
        assertFalse(config.isMixinAstReclaimEnabled());
        assertEquals("overwrite", config.getMixinConflictPolicy());
        assertEquals(42.5d, config.getObservabilityEwmaThresholdMs());
        assertFalse(config.isEspressoSandboxEnabled());
        assertFalse(config.isWasmSandboxEnabled());
        assertTrue(config.isNativeSandboxFallbackEnabled());
        assertEquals(8192, config.getSandboxSharedRegionBytes());
        assertEquals(8, config.getSandboxSharedRegionPoolMax());
        assertFalse(config.isClassloaderDynamicWeakEdgesEnabled());
        assertFalse(config.isVfsEnabled());
        assertEquals("priority", config.getVfsConflictPolicy());
        assertEquals(java.util.List.of("preferred_mod", "second_mod"), config.getVfsPriorityOrder());
    }

    @Test
    void resolvesRuntimeDirectoriesFromProperties() {
        System.setProperty("runtime.game.dir", "/tmp/intermed-game");
        System.setProperty("runtime.config.dir", "cfg");
        System.setProperty("intermed.modsDir", "/tmp/custom-mods");
        System.setProperty("resolver.allow.fallback", "true");
        RuntimeConfig.reload();

        RuntimeConfig config = RuntimeConfig.get();
        assertTrue(config.isResolverFallbackEnabled());
        assertEquals(Path.of("/tmp/intermed-game"), config.getGameDir());
        assertEquals(Path.of("/tmp/intermed-game", "cfg"), config.getConfigDir());
        assertEquals(Path.of("/tmp/custom-mods"), config.getModsDir());
    }

    @Test
    void loadsExternalRuntimeConfigOverridesFromConfigDirectory() throws Exception {
        Path gameDir = Files.createTempDirectory("intermed-runtime-config");
        Path configDir = gameDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("intermed-runtime.properties"), """
            security.strict.mode=false
            mixin.conflict.policy=overwrite
            runtime.mods.dir=external_mods
            """);

        System.setProperty("runtime.game.dir", gameDir.toString());
        RuntimeConfig.reload();

        RuntimeConfig config = RuntimeConfig.get();
        assertFalse(config.isSecurityStrictMode());
        assertEquals("overwrite", config.getMixinConflictPolicy());
        assertEquals(gameDir.resolve("external_mods"), config.getModsDir());
        assertEquals(configDir.resolve("intermed-runtime.properties"), config.getRuntimeConfigFile());
        assertTrue(config.isExternalRuntimeConfigLoaded());
    }

    @Test
    void resolvesEnvironmentTypeFromRuntimeProperties() {
        System.setProperty("runtime.env", "server");
        RuntimeConfig.reload();

        RuntimeConfig config = RuntimeConfig.get();
        assertEquals(EnvType.SERVER, config.getEnvironmentType());
    }

    @Test
    void reloadingStrictModeInvalidatesCapabilityDecisionCache() throws Exception {
        Path deniedPath = Files.createTempFile("intermed-strict-mode-cache", ".txt");

        SecurityPolicy.registerModCapabilities("strict_reload_mod", new com.google.gson.JsonObject());

        System.setProperty("security.strict.mode", "false");
        RuntimeConfig.reload();
        assertDoesNotThrow(() -> CapabilityManager.executeAsMod("strict_reload_mod", () ->
            CapabilityManager.checkFileRead(deniedPath)));

        System.setProperty("security.strict.mode", "true");
        RuntimeConfig.reload();
        assertThrows(SecurityException.class, () -> CapabilityManager.executeAsMod("strict_reload_mod", () ->
            CapabilityManager.checkFileRead(deniedPath)));
    }
}
