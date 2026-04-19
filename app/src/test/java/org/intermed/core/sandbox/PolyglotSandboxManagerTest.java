package org.intermed.core.sandbox;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.metadata.ModMetadataParser;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.monitor.RiskyModRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@Tag("strict-security")
class PolyglotSandboxManagerTest {

    @AfterEach
    void tearDown() {
        PolyglotSandboxManager.resetForTests();
        RiskyModRegistry.resetForTests();
        System.clearProperty("sandbox.espresso.enabled");
        System.clearProperty("sandbox.wasm.enabled");
        System.clearProperty("sandbox.native.fallback.enabled");
        RuntimeConfig.resetForTests();
    }

    @Test
    void plansWasmSandboxWhenModuleIsPresentAndColdPath() throws Exception {
        Path jar = Files.createTempFile("intermed-wasm-plan-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                {
                  "id": "wasm_mod",
                  "version": "1.0.0",
                  "intermed:sandbox": {
                    "mode": "wasm",
                    "modulePath": "META-INF/wasm/demo.wasm",
                    "entrypoint": "init_mod"
                  },
                  "entrypoints": { "main": ["demo.wasm.EntryPoint"] }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new JarEntry("META-INF/wasm/demo.wasm"));
            output.write(simpleConstModule(7));
            output.closeEntry();
        }

        Optional<NormalizedModMetadata> parsed = ModMetadataParser.parse(jar.toFile());
        assertTrue(parsed.isPresent());

        SandboxPlan plan = PolyglotSandboxManager.planFor(parsed.get());
        assertEquals(SandboxMode.WASM, plan.requestedMode());
        assertEquals(SandboxMode.WASM, plan.effectiveMode());
        assertFalse(plan.hotPath());
        assertFalse(plan.fallbackApplied());
    }

    @Test
    void fallsBackToNativeForHotPathSandboxRequests() throws Exception {
        System.setProperty("sandbox.native.fallback.enabled", "true");
        RuntimeConfig.reload();

        Path jar = Files.createTempFile("intermed-espresso-plan-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                {
                  "id": "hot_path_mod",
                  "version": "1.0.0",
                  "mixins": ["hotpath.mixins.json"],
                  "intermed:sandbox": {
                    "mode": "espresso",
                    "allowNativeFallback": true
                  },
                  "entrypoints": { "main": ["demo.hot.EntryPoint"] }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        Optional<NormalizedModMetadata> parsed = ModMetadataParser.parse(jar.toFile());
        assertTrue(parsed.isPresent());

        SandboxPlan plan = PolyglotSandboxManager.planFor(parsed.get());
        assertEquals(SandboxMode.ESPRESSO, plan.requestedMode());
        assertEquals(SandboxMode.NATIVE, plan.effectiveMode());
        assertTrue(plan.hotPath());
        assertTrue(plan.fallbackApplied());
        assertTrue(plan.reason().contains("hot-path"));
    }

    @Test
    void failsClosedForHotPathSandboxRequestsWhenNativeFallbackIsDisabled() throws Exception {
        Path jar = Files.createTempFile("intermed-espresso-hot-closed-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                {
                  "id": "hot_path_closed_mod",
                  "version": "1.0.0",
                  "mixins": ["hotpath.mixins.json"],
                  "intermed:sandbox": {
                    "mode": "espresso",
                    "allowNativeFallback": false
                  },
                  "entrypoints": { "main": ["demo.hot.EntryPoint"] }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        NormalizedModMetadata metadata = ModMetadataParser.parse(jar.toFile()).orElseThrow();

        SandboxPlan plan = PolyglotSandboxManager.planFor(metadata);
        assertEquals(SandboxMode.ESPRESSO, plan.requestedMode());
        assertEquals(SandboxMode.ESPRESSO, plan.effectiveMode());
        assertTrue(plan.hotPath());
        assertFalse(plan.fallbackApplied());
        assertEquals("sandbox-hot-path-rejected", plan.reason());

        SandboxExecutionResult result = PolyglotSandboxManager.executeSandboxedEntrypoint(
            metadata,
            "main",
            "demo.hot.EntryPoint",
            "invoke"
        );

        assertFalse(result.success());
        assertEquals(SandboxMode.ESPRESSO, result.effectiveMode());
        assertFalse(result.nativeFallbackRecommended());
        assertEquals("sandbox-hot-path-rejected", result.message());
        assertEquals("sandbox-hot-path-rejected", result.planReason());
    }

    @Test
    void runtimeConfigReloadInvalidatesCachedSandboxPlans() throws Exception {
        Path jar = createEspressoModJar("espresso_reload_mod", "demo.espresso.EntryPoint", false, """
            package demo.espresso;
            public class EntryPoint implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                }
            }
            """);

        NormalizedModMetadata metadata = ModMetadataParser.parse(jar.toFile()).orElseThrow();

        System.setProperty("sandbox.espresso.enabled", "false");
        RuntimeConfig.reload();
        SandboxPlan disabledPlan = PolyglotSandboxManager.registerPlan(metadata);
        assertEquals(SandboxMode.ESPRESSO, disabledPlan.effectiveMode());
        assertEquals("espresso-disabled-by-config", disabledPlan.reason());
        assertTrue(PolyglotSandboxManager.getPlan("espresso_reload_mod").isPresent());

        System.setProperty("sandbox.espresso.enabled", "true");
        RuntimeConfig.reload();
        assertTrue(PolyglotSandboxManager.getPlan("espresso_reload_mod").isEmpty());

        SandboxPlan refreshedPlan = PolyglotSandboxManager.registerPlan(metadata);
        assertEquals(SandboxMode.ESPRESSO, refreshedPlan.effectiveMode());
        assertNotEquals("espresso-disabled-by-config", refreshedPlan.reason());
    }

    @Test
    void executesWasmSandboxThroughUnifiedFacade() throws Exception {
        Path jar = Files.createTempFile("intermed-wasm-exec-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                {
                  "id": "wasm_exec_mod",
                  "version": "1.0.0",
                  "intermed:sandbox": {
                    "mode": "wasm",
                    "modulePath": "META-INF/wasm/demo.wasm",
                    "entrypoint": "init_mod"
                  },
                  "entrypoints": { "main": ["demo.wasm.EntryPoint"] }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new JarEntry("META-INF/wasm/demo.wasm"));
            output.write(simpleConstModule(7));
            output.closeEntry();
        }

        NormalizedModMetadata metadata = ModMetadataParser.parse(jar.toFile()).orElseThrow();

        SandboxExecutionResult result = PolyglotSandboxManager.executeSandboxedEntrypoint(
            metadata,
            "main",
            "demo.wasm.EntryPoint",
            "invoke"
        );

        assertTrue(result.success());
        assertEquals(SandboxMode.WASM, result.effectiveMode());
        assertEquals(List.of(7L), result.numericResults());
        assertEquals("init_mod", result.target());
        assertEquals("wasm-ready", result.planReason());
        assertTrue(result.runtimeDiagnostics().contains("probeState=ready"), result.runtimeDiagnostics());
    }

    @Test
    void repeatedSandboxInvocationsExecuteAgainInsteadOfServingCachedResults() throws Exception {
        Path jar = createWasmModJar("repeat_wasm_mod", "1.0.0", simpleConstModule(7));
        NormalizedModMetadata metadata = ModMetadataParser.parse(jar.toFile()).orElseThrow();

        SandboxExecutionResult first = PolyglotSandboxManager.executeSandboxedEntrypoint(
            metadata,
            "main",
            "demo.wasm.EntryPoint",
            "invoke"
        );
        long executedAfterFirst = PolyglotSandboxManager.diagnostics().executedSandboxes();

        SandboxExecutionResult second = PolyglotSandboxManager.executeSandboxedEntrypoint(
            metadata,
            "main",
            "demo.wasm.EntryPoint",
            "invoke"
        );
        PolyglotSandboxManager.SandboxRuntimeDiagnostics diagnostics = PolyglotSandboxManager.diagnostics();

        assertTrue(first.success(), first.message());
        assertTrue(second.success(), second.message());
        assertTrue(diagnostics.executedSandboxes() >= executedAfterFirst + 1L);
        assertTrue(diagnostics.wasmExecutions() >= 2L);
    }

    @Test
    void invalidatesCachedSandboxExecutionWhenSourceJarChanges() throws Exception {
        Path jarV1 = createWasmModJar("cached_wasm_mod", "1.0.0", simpleConstModule(7));
        Path jarV2 = createWasmModJar("cached_wasm_mod", "2.0.0", simpleConstModule(9));

        NormalizedModMetadata metadataV1 = ModMetadataParser.parse(jarV1.toFile()).orElseThrow();
        NormalizedModMetadata metadataV2 = ModMetadataParser.parse(jarV2.toFile()).orElseThrow();

        SandboxExecutionResult first = PolyglotSandboxManager.executeSandboxedEntrypoint(
            metadataV1,
            "main",
            "demo.wasm.EntryPoint",
            "invoke"
        );
        SandboxExecutionResult second = PolyglotSandboxManager.executeSandboxedEntrypoint(
            metadataV2,
            "main",
            "demo.wasm.EntryPoint",
            "invoke"
        );

        assertTrue(first.success(), first.message());
        assertTrue(second.success(), second.message());
        assertEquals(List.of(7L), first.numericResults());
        assertEquals(List.of(9L), second.numericResults());
    }

    @Test
    void wasmSandboxReceivesWasmRuntimeContextThroughHostApi() throws Exception {
        Path jar = createWasmModJar(
            "wasm_context_mod",
            "1.0.0",
            WasmTestModuleFactory.currentSandboxInvocationKeyLengthModule()
        );
        NormalizedModMetadata metadata = ModMetadataParser.parse(jar.toFile()).orElseThrow();

        SandboxExecutionResult result = PolyglotSandboxManager.executeSandboxedEntrypoint(
            metadata,
            "main",
            "demo.wasm.EntryPoint",
            "invoke"
        );

        assertTrue(result.success(), result.message());
        assertEquals(SandboxMode.WASM, result.effectiveMode());
        assertEquals(List.of(4L), result.numericResults());
        assertTrue(result.runtimeDiagnostics().contains("sharedTransport="), result.runtimeDiagnostics());
        assertTrue(result.runtimeDiagnostics().contains("sharedStateBytes="), result.runtimeDiagnostics());
    }

    @Test
    void wasmSandboxReceivesSharedStateSizeThroughHostApi() throws Exception {
        Path jar = createWasmModJar(
            "wasm_shared_state_mod",
            "1.0.0",
            WasmTestModuleFactory.currentSandboxSharedStateBytesModule()
        );
        NormalizedModMetadata metadata = ModMetadataParser.parse(jar.toFile()).orElseThrow();

        SandboxExecutionResult result = PolyglotSandboxManager.executeSandboxedEntrypoint(
            metadata,
            "main",
            "demo.wasm.EntryPoint",
            "invoke"
        );

        assertTrue(result.success(), result.message());
        assertEquals(SandboxMode.WASM, result.effectiveMode());
        assertEquals(1, result.numericResults().size());
        assertTrue(result.numericResults().getFirst() > 0L);
    }

    @Test
    void sandboxDiagnosticsExposeRuntimeHealthAndCacheState() throws Exception {
        Path jar = createWasmModJar("diag_wasm_mod", "1.0.0", simpleConstModule(7));
        NormalizedModMetadata metadata = ModMetadataParser.parse(jar.toFile()).orElseThrow();

        SandboxExecutionResult result = PolyglotSandboxManager.executeSandboxedEntrypoint(
            metadata,
            "main",
            "demo.wasm.EntryPoint",
            "invoke"
        );

        assertTrue(result.success(), result.message());

        JsonObject json = JsonParser.parseString(PolyglotSandboxManager.diagnosticsJson()).getAsJsonObject();
        assertTrue(json.get("hostExportCount").getAsInt() >= 1);
        assertEquals(WitContractCatalog.contractDigest(), json.get("hostContractDigest").getAsString());
        assertTrue(json.get("executedSandboxes").getAsLong() >= 1L);
        assertTrue(json.getAsJsonObject("wasmModuleCache").get("entries").getAsInt() >= 1);
    }

    @Test
    void espressoSandboxExecutesOrFailsClosedWithoutSkipping() throws Exception {
        Path jar = createEspressoModJar("espresso_exec_mod", "demo.espresso.EntryPoint", false, """
            package demo.espresso;
            public class EntryPoint implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.out.println("espresso-ok");
                }
            }
            """);

        NormalizedModMetadata metadata = ModMetadataParser.parse(jar.toFile()).orElseThrow();
        GraalVMSandbox.HostStatus hostStatus = GraalVMSandbox.probeAvailability();
        SandboxPlan plan = PolyglotSandboxManager.planFor(metadata);

        SandboxExecutionResult result = PolyglotSandboxManager.executeSandboxedEntrypoint(
            metadata,
            "main",
            "demo.espresso.EntryPoint",
            "invoke"
        );

        assertEquals(SandboxMode.ESPRESSO, plan.effectiveMode());
        assertEquals(SandboxMode.ESPRESSO, result.effectiveMode());
        assertEquals("demo.espresso.EntryPoint", result.target());
        assertFalse(result.nativeFallbackRecommended());
        assertEquals(plan.reason(), result.planReason());
        assertTrue(result.runtimeDiagnostics().contains("requested=espresso"), result.runtimeDiagnostics());

        if (hostStatus.isReady()) {
            assertTrue(result.success(), result.message());
            assertTrue(result.stdout().contains("espresso-ok"));
            assertTrue(result.runtimeDiagnostics().contains("state=ready"), result.runtimeDiagnostics());
        } else {
            assertFalse(result.success());
            assertTrue(
                result.message().startsWith("secure-policy-rejected:")
                    || result.message().startsWith("failed:")
                    || result.message().startsWith("polyglot-host-missing")
                    || result.message().startsWith("espresso-language-unavailable"),
                result.message()
            );
        }
    }

    @Test
    void failsClosedWhenRequestedEspressoLifecycleMethodIsMissing() throws Exception {
        Path jar = createEspressoModJar("espresso_missing_lifecycle", "demo.espresso.EntryPoint", false, """
            package demo.espresso;
            public class EntryPoint implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.out.println("espresso-main-only");
                }
            }
            """);

        NormalizedModMetadata metadata = ModMetadataParser.parse(jar.toFile()).orElseThrow();
        GraalVMSandbox.HostStatus hostStatus = GraalVMSandbox.probeAvailability();

        SandboxExecutionResult result = PolyglotSandboxManager.executeSandboxedEntrypoint(
            metadata,
            "server",
            "demo.espresso.EntryPoint",
            "invoke"
        );

        assertEquals(SandboxMode.ESPRESSO, PolyglotSandboxManager.planFor(metadata).effectiveMode());
        if (hostStatus.isReady()) {
            assertFalse(result.success());
            assertEquals(
                "entrypoint-method-not-found:onInitializeServer",
                result.message()
            );
        } else {
            assertFalse(result.success());
        }
    }

    @Test
    void espressoSandboxPropagatesCapabilitySecurityContextIntoGuestApi() throws Exception {
        Path jar = createEspressoModJar(
            "espresso_context_mod",
            "demo.espresso.EntryPoint",
            false,
            """
            package demo.espresso;
            public class EntryPoint implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.out.println("mod=" + org.intermed.api.InterMedAPI.currentModId());
                    System.out.println("mode=" + org.intermed.api.InterMedAPI.currentSandboxMode());
                    System.out.println("key=" + org.intermed.api.InterMedAPI.currentSandboxInvocationKey());
                    System.out.println("target=" + org.intermed.api.InterMedAPI.currentSandboxTarget());
                    System.out.println("shared=" + org.intermed.api.InterMedAPI.currentSandboxSharedStateBytes());
                    System.out.println("transport=" + org.intermed.api.InterMedAPI.currentSandboxSharedTransport());
                    System.out.println("read=" + org.intermed.api.InterMedAPI.hasCurrentCapability("FILE_READ"));
                    System.out.println("write=" + org.intermed.api.InterMedAPI.hasCurrentCapability("FILE_WRITE"));
                    System.out.println("contract=" + System.getProperty("intermed.sandbox.hostContract.sha256"));
                    System.out.println("transportProp=" + System.getProperty("intermed.sandbox.shared.transport"));
                    System.out.println("sharedBytesProp=" + System.getProperty("intermed.sandbox.shared.bytes"));
                }
            }
            """,
            """
                    "intermed:permissions": ["FILE_READ"],
            """
        );

        NormalizedModMetadata metadata = ModMetadataParser.parse(jar.toFile()).orElseThrow();
        GraalVMSandbox.HostStatus hostStatus = GraalVMSandbox.probeAvailability();

        SandboxExecutionResult result = PolyglotSandboxManager.executeSandboxedEntrypoint(
            metadata,
            "main",
            "demo.espresso.EntryPoint",
            "invoke"
        );

        assertEquals(SandboxMode.ESPRESSO, result.effectiveMode());
        if (hostStatus.isReady()) {
            assertTrue(result.success(), result.message());
            assertTrue(result.stdout().contains("mod=espresso_context_mod"), result.stdout());
            assertTrue(result.stdout().contains("mode=espresso"), result.stdout());
            assertTrue(result.stdout().contains("key=main"), result.stdout());
            assertTrue(result.stdout().contains("target=demo.espresso.EntryPoint"), result.stdout());
            assertTrue(result.stdout().contains("shared="), result.stdout());
            assertTrue(result.stdout().contains("transport="), result.stdout());
            assertTrue(result.stdout().contains("read=true"), result.stdout());
            assertTrue(result.stdout().contains("write=false"), result.stdout());
            assertTrue(result.stdout().contains("contract=" + WitContractCatalog.contractDigest()), result.stdout());
            assertTrue(result.stdout().contains("transportProp="), result.stdout());
            assertTrue(result.stdout().contains("sharedBytesProp="), result.stdout());
            assertTrue(result.runtimeDiagnostics().contains("sharedTransport="), result.runtimeDiagnostics());
            assertTrue(result.runtimeDiagnostics().contains("sharedStateBytes="), result.runtimeDiagnostics());
        } else {
            assertFalse(result.success());
        }
    }

    @Test
    void recommendsNativeFallbackForUnavailableOrBrokenEspressoEntrypointsWithoutSkipping() throws Exception {
        System.setProperty("sandbox.native.fallback.enabled", "true");
        RuntimeConfig.reload();

        Path jar = Files.createTempFile("intermed-espresso-fallback-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                {
                  "id": "espresso_fallback_mod",
                  "version": "1.0.0",
                  "intermed:sandbox": {
                    "mode": "espresso",
                    "allowNativeFallback": true
                  },
                  "entrypoints": { "main": ["demo.espresso.MissingEntryPoint"] }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        NormalizedModMetadata metadata = ModMetadataParser.parse(jar.toFile()).orElseThrow();
        SandboxPlan plan = PolyglotSandboxManager.planFor(metadata);

        SandboxExecutionResult result = PolyglotSandboxManager.executeSandboxedEntrypoint(
            metadata,
            "main",
            "demo.espresso.MissingEntryPoint",
            "invoke"
        );

        assertFalse(result.success());
        assertTrue(result.nativeFallbackRecommended());
        assertEquals(plan.reason(), result.planReason());
        if (plan.effectiveMode() == SandboxMode.ESPRESSO) {
            assertEquals(SandboxMode.ESPRESSO, result.effectiveMode());
            assertTrue(result.runtimeDiagnostics().contains("requested=espresso"), result.runtimeDiagnostics());
        } else {
            assertEquals(SandboxMode.NATIVE, plan.effectiveMode());
            assertTrue(plan.fallbackApplied());
            assertTrue(plan.reason().startsWith("espresso-host-unavailable:"), plan.reason());
            assertTrue(result.message().startsWith("native-path-required:"), result.message());
            assertTrue(result.runtimeDiagnostics().contains("effective=native"), result.runtimeDiagnostics());
        }
    }

    @Test
    void keepsSandboxFailureClosedByDefaultWhenNativeFallbackIsDisabled() throws Exception {
        System.setProperty("sandbox.native.fallback.enabled", "false");
        RuntimeConfig.reload();

        Path jar = Files.createTempFile("intermed-espresso-closed-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                {
                  "id": "espresso_closed_mod",
                  "version": "1.0.0",
                  "intermed:sandbox": {
                    "mode": "espresso",
                    "allowNativeFallback": true
                  },
                  "entrypoints": { "main": ["demo.espresso.MissingEntryPoint"] }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        NormalizedModMetadata metadata = ModMetadataParser.parse(jar.toFile()).orElseThrow();

        SandboxExecutionResult result = PolyglotSandboxManager.executeSandboxedEntrypoint(
            metadata,
            "main",
            "demo.espresso.MissingEntryPoint",
            "invoke"
        );

        assertFalse(result.success());
        assertFalse(result.nativeFallbackRecommended());
    }

    @Test
    void riskyModsArePromotedToSandboxAndNeverFallBackToNative() throws Exception {
        System.setProperty("sandbox.native.fallback.enabled", "true");
        RuntimeConfig.reload();

        Path jar = Files.createTempFile("intermed-risky-native-promote-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                {
                  "id": "risky_promoted_mod",
                  "version": "1.0.0",
                  "intermed:sandbox": {
                    "mode": "native",
                    "allowNativeFallback": true
                  },
                  "entrypoints": { "main": ["demo.espresso.MissingEntryPoint"] }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        NormalizedModMetadata metadata = ModMetadataParser.parse(jar.toFile()).orElseThrow();
        RiskyModRegistry.markRisky("risky_promoted_mod", "demo.espresso.MissingEntryPoint", "ambiguous-remap");

        SandboxPlan plan = PolyglotSandboxManager.planFor(metadata);
        assertEquals(SandboxMode.ESPRESSO, plan.requestedMode());
        assertEquals(SandboxMode.ESPRESSO, plan.effectiveMode());
        assertFalse(plan.fallbackApplied());
        assertTrue(plan.reason().contains("risky-mod-promoted-to-espresso"), plan.reason());

        SandboxExecutionResult result = PolyglotSandboxManager.executeSandboxedEntrypoint(
            metadata,
            "main",
            "demo.espresso.MissingEntryPoint",
            "invoke"
        );

        assertFalse(result.success());
        assertEquals(SandboxMode.ESPRESSO, result.effectiveMode());
        assertFalse(result.nativeFallbackRecommended());
    }

    @Test
    void initializesEspressoSandboxFromArchiveBytesWhenProvided() throws Exception {
        Path jar = createEspressoModJar("espresso_archive_mod", "demo.espresso.EntryPoint", false, """
            package demo.espresso;
            public class EntryPoint implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.out.println("espresso-archive-ok");
                }
            }
            """);

        GraalVMSandbox.HostStatus hostStatus = GraalVMSandbox.probeAvailability();
        try (GraalVMSandbox sandbox = PolyglotSandboxManager.initializeEspressoSandbox(
            "espresso_archive_mod",
            Files.readAllBytes(jar)
        )) {
            if (hostStatus.isReady()) {
                GraalVMSandbox.EspressoExecutionResult result = sandbox.executeEntrypoint(
                    null,
                    "demo.espresso.EntryPoint",
                    "onInitialize",
                    false
                );
                assertTrue(result.success(), result.message());
                assertTrue(result.stdout().contains("espresso-archive-ok"));
            } else {
                assertFalse(sandbox.isInitialized());
            }
        }
    }

    private static Path createEspressoModJar(String modId,
                                             String entrypoint,
                                             boolean hotPath,
                                             String javaSource) throws Exception {
        return createEspressoModJar(modId, entrypoint, hotPath, javaSource, "");
    }

    private static Path createEspressoModJar(String modId,
                                             String entrypoint,
                                             boolean hotPath,
                                             String javaSource,
                                             String extraManifestFields) throws Exception {
        Path root = Files.createTempDirectory("intermed-espresso-src");
        Path srcRoot = root.resolve("src");
        Path classesRoot = root.resolve("classes");
        Files.createDirectories(srcRoot);
        Files.createDirectories(classesRoot);

        Path sourceFile = srcRoot.resolve(entrypoint.replace('.', '/') + ".java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, javaSource, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        String classpath = System.getProperty("java.class.path");
        int compileResult = compiler.run(
            null,
            null,
            null,
            "-classpath", classpath,
            "-d", classesRoot.toString(),
            sourceFile.toString()
        );
        assertEquals(0, compileResult);

        Path jar = Files.createTempFile("intermed-espresso-mod-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            String normalizedExtraFields = "";
            if (extraManifestFields != null && !extraManifestFields.isBlank()) {
                normalizedExtraFields = extraManifestFields.stripTrailing() + System.lineSeparator();
            }
            output.write(
                ("""
                {
                  "id": "%s",
                  "version": "1.0.0",
                  "intermed:sandbox": {
                    "mode": "espresso",
                    "allowNativeFallback": false,
                    "hotPath": %s
                  },
                  %s
                  "entrypoints": { "main": ["%s"] }
                }
                """).formatted(modId, hotPath, normalizedExtraFields, entrypoint)
                    .getBytes(StandardCharsets.UTF_8)
            );
            output.closeEntry();

            for (Path file : Files.walk(classesRoot).filter(Files::isRegularFile).toList()) {
                String entryName = classesRoot.relativize(file).toString().replace('\\', '/');
                output.putNextEntry(new JarEntry(entryName));
                output.write(Files.readAllBytes(file));
                output.closeEntry();
            }
        }
        return jar;
    }

    private static Path createWasmModJar(String modId, String version, byte[] moduleBytes) throws Exception {
        Path jar = Files.createTempFile("intermed-wasm-mod-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write(
                ("""
                {
                  "id": "%s",
                  "version": "%s",
                  "intermed:sandbox": {
                    "mode": "wasm",
                    "allowNativeFallback": false,
                    "modulePath": "META-INF/wasm/demo.wasm",
                    "entrypoint": "init_mod"
                  },
                  "entrypoints": { "main": ["demo.wasm.EntryPoint"] }
                }
                """).formatted(modId, version)
                    .getBytes(StandardCharsets.UTF_8)
            );
            output.closeEntry();

            output.putNextEntry(new JarEntry("META-INF/wasm/demo.wasm"));
            output.write(moduleBytes);
            output.closeEntry();
        }
        return jar;
    }

    private static byte[] simpleConstModule(int value) {
        return new byte[] {
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7f,
            0x03, 0x02, 0x01, 0x00,
            0x07, 0x0c, 0x01, 0x08, 0x69, 0x6e, 0x69, 0x74, 0x5f, 0x6d, 0x6f, 0x64, 0x00, 0x00,
            0x0a, 0x06, 0x01, 0x04, 0x00, 0x41, (byte) value, 0x0b
        };
    }
}
