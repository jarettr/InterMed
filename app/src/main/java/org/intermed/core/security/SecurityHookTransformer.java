package org.intermed.core.security;

import org.intermed.core.classloading.BytecodeTransformer;
import org.objectweb.asm.*;

/**
 * Внедряет проверки безопасности прямо в байт-код мода.
 */
public class SecurityHookTransformer implements BytecodeTransformer {

    @Override
    public byte[] transform(String className, byte[] originalBytes) {
        ClassReader cr = new ClassReader(originalBytes);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);

        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                
                return new MethodVisitor(Opcodes.ASM9, mv) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                        
                        // 1. Защита от Рефлексии (setAccessible)
                        if (owner.equals("java/lang/reflect/AccessibleObject") && name.equals("setAccessible")) {
                            injectSecurityCheck(Capability.REFLECTION_HACK);
                        }
                        
                        // 2. Защита Сети (Socket, URL)
                        else if (owner.equals("java/net/Socket") && name.equals("<init>")) {
                            injectSecurityCheck(Capability.NETWORK_ACCESS);
                        }
                        
                        // 3. Защита Памяти (Unsafe)
                        else if (owner.equals("sun/misc/Unsafe")) {
                            injectSecurityCheck(Capability.UNSAFE_MEMORY);
                        }

                        // Вызов оригинального метода
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }

                    // Метод, который генерирует байт-код для вызова CapabilityManager
                    private void injectSecurityCheck(Capability cap) {
                        // Получаем enum поле
                        super.visitFieldInsn(Opcodes.GETSTATIC, "org/intermed/core/security/Capability", cap.name(), "Lorg/intermed/core/security/Capability;");
                        // Вызываем checkPermission
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "org/intermed/core/security/CapabilityManager", "checkPermission", "(Lorg/intermed/core/security/Capability;)V", false);
                    }
                };
            }
        };

        try {
            cr.accept(cv, 0);
            return cw.toByteArray();
        } catch (Exception e) {
            return originalBytes;
        }
    }
}