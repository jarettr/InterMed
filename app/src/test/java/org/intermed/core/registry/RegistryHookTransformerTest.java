package org.intermed.core.registry;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RegistryHookTransformerTest {

    @Test
    void rewritesPayloadReturningFacadeLookups() {
        RegistryHookTransformer transformer = new RegistryHookTransformer();
        byte[] transformed = transformer.transform("demo.RegistryProbe", buildProbeClass());
        ClassNode node = read(transformed);

        MethodNode classic = method(node, "classicGet");
        MethodNode defaulted = method(node, "defaultedRegistryGet");
        MethodNode registryAccess = method(node, "registryAccessGet");
        MethodNode frozenAccess = method(node, "frozenRegistryAccessGet");
        MethodNode layeredAccess = method(node, "layeredRegistryAccessGet");
        MethodNode holderLookup = method(node, "holderLookupGet");
        MethodNode providerLookup = method(node, "providerLookupGet");
        MethodNode builtIns = method(node, "builtInByName");
        MethodNode viewOnly = method(node, "registryViewAccess");

        assertNotNull(findInvokeDynamic(classic), "Classic registry payload lookup must be rewritten");
        assertNull(findMethodCall(classic), "Classic registry payload lookup should not remain as direct invoke");

        assertNotNull(findInvokeDynamic(defaulted), "DefaultedRegistry payload lookup must be rewritten");
        assertNull(findMethodCall(defaulted));

        assertNotNull(findInvokeDynamic(registryAccess),
            "Payload-returning RegistryAccess facades should be rewritten");
        assertNull(findMethodCall(registryAccess));

        assertNotNull(findInvokeDynamic(frozenAccess),
            "Payload-returning RegistryAccess$Frozen facades should be rewritten");
        assertNull(findMethodCall(frozenAccess));

        assertNotNull(findInvokeDynamic(layeredAccess),
            "Payload-returning LayeredRegistryAccess facades should be rewritten");
        assertNull(findMethodCall(layeredAccess));

        assertNotNull(findInvokeDynamic(holderLookup),
            "Payload-returning HolderLookup calls should be rewritten");
        assertNull(findMethodCall(holderLookup));

        assertNotNull(findInvokeDynamic(providerLookup),
            "Payload-returning HolderLookup$Provider calls should be rewritten");
        assertNull(findMethodCall(providerLookup));

        assertNotNull(findInvokeDynamic(builtIns),
            "Payload-returning BuiltInRegistries accessors should be rewritten");
        assertNull(findMethodCall(builtIns));

        assertNull(findInvokeDynamic(viewOnly),
            "Facade calls that return registry views must remain untouched");
        assertNotNull(findMethodCall(viewOnly));
    }

    private static byte[] buildProbeClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "demo/RegistryProbe", null, "java/lang/Object", null);

        emitConstructor(writer);
        emitClassicGet(writer);
        emitDefaultedRegistryGet(writer);
        emitRegistryAccessGet(writer);
        emitFrozenRegistryAccessGet(writer);
        emitLayeredRegistryAccessGet(writer);
        emitHolderLookupGet(writer);
        emitProviderLookupGet(writer);
        emitBuiltInByName(writer);
        emitRegistryViewAccess(writer);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitConstructor(ClassWriter writer) {
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
    }

    private static void emitClassicGet(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "classicGet",
            "(Lnet/minecraft/core/Registry;Ljava/lang/Object;)Ljava/lang/Object;",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/core/Registry",
            "getValue",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitDefaultedRegistryGet(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "defaultedRegistryGet",
            "(Lnet/minecraft/core/DefaultedRegistry;Ljava/lang/Object;)Ljava/lang/Object;",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/core/DefaultedRegistry",
            "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitRegistryAccessGet(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "registryAccessGet",
            "(Lnet/minecraft/core/RegistryAccess;Ljava/lang/Object;)Ljava/lang/Object;",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/core/RegistryAccess",
            "registryOrThrow",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitFrozenRegistryAccessGet(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "frozenRegistryAccessGet",
            "(Lnet/minecraft/core/RegistryAccess$Frozen;Ljava/lang/Object;)Ljava/lang/Object;",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/core/RegistryAccess$Frozen",
            "registryOrThrow",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitLayeredRegistryAccessGet(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "layeredRegistryAccessGet",
            "(Lnet/minecraft/core/LayeredRegistryAccess;Ljava/lang/Object;)Ljava/lang/Object;",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/core/LayeredRegistryAccess",
            "registryOrThrow",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitHolderLookupGet(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "holderLookupGet",
            "(Lnet/minecraft/core/HolderLookup$RegistryLookup;Ljava/lang/Object;)Ljava/lang/Object;",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,
            "net/minecraft/core/HolderLookup$RegistryLookup",
            "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            true);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitProviderLookupGet(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "providerLookupGet",
            "(Lnet/minecraft/core/HolderLookup$Provider;Ljava/lang/Object;)Ljava/lang/Object;",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/core/HolderLookup$Provider",
            "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;",
            false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitBuiltInByName(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "builtInByName",
            "(Ljava/lang/String;)Ljava/lang/Object;",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
            "net/minecraft/resources/BuiltInRegistries",
            "byName",
            "(Ljava/lang/String;)Ljava/lang/Object;",
            false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void emitRegistryViewAccess(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "registryViewAccess",
            "(Lnet/minecraft/core/RegistryAccess;Ljava/lang/Object;)Lnet/minecraft/core/Registry;",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "net/minecraft/core/RegistryAccess",
            "registry",
            "(Ljava/lang/Object;)Lnet/minecraft/core/Registry;",
            false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream()
            .filter(method -> method.name.equals(name))
            .findFirst()
            .orElseThrow();
    }

    private static InvokeDynamicInsnNode findInvokeDynamic(MethodNode method) {
        return java.util.Arrays.stream(method.instructions.toArray())
            .filter(InvokeDynamicInsnNode.class::isInstance)
            .map(InvokeDynamicInsnNode.class::cast)
            .findFirst()
            .orElse(null);
    }

    private static MethodInsnNode findMethodCall(MethodNode method) {
        return java.util.Arrays.stream(method.instructions.toArray())
            .filter(MethodInsnNode.class::isInstance)
            .map(MethodInsnNode.class::cast)
            .findFirst()
            .orElse(null);
    }
}
