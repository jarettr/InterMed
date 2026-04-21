package org.intermed.core.config;

import net.fabricmc.api.EnvType;
import org.intermed.core.cache.AOTCacheManager;
import org.intermed.core.sandbox.PolyglotSandboxManager;
import org.intermed.core.security.CapabilityManager;
import org.intermed.core.vfs.VirtualFileSystemRouter;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;

/**
 * Central runtime configuration for the current MVP pipeline.
 * Values are loaded from {@code intermed-runtime.properties} and can be
 * overridden via JVM system properties.
 */
public final class RuntimeConfig {

    private static final String RESOURCE_NAME = "intermed-runtime.properties";
    private static volatile RuntimeConfig current = load();

    private final boolean aotCacheEnabled;
    private final boolean securityStrictMode;
    private final boolean legacyBroadPermissionDefaultsEnabled;
    private final boolean mixinAstReclaimEnabled;
    private final String mixinConflictPolicy;
    private final double observabilityEwmaThresholdMs;
    private final boolean resolverFallbackEnabled;
    private final boolean espressoSandboxEnabled;
    private final boolean wasmSandboxEnabled;
    private final boolean nativeSandboxFallbackEnabled;
    private final int sandboxSharedRegionBytes;
    private final int sandboxSharedRegionPoolMax;
    private final boolean classloaderDynamicWeakEdgesEnabled;
    private final boolean vfsEnabled;
    private final String vfsConflictPolicy;
    private final List<String> vfsPriorityOrder;
    private final Path vfsCacheDir;
    private final EnvType environmentType;
    private final Path gameDir;
    private final Path configDir;
    private final Path modsDir;
    private final Path runtimeConfigFile;
    private final boolean externalRuntimeConfigLoaded;
    private final boolean prometheusEnabled;
    private final int prometheusPort;
    private final boolean otelEnabled;
    private final Path otelOutputPath;

    private RuntimeConfig(boolean aotCacheEnabled,
                          boolean securityStrictMode,
                          boolean legacyBroadPermissionDefaultsEnabled,
                          boolean mixinAstReclaimEnabled,
                          String mixinConflictPolicy,
                          double observabilityEwmaThresholdMs,
                          boolean resolverFallbackEnabled,
                          boolean espressoSandboxEnabled,
                          boolean wasmSandboxEnabled,
                          boolean nativeSandboxFallbackEnabled,
                          int sandboxSharedRegionBytes,
                          int sandboxSharedRegionPoolMax,
                          boolean classloaderDynamicWeakEdgesEnabled,
                          boolean vfsEnabled,
                          String vfsConflictPolicy,
                          List<String> vfsPriorityOrder,
                          Path vfsCacheDir,
                          EnvType environmentType,
                          Path gameDir,
                          Path configDir,
                          Path modsDir,
                          Path runtimeConfigFile,
                          boolean externalRuntimeConfigLoaded,
                          boolean prometheusEnabled,
                          int prometheusPort,
                          boolean otelEnabled,
                          Path otelOutputPath) {
        this.aotCacheEnabled = aotCacheEnabled;
        this.securityStrictMode = securityStrictMode;
        this.legacyBroadPermissionDefaultsEnabled = legacyBroadPermissionDefaultsEnabled;
        this.mixinAstReclaimEnabled = mixinAstReclaimEnabled;
        this.mixinConflictPolicy = mixinConflictPolicy;
        this.observabilityEwmaThresholdMs = observabilityEwmaThresholdMs;
        this.resolverFallbackEnabled = resolverFallbackEnabled;
        this.espressoSandboxEnabled = espressoSandboxEnabled;
        this.wasmSandboxEnabled = wasmSandboxEnabled;
        this.nativeSandboxFallbackEnabled = nativeSandboxFallbackEnabled;
        this.sandboxSharedRegionBytes = sandboxSharedRegionBytes;
        this.sandboxSharedRegionPoolMax = sandboxSharedRegionPoolMax;
        this.classloaderDynamicWeakEdgesEnabled = classloaderDynamicWeakEdgesEnabled;
        this.vfsEnabled = vfsEnabled;
        this.vfsConflictPolicy = vfsConflictPolicy;
        this.vfsPriorityOrder = List.copyOf(vfsPriorityOrder);
        this.vfsCacheDir = vfsCacheDir;
        this.environmentType = environmentType;
        this.gameDir = gameDir;
        this.configDir = configDir;
        this.modsDir = modsDir;
        this.runtimeConfigFile = runtimeConfigFile;
        this.externalRuntimeConfigLoaded = externalRuntimeConfigLoaded;
        this.prometheusEnabled = prometheusEnabled;
        this.prometheusPort = prometheusPort;
        this.otelEnabled = otelEnabled;
        this.otelOutputPath = otelOutputPath;
    }

    public static RuntimeConfig get() {
        return current;
    }

    public static void reload() {
        current = load();
        CapabilityManager.invalidateDecisionCache();
        PolyglotSandboxManager.invalidateRuntimeCaches();
        VirtualFileSystemRouter.invalidateCache();
    }

    public static void resetForTests() {
        current = load();
        CapabilityManager.invalidateDecisionCache();
        PolyglotSandboxManager.invalidateRuntimeCaches();
        VirtualFileSystemRouter.invalidateCache();
    }

    public boolean isAotCacheEnabled() {
        return aotCacheEnabled;
    }

    public boolean isSecurityStrictMode() {
        return securityStrictMode;
    }

    public boolean isLegacyBroadPermissionDefaultsEnabled() {
        return legacyBroadPermissionDefaultsEnabled;
    }

    public boolean isMixinAstReclaimEnabled() {
        return mixinAstReclaimEnabled;
    }

    public String getMixinConflictPolicy() {
        return mixinConflictPolicy;
    }

    public double getObservabilityEwmaThresholdMs() {
        return observabilityEwmaThresholdMs;
    }

    public boolean isResolverFallbackEnabled() {
        return resolverFallbackEnabled;
    }

    public boolean isEspressoSandboxEnabled() {
        return espressoSandboxEnabled;
    }

    public boolean isWasmSandboxEnabled() {
        return wasmSandboxEnabled;
    }

    public boolean isNativeSandboxFallbackEnabled() {
        return nativeSandboxFallbackEnabled;
    }

    public int getSandboxSharedRegionBytes() {
        return sandboxSharedRegionBytes;
    }

    public int getSandboxSharedRegionPoolMax() {
        return sandboxSharedRegionPoolMax;
    }

    public boolean isClassloaderDynamicWeakEdgesEnabled() {
        return classloaderDynamicWeakEdgesEnabled;
    }

    public boolean isVfsEnabled() {
        return vfsEnabled;
    }

    public String getVfsConflictPolicy() {
        return vfsConflictPolicy;
    }

    public List<String> getVfsPriorityOrder() {
        return vfsPriorityOrder;
    }

    public Path getVfsCacheDir() {
        return vfsCacheDir;
    }

    public EnvType getEnvironmentType() {
        return environmentType;
    }

    public Path getGameDir() {
        return gameDir;
    }

    public Path getConfigDir() {
        return configDir;
    }

    public Path getModsDir() {
        return modsDir;
    }

    public Path getRuntimeConfigFile() {
        return runtimeConfigFile;
    }

    public boolean isExternalRuntimeConfigLoaded() {
        return externalRuntimeConfigLoaded;
    }

    public boolean isPrometheusEnabled() {
        return prometheusEnabled;
    }

    public int getPrometheusPort() {
        return prometheusPort;
    }

    public boolean isOtelEnabled() {
        return otelEnabled;
    }

    public Path getOtelOutputPath() {
        return otelOutputPath;
    }

    public String cacheFingerprint() {
        String descriptor = "aot=" + aotCacheEnabled
            + "|strict=" + securityStrictMode
            + "|legacyBroadPermissions=" + legacyBroadPermissionDefaultsEnabled
            + "|mixinAstReclaim=" + mixinAstReclaimEnabled
            + "|policy=" + mixinConflictPolicy
            + "|ewma=" + observabilityEwmaThresholdMs
            + "|resolverFallback=" + resolverFallbackEnabled
            + "|espressoSandbox=" + espressoSandboxEnabled
            + "|wasmSandbox=" + wasmSandboxEnabled
            + "|nativeSandboxFallback=" + nativeSandboxFallbackEnabled
            + "|sandboxSharedRegionBytes=" + sandboxSharedRegionBytes
            + "|sandboxSharedRegionPoolMax=" + sandboxSharedRegionPoolMax
            + "|classloaderDynamicWeakEdges=" + classloaderDynamicWeakEdgesEnabled
            + "|vfsEnabled=" + vfsEnabled
            + "|vfsConflictPolicy=" + vfsConflictPolicy
            + "|vfsPriority=" + String.join(",", vfsPriorityOrder)
            + "|vfsCacheDir=" + vfsCacheDir
            + "|env=" + environmentType
            + "|gameDir=" + gameDir
            + "|configDir=" + configDir
            + "|modsDir=" + modsDir
            + "|runtimeConfigFile=" + runtimeConfigFile
            + "|externalRuntimeConfigLoaded=" + externalRuntimeConfigLoaded;
        return AOTCacheManager.sha256(descriptor);
    }

    private static RuntimeConfig load() {
        Properties properties = loadBundledDefaults();
        Path bootstrapGameDir = resolveGameDir(properties);
        Path bootstrapConfigDir = resolvePath(properties, "runtime.config.dir",
            bootstrapGameDir.resolve("config"), bootstrapGameDir);
        Path runtimeConfigFile = bootstrapConfigDir.resolve(RESOURCE_NAME).toAbsolutePath().normalize();
        boolean externalRuntimeConfigLoaded = loadExternalOverrides(properties, runtimeConfigFile);

        boolean aotCacheEnabled = getBoolean(properties, "aot.cache.enabled", true);
        boolean securityStrictMode = getBoolean(properties, "security.strict.mode", true);
        boolean legacyBroadPermissionDefaultsEnabled = getBoolean(
            properties,
            "security.legacy.broad.permissions.enabled",
            false
        );
        boolean mixinAstReclaimEnabled = getBoolean(properties, "mixin.ast.reclaim.enabled", true);
        String mixinConflictPolicy = getString(properties, "mixin.conflict.policy", "bridge");
        double observabilityThreshold = getDouble(properties, "observability.ewma.threshold.ms", 55.0d);
        // Default false: missing dependencies are a hard error unless the operator
        // explicitly opts in via resolver.allow.fallback=true.  Failing loud is safer
        // than silently loading an incomplete graph and hitting ClassNotFound at runtime.
        boolean resolverFallbackEnabled = getBoolean(properties, "resolver.allow.fallback", false);
        boolean espressoSandboxEnabled = getBoolean(properties, "sandbox.espresso.enabled", true);
        boolean wasmSandboxEnabled = getBoolean(properties, "sandbox.wasm.enabled", true);
        boolean nativeSandboxFallbackEnabled = getBoolean(properties, "sandbox.native.fallback.enabled", false);
        int sandboxSharedRegionBytes = getInt(properties, "sandbox.shared.region.bytes", 4096);
        int sandboxSharedRegionPoolMax = getInt(properties, "sandbox.shared.region.pool.max", 32);
        boolean classloaderDynamicWeakEdgesEnabled =
            getBoolean(properties, "classloader.dynamic.weak.edges.enabled", true);
        EnvType environmentType = resolveEnvironmentType(properties);
        Path gameDir = resolveGameDir(properties);
        Path configDir = resolvePath(properties, "runtime.config.dir", gameDir.resolve("config"), gameDir);
        if (!externalRuntimeConfigLoaded) {
            runtimeConfigFile = configDir.resolve(RESOURCE_NAME).toAbsolutePath().normalize();
        }
        boolean vfsEnabled = getBoolean(properties, "vfs.enabled", true);
        String vfsConflictPolicy = getString(properties, "vfs.conflict.policy", "merge_then_priority");
        List<String> vfsPriorityOrder = splitCsv(
            System.getProperty("vfs.priority.mods", properties.getProperty("vfs.priority.mods"))
        );
        Path vfsCacheDir = resolvePath(properties, "vfs.cache.dir", gameDir.resolve(".intermed").resolve("vfs"), gameDir);
        String modsOverride = firstNonBlank(
            System.getProperty("intermed.modsDir"),
            System.getProperty("runtime.mods.dir"),
            properties.getProperty("runtime.mods.dir")
        );
        Path modsDir = resolvePath(modsOverride, gameDir.resolve("intermed_mods"), gameDir);

        boolean prometheusEnabled = getBoolean(properties, "metrics.prometheus.enabled", false);
        int prometheusPort = getInt(properties, "metrics.prometheus.port", 9090);
        boolean otelEnabled = getBoolean(properties, "metrics.otel.enabled", false);
        Path otelOutputPath = resolvePath(properties, "metrics.otel.path",
            gameDir.resolve("logs").resolve("intermed-metrics.json"), gameDir);

        return new RuntimeConfig(
            aotCacheEnabled,
            securityStrictMode,
            legacyBroadPermissionDefaultsEnabled,
            mixinAstReclaimEnabled,
            mixinConflictPolicy,
            observabilityThreshold,
            resolverFallbackEnabled,
            espressoSandboxEnabled,
            wasmSandboxEnabled,
            nativeSandboxFallbackEnabled,
            Math.max(1024, sandboxSharedRegionBytes),
            Math.max(1, sandboxSharedRegionPoolMax),
            classloaderDynamicWeakEdgesEnabled,
            vfsEnabled,
            vfsConflictPolicy,
            vfsPriorityOrder,
            vfsCacheDir,
            environmentType,
            gameDir,
            configDir,
            modsDir,
            runtimeConfigFile,
            externalRuntimeConfigLoaded,
            prometheusEnabled,
            prometheusPort,
            otelEnabled,
            otelOutputPath
        );
    }

    private static Properties loadBundledDefaults() {
        Properties properties = new Properties();
        try (InputStream input = RuntimeConfig.class.getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (input != null) {
                properties.load(input);
            }
        } catch (Exception e) {
            System.err.println("[RuntimeConfig] Failed to load " + RESOURCE_NAME + ": " + e.getMessage());
        }
        return properties;
    }

    private static boolean loadExternalOverrides(Properties properties, Path runtimeConfigFile) {
        if (runtimeConfigFile == null || !Files.isRegularFile(runtimeConfigFile)) {
            return false;
        }
        try (InputStream input = Files.newInputStream(runtimeConfigFile)) {
            Properties overrides = new Properties();
            overrides.load(input);
            properties.putAll(overrides);
            return true;
        } catch (Exception e) {
            System.err.println("[RuntimeConfig] Failed to load external overrides from "
                + runtimeConfigFile + ": " + e.getMessage());
            return false;
        }
    }

    private static boolean getBoolean(Properties properties, String key, boolean defaultValue) {
        String value = System.getProperty(key, properties.getProperty(key));
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private static String getString(Properties properties, String key, String defaultValue) {
        String value = System.getProperty(key, properties.getProperty(key));
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static int getInt(Properties properties, String key, int defaultValue) {
        String value = System.getProperty(key, properties.getProperty(key));
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double getDouble(Properties properties, String key, double defaultValue) {
        String value = System.getProperty(key, properties.getProperty(key));
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Path resolveGameDir(Properties properties) {
        String defaultGameDir = System.getProperty("user.dir", ".");
        return resolvePath(properties, "runtime.game.dir", Paths.get(defaultGameDir), null);
    }

    private static EnvType resolveEnvironmentType(Properties properties) {
        String configured = firstNonBlank(
            System.getProperty("runtime.env"),
            System.getProperty("intermed.env"),
            System.getProperty("fabric.env"),
            properties.getProperty("runtime.env")
        );
        if (configured == null) {
            return EnvType.CLIENT;
        }
        return "server".equalsIgnoreCase(configured.trim()) ? EnvType.SERVER : EnvType.CLIENT;
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }

    private static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
            .map(token -> token == null ? "" : token.trim())
            .filter(token -> !token.isEmpty())
            .distinct()
            .toList();
    }

    private static Path resolvePath(Properties properties, String key, Path defaultValue, Path baseDir) {
        String configured = System.getProperty(key, properties.getProperty(key));
        return resolvePath(configured, defaultValue, baseDir);
    }

    private static Path resolvePath(String configured, Path defaultValue, Path baseDir) {
        if (configured == null || configured.isBlank()) {
            return defaultValue.toAbsolutePath().normalize();
        }
        Path candidate = Paths.get(configured.trim());
        if (!candidate.isAbsolute() && baseDir != null) {
            candidate = baseDir.resolve(candidate);
        }
        return candidate.toAbsolutePath().normalize();
    }
}
