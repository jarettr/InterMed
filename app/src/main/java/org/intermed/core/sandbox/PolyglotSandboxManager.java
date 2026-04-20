package org.intermed.core.sandbox;

import com.google.gson.JsonObject;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.monitor.RiskyModRegistry;
import org.intermed.core.security.Capability;
import org.intermed.core.security.CapabilityManager;
import org.intermed.core.security.SecurityPolicy;

import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarFile;

/**
 * Central Phase 4 sandbox planner and runtime facade.
 *
 * <p>Each sandboxed mod is wrapped in a {@link SupervisorNode} registered with
 * {@link SupervisorTree#root()}.  Failures are caught, the supervisor applies its
 * restart strategy (ONE_FOR_ONE by default), and the result is recorded as a
 * failure outcome rather than propagating as an uncaught exception (ТЗ 3.5.7).
 */
public final class PolyglotSandboxManager {

    private static final Logger LOG = Logger.getLogger(PolyglotSandboxManager.class.getName());
    private static final Map<String, SandboxPlan> PLANS = new ConcurrentHashMap<>();
    private static final Map<String, SandboxExecutionResult> EXECUTIONS = new ConcurrentHashMap<>();
    private static final AtomicLong EXECUTED_SANDBOXES = new AtomicLong();
    private static final AtomicLong ESPRESSO_EXECUTIONS = new AtomicLong();
    private static final AtomicLong WASM_EXECUTIONS = new AtomicLong();
    private static final AtomicLong EXECUTION_SUCCESSES = new AtomicLong();
    private static final AtomicLong EXECUTION_FAILURES = new AtomicLong();
    private static final AtomicLong HOT_PATH_REJECTIONS = new AtomicLong();
    private static final AtomicLong NATIVE_FALLBACK_RECOMMENDATIONS = new AtomicLong();

    private PolyglotSandboxManager() {}

    public static SandboxPlan registerPlan(NormalizedModMetadata metadata) {
        SandboxPlan plan = planFor(metadata);
        PLANS.put(plan.modId(), plan);
        return plan;
    }

    public static SandboxPlan planFor(NormalizedModMetadata metadata) {
        if (metadata == null) {
            return new SandboxPlan(
                "unknown",
                SandboxMode.NATIVE,
                SandboxMode.NATIVE,
                false,
                false,
                false,
                "metadata-missing",
                null,
                "init_mod"
            );
        }

        SandboxMode requested = SandboxMode.fromString(metadata.requestedSandboxMode());
        boolean risky = RiskyModRegistry.isModRisky(metadata.id());
        String reasonHint = metadata.sandboxReasonHint();
        if (requested == SandboxMode.NATIVE && risky) {
            requested = SandboxMode.ESPRESSO;
            if (reasonHint == null || reasonHint.isBlank()) {
                reasonHint = "risky-mod-promoted-to-espresso";
            }
        }

        boolean hotPath = metadata.sandboxHotPath();
        String modulePath = metadata.sandboxModulePath();
        String entrypoint = metadata.sandboxEntrypoint();
        boolean fallbackAllowed = nativeFallbackAllowed(metadata);

        if (requested == SandboxMode.NATIVE) {
            return new SandboxPlan(
                metadata.id(),
                SandboxMode.NATIVE,
                SandboxMode.NATIVE,
                risky,
                hotPath,
                false,
                risky ? "risky-mod-retained-native" : defaultReason(reasonHint, "native-execution"),
                modulePath,
                entrypoint
            );
        }

        if (hotPath) {
            if (fallbackAllowed) {
                // Mod allows native fallback: silently degrade to NATIVE to avoid
                // sandbox overhead on the tick thread.
                return forceNative(
                    metadata,
                    requested,
                    risky,
                    modulePath,
                    entrypoint,
                    composeReason(reasonHint, "sandbox-disabled-for-hot-path")
                );
            }
            // Mod explicitly disabled native fallback but is on the hot path.
            // Record the conflict in the plan; executeSandboxedEntrypoint() will
            // reject actual execution via the hotPath guard at the call site.
            System.err.printf(
                "[Sandbox] Hot-path mod '%s' requested %s with fallback disabled — "
                + "execution will be rejected at runtime (hot-path restriction)%n",
                metadata.id(), requested.externalName());
            return new SandboxPlan(
                metadata.id(),
                requested,
                requested,
                risky,
                true,
                false,
                composeReason(reasonHint, "sandbox-hot-path-rejected"),
                modulePath,
                entrypoint
            );
        }

        return switch (requested) {
            case ESPRESSO -> planEspresso(metadata, risky, modulePath, entrypoint, fallbackAllowed, reasonHint);
            case WASM -> planWasm(metadata, risky, modulePath, entrypoint, fallbackAllowed, reasonHint);
            case NATIVE -> new SandboxPlan(
                metadata.id(), SandboxMode.NATIVE, SandboxMode.NATIVE, risky, hotPath, false,
                defaultReason(reasonHint, "native-execution"), modulePath, entrypoint
            );
        };
    }

    public static Optional<SandboxPlan> getPlan(String modId) {
        if (modId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(PLANS.get(modId));
    }

    public static Collection<SandboxPlan> snapshotPlans() {
        return Collections.unmodifiableCollection(PLANS.values());
    }

    public static int hostExportCount() {
        return WitContractCatalog.hostExportCount();
    }

    public static String hostWitInterface() {
        return WitContractCatalog.renderHostInterface();
    }

    public static String hostContractDigest() {
        return WitContractCatalog.contractDigest();
    }

    public static SandboxRuntimeDiagnostics diagnostics() {
        GraalVMSandbox.HostStatus espressoStatus = GraalVMSandbox.probeAvailability();
        WasmSandbox.HostStatus wasmStatus = WasmSandbox.probeAvailability();
        WasmSandbox.ModuleCacheDiagnostics wasmCache = WasmSandbox.moduleCacheDiagnostics();
        RuntimeConfig config = RuntimeConfig.get();
        return new SandboxRuntimeDiagnostics(
            espressoStatus.isReady(),
            espressoStatus.state(),
            wasmStatus.isReady(),
            wasmStatus.state(),
            WitContractCatalog.hostExportCount(),
            WitContractCatalog.contractDigest(),
            WitContractCatalog.defaultJavaBindingsClassName(),
            PLANS.size(),
            EXECUTIONS.size(),
            EXECUTED_SANDBOXES.get(),
            ESPRESSO_EXECUTIONS.get(),
            WASM_EXECUTIONS.get(),
            EXECUTION_SUCCESSES.get(),
            EXECUTION_FAILURES.get(),
            HOT_PATH_REJECTIONS.get(),
            NATIVE_FALLBACK_RECOMMENDATIONS.get(),
            wasmCache.entries(),
            wasmCache.hits(),
            wasmCache.misses(),
            config.getSandboxSharedRegionBytes(),
            config.getSandboxSharedRegionPoolMax()
        );
    }

    public static String diagnosticsJson() {
        return diagnostics().toJson().toString();
    }

    public static GraalVMSandbox initializeEspressoSandbox(String modId, byte[] modData) {
        GraalVMSandbox sandbox = new GraalVMSandbox(modId);
        if (modData == null || modData.length == 0) {
            sandbox.initialize();
        } else {
            sandbox.initialize(modData);
        }
        return sandbox;
    }

    public static WasmSandbox.WasmExecutionResult initializeWasmSandbox(String modId, byte[] wasmBinary) {
        try (WasmSandbox sandbox = new WasmSandbox(modId)) {
            return sandbox.loadAndExecute(wasmBinary, "init_mod");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize Wasm sandbox for " + modId, e);
        }
    }

    public static void invalidateRuntimeCaches() {
        PLANS.clear();
        EXECUTIONS.clear();
        SandboxSharedExecutionContext.invalidatePool();
        EXECUTED_SANDBOXES.set(0L);
        ESPRESSO_EXECUTIONS.set(0L);
        WASM_EXECUTIONS.set(0L);
        EXECUTION_SUCCESSES.set(0L);
        EXECUTION_FAILURES.set(0L);
        HOT_PATH_REJECTIONS.set(0L);
        NATIVE_FALLBACK_RECOMMENDATIONS.set(0L);
    }

    public static void resetForTests() {
        invalidateRuntimeCaches();
        GraalVMSandbox.resetForTests();
        WasmSandbox.resetForTests();
        HotReloadController.get().resetForTests();
        SupervisorTree.resetRootForTests();
    }

    public static SandboxExecutionResult executeSandboxedEntrypoint(NormalizedModMetadata metadata,
                                                                    String key,
                                                                    String entrypointDefinition,
                                                                    String mode) {
        if (metadata == null) {
            return recordOutcome(new SandboxExecutionResult(
                "unknown",
                SandboxMode.NATIVE,
                SandboxMode.NATIVE,
                normalizeKey(key),
                sanitize(entrypointDefinition),
                false,
                false,
                "metadata-missing",
                "metadata-missing",
                "metadata=missing",
                "",
                "",
                List.of()
            ), false, false);
        }

        SandboxPlan plan = registerPlan(metadata);
        if (plan.hotPath() && plan.requestedMode() != SandboxMode.NATIVE && !plan.fallbackApplied()) {
            return recordOutcome(new SandboxExecutionResult(
                metadata.id(),
                plan.requestedMode(),
                plan.effectiveMode(),
                normalizeKey(key),
                sanitize(entrypointDefinition),
                false,
                false,
                "sandbox-hot-path-rejected",
                plan.reason(),
                describePlanRouting(plan),
                "",
                "",
                List.of()
            ), false, true);
        }
        if (!plan.isSandboxed()) {
            return recordOutcome(new SandboxExecutionResult(
                metadata.id(),
                plan.requestedMode(),
                plan.effectiveMode(),
                normalizeKey(key),
                sanitize(entrypointDefinition),
                false,
                plan.fallbackApplied(),
                "native-path-required:" + plan.reason(),
                plan.reason(),
                describePlanRouting(plan),
                "",
                "",
                List.of()
            ), false, false);
        }

        String normalizedKey = normalizeKey(key);
        String target = resolveTarget(plan, entrypointDefinition);
        String executionKey = executionCacheKey(metadata, plan, normalizedKey, target, mode);

        // Ensure a supervisor node exists for this mod (ТЗ 3.5.7).
        // The factory re-creates a SandboxedEntrypoint shell; the heavy lifting is in doExecute.
        final NormalizedModMetadata finalMetadata = metadata;
        final SandboxPlan finalPlan = plan;
        final String finalKey = normalizedKey;
        final String finalTarget = target;
        final String finalMode = mode;
        // Register a factory that produces a no-op SandboxedEntrypoint shell; the real
        // execution lives in doExecute — the supervisor's role is fault detection and restart
        // coordination, not the execution itself (ТЗ 3.5.7).
        HotReloadController.get().register(metadata.id(), () -> () -> null);
        SupervisorNode node = SupervisorTree.root().nodeFor(metadata.id());
        if (node == null) {
            node = new SupervisorNode(
                metadata.id(),
                SupervisorNode.RestartStrategy.ONE_FOR_ONE,
                /* maxRestarts */ 3,
                /* periodMs   */ 60_000,
                () -> () -> null
            );
            SupervisorTree.root().register(node);
        }

        SandboxExecutionResult result;
        try {
            final SupervisorNode finalNode = node;
            result = recordOutcome(
                finalNode.execute(() -> doExecute(finalMetadata, finalPlan, finalKey, finalTarget, finalMode)),
                true,
                false
            );
        } catch (SupervisorNode.SupervisedExecutionException ex) {
            LOG.log(Level.WARNING, "[Sandbox] supervised execution failed for " + metadata.id(), ex);
            result = recordOutcome(new SandboxExecutionResult(
                metadata.id(),
                plan.requestedMode(),
                plan.effectiveMode(),
                normalizedKey,
                target,
                false,
                plan.fallbackApplied(),
                "supervised-execution-failed:" + ex.getCause().getClass().getSimpleName(),
                ex.getCause().getMessage() != null ? ex.getCause().getMessage() : "unknown",
                describePlanRouting(plan),
                "",
                "",
                List.of()
            ), true, false);
        }

        if (result.success()) {
            EXECUTIONS.put(executionKey, result);
        } else {
            EXECUTIONS.remove(executionKey);
        }
        return result;
    }

    private static SandboxPlan planEspresso(NormalizedModMetadata metadata,
                                            boolean risky,
                                            String modulePath,
                                            String entrypoint,
                                            boolean fallbackAllowed,
                                            String reasonHint) {
        if (!RuntimeConfig.get().isEspressoSandboxEnabled()) {
            return fallback(metadata, SandboxMode.ESPRESSO, risky, false, modulePath, entrypoint,
                fallbackAllowed, composeReason(reasonHint, "espresso-disabled-by-config"));
        }
        GraalVMSandbox.HostStatus hostStatus = GraalVMSandbox.probeAvailability();
        if (!hostStatus.isReady()) {
            return fallback(metadata, SandboxMode.ESPRESSO, risky, false, modulePath, entrypoint,
                fallbackAllowed, composeReason(reasonHint, "espresso-host-unavailable:" + hostStatus.state()));
        }
        return new SandboxPlan(
            metadata.id(),
            SandboxMode.ESPRESSO,
            SandboxMode.ESPRESSO,
            risky,
            false,
            false,
            defaultReason(reasonHint, "espresso-ready"),
            modulePath,
            entrypoint
        );
    }

    private static SandboxPlan planWasm(NormalizedModMetadata metadata,
                                        boolean risky,
                                        String modulePath,
                                        String entrypoint,
                                        boolean fallbackAllowed,
                                        String reasonHint) {
        if (!RuntimeConfig.get().isWasmSandboxEnabled()) {
            return fallback(metadata, SandboxMode.WASM, risky, false, modulePath, entrypoint,
                fallbackAllowed, composeReason(reasonHint, "wasm-disabled-by-config"));
        }
        if (modulePath == null || modulePath.isBlank()) {
            return fallback(metadata, SandboxMode.WASM, risky, false, modulePath, entrypoint,
                fallbackAllowed, composeReason(reasonHint, "wasm-module-missing"));
        }
        if (!jarContains(metadata.sourceJar(), modulePath)) {
            return fallback(metadata, SandboxMode.WASM, risky, false, modulePath, entrypoint,
                fallbackAllowed, composeReason(reasonHint, "wasm-module-not-found-in-jar"));
        }
        WasmSandbox.HostStatus hostStatus = WasmSandbox.probeAvailability();
        if (!hostStatus.isReady()) {
            return fallback(metadata, SandboxMode.WASM, risky, false, modulePath, entrypoint,
                fallbackAllowed, composeReason(reasonHint, "wasm-host-unavailable:" + hostStatus.state()));
        }
        return new SandboxPlan(
            metadata.id(),
            SandboxMode.WASM,
            SandboxMode.WASM,
            risky,
            false,
            false,
            defaultReason(reasonHint, "wasm-ready"),
            modulePath,
            entrypoint
        );
    }

    private static SandboxPlan fallback(NormalizedModMetadata metadata,
                                        SandboxMode requested,
                                        boolean risky,
                                        boolean hotPath,
                                        String modulePath,
                                        String entrypoint,
                                        boolean fallbackAllowed,
                                        String reason) {
        SandboxMode effective = fallbackAllowed ? SandboxMode.NATIVE : requested;
        return new SandboxPlan(
            metadata.id(),
            requested,
            effective,
            risky,
            hotPath,
            fallbackAllowed && effective == SandboxMode.NATIVE,
            reason,
            modulePath,
            entrypoint
        );
    }

    private static SandboxPlan forceNative(NormalizedModMetadata metadata,
                                           SandboxMode requested,
                                           boolean risky,
                                           String modulePath,
                                           String entrypoint,
                                           String reason) {
        return new SandboxPlan(
            metadata.id(),
            requested,
            SandboxMode.NATIVE,
            risky,
            true,
            true,
            reason,
            modulePath,
            entrypoint
        );
    }

    private static boolean jarContains(File jarFile, String entryName) {
        if (jarFile == null || entryName == null || entryName.isBlank()) {
            return false;
        }
        try (JarFile jar = new JarFile(jarFile)) {
            return jar.getJarEntry(normalizeEntry(entryName)) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizeEntry(String entryName) {
        return entryName.replace('\\', '/');
    }

    private static String defaultReason(String hint, String defaultValue) {
        return hint == null || hint.isBlank() ? defaultValue : hint.trim();
    }

    private static String composeReason(String hint, String reason) {
        if (hint == null || hint.isBlank()) {
            return reason;
        }
        String normalizedHint = hint.trim();
        if (normalizedHint.equals(reason)) {
            return reason;
        }
        return normalizedHint + " | " + reason;
    }

    private static SandboxExecutionResult doExecute(NormalizedModMetadata metadata,
                                                    SandboxPlan plan,
                                                    String key,
                                                    String target,
                                                    String mode) {
        try (SandboxSharedExecutionContext.ExecutionFrame frame = SandboxSharedExecutionContext.open(
            metadata.id(),
            key,
            target,
            plan.requestedMode(),
            plan.effectiveMode(),
            plan.reason(),
            plan.hotPath(),
            plan.risky(),
            plan.fallbackApplied(),
            buildSharedStateGraph(metadata, plan, key, target, mode)
        )) {
            return SandboxSharedExecutionContext.bind(frame, () -> switch (plan.effectiveMode()) {
                case ESPRESSO -> executeEspresso(metadata, plan, key, target, mode, frame);
                case WASM -> executeWasm(metadata, plan, key, frame);
                case NATIVE -> new SandboxExecutionResult(
                    metadata.id(),
                    plan.requestedMode(),
                    plan.effectiveMode(),
                    key,
                    target,
                    false,
                    plan.fallbackApplied(),
                    "native-path-required:" + plan.reason(),
                    plan.reason(),
                    combineDiagnostics(describePlanRouting(plan), describeSharedTransport(frame)),
                    "",
                    "",
                    List.of()
                );
            });
        }
    }

    private static SandboxSharedExecutionContext.SharedStateGraph buildSharedStateGraph(NormalizedModMetadata metadata,
                                                                                        SandboxPlan plan,
                                                                                        String key,
                                                                                        String target,
                                                                                        String mode) {
        SandboxSharedExecutionContext.SharedStateNode root = SandboxSharedExecutionContext.SharedStateNode
            .of("execution", metadata.id())
            .withProperties(Map.of(
                "key", sanitize(key),
                "target", sanitize(target),
                "mode", sanitize(mode),
                "requestedSandbox", plan.requestedMode().externalName(),
                "effectiveSandbox", plan.effectiveMode().externalName(),
                "reason", sanitize(plan.reason())
            ))
            .withFlags(executionFlags(plan));

        SandboxSharedExecutionContext.SharedStateNode modNode = SandboxSharedExecutionContext.SharedStateNode
            .of("mod", metadata.id())
            .withProperties(Map.of(
                "version", sanitize(metadata.version()),
                "platform", metadata.platform().name().toLowerCase(Locale.ROOT),
                "sourceJar", metadata.sourceJar() == null ? "" : metadata.sourceJar().getAbsolutePath()
            ));

        SandboxSharedExecutionContext.SharedStateNode sandboxNode = SandboxSharedExecutionContext.SharedStateNode
            .of("sandbox-plan", plan.effectiveMode().externalName())
            .withProperties(Map.of(
                "requestedMode", plan.requestedMode().externalName(),
                "effectiveMode", plan.effectiveMode().externalName(),
                "entrypoint", sanitize(plan.entrypoint()),
                "modulePath", sanitize(plan.modulePath()),
                "reason", sanitize(plan.reason())
            ))
            .withFlags(executionFlags(plan));

        SandboxSharedExecutionContext.SharedStateNode targetNode = SandboxSharedExecutionContext.SharedStateNode
            .of("entrypoint-target", sanitize(target))
            .withProperties(Map.of(
                "invocationKey", sanitize(key),
                "invocationMode", sanitize(mode)
            ));

        SandboxSharedExecutionContext.SharedStateNode hostContractNode = SandboxSharedExecutionContext.SharedStateNode
            .of("host-contract", WitContractCatalog.contractDigest())
            .withProperties(Map.of(
                "digest", WitContractCatalog.contractDigest(),
                "exports", Integer.toString(WitContractCatalog.hostExportCount()),
                "package", WitContractCatalog.packageName(),
                "interface", WitContractCatalog.interfaceName(),
                "javaBindingsClass", WitContractCatalog.defaultJavaBindingsClassName()
            ));

        SandboxSharedExecutionContext.SharedStateNode capabilityNode = SandboxSharedExecutionContext.SharedStateNode
            .of("capabilities", metadata.id())
            .withProperties(capabilityProperties(metadata.id()));

        SandboxSharedExecutionContext.SharedStateNode manifestNode = SandboxSharedExecutionContext.SharedStateNode
            .of("manifest", metadata.platform().name().toLowerCase(Locale.ROOT))
            .withProperties(Map.of(
                "mixinConfigCount", Integer.toString(metadata.mixinConfigs().size()),
                "mainEntrypoints", Integer.toString(metadata.entrypoints("main").size()),
                "clientEntrypoints", Integer.toString(metadata.entrypoints("client").size()),
                "serverEntrypoints", Integer.toString(metadata.entrypoints("server").size())
            ));

        root = root.withChildren(List.of(modNode, sandboxNode, targetNode, hostContractNode, capabilityNode, manifestNode));
        return SandboxSharedExecutionContext.SharedStateGraph.ofRoot(root);
    }

    private static int executionFlags(SandboxPlan plan) {
        int flags = 0;
        if (plan.hotPath()) {
            flags |= 1;
        }
        if (plan.risky()) {
            flags |= 1 << 1;
        }
        if (plan.fallbackApplied()) {
            flags |= 1 << 2;
        }
        return flags;
    }

    private static SandboxExecutionResult executeEspresso(NormalizedModMetadata metadata,
                                                          SandboxPlan plan,
                                                          String key,
                                                          String target,
                                                          String mode,
                                                          SandboxSharedExecutionContext.ExecutionFrame frame) {
        String lifecycleMethod = resolveEspressoMethod(key, mode, plan.entrypoint());
        boolean constructOnly = "construct".equalsIgnoreCase(sanitize(mode));
        return CapabilityManager.executeAsMod(metadata.id(), () -> {
            GraalVMSandbox sandbox = new GraalVMSandbox(metadata.id());
            try (sandbox) {
                GraalVMSandbox.EspressoExecutionResult result = sandbox.executeEntrypoint(
                    metadata.sourceJar(),
                    target,
                    lifecycleMethod,
                    constructOnly,
                    guestPropertiesForExecution(metadata, plan, key, target, frame)
                );
                boolean fallback = !result.success() && nativeFallbackAllowed(metadata);
                return new SandboxExecutionResult(
                    metadata.id(),
                    plan.requestedMode(),
                    plan.effectiveMode(),
                    key,
                    target,
                    result.success(),
                    fallback,
                    result.message(),
                    plan.reason(),
                    combineDiagnostics(describePlanRouting(plan), describeSharedTransport(frame), sandbox.diagnostics()),
                    result.stdout(),
                    result.stderr(),
                    List.of()
                );
            } catch (Exception e) {
                return new SandboxExecutionResult(
                    metadata.id(),
                    plan.requestedMode(),
                    plan.effectiveMode(),
                    key,
                    target,
                    false,
                    nativeFallbackAllowed(metadata),
                    describeFailure("espresso-runtime-failed", e),
                    plan.reason(),
                    combineDiagnostics(describePlanRouting(plan), describeSharedTransport(frame), sandbox.diagnostics()),
                    "",
                    "",
                    List.of()
                );
            }
        });
    }

    private static SandboxExecutionResult executeWasm(NormalizedModMetadata metadata,
                                                      SandboxPlan plan,
                                                      String key,
                                                      SandboxSharedExecutionContext.ExecutionFrame frame) {
        return CapabilityManager.executeAsMod(metadata.id(), () -> {
            try (WasmSandbox sandbox = new WasmSandbox(metadata.id())) {
                byte[] moduleBytes = readJarEntryBytes(metadata.sourceJar(), plan.modulePath());
                WasmSandbox.WasmExecutionResult result = sandbox.loadAndExecute(moduleBytes, plan.entrypoint());
                return new SandboxExecutionResult(
                    metadata.id(),
                    plan.requestedMode(),
                    plan.effectiveMode(),
                    key,
                    plan.entrypoint(),
                    true,
                    false,
                    "wasm-executed",
                    plan.reason(),
                    combineDiagnostics(describeWasmDiagnostics(plan), describeSharedTransport(frame), sandbox.diagnostics()),
                    result.stdout(),
                    result.stderr(),
                    toLongList(result.results())
                );
            } catch (Exception e) {
                return new SandboxExecutionResult(
                    metadata.id(),
                    plan.requestedMode(),
                    plan.effectiveMode(),
                    key,
                    plan.entrypoint(),
                    false,
                    nativeFallbackAllowed(metadata),
                    describeFailure("wasm-runtime-failed", e),
                    plan.reason(),
                    combineDiagnostics(describeWasmDiagnostics(plan), describeSharedTransport(frame)),
                    "",
                    "",
                    List.of()
                );
            }
        });
    }

    private static byte[] readJarEntryBytes(File jarFile, String entryName) throws Exception {
        if (jarFile == null || entryName == null || entryName.isBlank()) {
            throw new IllegalStateException("jar-entry-missing");
        }
        try (JarFile jar = new JarFile(jarFile)) {
            var entry = jar.getJarEntry(normalizeEntry(entryName));
            if (entry == null) {
                throw new IllegalStateException("jar-entry-not-found:" + entryName);
            }
            try (InputStream input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }
    }

    private static String executionCacheKey(NormalizedModMetadata metadata,
                                            SandboxPlan plan,
                                            String key,
                                            String target,
                                            String mode) {
        String effectiveTarget = plan.effectiveMode() == SandboxMode.WASM
            ? sanitize(plan.entrypoint())
            : sanitize(target);
        return executionFingerprint(metadata, plan)
            + '|'
            + plan.effectiveMode().externalName()
            + '|'
            + key
            + '|'
            + effectiveTarget
            + '|'
            + sanitize(mode);
    }

    private static String executionFingerprint(NormalizedModMetadata metadata, SandboxPlan plan) {
        if (metadata == null) {
            return "unknown";
        }
        File sourceJar = metadata.sourceJar();
        String sourceFingerprint = sourceJar == null
            ? "no-source-jar"
            : sourceJar.getAbsolutePath()
                + ':'
                + sourceJar.length()
                + ':'
                + sourceJar.lastModified();
        return metadata.id()
            + '|'
            + metadata.version()
            + '|'
            + sanitize(plan.modulePath())
            + '|'
            + sourceFingerprint
            + '|'
            + RuntimeConfig.get().cacheFingerprint()
            + '|'
            + WitContractCatalog.contractDigest()
            + '|'
            + capabilityFingerprint(metadata.id());
    }

    private static String resolveTarget(SandboxPlan plan, String entrypointDefinition) {
        if (plan.effectiveMode() == SandboxMode.WASM) {
            return sanitize(plan.entrypoint());
        }
        return sanitize(entrypointDefinition);
    }

    private static String resolveEspressoMethod(String key, String mode, String sandboxEntrypoint) {
        if ("construct".equalsIgnoreCase(sanitize(mode))) {
            return "";
        }
        String explicitEntrypoint = sanitize(sandboxEntrypoint);
        if (!explicitEntrypoint.isBlank() && !"init_mod".equals(explicitEntrypoint)) {
            return explicitEntrypoint;
        }
        return switch (normalizeKey(key)) {
            case "client" -> "onInitializeClient";
            case "main" -> "onInitialize";
            case "server" -> "onInitializeServer";
            default -> "";
        };
    }

    private static Map<String, String> guestPropertiesForExecution(NormalizedModMetadata metadata,
                                                                   SandboxPlan plan,
                                                                   String key,
                                                                   String target,
                                                                   SandboxSharedExecutionContext.ExecutionFrame frame) {
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        properties.put("intermed.sandbox.invocationKey", sanitize(key));
        properties.put("intermed.sandbox.target", sanitize(target));
        properties.put("intermed.sandbox.planReason", sanitize(plan.reason()));
        properties.put("intermed.sandbox.requestedMode", plan.requestedMode().externalName());
        properties.put("intermed.sandbox.effectiveMode", plan.effectiveMode().externalName());
        properties.put("intermed.sandbox.shared.transport", frame.transportKind());
        properties.put("intermed.sandbox.shared.bytes", Integer.toString(frame.bytesUsed()));
        properties.put("intermed.sandbox.hostContract.sha256", WitContractCatalog.contractDigest());
        properties.put("intermed.sandbox.hostContract.exports", Integer.toString(WitContractCatalog.hostExportCount()));
        properties.put("intermed.sandbox.hostContract.javaBindings", WitContractCatalog.defaultJavaBindingsClassName());
        properties.put("intermed.sandbox.hostContract.interface", WitContractCatalog.interfaceName());
        if (metadata != null) {
            properties.put("intermed.sandbox.modVersion", sanitize(metadata.version()));
            properties.put("intermed.sandbox.platform", metadata.platform().name().toLowerCase(Locale.ROOT));
        }
        return Map.copyOf(properties);
    }

    private static Map<String, String> capabilityProperties(String modId) {
        LinkedHashMap<String, String> properties = new LinkedHashMap<>();
        int granted = 0;
        for (Capability capability : Capability.values()) {
            boolean allowed = SecurityPolicy.hasCapabilityGrant(modId, capability);
            if (allowed) {
                granted++;
            }
            properties.put(capability.name().toLowerCase(Locale.ROOT), Boolean.toString(allowed));
        }
        properties.put("grantedCount", Integer.toString(granted));
        return Map.copyOf(properties);
    }

    private static String capabilityFingerprint(String modId) {
        Map<String, String> properties = capabilityProperties(modId);
        StringBuilder builder = new StringBuilder();
        properties.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> builder
                .append(entry.getKey())
                .append('=')
                .append(entry.getValue())
                .append(';'));
        return builder.toString();
    }

    private static boolean nativeFallbackAllowed(NormalizedModMetadata metadata) {
        return metadata != null
            && !RiskyModRegistry.isModRisky(metadata.id())
            && metadata.sandboxAllowNativeFallback()
            && RuntimeConfig.get().isNativeSandboxFallbackEnabled();
    }

    private static String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return "main";
        }
        return key.trim().toLowerCase(Locale.ROOT);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String describePlanRouting(SandboxPlan plan) {
        if (plan == null) {
            return "plan=missing";
        }
        return combineDiagnostics(
            "requested=" + plan.requestedMode().externalName(),
            "effective=" + plan.effectiveMode().externalName(),
            "fallbackApplied=" + plan.fallbackApplied(),
            "hotPath=" + plan.hotPath(),
            plan.modulePath() == null || plan.modulePath().isBlank() ? "" : "modulePath=" + plan.modulePath(),
            plan.entrypoint() == null || plan.entrypoint().isBlank() ? "" : "entrypoint=" + plan.entrypoint()
        );
    }

    private static String describeWasmDiagnostics(SandboxPlan plan) {
        WasmSandbox.HostStatus hostStatus = WasmSandbox.probeAvailability();
        return combineDiagnostics(
            describePlanRouting(plan),
            "runtimeAvailable=" + hostStatus.runtimeAvailable(),
            "probeSucceeded=" + hostStatus.probeSucceeded(),
            "probeState=" + hostStatus.state()
        );
    }

    private static String describeSharedTransport(SandboxSharedExecutionContext.ExecutionFrame frame) {
        if (frame == null) {
            return "";
        }
        return combineDiagnostics(
            "sharedTransport=" + frame.transportKind(),
            "sharedStateBytes=" + frame.bytesUsed()
        );
    }

    private static String combineDiagnostics(String... values) {
        if (values == null || values.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(value.trim());
        }
        return builder.toString();
    }

    private static SandboxExecutionResult recordOutcome(SandboxExecutionResult result,
                                                        boolean countedExecution,
                                                        boolean hotPathRejected) {
        if (hotPathRejected) {
            HOT_PATH_REJECTIONS.incrementAndGet();
        }
        if (countedExecution) {
            EXECUTED_SANDBOXES.incrementAndGet();
            if (result.effectiveMode() == SandboxMode.ESPRESSO) {
                ESPRESSO_EXECUTIONS.incrementAndGet();
            } else if (result.effectiveMode() == SandboxMode.WASM) {
                WASM_EXECUTIONS.incrementAndGet();
            }
            if (result.success()) {
                EXECUTION_SUCCESSES.incrementAndGet();
            } else {
                EXECUTION_FAILURES.incrementAndGet();
            }
        }
        if (result.nativeFallbackRecommended()) {
            NATIVE_FALLBACK_RECOMMENDATIONS.incrementAndGet();
        }
        return result;
    }

    private static String describeFailure(String prefix, Throwable throwable) {
        if (throwable == null) {
            return prefix + ":unknown";
        }
        String detail = throwable.getClass().getSimpleName();
        String message = throwable.getMessage();
        if (message != null && !message.isBlank()) {
            detail += ":" + message.trim();
        }
        return prefix + ':' + detail;
    }

    private static List<Long> toLongList(long[] values) {
        if (values == null || values.length == 0) {
            return List.of();
        }
        Long[] boxed = new Long[values.length];
        for (int i = 0; i < values.length; i++) {
            boxed[i] = values[i];
        }
        return List.of(boxed);
    }

    public record SandboxRuntimeDiagnostics(boolean espressoReady,
                                            String espressoState,
                                            boolean wasmReady,
                                            String wasmState,
                                            int hostExportCount,
                                            String hostContractDigest,
                                            String javaBindingsClassName,
                                            int planCacheEntries,
                                            int executionCacheEntries,
                                            long executedSandboxes,
                                            long espressoExecutions,
                                            long wasmExecutions,
                                            long executionSuccesses,
                                            long executionFailures,
                                            long hotPathRejections,
                                            long nativeFallbackRecommendations,
                                            int wasmModuleCacheEntries,
                                            long wasmModuleCacheHits,
                                            long wasmModuleCacheMisses,
                                            int sharedRegionBytes,
                                            int sharedRegionPoolMax) {

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            JsonObject espresso = new JsonObject();
            espresso.addProperty("ready", espressoReady);
            espresso.addProperty("state", sanitize(espressoState));
            json.add("espresso", espresso);

            JsonObject wasm = new JsonObject();
            wasm.addProperty("ready", wasmReady);
            wasm.addProperty("state", sanitize(wasmState));
            json.add("wasm", wasm);

            json.addProperty("hostExportCount", hostExportCount);
            json.addProperty("hostContractDigest", sanitize(hostContractDigest));
            json.addProperty("javaBindingsClassName", sanitize(javaBindingsClassName));
            json.addProperty("planCacheEntries", planCacheEntries);
            json.addProperty("executionCacheEntries", executionCacheEntries);
            json.addProperty("executedSandboxes", executedSandboxes);
            json.addProperty("espressoExecutions", espressoExecutions);
            json.addProperty("wasmExecutions", wasmExecutions);
            json.addProperty("executionSuccesses", executionSuccesses);
            json.addProperty("executionFailures", executionFailures);
            json.addProperty("hotPathRejections", hotPathRejections);
            json.addProperty("nativeFallbackRecommendations", nativeFallbackRecommendations);

            JsonObject wasmCache = new JsonObject();
            wasmCache.addProperty("entries", wasmModuleCacheEntries);
            wasmCache.addProperty("hits", wasmModuleCacheHits);
            wasmCache.addProperty("misses", wasmModuleCacheMisses);
            json.add("wasmModuleCache", wasmCache);

            JsonObject shared = new JsonObject();
            shared.addProperty("regionBytes", sharedRegionBytes);
            shared.addProperty("regionPoolMax", sharedRegionPoolMax);
            json.add("sharedTransport", shared);
            return json;
        }

        private static String sanitize(String value) {
            return value == null ? "" : value;
        }
    }
}
