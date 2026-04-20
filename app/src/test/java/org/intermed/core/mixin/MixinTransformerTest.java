package org.intermed.core.mixin;

import org.intermed.core.config.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MixinTransformerTest {

    @AfterEach
    void tearDown() {
        MixinTransformer.resetForTests();
        System.clearProperty("runtime.env");
        RuntimeConfig.resetForTests();
    }

    @Test
    void registersInvisibleTargetStringMixinsFromClientSection() throws Exception {
        RuntimeConfig.reload();

        Path jar = Files.createTempFile("intermed-mixin-client-", ".jar");
        createMixinJar(
            jar,
            """
            {
              "required": true,
              "package": "demo.mixin",
              "priority": 1400,
              "client": ["ClientOnlyMixin"]
            }
            """,
            Map.of(
                "demo/mixin/ClientOnlyMixin.class",
                createInvisibleTargetMixin("demo/mixin/ClientOnlyMixin", "demo.targets.ClientTarget")
            )
        );

        MixinTransformer.registerModMixins(jar.toFile());

        List<MixinInfo> registered = MixinTransformer.registeredMixinsForTarget("demo.targets.ClientTarget");
        assertEquals(1, registered.size());
        assertEquals("demo.mixin.ClientOnlyMixin", registered.get(0).getMixinClass());
        assertTrue(registered.get(0).getTargetClasses().contains("demo.targets.ClientTarget"));
    }

    @Test
    void registersOnlyServerSectionMixinsWhenRuntimeIsServer() throws Exception {
        System.setProperty("runtime.env", "server");
        RuntimeConfig.reload();

        Path jar = Files.createTempFile("intermed-mixin-server-", ".jar");
        createMixinJar(
            jar,
            """
            {
              "required": true,
              "package": "demo.mixin",
              "priority": 1500,
              "client": ["ClientOnlyMixin"],
              "server": ["ServerOnlyMixin"]
            }
            """,
            Map.of(
                "demo/mixin/ClientOnlyMixin.class",
                createInvisibleTargetMixin("demo/mixin/ClientOnlyMixin", "demo.targets.ClientTarget"),
                "demo/mixin/ServerOnlyMixin.class",
                createInvisibleTargetMixin("demo/mixin/ServerOnlyMixin", "demo.targets.ServerTarget")
            )
        );

        MixinTransformer.registerModMixins(jar.toFile());

        assertEquals(1, MixinTransformer.registeredMixinsForTarget("demo.targets.ServerTarget").size());
        assertTrue(MixinTransformer.registeredMixinsForTarget("demo.targets.ClientTarget").isEmpty());
    }

    @Test
    void preservesRegistrationOrderForEqualPriorityMixins() throws Exception {
        RuntimeConfig.reload();

        Path jar = Files.createTempFile("intermed-mixin-order-", ".jar");
        createMixinJar(
            jar,
            """
            {
              "required": true,
              "package": "demo.mixin",
              "priority": 1200,
              "mixins": ["FirstMixin", "SecondMixin"]
            }
            """,
            Map.of(
                "demo/mixin/FirstMixin.class",
                createInvisibleTargetMixin("demo/mixin/FirstMixin", "demo.targets.OrderedTarget"),
                "demo/mixin/SecondMixin.class",
                createInvisibleTargetMixin("demo/mixin/SecondMixin", "demo.targets.OrderedTarget")
            )
        );

        MixinTransformer.registerModMixins(jar.toFile());

        List<MixinInfo> registered = MixinTransformer.registeredMixinsForTarget("demo.targets.OrderedTarget");
        assertEquals(2, registered.size());
        assertEquals("demo.mixin.FirstMixin", registered.get(0).getMixinClass());
        assertEquals("demo.mixin.SecondMixin", registered.get(1).getMixinClass());
        assertTrue(registered.get(0).getRegistrationOrder() < registered.get(1).getRegistrationOrder());
    }

    private static void createMixinJar(Path jarPath,
                                       String mixinConfig,
                                       Map<String, byte[]> classEntries) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                {
                  "schemaVersion": 1,
                  "id": "mixin_transformer_test",
                  "version": "1.0.0",
                  "mixins": ["phase3.mixins.json"]
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new JarEntry("phase3.mixins.json"));
            output.write(mixinConfig.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            for (Map.Entry<String, byte[]> entry : classEntries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    private static byte[] createInvisibleTargetMixin(String internalName, String targetName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("targets");
        targets.visit(null, targetName);
        targets.visitEnd();
        mixin.visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}
