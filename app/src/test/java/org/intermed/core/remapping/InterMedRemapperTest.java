package org.intermed.core.remapping;

import org.intermed.api.InterMedAPI;
import org.intermed.core.classloading.TransformationContext;
import org.intermed.core.lifecycle.LifecycleManager;
import org.intermed.core.monitor.RiskyModRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InterMedRemapperTest {

    @BeforeEach
    void setUp() {
        LifecycleManager.DICTIONARY.clear();
        LifecycleManager.DICTIONARY.addClass("net/minecraft/class_42", "net/minecraft/server/level/ServerPlayer");
        LifecycleManager.DICTIONARY.addMethod("net/minecraft/class_42", "method_1000", "()V", "tick");
        LifecycleManager.DICTIONARY.addField("net/minecraft/class_42", "field_500", "I", "count");
        InterMedRemapper.installDictionary(LifecycleManager.DICTIONARY);
    }

    @AfterEach
    void tearDown() {
        InterMedRemapper.clearCaches();
        LifecycleManager.DICTIONARY.clear();
        RiskyModRegistry.resetForTests();
    }

    @Test
    void remapBinaryClassNameUsesInstalledDictionary() {
        assertEquals(
            "net.minecraft.server.level.ServerPlayer",
            InterMedRemapper.remapBinaryClassName("net.minecraft.class_42")
        );
        assertEquals(
            "net/minecraft/server/level/ServerPlayer",
            InterMedRemapper.remapBinaryClassName("net/minecraft/class_42")
        );
    }

    @Test
    void interMedApiUsesRuntimeRemapperFromCorrectPackage() {
        assertEquals(
            "net.minecraft.server.level.ServerPlayer",
            InterMedAPI.remapClassname("net.minecraft.class_42")
        );
        assertEquals(
            "Owner=net.minecraft.server.level.ServerPlayer",
            InterMedAPI.remapRuntimeString("Owner=net.minecraft.class_42")
        );
    }

    @Test
    void translateRuntimeStringRemapsDescriptorsAndArrays() {
        assertEquals(
            "(Lnet/minecraft/server/level/ServerPlayer;)V",
            InterMedRemapper.translateRuntimeString("(Lnet/minecraft/class_42;)V")
        );
        assertEquals(
            "[Lnet/minecraft/server/level/ServerPlayer;",
            InterMedRemapper.translateRuntimeString("[Lnet/minecraft/class_42;")
        );
    }

    @Test
    void transformClassBytesRemapsOwnersMembersAndReflectionStrings() {
        byte[] original = buildProbeClass();

        byte[] transformed = InterMedRemapper.transformClassBytes("example.Probe", original);
        ClassNode node = read(transformed);
        MethodNode probe = node.methods.stream()
            .filter(method -> method.name.equals("probe"))
            .findFirst()
            .orElseThrow();

        FieldInsnNode fieldInsn = findFirst(probe, FieldInsnNode.class);
        MethodInsnNode methodInsn = findFirstMatching(probe, MethodInsnNode.class,
            insn -> "net/minecraft/server/level/ServerPlayer".equals(insn.owner));
        List<LdcInsnNode> ldcInsns = java.util.Arrays.stream(probe.instructions.toArray())
            .filter(LdcInsnNode.class::isInstance)
            .map(LdcInsnNode.class::cast)
            .toList();

        assertEquals("net/minecraft/server/level/ServerPlayer", fieldInsn.owner);
        assertEquals("count", fieldInsn.name);
        assertEquals("net/minecraft/server/level/ServerPlayer", methodInsn.owner);
        assertEquals("tick", methodInsn.name);
        assertTrue(ldcInsns.stream().anyMatch(ldc ->
            "net.minecraft.class_42".equals(ldc.cst)
                || "net.minecraft.server.level.ServerPlayer".equals(ldc.cst)));
        assertTrue(java.util.Arrays.stream(probe.instructions.toArray())
            .filter(MethodInsnNode.class::isInstance)
            .map(MethodInsnNode.class::cast)
            .anyMatch(insn -> insn.owner.equals("org/intermed/core/remapping/InterMedRemapper")
                && insn.name.equals("translateRuntimeString")));
    }

    @Test
    void tinyRemapperTransformerFallsBackToUnifiedRuntimePipeline() {
        byte[] original = buildProbeClass();

        TinyRemapperTransformer transformer = new TinyRemapperTransformer();
        byte[] transformed = transformer.transform("example.Probe", original);
        ClassNode node = read(transformed);
        MethodNode probe = node.methods.stream()
            .filter(method -> method.name.equals("probe"))
            .findFirst()
            .orElseThrow();

        MethodInsnNode methodInsn = findFirstMatching(probe, MethodInsnNode.class,
            insn -> "net/minecraft/server/level/ServerPlayer".equals(insn.owner));
        assertEquals("net/minecraft/server/level/ServerPlayer", methodInsn.owner);
        assertEquals("tick", methodInsn.name);
    }

    @Test
    void transformClassBytesInstrumentsInvokeDynamicConcatSites() {
        byte[] original = buildInvokeDynamicConcatClass();

        byte[] transformed = InterMedRemapper.transformClassBytes("example.ConcatProbe", original);
        ClassNode node = read(transformed);
        MethodNode probe = node.methods.stream()
            .filter(method -> method.name.equals("probeConcat"))
            .findFirst()
            .orElseThrow();

        InvokeDynamicInsnNode indy = findFirst(probe, InvokeDynamicInsnNode.class);
        MethodInsnNode translator = findMethodCallAfter(probe, indy, "org/intermed/core/remapping/InterMedRemapper", "translateRuntimeString");

        assertNotNull(indy);
        assertNotNull(translator);
    }

    @Test
    void transformClassBytesInstrumentsStringBuilderConcatChains() {
        byte[] original = buildStringBuilderConcatClass();

        byte[] transformed = InterMedRemapper.transformClassBytes("example.BuilderProbe", original);
        ClassNode node = read(transformed);
        MethodNode probe = node.methods.stream()
            .filter(method -> method.name.equals("probeBuilder"))
            .findFirst()
            .orElseThrow();

        MethodInsnNode toStringCall = findFirstMatching(probe, MethodInsnNode.class,
            insn -> "java/lang/StringBuilder".equals(insn.owner) && "toString".equals(insn.name));
        MethodInsnNode translator = findMethodCallAfter(probe, toStringCall, "org/intermed/core/remapping/InterMedRemapper", "translateRuntimeString");

        assertNotNull(translator);
    }

    @Test
    void ambiguousReflectionPatternsMarkOwningModAsRisky() {
        byte[] original = buildAmbiguousStringBuilderClass();

        try (TransformationContext.Scope ignored =
                 TransformationContext.enter("ambiguous_mod", "example.AmbiguousBuilder")) {
            InterMedRemapper.transformClassBytes("example.AmbiguousBuilder", original);
        }

        assertTrue(RiskyModRegistry.isModRisky("ambiguous_mod"));
        assertTrue(RiskyModRegistry.reasonsForMod("ambiguous_mod").stream()
            .anyMatch(reason -> reason.contains("ambiguous StringBuilder concat")));
    }

    @Test
    void transformClassBytesInstrumentsReflectionCallsitesWithDynamicNames() {
        byte[] original = buildReflectionCallsiteClass();

        byte[] transformed = InterMedRemapper.transformClassBytes("example.ReflectionProbe", original);
        ClassNode node = read(transformed);
        MethodNode probe = node.methods.stream()
            .filter(method -> method.name.equals("probeReflection"))
            .findFirst()
            .orElseThrow();

        MethodInsnNode forNameCall = findFirstMatching(probe, MethodInsnNode.class,
            insn -> "org/intermed/core/remapping/SymbolicReflectionFacade".equals(insn.owner)
                && "forName".equals(insn.name));
        MethodInsnNode getDeclaredMethodCall = findFirstMatching(probe, MethodInsnNode.class,
            insn -> "org/intermed/core/remapping/SymbolicReflectionFacade".equals(insn.owner)
                && "getDeclaredMethod".equals(insn.name));

        assertNotNull(forNameCall);
        assertNotNull(getDeclaredMethodCall);
    }

    @Test
    void selectiveBypassKeepsReflectiveLibrariesOnSymbolicOnlyPath() {
        byte[] original = buildSelectiveBypassProbeClass("org/reflections/util/NameHelper");

        byte[] transformed = InterMedRemapper.transformClassBytes("org.reflections.util.NameHelper", original);
        ClassNode node = read(transformed);
        MethodNode probe = node.methods.stream()
            .filter(method -> method.name.equals("probe"))
            .findFirst()
            .orElseThrow();

        FieldInsnNode fieldInsn = findFirst(probe, FieldInsnNode.class);
        MethodInsnNode legacyMethodInsn = findFirstMatching(probe, MethodInsnNode.class,
            insn -> "net/minecraft/class_42".equals(insn.owner) && "method_1000".equals(insn.name));
        MethodInsnNode symbolicForName = findFirstMatching(probe, MethodInsnNode.class,
            insn -> "org/intermed/core/remapping/SymbolicReflectionFacade".equals(insn.owner)
                && "forName".equals(insn.name));

        assertEquals("net/minecraft/class_42", fieldInsn.owner);
        assertEquals("field_500", fieldInsn.name);
        assertNotNull(legacyMethodInsn);
        assertNotNull(symbolicForName);
    }

    @Test
    void tinyRemapperTransformerUsesSelectiveBypassForReflectiveLibraries() {
        byte[] original = buildSelectiveBypassProbeClass("javassist/bytecode/Descriptor");

        TinyRemapperTransformer transformer = new TinyRemapperTransformer();
        byte[] transformed = transformer.transform("javassist.bytecode.Descriptor", original);
        ClassNode node = read(transformed);
        MethodNode probe = node.methods.stream()
            .filter(method -> method.name.equals("probe"))
            .findFirst()
            .orElseThrow();

        MethodInsnNode legacyMethodInsn = findFirstMatching(probe, MethodInsnNode.class,
            insn -> "net/minecraft/class_42".equals(insn.owner) && "method_1000".equals(insn.name));
        MethodInsnNode symbolicForName = findFirstMatching(probe, MethodInsnNode.class,
            insn -> "org/intermed/core/remapping/SymbolicReflectionFacade".equals(insn.owner)
                && "forName".equals(insn.name));

        assertNotNull(legacyMethodInsn);
        assertNotNull(symbolicForName);
    }

    @Test
    void transformClassBytesRemapsInheritedMethodCallsUsingSuperclassMappings() {
        String attributeOwner = internalName(InheritedAttribute.class);
        String rangedOwner = internalName(InheritedRangedAttribute.class);

        LifecycleManager.DICTIONARY.addClass("test/class_1320", attributeOwner);
        LifecycleManager.DICTIONARY.addClass("test/class_1329", rangedOwner);
        LifecycleManager.DICTIONARY.addMethod(attributeOwner, "method_26829", "(Z)Ltest/class_1320;", "m_22084_");
        InterMedRemapper.installDictionary(LifecycleManager.DICTIONARY);

        byte[] original = buildInheritedMethodProbeClass();

        byte[] transformed = InterMedRemapper.transformClassBytes("example.InheritedProbe", original);
        ClassNode node = read(transformed);
        MethodNode probe = node.methods.stream()
            .filter(method -> method.name.equals("probeInherited"))
            .findFirst()
            .orElseThrow();

        MethodInsnNode methodInsn = findFirstMatching(probe, MethodInsnNode.class,
            insn -> rangedOwner.equals(insn.owner));

        assertEquals(rangedOwner, methodInsn.owner);
        assertEquals("m_22084_", methodInsn.name);
        assertEquals("(Z)L" + attributeOwner + ";", methodInsn.desc);
    }

    private byte[] buildProbeClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "example/Probe", null, "java/lang/Object", null);

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC, "probe", "(Lnet/minecraft/class_42;)V", null, null);
        probe.visitCode();
        probe.visitLdcInsn("net.minecraft.class_42");
        probe.visitInsn(Opcodes.POP);
        probe.visitVarInsn(Opcodes.ALOAD, 1);
        probe.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/class_42", "field_500", "I");
        probe.visitInsn(Opcodes.POP);
        probe.visitVarInsn(Opcodes.ALOAD, 1);
        probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_42", "method_1000", "()V", false);
        probe.visitInsn(Opcodes.RETURN);
        probe.visitMaxs(2, 2);
        probe.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] buildInheritedMethodProbeClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "example/InheritedProbe", null, "java/lang/Object", null);

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "probeInherited", "(Ltest/class_1329;)Ltest/class_1320;", null, null);
        probe.visitCode();
        probe.visitVarInsn(Opcodes.ALOAD, 0);
        probe.visitInsn(Opcodes.ICONST_1);
        probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "test/class_1329", "method_26829", "(Z)Ltest/class_1320;", false);
        probe.visitInsn(Opcodes.ARETURN);
        probe.visitMaxs(2, 1);
        probe.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] buildSelectiveBypassProbeClass(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "probe", "(Lnet/minecraft/class_42;)V", null, null);
        probe.visitCode();
        probe.visitLdcInsn("net.minecraft.class_42");
        probe.visitMethodInsn(Opcodes.INVOKESTATIC,
            "java/lang/Class",
            "forName",
            "(Ljava/lang/String;)Ljava/lang/Class;",
            false);
        probe.visitInsn(Opcodes.POP);
        probe.visitVarInsn(Opcodes.ALOAD, 0);
        probe.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/class_42", "field_500", "I");
        probe.visitInsn(Opcodes.POP);
        probe.visitVarInsn(Opcodes.ALOAD, 0);
        probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_42", "method_1000", "()V", false);
        probe.visitInsn(Opcodes.RETURN);
        probe.visitMaxs(2, 1);
        probe.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] buildInvokeDynamicConcatClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "example/ConcatProbe", null, "java/lang/Object", null);

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "probeConcat", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        probe.visitCode();
        probe.visitVarInsn(Opcodes.ALOAD, 0);
        probe.visitInvokeDynamicInsn(
            "makeConcatWithConstants",
            "(Ljava/lang/String;)Ljava/lang/String;",
            new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcatWithConstants",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
                false
            ),
            "net.minecraft.\u0001"
        );
        probe.visitInsn(Opcodes.DUP);
        probe.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
            "(Ljava/lang/String;)Ljava/lang/Class;", false);
        probe.visitInsn(Opcodes.POP);
        probe.visitInsn(Opcodes.ARETURN);
        probe.visitMaxs(2, 1);
        probe.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] buildStringBuilderConcatClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "example/BuilderProbe", null, "java/lang/Object", null);

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "probeBuilder", "()Ljava/lang/String;", null, null);
        probe.visitCode();
        probe.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        probe.visitInsn(Opcodes.DUP);
        probe.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        probe.visitLdcInsn("net.");
        probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        probe.visitLdcInsn("minecraft.");
        probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        probe.visitLdcInsn("class_42");
        probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
            "()Ljava/lang/String;", false);
        probe.visitInsn(Opcodes.DUP);
        probe.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
            "(Ljava/lang/String;)Ljava/lang/Class;", false);
        probe.visitInsn(Opcodes.POP);
        probe.visitInsn(Opcodes.ARETURN);
        probe.visitMaxs(3, 0);
        probe.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] buildAmbiguousStringBuilderClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "example/AmbiguousBuilder", null, "java/lang/Object", null);

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "probeAmbiguous", "(Ljava/lang/String;)Ljava/lang/String;", null, null);
        probe.visitCode();
        probe.visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
        probe.visitInsn(Opcodes.DUP);
        probe.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
        probe.visitVarInsn(Opcodes.ALOAD, 0);
        probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
        probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
            "()Ljava/lang/String;", false);
        probe.visitInsn(Opcodes.DUP);
        probe.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
            "(Ljava/lang/String;)Ljava/lang/Class;", false);
        probe.visitInsn(Opcodes.POP);
        probe.visitInsn(Opcodes.ARETURN);
        probe.visitMaxs(3, 1);
        probe.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] buildReflectionCallsiteClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "example/ReflectionProbe", null, "java/lang/Object", null);

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(1, 1);
        init.visitEnd();

        MethodVisitor probe = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "probeReflection", "(Ljava/lang/String;)V", null, null);
        probe.visitCode();
        probe.visitVarInsn(Opcodes.ALOAD, 0);
        probe.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
            "(Ljava/lang/String;)Ljava/lang/Class;", false);
        probe.visitInsn(Opcodes.POP);
        probe.visitLdcInsn(Type.getType("Lnet/minecraft/class_42;"));
        probe.visitVarInsn(Opcodes.ALOAD, 0);
        probe.visitInsn(Opcodes.ICONST_0);
        probe.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Class");
        probe.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getDeclaredMethod",
            "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;", false);
        probe.visitInsn(Opcodes.POP);
        probe.visitInsn(Opcodes.RETURN);
        probe.visitMaxs(4, 1);
        probe.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private <T extends AbstractInsnNode> T findFirst(MethodNode method, Class<T> type) {
        return java.util.Arrays.stream(method.instructions.toArray())
            .filter(type::isInstance)
            .map(type::cast)
            .findFirst()
            .orElseThrow();
    }

    private <T extends AbstractInsnNode> T findFirstMatching(MethodNode method,
                                                             Class<T> type,
                                                             java.util.function.Predicate<T> predicate) {
        return java.util.Arrays.stream(method.instructions.toArray())
            .filter(type::isInstance)
            .map(type::cast)
            .filter(predicate)
            .findFirst()
            .orElseThrow();
    }

    private MethodInsnNode findMethodCallAfter(MethodNode method,
                                               AbstractInsnNode anchor,
                                               String owner,
                                               String name) {
        boolean seenAnchor = false;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn == anchor) {
                seenAnchor = true;
                continue;
            }
            if (!seenAnchor) {
                continue;
            }
            if (insn instanceof MethodInsnNode methodInsn
                && owner.equals(methodInsn.owner)
                && name.equals(methodInsn.name)) {
                return methodInsn;
            }
        }
        return null;
    }

    private MethodInsnNode findMethodCallBefore(MethodNode method,
                                                AbstractInsnNode anchor,
                                                String owner,
                                                String name) {
        MethodInsnNode lastMatch = null;
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (insn == anchor) {
                return lastMatch;
            }
            if (insn instanceof MethodInsnNode methodInsn
                && owner.equals(methodInsn.owner)
                && name.equals(methodInsn.name)) {
                lastMatch = methodInsn;
            }
        }
        return lastMatch;
    }

    private static String internalName(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    static class InheritedAttribute {
        InheritedAttribute m_22084_(boolean syncable) {
            return this;
        }
    }

    static class InheritedRangedAttribute extends InheritedAttribute {}
}
