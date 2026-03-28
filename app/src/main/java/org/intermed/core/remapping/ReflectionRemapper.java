package org.intermed.core.remapping;

import org.intermed.core.classloading.BytecodeTransformer;
import org.objectweb.asm.*;

public class ReflectionRemapper implements BytecodeTransformer {
    @Override
    public byte[] transform(String className, byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] exc) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(acc, name, desc, sig, exc)) {
                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof String str && (str.contains("minecraft") || str.startsWith("net/"))) {
                            // Заменяем LDC константу на вызов нашего транслятора
                            super.visitLdcInsn(str);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "org/intermed/core/remapping/ReflectionRemapper", "translate", "(Ljava/lang/String;)Ljava/lang/String;", false);
                        } else {
                            super.visitLdcInsn(value);
                        }
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }

    public static String translate(String original) {
        // Вызов маппинг-движка InterMed
        return InterMedRemapper.translateRuntimeString(original);
    }
}