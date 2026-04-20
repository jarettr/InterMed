package org.intermed.core.mixin;

import org.intermed.core.lifecycle.LifecycleManager;
import org.intermed.mixin.InterMedMixinBootstrap;
import org.intermed.mixin.service.InterMedGlobalPropertyService;
import org.intermed.mixin.service.InterMedMixinService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.spongepowered.asm.logging.LoggerAdapterDefault;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.IPropertyKey;
import org.spongepowered.asm.util.ReEntranceLock;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class MixinIntegrationTest {

    @AfterEach
    void tearDown() {
        LifecycleManager.resetForTests();
        clearProperties(
            "intermed.modsDir",
            "intermed.mixin.phase3.value",
            "intermed.forge.phase3.loaded",
            "intermed.neoforge.phase3.loaded",
            "mixin.conflict.policy"
        );
    }

    @Test
    void phaseZeroActivatesCanonicalTransmogrifier() {
        LifecycleManager.startPhase0_Preloader();

        assertNotNull(InterMedMixinBootstrap.getClassSource());
        assertNotNull(InterMedMixinBootstrap.getClassSource().getClassPath());
        assertTrue(InterMedMixinBootstrap.isInitialised());
        assertTrue(InterMedMixinBootstrap.wasServiceBootstrapInvoked());
        assertNotEquals(
            InterMedMixinBootstrap.MixinExtrasState.NOT_ATTEMPTED,
            InterMedMixinBootstrap.getMixinExtrasState()
        );
        assertTrue(MixinTransmogrifier.isActive());
        assertEquals(MixinTransmogrifier.ActivationMode.CANONICAL_SERVICE, MixinTransmogrifier.getActivationMode());
        assertEquals(InterMedMixinService.class.getName(), MixinTransmogrifier.getSelectedServiceClass());
        assertEquals(InterMedGlobalPropertyService.class.getName(), MixinTransmogrifier.getSelectedGlobalPropertyClass());
        assertTrue(MixinTransmogrifier.getServiceDescriptors().contains(InterMedMixinService.class.getName()));
        assertTrue(MixinTransmogrifier.getBootstrapDescriptors()
            .contains("org.intermed.mixin.service.InterMedMixinServiceBootstrap"));
        assertTrue(MixinTransmogrifier.getGlobalPropertyDescriptors()
            .contains(InterMedGlobalPropertyService.class.getName()));
        assertTrue(MixinTransmogrifier.getDiagnostics().contains("canonical="));
        if (isMixinExtrasAvailable()) {
            assertTrue(
                InterMedMixinBootstrap.getMixinExtrasState() == InterMedMixinBootstrap.MixinExtrasState.ACTIVE
                    || InterMedMixinBootstrap.getMixinExtrasState() == InterMedMixinBootstrap.MixinExtrasState.DEFERRED
            );
            assertNotEquals("unknown", InterMedMixinBootstrap.getMixinExtrasVersion());
        }
    }

    @Test
    void phaseZeroRecoversFromForeignPreselectedMixinRuntimeViaReflectionHijack() {
        MixinTransmogrifier.installRuntimeSelectionForTests(
            foreignMixinService(),
            new ForeignGlobalPropertyService()
        );

        LifecycleManager.startPhase0_Preloader();

        assertTrue(MixinTransmogrifier.isActive());
        assertEquals(MixinTransmogrifier.ActivationMode.REFLECTION_HIJACK, MixinTransmogrifier.getActivationMode());
        assertEquals(InterMedMixinService.class.getName(), MixinTransmogrifier.getSelectedServiceClass());
        assertEquals(InterMedGlobalPropertyService.class.getName(), MixinTransmogrifier.getSelectedGlobalPropertyClass());
        assertTrue(MixinTransmogrifier.getDiagnostics().contains("preHijack=service=true"));
    }

    @Test
    void mixinServiceExposesNonNullRuntimeCollaborators() {
        InterMedMixinService service = new InterMedMixinService();

        assertNotNull(service.getTransformerProvider());
        assertNotNull(service.getAuditTrail());
        assertNotNull(service.getClassProvider());
        assertNotNull(service.getBytecodeProvider());
        assertNotNull(service.getLogger("test"));
        assertFalse(service.getMixinContainers().isEmpty());
        assertTrue(service.getPlatformAgents().contains(InterMedPlatformAgent.class.getName()));
    }

    @Test
    void repeatedPhaseZeroBootRemainsStableAndKeepsMixinExtrasHealthy() {
        LifecycleManager.startPhase0_Preloader();
        assertTrue(MixinTransmogrifier.isActive());
        assertTrue(InterMedMixinBootstrap.wasServiceBootstrapInvoked());
        if (isMixinExtrasAvailable()) {
            assertTrue(
                InterMedMixinBootstrap.getMixinExtrasState() == InterMedMixinBootstrap.MixinExtrasState.ACTIVE
                    || InterMedMixinBootstrap.getMixinExtrasState() == InterMedMixinBootstrap.MixinExtrasState.DEFERRED
            );
        }

        LifecycleManager.resetForTests();

        LifecycleManager.startPhase0_Preloader();
        assertTrue(MixinTransmogrifier.isActive());
        assertTrue(InterMedMixinBootstrap.wasServiceBootstrapInvoked());
        if (isMixinExtrasAvailable()) {
            assertTrue(
                InterMedMixinBootstrap.getMixinExtrasState() == InterMedMixinBootstrap.MixinExtrasState.ACTIVE
                    || InterMedMixinBootstrap.getMixinExtrasState() == InterMedMixinBootstrap.MixinExtrasState.DEFERRED
            );
        }
    }

    @Test
    void globalPropertyServiceRoundTripsValuesAndDefaults() {
        InterMedGlobalPropertyService service = new InterMedGlobalPropertyService();
        var key = service.resolveKey("intermed.test.key");

        assertEquals("fallback", service.getPropertyString(key, "fallback"));
        service.setProperty(key, "value");
        assertEquals("value", service.getPropertyString(key, "fallback"));
        assertEquals("value", service.getProperty(key, "fallback"));
        service.setProperty(key, null);
        assertNull(service.getProperty(key));
        assertEquals("fallback", service.getPropertyString(key, "fallback"));
    }

    @Test
    void fabricMixinResolutionIsCachedAndCoexistsWithForgeAndNeoForgeMods() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-phase3-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());

        MixinProbeSpec probe = createFabricMixinProbeJar(modsDir.resolve("fabric-mixin-probe.jar"));
        createForgeStyleSmokeJar(
            modsDir.resolve("forge-phase3.jar"),
            "forge_phase3",
            "test.phase3.ForgePhaseThree",
            "META-INF/mods.toml",
            "forge",
            "[47,)",
            "Lnet/minecraftforge/fml/common/Mod;",
            "intermed.forge.phase3.loaded"
        );
        createForgeStyleSmokeJar(
            modsDir.resolve("neoforge-phase3.jar"),
            "neoforge_phase3",
            "test.phase3.NeoForgePhaseThree",
            "META-INF/neoforge.mods.toml",
            "neoforge",
            "[21,)",
            "Lnet/neoforged/fml/common/Mod;",
            "intermed.neoforge.phase3.loaded"
        );

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        assertEquals("7", System.getProperty("intermed.mixin.phase3.value"));
        assertEquals("true", System.getProperty("intermed.forge.phase3.loaded"));
        assertEquals("true", System.getProperty("intermed.neoforge.phase3.loaded"));
        if (isMixinExtrasAvailable()) {
            assertTrue(
                InterMedMixinBootstrap.getMixinExtrasState() == InterMedMixinBootstrap.MixinExtrasState.ACTIVE
                    || InterMedMixinBootstrap.getMixinExtrasState() == InterMedMixinBootstrap.MixinExtrasState.DEFERRED
            );
            assertNotEquals(InterMedMixinBootstrap.MixinExtrasState.FAILED, InterMedMixinBootstrap.getMixinExtrasState());
        }

        assertTrue(hasCachedMetadataForClass(probe.targetClassName()),
            "Resolved mixin target should be persisted in AOT cache metadata");
    }

    @Test
    void noOpMixinResolutionIsStillCachedAsPassThrough() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-noop-phase3-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());

        MixinProbeSpec probe = createFabricMixinProbeJar(modsDir.resolve("fabric-mixin-noop.jar"), 1);

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        assertEquals("1", System.getProperty("intermed.mixin.phase3.value"));
        Properties properties = metadataForClass(probe.targetClassName());
        assertNotNull(properties, "Pass-through resolution should still be recorded in AOT cache");
        assertEquals("PASS_THROUGH", properties.getProperty("extra.cache.kind"));
        assertEquals("0", properties.getProperty("extra.resolution.modifiedMethods"));
    }

    @Test
    void astAnalyzerFailsClosedWhenTargetBytecodeIsInvalid() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            MixinASTAnalyzer.analyzeAndResolve("broken.Target", new byte[] {0x01, 0x02}, java.util.List.of())
        );
        assertTrue(error.getMessage().contains("broken.Target"));
    }

    private static MixinProbeSpec createFabricMixinProbeJar(Path jarPath) throws Exception {
        return createFabricMixinProbeJar(jarPath, 7);
    }

    private static MixinProbeSpec createFabricMixinProbeJar(Path jarPath, int replacementValue) throws Exception {
        Path root = Files.createTempDirectory("intermed-mixin-probe");
        Path srcRoot = root.resolve("src");
        Path classesRoot = root.resolve("classes");
        Files.createDirectories(srcRoot);
        Files.createDirectories(classesRoot);

        Path entrypointJava = srcRoot.resolve("test/phase3/PhaseThreeMixinMod.java");
        Files.createDirectories(entrypointJava.getParent());
        Files.writeString(
            entrypointJava,
            """
            package test.phase3;
            public class PhaseThreeMixinMod implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.setProperty("intermed.mixin.phase3.value",
                        Integer.toString(new test.phase3.TargetThing().value()));
                }
            }
            """,
            StandardCharsets.UTF_8
        );

        Path targetJava = srcRoot.resolve("test/phase3/TargetThing.java");
        Files.createDirectories(targetJava.getParent());
        Files.writeString(
            targetJava,
            """
            package test.phase3;
            public class TargetThing {
                public int value() {
                    return 1;
                }
            }
            """,
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

        byte[] targetBytes = Files.readAllBytes(classesRoot.resolve("test/phase3/TargetThing.class"));
        byte[] mixinBytes = createOverwriteMixin("test/phase3/TargetThingMixin", "test/phase3/TargetThing", replacementValue);

        String modJson = """
            {
              "schemaVersion": 1,
              "id": "fabric_mixin_phase3",
              "version": "1.0.0",
              "entrypoints": {
                "main": ["test.phase3.PhaseThreeMixinMod"]
              },
              "mixins": ["phase3.mixins.json"]
            }
            """;

        String mixinConfig = """
            {
              "required": true,
              "package": "test.phase3",
              "priority": 1500,
              "mixins": ["TargetThingMixin"]
            }
            """;

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (Path file : Files.walk(classesRoot).filter(Files::isRegularFile).toList()) {
                String entryName = classesRoot.relativize(file).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(entryName));
                jos.write(Files.readAllBytes(file));
                jos.closeEntry();
            }

            jos.putNextEntry(new JarEntry("test/phase3/TargetThingMixin.class"));
            jos.write(mixinBytes);
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("phase3.mixins.json"));
            jos.write(mixinConfig.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("fabric.mod.json"));
            jos.write(modJson.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }

        return new MixinProbeSpec(
            "test.phase3.TargetThing",
            targetBytes,
            "test.phase3.TargetThingMixin",
            1500,
            mixinBytes
        );
    }

    private static void createForgeStyleSmokeJar(Path jarPath,
                                                 String modId,
                                                 String entrypoint,
                                                 String manifestPath,
                                                 String dependencyId,
                                                 String dependencyRange,
                                                 String annotationDesc,
                                                 String propertyName) throws Exception {
        String internalName = entrypoint.replace('.', '/');
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new JarEntry(internalName + ".class"));
            jos.write(createLoaderSmokeClass(internalName, modId, annotationDesc, propertyName));
            jos.closeEntry();

            String manifest = """
                modLoader="javafml"
                loaderVersion="%s"

                [[mods]]
                modId="%s"
                version="1.0.0"

                [[mods.dependencies]]
                modId="%s"
                mandatory=true
                versionRange="%s"
                """.formatted(dependencyRange, modId, dependencyId, dependencyRange);

            jos.putNextEntry(new JarEntry(manifestPath));
            jos.write(manifest.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
    }

    private static byte[] createLoaderSmokeClass(String internalName,
                                                 String modId,
                                                 String annotationDesc,
                                                 String propertyName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        AnnotationVisitor annotation = writer.visitAnnotation(annotationDesc, true);
        annotation.visit("value", modId);
        annotation.visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitLdcInsn(propertyName);
        constructor.visitLdcInsn("true");
        constructor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        constructor.visitInsn(Opcodes.POP);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(3, 1);
        constructor.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
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

    private static boolean hasCachedMetadataForClass(String className) throws Exception {
        Path metadataDir = Path.of(System.getProperty("user.home"), ".intermed", "aot_v8", "metadata");
        if (!Files.isDirectory(metadataDir)) {
            return false;
        }

        for (Path file : Files.list(metadataDir).filter(path -> path.getFileName().toString().endsWith(".properties")).toList()) {
            Properties properties = new Properties();
            try (var input = Files.newInputStream(file)) {
                properties.load(input);
            }
            if (className.equals(properties.getProperty("className"))) {
                return true;
            }
        }
        return false;
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

    private static void clearProperties(String... keys) {
        for (String key : keys) {
            System.clearProperty(key);
        }
    }

    private static boolean isMixinExtrasAvailable() {
        try {
            Class.forName("com.llamalad7.mixinextras.MixinExtrasBootstrap");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private static IMixinService foreignMixinService() {
        return (IMixinService) Proxy.newProxyInstance(
            MixinIntegrationTest.class.getClassLoader(),
            new Class<?>[]{IMixinService.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "ForeignRuntime";
                case "isValid" -> true;
                case "getLogger" -> new LoggerAdapterDefault("foreign");
                case "getInitialPhase" -> MixinEnvironment.Phase.PREINIT;
                case "getMinCompatibilityLevel" -> MixinEnvironment.CompatibilityLevel.JAVA_17;
                case "getMaxCompatibilityLevel" -> MixinEnvironment.CompatibilityLevel.MAX_SUPPORTED;
                case "getPlatformAgents" -> java.util.List.of();
                case "getMixinContainers", "getTransformers", "getDelegatedTransformers" -> java.util.List.of();
                case "getReEntranceLock" -> new ReEntranceLock(1);
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0f;
        }
        if (returnType == double.class) {
            return 0.0d;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }

    private static final class ForeignGlobalPropertyService implements IGlobalPropertyService {
        private final java.util.concurrent.ConcurrentHashMap<String, Key> keys = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<Key, Object> values = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public IPropertyKey resolveKey(String name) {
            return keys.computeIfAbsent(name == null ? "" : name, Key::new);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T getProperty(IPropertyKey key) {
            return key == null ? null : (T) values.get(key);
        }

        @Override
        public void setProperty(IPropertyKey key, Object value) {
            if (!(key instanceof Key typedKey)) {
                return;
            }
            if (value == null) {
                values.remove(typedKey);
                return;
            }
            values.put(typedKey, value);
        }

        @Override
        public <T> T getProperty(IPropertyKey key, T defaultValue) {
            T value = getProperty(key);
            return value != null ? value : defaultValue;
        }

        @Override
        public String getPropertyString(IPropertyKey key, String defaultValue) {
            Object value = getProperty(key);
            return value != null ? String.valueOf(value) : defaultValue;
        }

        private record Key(String name) implements IPropertyKey {}
    }

    private record MixinProbeSpec(String targetClassName,
                                  byte[] originalTargetBytes,
                                  String mixinClassName,
                                  int priority,
                                  byte[] mixinBytes) {}
}
