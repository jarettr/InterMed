package org.intermed.core.mixin;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MixinAccessorFallbackTransformerTest {
    @Test
    void rewritesStaticAccessorAssertionStubToReflectionFallback() throws Exception {
        byte[] targetBytes = targetWithObfuscatedStaticMap();
        byte[] accessorBytes = accessorForNamedCriteriaField();
        byte[] transformedAccessor = new MixinAccessorFallbackTransformer()
            .transform("demo.mixin.CriteriaAccessor", accessorBytes);

        DefiningLoader loader = new DefiningLoader();
        Class<?> target = loader.define("demo.targets.CriteriaTriggers", targetBytes);
        Class<?> accessor = loader.define("demo.mixin.CriteriaAccessor", transformedAccessor);

        Method method = accessor.getMethod("getValues");
        Field field = target.getDeclaredField("f_10566_");
        field.setAccessible(true);
        assertEquals(field.get(null), method.invoke(null));
    }

    private static byte[] targetWithObfuscatedStaticMap() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "demo/targets/CriteriaTriggers",
            null, "java/lang/Object", null);

        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
            "f_10566_", "Ljava/util/Map;", null, null).visitEnd();

        MethodVisitor clinit = writer.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/util/LinkedHashMap");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/LinkedHashMap", "<init>", "()V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, "demo/targets/CriteriaTriggers", "f_10566_", "Ljava/util/Map;");
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(2, 0);
        clinit.visitEnd();

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

    private static byte[] accessorForNamedCriteriaField() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
            "demo/mixin/CriteriaAccessor", null, "java/lang/Object", null);

        AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType("demo/targets/CriteriaTriggers"));
        targets.visitEnd();
        mixin.visitEnd();

        MethodVisitor accessor = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "getValues", "()Ljava/util/Map;", null, null);
        AnnotationVisitor annotation = accessor.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", true);
        annotation.visit("value", "CRITERIA");
        annotation.visitEnd();
        accessor.visitCode();
        accessor.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError");
        accessor.visitInsn(Opcodes.DUP);
        accessor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false);
        accessor.visitInsn(Opcodes.ATHROW);
        accessor.visitMaxs(2, 0);
        accessor.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static final class DefiningLoader extends ClassLoader {
        Class<?> define(String binaryName, byte[] bytes) {
            return defineClass(binaryName, bytes, 0, bytes.length);
        }
    }
}
