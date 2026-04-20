package org.intermed.core.sandbox;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.intermed.core.metadata.ModPlatform;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.metadata.RuntimeModIndex;
import org.intermed.core.security.CapabilityManager;
import org.intermed.core.security.SecurityPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Tag("strict-security")
class WasmSandboxTest {

    @AfterEach
    void tearDown() {
        WasmSandbox.resetForTests();
        RuntimeModIndex.clear();
        SecurityPolicy.resetForTests();
        CapabilityManager.resetForTests();
    }

    @Test
    void probesRuntimeWithRealModuleExecution() {
        WasmSandbox.HostStatus status = WasmSandbox.probeAvailability();
        assertTrue(status.runtimeAvailable());
        assertTrue(status.isReady(), status.state());
    }

    @Test
    void executesExportedFunctionThroughChicoryRuntime() throws Exception {
        assertTrue(WasmSandbox.isRuntimeAvailable());

        try (WasmSandbox sandbox = new WasmSandbox("wasm_smoke")) {
            WasmSandbox.WasmExecutionResult result = sandbox.loadAndExecute(simpleConstSevenModule(), "init_mod");
            assertArrayEquals(new long[]{7L}, result.results());
            assertEquals("wasm_smoke", result.modId());
            assertEquals("init_mod", result.entryPoint());
            assertTrue(sandbox.diagnostics().contains("mountedHostContractFiles=3"), sandbox.diagnostics());
            assertTrue(sandbox.diagnostics().contains("hostContractDigest="), sandbox.diagnostics());
        }
    }

    @Test
    void exposesStringArgumentsToWasmHostFunctions() throws Exception {
        assertTrue(WasmSandbox.isRuntimeAvailable());

        JsonObject manifest = new JsonObject();
        JsonArray permissions = new JsonArray();
        permissions.add("FILE_READ");
        manifest.add("intermed:permissions", permissions);
        SecurityPolicy.registerModCapabilities("wasm_host_mod", manifest);

        try (WasmSandbox sandbox = new WasmSandbox("wasm_host_mod")) {
            WasmSandbox.WasmExecutionResult result = CapabilityManager.executeAsMod(
                "wasm_host_mod",
                () -> sandbox.loadAndExecute(WasmTestModuleFactory.hasCurrentCapabilityModule("FILE_READ"), "init_mod")
            );
            assertArrayEquals(new long[] { 1L }, result.results());
        }
    }

    @Test
    void roundTripsHostReturnedStringsBackIntoWasmImports() throws Exception {
        assertTrue(WasmSandbox.isRuntimeAvailable());

        JsonObject manifest = new JsonObject();
        RuntimeModIndex.register(new NormalizedModMetadata(
            "wasm_host_mod",
            "1.0.0",
            null,
            ModPlatform.FABRIC,
            manifest,
            Map.of()
        ));

        try (WasmSandbox sandbox = new WasmSandbox("wasm_host_mod")) {
            WasmSandbox.WasmExecutionResult result = CapabilityManager.executeAsMod(
                "wasm_host_mod",
                () -> sandbox.loadAndExecute(WasmTestModuleFactory.currentModIdRoundTripModule(), "init_mod")
            );
            assertArrayEquals(new long[] { 1L }, result.results());
        }
    }

    @Test
    void reusesParsedModulesThroughStaticCache() throws Exception {
        byte[] module = simpleConstSevenModule();

        try (WasmSandbox first = new WasmSandbox("wasm_cache_first")) {
            first.loadAndExecute(module, "init_mod");
        }
        WasmSandbox.ModuleCacheDiagnostics afterFirst = WasmSandbox.moduleCacheDiagnostics();

        try (WasmSandbox second = new WasmSandbox("wasm_cache_second")) {
            second.loadAndExecute(module, "init_mod");
            assertTrue(second.diagnostics().contains("moduleCacheHits="), second.diagnostics());
        }
        WasmSandbox.ModuleCacheDiagnostics afterSecond = WasmSandbox.moduleCacheDiagnostics();

        assertEquals(1, afterFirst.entries());
        assertEquals(1, afterSecond.entries());
        assertTrue(afterSecond.hits() >= afterFirst.hits() + 1L);
        assertEquals(afterFirst.misses(), afterSecond.misses());
    }

    private static byte[] simpleConstSevenModule() {
        return new byte[] {
            0x00, 0x61, 0x73, 0x6d,
            0x01, 0x00, 0x00, 0x00,
            0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7f,
            0x03, 0x02, 0x01, 0x00,
            0x07, 0x0c, 0x01, 0x08, 0x69, 0x6e, 0x69, 0x74, 0x5f, 0x6d, 0x6f, 0x64, 0x00, 0x00,
            0x0a, 0x06, 0x01, 0x04, 0x00, 0x41, 0x07, 0x0b
        };
    }
}
