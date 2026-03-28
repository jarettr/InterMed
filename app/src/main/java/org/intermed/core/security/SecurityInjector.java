package org.intermed.core.security;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class SecurityInjector extends ClassVisitor {

    public SecurityInjector(ClassVisitor cv) {
        super(Opcodes.ASM9, cv);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);

        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                
                // Перехват: Открытие сетевого соединения (NETWORK_CONNECT)
                if (owner.equals("java/net/URL") && name.equals("openConnection")) {
                    injectSecurityCheck("NETWORK_CONNECT");
                }
                
                // Перехват: Чтение файлов (FILE_READ)
                if (owner.equals("java/io/FileInputStream") && name.equals("<init>")) {
                    injectSecurityCheck("FILE_READ");
                }

                // Перехват: Использование Unsafe (NATIVE_CODE / UNSAFE)
                if (owner.equals("sun/misc/Unsafe")) {
                    injectSecurityCheck("UNSAFE_ACCESS");
                }

                // Оригинальный вызов
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }

            private void injectSecurityCheck(String capability) {
                System.out.println("[Security Injector] Внедрение проверки '" + capability + "' в байт-код.");
                // Кладем название capability на стек
                super.visitLdcInsn(capability);
                // Вызываем наш CapabilityManager
                super.visitMethodInsn(
                    Opcodes.INVOKESTATIC, 
                    "org/intermed/core/security/CapabilityManager", 
                    "checkPermission", 
                    "(Ljava/lang/String;)V", 
                    false
                );
            }
        };
    }
}