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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("microbench")
class RemapperHotPathMicrobenchTest {

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

        for (int i = 0; i < 10_000; i++) {
            InterMedRemapper.translateRuntimeString(runtimeString);
        }
        for (int i = 0; i < 5_000; i++) {
            InterMedRemapper.transformClassBytes("bench.Probe", original);
        }

        long stringStarted = System.nanoTime();
        for (int i = 0; i < 250_000; i++) {
            InterMedRemapper.translateRuntimeString(runtimeString);
        }
        long stringElapsed = System.nanoTime() - stringStarted;

        long classStarted = System.nanoTime();
        for (int i = 0; i < 50_000; i++) {
            InterMedRemapper.transformClassBytes("bench.Probe", original);
        }
        long classElapsed = System.nanoTime() - classStarted;

        double stringNanosPerOp = stringElapsed / 250_000.0d;
        double classNanosPerOp = classElapsed / 50_000.0d;

        String report = """
            remapper_hot_path:
              string_iterations: 250000
              string_nanos_total: %d
              string_nanos_per_op: %.2f
              class_iterations: 50000
              class_nanos_total: %d
              class_nanos_per_op: %.2f
            """.formatted(
            stringElapsed,
            stringNanosPerOp,
            classElapsed,
            classNanosPerOp
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
        return Double.parseDouble(System.getProperty("intermed.budget.remapper.class.maxNanosPerOp", "12000.0"));
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
}
