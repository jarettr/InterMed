package org.intermed.core.remapping;

import org.intermed.core.classloading.BytecodeTransformer;
import org.objectweb.asm.*;

public class RegistryHookTransformer implements BytecodeTransformer {
    @Override
    public byte[] transform(String className, byte[] bytes) {
        if (className.startsWith("net/minecraft/") || 
            className.startsWith("net/minecraftforge/") || 
            className.startsWith("com/mojang/") ||
            className.startsWith("org/intermed/")) {
            return bytes;
        }

        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String methodName, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, methodName, descriptor, signature, exceptions)) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                        
                        if (name.equals("register") || name.equals("m_21102_") || name.equals("m_255069_")) {
                            Type[] args = Type.getArgumentTypes(desc);
                            
                            // Если метод принимает (ResourceLocation, Object)
                            if (args.length >= 2 && args[0].getClassName().equals("net.minecraft.resources.ResourceLocation")) {
                                
                                // Дублируем ID и Object, не трогая оригинальные
                                super.visitInsn(Opcodes.DUP2); 
                                
                                // Отправляем копии на наш виртуальный склад
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, 
                                    "org/intermed/core/bridge/RegistryCache", 
                                    "harvest", 
                                    "(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Object;)V", 
                                    false);
                            }
                        }
                        // Выполняем оригинальный вызов без изменений
                        super.visitMethodInsn(opcode, owner, name, desc, itf);
                    }
                };
            }
        }, 0);
        return cw.toByteArray();
    }
}