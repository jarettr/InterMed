package org.intermed.core.sandbox;

import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.ImportValues;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasi.WasiOptions;
import com.dylibso.chicory.wasi.WasiPreview1;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.google.gson.GsonBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.security.MessageDigest;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Real Chicory/WASI-backed runtime used for high-level Wasm modules.
 */
public final class WasmSandbox implements AutoCloseable {

    private static final byte[] PROBE_MODULE = new byte[] {
        0x00, 0x61, 0x73, 0x6d,
        0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7f,
        0x03, 0x02, 0x01, 0x00,
        0x07, 0x0c, 0x01, 0x08, 0x69, 0x6e, 0x69, 0x74, 0x5f, 0x6d, 0x6f, 0x64, 0x00, 0x00,
        0x0a, 0x06, 0x01, 0x04, 0x00, 0x41, 0x07, 0x0b
    };
    private static final byte[] HOST_PROBE_MODULE = new byte[] {
        0x00, 0x61, 0x73, 0x6d,
        0x01, 0x00, 0x00, 0x00,
        0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7f,
        0x02, 0x2c, 0x01,
        0x10, 0x69, 0x6e, 0x74, 0x65, 0x72, 0x6d, 0x65, 0x64, 0x3a, 0x73, 0x61, 0x6e, 0x64, 0x62, 0x6f, 0x78,
        0x17, 0x63, 0x75, 0x72, 0x72, 0x65, 0x6e, 0x74, 0x2d, 0x73, 0x61, 0x6e, 0x64, 0x62, 0x6f, 0x78, 0x2d,
        0x6d, 0x6f, 0x64, 0x65, 0x2d, 0x69, 0x64,
        0x00, 0x00,
        0x03, 0x02, 0x01, 0x00,
        0x07, 0x0c, 0x01, 0x08, 0x69, 0x6e, 0x69, 0x74, 0x5f, 0x6d, 0x6f, 0x64, 0x00, 0x01,
        0x0a, 0x06, 0x01, 0x04, 0x00, 0x10, 0x00, 0x0b
    };

    private static volatile HostStatus cachedHostStatus;
    private static final Map<String, WasmModule> MODULE_CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong MODULE_CACHE_HITS = new AtomicLong();
    private static final AtomicLong MODULE_CACHE_MISSES = new AtomicLong();
    private final String modId;
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private Path mountedDirectory;
    private WasiPreview1 wasi;
    private final WasmHostBridge hostBridge = new WasmHostBridge();
    private String lastModuleFingerprint = "";
    private int mountedHostContractFiles;

    public WasmSandbox(String modId) {
        this.modId = modId == null || modId.isBlank() ? "unknown" : modId;
    }

    public static boolean isRuntimeAvailable() {
        return classPresent("com.dylibso.chicory.runtime.Instance")
            && classPresent("com.dylibso.chicory.wasm.Parser")
            && classPresent("com.dylibso.chicory.wasi.WasiPreview1");
    }

    public static HostStatus probeAvailability() {
        HostStatus cached = cachedHostStatus;
        if (cached != null) {
            return cached;
        }
        if (!isRuntimeAvailable()) {
            HostStatus unavailable = new HostStatus(false, false, "chicory-runtime-missing");
            cachedHostStatus = unavailable;
            return unavailable;
        }
        try (WasmSandbox sandbox = new WasmSandbox("__probe__")) {
            WasmExecutionResult result = sandbox.loadAndExecute(PROBE_MODULE, "init_mod");
            WasmExecutionResult hostBridgeResult = sandbox.loadAndExecute(HOST_PROBE_MODULE, "init_mod");
            boolean ready = result.results().length == 1
                && result.results()[0] == 7L
                && hostBridgeResult.results().length == 1
                && hostBridgeResult.results()[0] == 0L;
            HostStatus status = new HostStatus(
                true,
                ready,
                ready
                    ? "ready"
                    : "probe-result-mismatch:runtime="
                        + java.util.Arrays.toString(result.results())
                        + ",host="
                        + java.util.Arrays.toString(hostBridgeResult.results())
            );
            cachedHostStatus = status;
            return status;
        } catch (Throwable throwable) {
            HostStatus failed = new HostStatus(true, false, describeProbeFailure(throwable));
            cachedHostStatus = failed;
            return failed;
        }
    }

    public static WasmExecutionResult loadAndExecuteWasmMod(File wasmFile, String entryPoint) {
        Objects.requireNonNull(wasmFile, "wasmFile");
        try (WasmSandbox sandbox = new WasmSandbox(wasmFile == null ? "unknown" : wasmFile.getName())) {
            return sandbox.loadAndExecute(wasmFile.toPath(), entryPoint);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to execute Wasm module", e);
        }
    }

    public WasmExecutionResult loadAndExecute(Path wasmPath, String entryPoint) {
        Objects.requireNonNull(wasmPath, "wasmPath");
        try {
            return loadAndExecute(Files.readAllBytes(wasmPath), entryPoint);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Wasm module for " + modId, e);
        }
    }

    public WasmExecutionResult loadAndExecute(byte[] wasmBinary, String entryPoint) {
        if (wasmBinary == null || wasmBinary.length == 0) {
            throw new IllegalArgumentException("wasm-binary-missing");
        }
        String fingerprint = fingerprint(wasmBinary);
        return instantiateAndExecute(resolveModule(fingerprint, wasmBinary), normalizeEntrypoint(entryPoint), fingerprint);
    }

    public String diagnostics() {
        return "mod=" + modId
            + ", runtimeAvailable=" + isRuntimeAvailable()
            + ", hostContractDigest=" + WitContractCatalog.contractDigest()
            + ", hostExports=" + WitContractCatalog.hostExportCount()
            + ", mountedHostContractFiles=" + mountedHostContractFiles
            + ", moduleFingerprint=" + lastModuleFingerprint
            + ", moduleCacheEntries=" + moduleCacheDiagnostics().entries()
            + ", moduleCacheHits=" + moduleCacheDiagnostics().hits()
            + ", moduleCacheMisses=" + moduleCacheDiagnostics().misses();
    }

    static ModuleCacheDiagnostics moduleCacheDiagnostics() {
        return new ModuleCacheDiagnostics(
            MODULE_CACHE.size(),
            MODULE_CACHE_HITS.get(),
            MODULE_CACHE_MISSES.get()
        );
    }

    private WasmExecutionResult instantiateAndExecute(WasmModule module, String entryPoint, String moduleFingerprint) {
        if (!isRuntimeAvailable()) {
            throw new IllegalStateException("Chicory runtime is not available on the classpath");
        }
        try {
            stdout.reset();
            stderr.reset();
            lastModuleFingerprint = moduleFingerprint == null ? "" : moduleFingerprint;
            cleanupMountedDirectory();
            mountedDirectory = Files.createTempDirectory("intermed-wasm-" + sanitize(modId));
            mountedHostContractFiles = materializeHostContractFiles(mountedDirectory);
            WasiOptions options = WasiOptions.builder()
                .withStdout(stdout, false)
                .withStderr(stderr, false)
                .withStdin(new ByteArrayInputStream(new byte[0]), false)
                .withDirectory("/sandbox", mountedDirectory)
                .build();
            wasi = WasiPreview1.builder()
                .withOptions(options)
                .build();

            ImportValues imports = ImportValues.builder()
                .addFunction(wasi.toHostFunctions())
                .addFunction(hostBridge.hostFunctions())
                .build();

            Instance instance = Instance.builder(module)
                .withImportValues(imports)
                .withInitialize(true)
                .withStart(true)
                .build();

            ExportFunction function = instance.export(entryPoint);
            if (function == null) {
                throw new IllegalStateException("Wasm export not found: " + entryPoint);
            }
            long[] results = function.apply();
            return new WasmExecutionResult(
                modId,
                entryPoint,
                results == null ? new long[0] : results.clone(),
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            throw new IllegalStateException("Failed to execute Wasm module for " + modId, e);
        }
    }

    static void resetForTests() {
        cachedHostStatus = null;
        MODULE_CACHE.clear();
        MODULE_CACHE_HITS.set(0L);
        MODULE_CACHE_MISSES.set(0L);
    }

    @Override
    public void close() throws IOException {
        if (wasi != null) {
            wasi.close();
            wasi = null;
        }
        cleanupMountedDirectory();
        hostBridge.reset();
        mountedHostContractFiles = 0;
        lastModuleFingerprint = "";
    }

    private void cleanupMountedDirectory() throws IOException {
        if (mountedDirectory == null || !Files.exists(mountedDirectory)) {
            mountedDirectory = null;
            return;
        }
        try (var walk = Files.walk(mountedDirectory)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        }
        mountedDirectory = null;
    }

    private static WasmModule resolveModule(String fingerprint, byte[] wasmBinary) {
        WasmModule cached = MODULE_CACHE.get(fingerprint);
        if (cached != null) {
            MODULE_CACHE_HITS.incrementAndGet();
            return cached;
        }
        MODULE_CACHE_MISSES.incrementAndGet();
        WasmModule parsed = Parser.parse(wasmBinary);
        WasmModule existing = MODULE_CACHE.putIfAbsent(fingerprint, parsed);
        return existing == null ? parsed : existing;
    }

    private static String fingerprint(byte[] wasmBinary) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(wasmBinary));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to fingerprint Wasm module", e);
        }
    }

    private static int materializeHostContractFiles(Path mountedDirectory) throws IOException {
        if (mountedDirectory == null) {
            return 0;
        }
        Path intermedDir = mountedDirectory.resolve("intermed");
        Files.createDirectories(intermedDir);
        Path witFile = intermedDir.resolve("host.wit");
        Path javaBindingsFile = intermedDir.resolve("InterMedSandboxHost.java");
        Path contractJsonFile = intermedDir.resolve("host-contract.json");

        Files.writeString(witFile, WitContractCatalog.renderHostInterface(), StandardCharsets.UTF_8);
        Files.writeString(javaBindingsFile, WitContractCatalog.renderJavaBindings(), StandardCharsets.UTF_8);
        Files.writeString(
            contractJsonFile,
            new GsonBuilder().setPrettyPrinting().create().toJson(WitContractCatalog.toJson()),
            StandardCharsets.UTF_8
        );
        return 3;
    }

    private static boolean classPresent(String className) {
        try {
            Class.forName(className, false, WasmSandbox.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String normalizeEntrypoint(String entryPoint) {
        if (entryPoint == null || entryPoint.isBlank()) {
            throw new IllegalArgumentException("wasm-entrypoint-missing");
        }
        return entryPoint.trim();
    }

    private static String describeProbeFailure(Throwable throwable) {
        if (throwable == null) {
            return "probe-failed:unknown";
        }
        StringBuilder builder = new StringBuilder("probe-failed:")
            .append(throwable.getClass().getSimpleName());
        String message = throwable.getMessage();
        if (message != null && !message.isBlank()) {
            builder.append(':').append(message.trim().toLowerCase(Locale.ROOT).replace(' ', '-'));
        }
        return builder.toString();
    }

    public record WasmExecutionResult(String modId,
                                      String entryPoint,
                                      long[] results,
                                      String stdout,
                                      String stderr) {}

    public record HostStatus(boolean runtimeAvailable,
                             boolean probeSucceeded,
                             String state) {

        public boolean isReady() {
            return runtimeAvailable && probeSucceeded && "ready".equals(state);
        }
    }

    record ModuleCacheDiagnostics(int entries, long hits, long misses) {}
}
