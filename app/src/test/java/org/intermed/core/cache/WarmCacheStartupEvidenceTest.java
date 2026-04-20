package org.intermed.core.cache;

import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.lifecycle.LifecycleManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("compat-smoke")
class WarmCacheStartupEvidenceTest {

    @AfterEach
    void tearDown() {
        LifecycleManager.resetForTests();
        AOTCacheManager.resetForTests();
        RuntimeConfig.resetForTests();
        System.clearProperty("intermed.modsDir");
        System.clearProperty("intermed.mixin.warm.value");
    }

    @Test
    void warmCacheStartupHitsAotCacheAndStaysWithinBudget() throws Exception {
        AOTCacheManager.resetForTests();

        Path modsDir = Files.createTempDirectory("intermed-warm-cache-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());

        StartupProbe probe = createFabricMixinProbeJar(modsDir.resolve("warm-cache-probe.jar"));

        long coldStarted = System.nanoTime();
        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();
        long coldElapsed = System.nanoTime() - coldStarted;

        assertEquals("7", System.getProperty("intermed.mixin.warm.value"));
        assertTrue(AOTCacheManager.cacheSaveCountForTests() > 0,
            "Cold startup should persist at least one AOT cache entry");
        assertNotNull(metadataForClass(probe.targetClassName()),
            "Cold startup should leave AOT metadata for the transformed mixin target");

        long hitsBeforeWarm = AOTCacheManager.cacheHitCountForTests();
        System.clearProperty("intermed.mixin.warm.value");
        LifecycleManager.resetForTests();
        RuntimeConfig.resetForTests();

        long warmStarted = System.nanoTime();
        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();
        long warmElapsed = System.nanoTime() - warmStarted;

        long warmHitDelta = AOTCacheManager.cacheHitCountForTests() - hitsBeforeWarm;
        double ratio = warmElapsed / (double) Math.max(1L, coldElapsed);
        long allowedNanos = Math.max(
            (long) Math.ceil(coldElapsed * maxWarmRatio()),
            coldElapsed + maxWarmDeltaNanos()
        );

        assertEquals("7", System.getProperty("intermed.mixin.warm.value"));
        assertTrue(warmHitDelta > 0,
            "Warm startup should observe at least one AOT cache hit");
        assertTrue(warmElapsed <= allowedNanos,
            "Warm startup exceeded budget: cold=" + coldElapsed + " ns warm=" + warmElapsed
                + " ns allowed=" + allowedNanos + " ns ratio=" + ratio);

        Path outputDir = resolveOutputDir();
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("warm-cache-startup.txt"), """
            warm_cache_startup:
              cold_nanos_total: %d
              warm_nanos_total: %d
              warm_to_cold_ratio: %.4f
              cache_saves_after_cold: %d
              cache_hits_on_warm: %d
              target_class: %s
            """.formatted(
            coldElapsed,
            warmElapsed,
            ratio,
            AOTCacheManager.cacheSaveCountForTests(),
            warmHitDelta,
            probe.targetClassName()
        ));
    }

    private static Path resolveOutputDir() {
        String configured = System.getProperty("intermed.startup.outputDir");
        if (configured == null || configured.isBlank()) {
            return Path.of("build", "reports", "startup");
        }
        return Path.of(configured);
    }

    private static double maxWarmRatio() {
        return Double.parseDouble(System.getProperty("intermed.budget.startup.warm.maxRatio", "1.50"));
    }

    private static long maxWarmDeltaNanos() {
        double millis = Double.parseDouble(System.getProperty("intermed.budget.startup.warm.maxDeltaMs", "250.0"));
        return (long) (millis * 1_000_000.0d);
    }

    private static StartupProbe createFabricMixinProbeJar(Path jarPath) throws Exception {
        long unique = System.nanoTime();
        String packageName = "test.warmcache" + unique;
        String packagePath = packageName.replace('.', '/');
        String entrypointClass = packageName + ".WarmCacheMod";
        String targetClass = packageName + ".TargetThing";
        String mixinClass = packageName + ".TargetThingMixin";
        String modId = "warm_cache_" + unique;

        Path root = Files.createTempDirectory("intermed-warm-cache-src");
        Path srcRoot = root.resolve("src");
        Path classesRoot = root.resolve("classes");
        Files.createDirectories(srcRoot);
        Files.createDirectories(classesRoot);

        Path entrypointJava = srcRoot.resolve(packagePath + "/WarmCacheMod.java");
        Files.createDirectories(entrypointJava.getParent());
        Files.writeString(
            entrypointJava,
            """
            package %s;
            public class WarmCacheMod implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.setProperty("intermed.mixin.warm.value",
                        Integer.toString(new TargetThing().value()));
                }
            }
            """.formatted(packageName),
            StandardCharsets.UTF_8
        );

        Path targetJava = srcRoot.resolve(packagePath + "/TargetThing.java");
        Files.createDirectories(targetJava.getParent());
        Files.writeString(
            targetJava,
            """
            package %s;
            public class TargetThing {
                public int value() {
                    return 1;
                }
            }
            """.formatted(packageName),
            StandardCharsets.UTF_8
        );

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        String classpath = System.getProperty("java.class.path");
        int compileResult = compiler.run(
            null,
            null,
            null,
            "-classpath", classpath,
            "-d", classesRoot.toString(),
            entrypointJava.toString(),
            targetJava.toString()
        );
        assertEquals(0, compileResult);

        byte[] mixinBytes = createOverwriteMixin(
            mixinClass.replace('.', '/'),
            targetClass.replace('.', '/'),
            7
        );

        String modJson = """
            {
              "schemaVersion": 1,
              "id": "%s",
              "version": "1.0.0",
              "entrypoints": {
                "main": ["%s"]
              },
              "mixins": ["warm-cache.mixins.json"]
            }
            """.formatted(modId, entrypointClass);

        String mixinConfig = """
            {
              "required": true,
              "package": "%s",
              "priority": 1500,
              "mixins": ["TargetThingMixin"]
            }
            """.formatted(packageName);

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (Path file : Files.walk(classesRoot).filter(Files::isRegularFile).toList()) {
                String entryName = classesRoot.relativize(file).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(entryName));
                jos.write(Files.readAllBytes(file));
                jos.closeEntry();
            }

            jos.putNextEntry(new JarEntry(mixinClass.replace('.', '/') + ".class"));
            jos.write(mixinBytes);
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("warm-cache.mixins.json"));
            jos.write(mixinConfig.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("fabric.mod.json"));
            jos.write(modJson.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        return new StartupProbe(targetClass);
    }

    private static byte[] createOverwriteMixin(String internalName, String targetInternalName, int replacementValue) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        AnnotationVisitor mixinAnnotation = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", true);
        AnnotationVisitor valueArray = mixinAnnotation.visitArray("value");
        valueArray.visit(null, Type.getObjectType(targetInternalName));
        valueArray.visitEnd();
        mixinAnnotation.visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor value = writer.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null);
        AnnotationVisitor overwrite = value.visitAnnotation("Lorg/spongepowered/asm/mixin/Overwrite;", true);
        overwrite.visitEnd();
        value.visitCode();
        value.visitIntInsn(Opcodes.BIPUSH, replacementValue);
        value.visitInsn(Opcodes.IRETURN);
        value.visitMaxs(1, 1);
        value.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Properties metadataForClass(String className) throws Exception {
        Path metadataDir = Path.of(System.getProperty("user.home"), ".intermed", "aot_v8", "metadata");
        if (!Files.isDirectory(metadataDir)) {
            return null;
        }

        Properties latest = null;
        long latestCreatedAt = Long.MIN_VALUE;
        for (Path file : Files.list(metadataDir).filter(path -> path.getFileName().toString().endsWith(".properties")).toList()) {
            Properties properties = new Properties();
            try (var input = Files.newInputStream(file)) {
                properties.load(input);
            }
            if (className.equals(properties.getProperty("className"))) {
                long createdAt = Long.parseLong(properties.getProperty("createdAtEpochMs", "0"));
                if (latest == null || createdAt >= latestCreatedAt) {
                    latest = properties;
                    latestCreatedAt = createdAt;
                }
            }
        }
        return latest;
    }

    private record StartupProbe(String targetClassName) {}
}
