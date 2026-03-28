package org.intermed.core.remapping;

import org.objectweb.asm.*;

public class RegistryTransformer extends ClassVisitor {
    
    // Используем ASM7 для максимальной стабильности с Forge
    public RegistryTransformer(ClassVisitor cv) {
        super(Opcodes.ASM7, cv);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
        
        // Ищем методы регистрации блоков/предметов в реестрах игры
        if (name.equals("registerMapping") || name.equals("m_203505_") || name.equals("a")) { 
            return new MethodVisitor(Opcodes.ASM7, mv) {
                @Override
                public void visitCode() {
                    super.visitCode();
                    // Инъекция нашего хука в начало метода регистрации
                    mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
                    mv.visitLdcInsn("\033[1;36m[Hook] Registry insertion intercepted!\033[0m");
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                }
            };
        }
        return mv;
    }
}