package org.intermed.core.mixin;

import org.intermed.core.classloading.BytecodeTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.List;

/**
 * Rewrites Fabric accessor mixin stubs to reflection-backed InterMed fallbacks.
 *
 * <p>This covers the cross-loader case where Sponge Mixin has seen the config too
 * late to post-process the accessor interface before the mod entrypoint calls it.
 */
public final class MixinAccessorFallbackTransformer implements BytecodeTransformer {
    private static final String FALLBACK_OWNER = "org/intermed/core/mixin/MixinAccessorFallback";

    @Override
    public byte[] transform(String className, byte[] originalBytes) {
        try {
            ClassNode node = new ClassNode();
            new ClassReader(originalBytes).accept(node, 0);
            String targetInternalName = findMixinTarget(node);
            if (targetInternalName == null) {
                return originalBytes;
            }

            boolean changed = false;
            for (MethodNode method : node.methods) {
                Accessor accessor = Accessor.from(method);
                if (accessor == null) {
                    continue;
                }
                if (rewriteAccessorMethod(method, targetInternalName, accessor)) {
                    changed = true;
                }
            }
            if (!changed) {
                return originalBytes;
            }

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            System.out.println("[MixinAccessorFallback] Rewrote accessor stubs in " + className);
            return writer.toByteArray();
        } catch (Throwable throwable) {
            System.err.println("[MixinAccessorFallback] Failed for " + className + ": " + throwable.getMessage());
            return originalBytes;
        }
    }

    private static boolean rewriteAccessorMethod(MethodNode method, String targetInternalName, Accessor accessor) {
        Type methodType = Type.getMethodType(method.desc);
        Type returnType = methodType.getReturnType();
        Type[] args = methodType.getArgumentTypes();
        boolean staticMethod = (method.access & Opcodes.ACC_STATIC) != 0;

        if (accessor.kind() == AccessorKind.FIELD_GETTER) {
            boolean staticTarget = staticMethod && args.length == 0;
            boolean instanceTarget = staticMethod && args.length == 1;
            if (!staticTarget && !instanceTarget) {
                return false;
            }
            method.instructions = staticTarget
                ? staticGetter(targetInternalName, accessor.targetName(), returnType)
                : instanceGetter(targetInternalName, accessor.targetName(), args[0], returnType);
            method.access &= ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
            method.visibleAnnotations = null;
            method.invisibleAnnotations = null;
            return true;
        }

        if (accessor.kind() == AccessorKind.FIELD_SETTER && staticMethod) {
            boolean staticTarget = returnType.getSort() == Type.VOID && args.length == 1;
            boolean instanceTarget = returnType.getSort() == Type.VOID && args.length == 2;
            if (!staticTarget && !instanceTarget) {
                return false;
            }
            method.instructions = staticTarget
                ? staticSetter(targetInternalName, accessor.targetName(), args[0])
                : instanceSetter(targetInternalName, accessor.targetName(), args[0], args[1]);
            method.access &= ~(Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE);
            method.visibleAnnotations = null;
            method.invisibleAnnotations = null;
            return true;
        }

        return false;
    }

    private static InsnList staticGetter(String owner, String fieldName, Type returnType) {
        InsnList insns = new InsnList();
        insns.add(new LdcInsnNode(Type.getObjectType(owner)));
        insns.add(new LdcInsnNode(fieldName));
        insns.add(classLiteral(returnType));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FALLBACK_OWNER, "getStaticField",
            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false));
        castOrUnbox(insns, returnType);
        insns.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
        return insns;
    }

    private static InsnList instanceGetter(String owner, String fieldName, Type instanceType, Type returnType) {
        InsnList insns = new InsnList();
        insns.add(new LdcInsnNode(Type.getObjectType(owner)));
        insns.add(new VarInsnNode(instanceType.getOpcode(Opcodes.ILOAD), 0));
        boxIfNeeded(insns, instanceType);
        insns.add(new LdcInsnNode(fieldName));
        insns.add(classLiteral(returnType));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FALLBACK_OWNER, "getInstanceField",
            "(Ljava/lang/Class;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", false));
        castOrUnbox(insns, returnType);
        insns.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
        return insns;
    }

    private static InsnList staticSetter(String owner, String fieldName, Type valueType) {
        InsnList insns = new InsnList();
        insns.add(new LdcInsnNode(Type.getObjectType(owner)));
        insns.add(new LdcInsnNode(fieldName));
        insns.add(new VarInsnNode(valueType.getOpcode(Opcodes.ILOAD), 0));
        boxIfNeeded(insns, valueType);
        insns.add(classLiteral(valueType));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FALLBACK_OWNER, "setStaticField",
            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Class;)V", false));
        insns.add(new InsnNode(Opcodes.RETURN));
        return insns;
    }

    private static InsnList instanceSetter(String owner, String fieldName, Type instanceType, Type valueType) {
        InsnList insns = new InsnList();
        insns.add(new LdcInsnNode(Type.getObjectType(owner)));
        insns.add(new VarInsnNode(instanceType.getOpcode(Opcodes.ILOAD), 0));
        boxIfNeeded(insns, instanceType);
        insns.add(new LdcInsnNode(fieldName));
        insns.add(new VarInsnNode(valueType.getOpcode(Opcodes.ILOAD), valueType.getSize()));
        boxIfNeeded(insns, valueType);
        insns.add(classLiteral(valueType));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FALLBACK_OWNER, "setInstanceField",
            "(Ljava/lang/Class;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Class;)V", false));
        insns.add(new InsnNode(Opcodes.RETURN));
        return insns;
    }

    private static void castOrUnbox(InsnList insns, Type type) {
        if (type.getSort() == Type.VOID) {
            return;
        }
        if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
            insns.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
            return;
        }
        Type wrapper = wrapperType(type);
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, wrapper.getInternalName()));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, wrapper.getInternalName(),
            primitiveValueMethod(type), "()" + type.getDescriptor(), false));
    }

    private static void boxIfNeeded(InsnList insns, Type type) {
        if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
            return;
        }
        Type wrapper = wrapperType(type);
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, wrapper.getInternalName(), "valueOf",
            "(" + type.getDescriptor() + ")" + wrapper.getDescriptor(), false));
    }

    private static LdcInsnNode classLiteral(Type type) {
        if (type.getSort() == Type.VOID) {
            return new LdcInsnNode(Type.VOID_TYPE);
        }
        if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
            return new LdcInsnNode(type);
        }
        return new LdcInsnNode(wrapperType(type));
    }

    private static Type wrapperType(Type primitive) {
        return switch (primitive.getSort()) {
            case Type.BOOLEAN -> Type.getType(Boolean.class);
            case Type.BYTE -> Type.getType(Byte.class);
            case Type.SHORT -> Type.getType(Short.class);
            case Type.CHAR -> Type.getType(Character.class);
            case Type.INT -> Type.getType(Integer.class);
            case Type.FLOAT -> Type.getType(Float.class);
            case Type.LONG -> Type.getType(Long.class);
            case Type.DOUBLE -> Type.getType(Double.class);
            case Type.VOID -> Type.getType(Void.class);
            default -> primitive;
        };
    }

    private static String primitiveValueMethod(Type primitive) {
        return switch (primitive.getSort()) {
            case Type.BOOLEAN -> "booleanValue";
            case Type.BYTE -> "byteValue";
            case Type.SHORT -> "shortValue";
            case Type.CHAR -> "charValue";
            case Type.INT -> "intValue";
            case Type.FLOAT -> "floatValue";
            case Type.LONG -> "longValue";
            case Type.DOUBLE -> "doubleValue";
            default -> throw new IllegalArgumentException("Not primitive: " + primitive);
        };
    }

    private static String findMixinTarget(ClassNode node) {
        String target = findMixinTarget(node.visibleAnnotations);
        return target != null ? target : findMixinTarget(node.invisibleAnnotations);
    }

    @SuppressWarnings("unchecked")
    private static String findMixinTarget(List<AnnotationNode> annotations) {
        if (annotations == null) {
            return null;
        }
        for (AnnotationNode annotation : annotations) {
            if (annotation.desc == null || !annotation.desc.endsWith("/Mixin;") || annotation.values == null) {
                continue;
            }
            for (int i = 0; i < annotation.values.size() - 1; i += 2) {
                Object key = annotation.values.get(i);
                Object value = annotation.values.get(i + 1);
                if ("value".equals(key) && value instanceof List<?> values && !values.isEmpty()) {
                    Object first = values.get(0);
                    if (first instanceof Type type) {
                        return type.getInternalName();
                    }
                }
                if ("targets".equals(key) && value instanceof List<?> values && !values.isEmpty()) {
                    Object first = values.get(0);
                    if (first instanceof String target && !target.isBlank()) {
                        return target.replace('.', '/');
                    }
                }
            }
        }
        return null;
    }

    private record Accessor(AccessorKind kind, String targetName) {
        static Accessor from(MethodNode method) {
            Accessor accessor = find(method.visibleAnnotations, method);
            return accessor != null ? accessor : find(method.invisibleAnnotations, method);
        }

        private static Accessor find(List<AnnotationNode> annotations, MethodNode method) {
            if (annotations == null) {
                return null;
            }
            for (AnnotationNode annotation : annotations) {
                if (annotation.desc == null) {
                    continue;
                }
                if (annotation.desc.endsWith("/Accessor;")) {
                    return new Accessor(inferAccessorKind(method), annotationValue(annotation, method.name));
                }
            }
            return null;
        }
    }

    private enum AccessorKind {
        FIELD_GETTER,
        FIELD_SETTER
    }

    private static AccessorKind inferAccessorKind(MethodNode method) {
        return Type.getMethodType(method.desc).getReturnType().getSort() == Type.VOID
            ? AccessorKind.FIELD_SETTER
            : AccessorKind.FIELD_GETTER;
    }

    private static String annotationValue(AnnotationNode annotation, String fallback) {
        if (annotation.values != null) {
            for (int i = 0; i < annotation.values.size() - 1; i += 2) {
                if ("value".equals(annotation.values.get(i)) && annotation.values.get(i + 1) instanceof String value
                    && !value.isBlank()) {
                    return value;
                }
            }
        }
        return inferTargetName(fallback);
    }

    private static String inferTargetName(String methodName) {
        if (methodName == null) {
            return "";
        }
        for (String prefix : new String[] {"get", "set", "is", "accessor"}) {
            if (methodName.startsWith(prefix) && methodName.length() > prefix.length()) {
                String tail = methodName.substring(prefix.length());
                return Character.toLowerCase(tail.charAt(0)) + tail.substring(1);
            }
        }
        return methodName;
    }
}
