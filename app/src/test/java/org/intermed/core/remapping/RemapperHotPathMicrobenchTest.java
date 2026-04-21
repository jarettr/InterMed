package org.intermed.core.remapping;

import org.intermed.core.lifecycle.LifecycleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("microbench")
class RemapperHotPathMicrobenchTest {
    private static final int STRING_WARMUP_ITERATIONS = 20_000;
    private static final int CLASS_WARMUP_ITERATIONS = 20_000;
    private static final int STRING_MEASUREMENT_ITERATIONS = 250_000;
    private static final int CLASS_MEASUREMENT_ITERATIONS = 50_000;
    private static final int MEASUREMENT_ROUNDS = 4;

    @BeforeEach
    void setUp() {
        LifecycleManager.DICTIONARY.clear();
        LifecycleManager.DICTIONARY.addClass("net/minecraft/class_42", "net/minecraft/server/level/ServerPlayer");
        LifecycleManager.DICTIONARY.addMethod("net/minecraft/class_42", "method_1000", "()V", "tick");
        InterMedRemapper.installDictionary(LifecycleManager.DICTIONARY);
    }

    @AfterEach
    void tearDown() {
        InterMedRemapper.clearCaches();
        LifecycleManager.DICTIONARY.clear();
    }

    @Test
    void writesRemapperHotPathReport() throws Exception {
        String runtimeString = "Owner=net.minecraft.class_42";
        byte[] original = buildProbeClass();

        String warmedString = InterMedRemapper.translateRuntimeString(runtimeString);
        assertTrue(warmedString.contains("ServerPlayer"));

        byte[] warmedClass = InterMedRemapper.transformClassBytes("bench.Probe", original);
        assertArrayEquals(warmedClass, InterMedRemapper.transformClassBytes("bench.Probe", original));

        for (int i = 0; i < STRING_WARMUP_ITERATIONS; i++) {
            InterMedRemapper.translateRuntimeString(runtimeString);
        }
        for (int i = 0; i < CLASS_WARMUP_ITERATIONS; i++) {
            InterMedRemapper.transformClassBytes("bench.Probe", original);
        }

        long[] stringRounds = measureRounds(MEASUREMENT_ROUNDS, STRING_MEASUREMENT_ITERATIONS,
            () -> InterMedRemapper.translateRuntimeString(runtimeString));
        long[] classRounds = measureRounds(MEASUREMENT_ROUNDS, CLASS_MEASUREMENT_ITERATIONS,
            () -> InterMedRemapper.transformClassBytes("bench.Probe", original));

        long stringElapsed = Arrays.stream(stringRounds).min().orElseThrow();
        long classElapsed = Arrays.stream(classRounds).min().orElseThrow();

        double stringNanosPerOp = stringElapsed / (double) STRING_MEASUREMENT_ITERATIONS;
        double classNanosPerOp = classElapsed / (double) CLASS_MEASUREMENT_ITERATIONS;

        String report = """
            remapper_hot_path:
              measurement_rounds: %d
              string_warmup_iterations: %d
              string_iterations: %d
              string_nanos_total: %d
              string_nanos_per_op: %.2f
              string_round_nanos: %s
              class_warmup_iterations: %d
              class_iterations: %d
              class_nanos_total: %d
              class_nanos_per_op: %.2f
              class_round_nanos: %s
            """.formatted(
            MEASUREMENT_ROUNDS,
            STRING_WARMUP_ITERATIONS,
            STRING_MEASUREMENT_ITERATIONS,
            stringElapsed,
            stringNanosPerOp,
            Arrays.toString(stringRounds),
            CLASS_WARMUP_ITERATIONS,
            CLASS_MEASUREMENT_ITERATIONS,
            classElapsed,
            classNanosPerOp,
            Arrays.toString(classRounds)
        );

        Path outputDir = resolveOutputDir();
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("remapper-hot-path.txt"), report);

        assertTrue(stringNanosPerOp <= maxStringBudget(),
            "Runtime string remapping exceeded budget: " + stringNanosPerOp + " ns/op");
        assertTrue(classNanosPerOp <= maxClassBudget(),
            "Warm class remapping exceeded budget: " + classNanosPerOp + " ns/op");
    }

    private static Path resolveOutputDir() {
        String configured = System.getProperty("intermed.microbench.outputDir");
        if (configured == null || configured.isBlank()) {
            return Path.of("build", "reports", "microbench");
        }
        return Path.of(configured);
    }

    private static double maxStringBudget() {
        return Double.parseDouble(System.getProperty("intermed.budget.remapper.string.maxNanosPerOp", "250.0"));
    }

    private static double maxClassBudget() {
        // GitHub-hosted runners are consistently slower than local developer machines for
        // the warm ASM remap path. Keep the gate strict enough to catch real regressions
        // while allowing clean-checkout CI to measure the steady-state hot path reliably.
        return Double.parseDouble(System.getProperty("intermed.budget.remapper.class.maxNanosPerOp", "20000.0"));
    }

    private static long[] measureRounds(int rounds, int iterations, ThrowingRunnable action) throws Exception {
        long[] elapsed = new long[rounds];
        for (int round = 0; round < rounds; round++) {
            long started = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                action.run();
            }
            elapsed[round] = System.nanoTime() - started;
        }
        return elapsed;
    }

    private static byte[] buildProbeClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "bench/Probe", null, "java/lang/Object", null);

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC, "probe", "(Lnet/minecraft/class_42;)V", null, null);
        probe.visitCode();
        probe.visitVarInsn(Opcodes.ALOAD, 1);
        probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_42", "method_1000", "()V", false);
        probe.visitInsn(Opcodes.RETURN);
        probe.visitMaxs(1, 2);
        probe.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
