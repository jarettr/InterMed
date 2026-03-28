package org.intermed.core.ast;

import org.objectweb.asm.Type;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

public class ResolutionEngine {

    /**
     * Анализирует миксины, нацеленные на один класс, и разрешает конфликты
     * путем генерации синтетических мостов (Synthetic Bridges).
     */
    public static void resolveMixinConflicts(ClassNode targetClass, List<ClassNode> mixins) {
        System.out.println("[AST Engine] Анализ " + mixins.size() + " миксинов для целевого класса: " + targetClass.name);

        for (ClassNode mixin : mixins) {
            for (MethodNode mixinMethod : mixin.methods) {
                // Ищем конфликт: если метод с таким именем и сигнатурой уже изменен
                MethodNode conflict = findMethod(targetClass, mixinMethod.name, mixinMethod.desc);
                
                if (conflict != null) {
                    System.out.println("[AST Engine] ОБНАРУЖЕН КОНФЛИКТ МЕТОДОВ: " + targetClass.name + "." + mixinMethod.name);
                    
                    // ТЗ 3.2.3: Применяем слияние AST
                    mergeMethods(targetClass, conflict, mixinMethod);
                } else {
                    // Конфликта нет - просто добавляем метод из миксина в целевой класс
                    targetClass.methods.add(mixinMethod);
                }
            }
        }
    }

    private static void mergeMethods(ClassNode target, MethodNode original, MethodNode incoming) {
        // Здесь мы генерируем "Синтетический Мост" (Synthetic Bridge)
        // Он переименовывает оба метода и создает новый, который вызывает их по очереди (на основе приоритетов)
        
        System.out.println("[AST Engine] Генерация синтетического моста для " + original.name);

        // 1. Переименовываем оригинал
        String originalRenamed = original.name + "$intermed_orig";
        original.name = originalRenamed;

        // 2. Переименовываем входящий
        String incomingRenamed = incoming.name + "$intermed_mixin";
        incoming.name = incomingRenamed;
        target.methods.add(incoming);

        // 3. Создаем мост-роутер
        MethodNode bridge = new MethodNode(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC, 
                original.name.replace("$intermed_orig", ""), // Возвращаем оригинальное имя
                original.desc, 
                original.signature, 
                original.exceptions.toArray(new String[0])
        );

        // Генерация байт-кода моста
        InsnList il = new InsnList();
        boolean isStatic = (original.access & Opcodes.ACC_STATIC) != 0;
        int invokeOpcode = isStatic ? Opcodes.INVOKESTATIC : Opcodes.INVOKEVIRTUAL;
        Type[] args = Type.getArgumentTypes(original.desc);
        Type returnType = Type.getReturnType(original.desc);

        // Вспомогательный метод (лямбда/блок) для загрузки аргументов на стек
        Runnable loadArgs = () -> {
            if (!isStatic) il.add(new VarInsnNode(Opcodes.ALOAD, 0)); // грузим this
            int localIndex = isStatic ? 0 : 1;
            for (Type arg : args) {
                il.add(new VarInsnNode(arg.getOpcode(Opcodes.ILOAD), localIndex));
                localIndex += arg.getSize();
            }
        };

        loadArgs.run();
        il.add(new MethodInsnNode(invokeOpcode, target.name, originalRenamed, original.desc, false));

        // ТЗ 3.2.3: Если метод возвращает не void, результат первого вызова нужно снять со стека,
        // чтобы избежать java.lang.VerifyError (Inconsistent stack height).
        if (returnType.getSort() != Type.VOID) {
            if (returnType.getSize() == 2) {
                il.add(new InsnNode(Opcodes.POP2)); // Для типов long и double
            } else {
                il.add(new InsnNode(Opcodes.POP));  // Для всех остальных объектов и примитивов
            }
        }

        loadArgs.run();
        il.add(new MethodInsnNode(invokeOpcode, target.name, incomingRenamed, incoming.desc, false));
        il.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));

        bridge.instructions.add(il);

        target.methods.add(bridge);
    }

    private static MethodNode findMethod(ClassNode clazz, String name, String desc) {
        for (MethodNode method : clazz.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return method;
            }
        }
        return null;
    }
}