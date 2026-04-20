package org.intermed.core.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("microbench")
class RegistryHotPathMicrobenchTest {

    @BeforeEach
    void reset() {
        VirtualRegistryService.resetForTests();
        RegistryLinker.resetForTests();
    }

    @Test
    void writesFrozenRegistryHotPathReport() throws Throwable {
        VirtualRegistryService.registerVirtualized("bench_mod", "minecraft:block", "bench:gear", -1, "payload");
        ScopedRegistry registry = new ScopedRegistry("minecraft:block");

        CallSite site = RegistryLinker.bootstrapGet(
            MethodHandles.lookup(),
            "registryGet",
            MethodType.methodType(Object.class, ScopedRegistry.class, Object.class)
        );
        var invoker = site.dynamicInvoker();

        Object baseline = invoker.invokeExact(registry, (Object) "bench:gear");
        assertEquals("payload", baseline);

        VirtualRegistryService.freeze();

        int rawId = VirtualRegistryService.lookupGlobalId("bench_mod", "minecraft:block", "bench:gear");
        assertEquals("payload", VirtualRegistry.getFastByRawId(rawId));

        Object warm = invoker.invokeExact(registry, (Object) "bench:gear");
        assertEquals("payload", warm);
        int dynamicLookupsAfterWarmup = RegistryLinker.frozenDynamicLookupCount();

        long started = System.nanoTime();
        for (int i = 0; i < 200_000; i++) {
            Object value = VirtualRegistry.getFastByRawId(rawId);
            if (value == null) {
                throw new IllegalStateException("Hot-path benchmark unexpectedly returned null");
            }
        }
        long elapsedNanos = System.nanoTime() - started;
        int dynamicLookupsAfterRun = RegistryLinker.frozenDynamicLookupCount();

        double nanosPerOp = elapsedNanos / 200_000.0d;
        String report = """
            registry_hot_path:
              iterations: 200000
              raw_lookup_nanos_total: %d
              raw_lookup_nanos_per_op: %.2f
              dynamic_lookups_after_warmup: %d
              dynamic_lookups_after_run: %d
            """.formatted(
            elapsedNanos,
            nanosPerOp,
            dynamicLookupsAfterWarmup,
            dynamicLookupsAfterRun
        );

        Path outputDir = resolveOutputDir();
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("registry-hot-path.txt"), report);

        assertEquals(dynamicLookupsAfterWarmup, dynamicLookupsAfterRun,
            "Frozen hot path should stay on the cached raw-id fast path for repeated lookups");
        assertTrue(nanosPerOp <= maxNanosBudget(),
            "Frozen registry hot path exceeded absolute budget: " + nanosPerOp + " ns/op");
    }

    private static Path resolveOutputDir() {
        String configured = System.getProperty("intermed.microbench.outputDir");
        if (configured == null || configured.isBlank()) {
            return Path.of("build", "reports", "microbench");
        }
        return Path.of(configured);
    }

    private static double maxNanosBudget() {
        return Double.parseDouble(System.getProperty("intermed.budget.registry.maxNanosPerOp", "100.0"));
    }

    private static final class ScopedRegistry {
        private final String scope;

        private ScopedRegistry(String scope) {
            this.scope = scope.toLowerCase(Locale.ROOT);
        }

        @Override
        public String toString() {
            return scope;
        }
    }
}
