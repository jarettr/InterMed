package org.intermed.core.registry;

import org.intermed.core.classloading.BytecodeTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * ASM Трансформатор. Ищет вызовы регистрации Fabric и перенаправляет их.
 */
public class RegistryHookTransformer implements BytecodeTransformer {

    // Имя класса Registry в Intermediary (Fabric)
    private static final String FABRIC_REGISTRY_CLASS = "net/minecraft/class_2378"; 
    // Имя метода register в Intermediary
    private static final String FABRIC_REGISTER_METHOD = "method_10226"; 

    @Override
    public byte[] transform(String className, byte[] originalBytes) {
        try {
            ClassReader reader = new ClassReader(originalBytes);
            ClassWriter writer = new ClassWriter(0);

            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName, String methodDescriptor, boolean isInterface) {
                            
                            // Если мод вызывает функцию регистрации блоков/предметов...
                            if (opcode == Opcodes.INVOKESTATIC && 
                                owner.equals(FABRIC_REGISTRY_CLASS) && 
                                methodName.equals(FABRIC_REGISTER_METHOD)) {
                                
                                // ...МЫ ПЕРЕПИСЫВАЕМ БАЙТ-КОД НА ЛЕТУ!
                                // Перенаправляем вызов в наш VirtualRegistry
                                super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC, 
                                    "org/intermed/core/registry/VirtualRegistry", 
                                    "redirectRegister", 
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", 
                                    false
                                );
                            } else {
                                // Иначе оставляем код без изменений
                                super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                            }
                        }
                    };
                }
            };

            reader.accept(visitor, 0);
            return writer.toByteArray();
        } catch (Exception e) {
            return originalBytes; // В случае ошибки (например, с Mixins) оставляем класс целым
        }
    }
}