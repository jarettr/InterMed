package org.intermed.core.security;

import com.google.gson.JsonObject;
import org.intermed.core.classloading.LazyInterMedClassLoader;
import org.intermed.core.classloading.TcclInterceptor;
import org.intermed.core.config.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("strict-security")
class HostileSecuritySmokeTest {

    private static final String HOSTILE_MOD = "hostile_smoke_mod";

    private ClassLoader originalContextClassLoader;

    @AfterEach
    void tearDown() {
        if (originalContextClassLoader != null) {
            Thread.currentThread().setContextClassLoader(originalContextClassLoader);
        }
        System.clearProperty("security.strict.mode");
        System.clearProperty("runtime.game.dir");
        RuntimeConfig.resetForTests();
        SecurityPolicy.resetForTests();
        CapabilityManager.resetForTests();
    }

    @Test
    void strictModeDeniesSyntheticHostileOperationsAndKeepsAsyncAttribution() throws Exception {
        originalContextClassLoader = Thread.currentThread().getContextClassLoader();
        Path gameDir = Files.createTempDirectory("intermed-hostile-smoke-game");
        Path target = gameDir.resolve("secret.txt");
        Files.writeString(target, "classified", StandardCharsets.UTF_8);

        System.setProperty("security.strict.mode", "true");
        System.setProperty("runtime.game.dir", gameDir.toString());
        RuntimeConfig.reload();
        SecurityPolicy.resetForTests();
        CapabilityManager.resetForTests();
        SecurityPolicy.registerModCapabilities(HOSTILE_MOD, new JsonObject());

        List<String> cases = new ArrayList<>();
        assertDenied(cases, "forbidden file read",
            () -> CapabilityManager.executeAsMod(HOSTILE_MOD, () -> CapabilityManager.checkFileRead(target)));
        assertDenied(cases, "forbidden file write",
            () -> CapabilityManager.executeAsMod(HOSTILE_MOD, () -> CapabilityManager.checkFileWrite(target)));
        assertDenied(cases, "forbidden socket",
            () -> CapabilityManager.executeAsMod(HOSTILE_MOD, () -> CapabilityManager.checkNetworkHost("evil.example.org")));
        assertDenied(cases, "reflection private access",
            () -> CapabilityManager.executeAsMod(HOSTILE_MOD, () -> CapabilityManager.checkReflectionAccess("Field.setAccessible")));
        assertDenied(cases, "process start",
            () -> CapabilityManager.executeAsMod(HOSTILE_MOD, () -> CapabilityManager.checkPermission(Capability.PROCESS_SPAWN)));
        assertDenied(cases, "native load",
            () -> CapabilityManager.executeAsMod(HOSTILE_MOD, () ->
                CapabilityManager.checkNativeLibraryOperation("System.loadLibrary", "hostile_native")));
        assertDenied(cases, "unsafe attempt",
            () -> CapabilityManager.executeAsMod(HOSTILE_MOD, () -> CapabilityManager.checkUnsafeOperation("allocateInstance")));
        assertDenied(cases, "varhandle attempt",
            () -> CapabilityManager.executeAsMod(HOSTILE_MOD, () -> CapabilityManager.checkVarHandleOperation("setVolatile")));
        assertDenied(cases, "ffm attempt",
            () -> CapabilityManager.executeAsMod(HOSTILE_MOD, () ->
                CapabilityManager.checkForeignMemoryAccess("java.lang.foreign.MemorySegment.reinterpret")));

        assertAsyncThreadAttribution(cases, target);
        assertAsyncExecutorAttribution(cases, target);
        assertCompletableFutureAttribution(cases);

        Path auditLog = gameDir.resolve("logs/intermed-security.log");
        assertTrue(Files.isRegularFile(auditLog));
        String audit = Files.readString(auditLog, StandardCharsets.UTF_8);
        assertTrue(audit.contains("verdict=DENY"));
        assertTrue(audit.contains("mod=" + HOSTILE_MOD));

        writeReport(cases);
    }

    private static void assertDenied(List<String> cases, String name, ThrowingRunnable action) {
        CapabilityDeniedException exception = assertThrows(CapabilityDeniedException.class, action::run, name);
        assertTrue(exception.getMessage().contains("CapabilityDeniedException"), exception.getMessage());
        assertTrue(exception.getMessage().contains("mod='" + HOSTILE_MOD + "'"), exception.getMessage());
        assertTrue(exception.getMessage().contains("action="), exception.getMessage());
        cases.add(name + ": PASS " + exception.capability());
    }

    private static void assertAsyncThreadAttribution(List<String> cases, Path target) throws Exception {
        try (LazyInterMedClassLoader loader = modLoader()) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Thread thread = TcclInterceptor.contextAwareFactory(r -> new Thread(r, "intermed-hostile-thread-smoke"))
                    .newThread(() -> {
                        try {
                            CapabilityManager.checkFileRead(target);
                        } catch (Throwable throwable) {
                            failure.set(throwable);
                        }
                    });
                thread.start();
                thread.join(TimeUnit.SECONDS.toMillis(5));
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
            assertNotNull(failure.get(), "thread task should have been denied");
            assertInstanceOf(CapabilityDeniedException.class, failure.get());
            cases.add("async attribution through Thread: PASS FILE_READ");
        }
    }

    private static void assertAsyncExecutorAttribution(List<String> cases, Path target) throws Exception {
        try (LazyInterMedClassLoader loader = modLoader()) {
            ExecutorService executor = TcclInterceptor.wrap(Executors.newSingleThreadExecutor());
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                ExecutionException exception = assertThrows(ExecutionException.class, () ->
                    executor.submit(() -> {
                        CapabilityManager.checkFileWrite(target);
                        return null;
                    }).get(5, TimeUnit.SECONDS));
                assertInstanceOf(CapabilityDeniedException.class, exception.getCause());
                cases.add("async attribution through Executor: PASS FILE_WRITE");
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
                executor.shutdownNow();
            }
        }
    }

    private static void assertCompletableFutureAttribution(List<String> cases) throws Exception {
        try (LazyInterMedClassLoader loader = modLoader()) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                CompletionException exception = assertThrows(CompletionException.class, () ->
                    CompletableFuture.runAsync(
                        TcclInterceptor.propagating(() -> CapabilityManager.checkNetworkHost("evil.example.org")),
                        executor
                    ).join());
                assertInstanceOf(CapabilityDeniedException.class, exception.getCause());
                cases.add("async attribution through CompletableFuture: PASS NETWORK_CONNECT");
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
                executor.shutdownNow();
            }
        }
    }

    private static LazyInterMedClassLoader modLoader() {
        return new LazyInterMedClassLoader(
            HOSTILE_MOD,
            null,
            Set.of(),
            HostileSecuritySmokeTest.class.getClassLoader()
        );
    }

    private static void writeReport(List<String> cases) throws Exception {
        Path outputDir = securityOutputDir();
        Files.createDirectories(outputDir);
        StringBuilder report = new StringBuilder();
        report.append("hostile_security_smoke:\n");
        report.append("  status: PASS\n");
        report.append("  cases:\n");
        for (String testCase : cases) {
            report.append("    - ").append(testCase).append('\n');
        }
        Files.writeString(outputDir.resolve("hostile-smoke.txt"), report.toString(), StandardCharsets.UTF_8);
    }

    private static Path securityOutputDir() {
        String configured = System.getProperty("intermed.security.outputDir");
        if (configured == null || configured.isBlank()) {
            return Path.of("build", "reports", "security");
        }
        return Path.of(configured);
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
