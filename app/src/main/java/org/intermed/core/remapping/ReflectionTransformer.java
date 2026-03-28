package org.intermed.core.remapping;

import org.objectweb.asm.*;

public class ReflectionTransformer extends ClassVisitor {
    public ReflectionTransformer(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitLdcInsn(Object value) {
                if (value instanceof String str && str.contains("minecraft")) {
                    String translated = InterMedRemapper.translateRuntimeString(str);
                    if (!translated.equals(str)) {
                        // Прямая подмена строковой константы в байт-коде!
                        System.out.println("[Matrix] Remapped LDC: " + str + " -> " + translated);
                        super.visitLdcInsn(translated);
                        return;
                    }
                }
                super.visitLdcInsn(value);
            }
        };
    }
}