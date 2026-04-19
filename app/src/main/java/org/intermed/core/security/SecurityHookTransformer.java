package org.intermed.core.security;

import org.intermed.core.classloading.BytecodeTransformer;
import org.intermed.core.classloading.DagAwareClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.AdviceAdapter;

import java.nio.file.Path;

/**
 * Injects capability checks directly into mod bytecode so security does not
 * rely solely on the Java agent being present.
 */
public class SecurityHookTransformer implements BytecodeTransformer {

    private static final String CAPABILITY_OWNER = "org/intermed/core/security/Capability";
    private static final String CAPABILITY_MANAGER = "org/intermed/core/security/CapabilityManager";
    private static final String NATIVE_LINKER_NODE = "org/intermed/core/classloading/NativeLinkerNode";
    private static final String TCCL_INTERCEPTOR = "org/intermed/core/classloading/TcclInterceptor";
    private static final Type RUNNABLE_TYPE = Type.getType(Runnable.class);
    private static final Type CALLABLE_TYPE = Type.getType(java.util.concurrent.Callable.class);
    private static final Type SUPPLIER_TYPE = Type.getType(java.util.function.Supplier.class);

    @Override
    public byte[] transform(String className, byte[] originalBytes) {
        ClassReader cr = new ClassReader(originalBytes);
        ClassWriter cw = DagAwareClassWriter.create(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                return new SecurityMethodVisitor(mv, access, name, descriptor);
            }
        };

        try {
            cr.accept(cv, ClassReader.EXPAND_FRAMES);
            return cw.toByteArray();
        } catch (Exception e) {
            return originalBytes;
        }
    }

    private final class SecurityMethodVisitor extends AdviceAdapter {

        private SecurityMethodVisitor(MethodVisitor delegate, int access, String name, String descriptor) {
            super(Opcodes.ASM9, delegate, access, name, descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String methodName,
                                    String descriptor, boolean isInterface) {
            Type[] argumentTypes = Type.getArgumentTypes(descriptor);

            if (owner.equals("java/lang/reflect/AccessibleObject")
                    && methodName.equals("setAccessible")) {
                injectSecurityCheck(Capability.REFLECTION_ACCESS);
            } else if ((owner.equals("sun/misc/Unsafe") || owner.equals("jdk/internal/misc/Unsafe"))
                    && !methodName.equals("<init>")) {
                injectStringConstantCheck("checkUnsafeOperation", methodName);
            } else if (owner.equals("java/lang/invoke/VarHandle")
                    && !isBenignVarHandleMethod(methodName)) {
                injectStringConstantCheck("checkVarHandleOperation", methodName);
            } else if (owner.equals("java/lang/invoke/MethodHandles$Lookup")
                    && (methodName.equals("findVarHandle")
                        || methodName.equals("findStaticVarHandle")
                        || methodName.equals("unreflectVarHandle"))) {
                injectStringConstantCheck("checkVarHandleLookup", methodName);
            } else if (owner.equals("java/lang/invoke/MethodHandles$Lookup")
                    && (methodName.equals("defineClass")
                        || methodName.equals("defineHiddenClass")
                        || methodName.equals("defineHiddenClassWithClassData"))) {
                injectStringConstantCheck("checkDynamicClassDefinition", methodName);
            } else if ((owner.equals("java/lang/foreign/MemorySegment")
                    || owner.equals("java/lang/foreign/Arena"))
                    && !isBenignForeignMemoryMethod(methodName)) {
                injectStringConstantCheck("checkForeignMemoryAccess", dottedOwner(owner) + "." + methodName);
            } else if (owner.equals("java/lang/foreign/Linker")
                    && !methodName.equals("<init>")) {
                injectStringConstantCheck("checkForeignLinkerOperation", dottedOwner(owner) + "." + methodName);
            } else if (owner.equals("java/lang/foreign/SymbolLookup")
                    && !methodName.equals("<init>")) {
                injectStringConstantCheck("checkForeignLinkerOperation", dottedOwner(owner) + "." + methodName);
            } else if (owner.equals("java/io/FileInputStream") && methodName.equals("<init>")) {
                if (argumentTypes.length == 1 && isReference(argumentTypes[0])) {
                    injectTopObjectCheck("checkFileReadTarget");
                } else {
                    injectSecurityCheck(Capability.FILE_READ);
                }
            } else if (owner.equals("java/io/FileOutputStream") && methodName.equals("<init>")) {
                if (argumentTypes.length == 1 && isReference(argumentTypes[0])) {
                    injectTopObjectCheck("checkFileWriteTarget");
                } else if (argumentTypes.length == 2 && isReference(argumentTypes[0])) {
                    injectFirstOfTwoObjectArgsCheck("checkFileWriteTarget");
                } else {
                    injectSecurityCheck(Capability.FILE_WRITE);
                }
            } else if (owner.equals("java/io/RandomAccessFile") && methodName.equals("<init>")) {
                if (argumentTypes.length == 2
                        && argumentTypes[1].equals(Type.getType(String.class))) {
                    injectTwoArgCheck("checkRandomAccessTarget", "(Ljava/lang/Object;Ljava/lang/String;)V");
                } else {
                    injectSecurityCheck(Capability.FILE_WRITE);
                }
            } else if (owner.equals("java/nio/file/Files")
                    && (methodName.equals("readAllBytes")
                        || methodName.equals("readString")
                        || methodName.equals("lines")
                        || methodName.equals("readAllLines"))) {
                if (argumentTypes.length == 1) {
                    injectTopObjectCheck("checkFileReadTarget");
                } else if (argumentTypes.length == 2) {
                    injectFirstOfTwoObjectArgsCheck("checkFileReadTarget");
                } else {
                    injectSecurityCheck(Capability.FILE_READ);
                }
            } else if (owner.equals("java/nio/file/Files")
                    && methodName.equals("newInputStream")) {
                if (argumentTypes.length == 1) {
                    injectTopObjectCheck("checkFileReadTarget");
                } else if (argumentTypes.length == 2) {
                    injectFirstOfTwoObjectArgsCheck("checkFileReadTarget");
                } else {
                    injectSecurityCheck(Capability.FILE_READ);
                }
            } else if (owner.equals("java/nio/file/Files")
                    && methodName.equals("newByteChannel")
                    && argumentTypes.length >= 2
                    && argumentTypes[0].equals(Type.getType(Path.class))) {
                injectCallArgumentCheck(opcode, owner, argumentTypes, 0, 1,
                    "checkFileChannel", "(Ljava/nio/file/Path;Ljava/lang/Object;)V");
            } else if ((owner.equals("java/nio/channels/FileChannel")
                    || owner.equals("java/nio/channels/AsynchronousFileChannel"))
                    && methodName.equals("open")
                    && argumentTypes.length >= 2
                    && argumentTypes[0].equals(Type.getType(Path.class))) {
                injectCallArgumentCheck(opcode, owner, argumentTypes, 0, 1,
                    "checkFileChannel", "(Ljava/nio/file/Path;Ljava/lang/Object;)V");
            } else if (owner.equals("java/nio/file/Files")
                    && (methodName.equals("newOutputStream")
                        || methodName.equals("createFile")
                        || methodName.equals("createDirectory")
                        || methodName.equals("createDirectories")
                        || methodName.equals("delete")
                        || methodName.equals("deleteIfExists")
                        || methodName.equals("write")
                        || methodName.equals("writeString"))) {
                if (argumentTypes.length == 1) {
                    injectTopObjectCheck("checkFileWriteTarget");
                } else if (argumentTypes.length == 2) {
                    injectFirstOfTwoObjectArgsCheck("checkFileWriteTarget");
                } else if (argumentTypes.length > 2) {
                    injectCallArgumentCheck(opcode, owner, argumentTypes, 0,
                        "checkFileWriteTarget", "(Ljava/lang/Object;)V");
                } else {
                    injectSecurityCheck(Capability.FILE_WRITE);
                }
            } else if (owner.equals("java/nio/file/Files")
                    && (methodName.equals("copy") || methodName.equals("move"))
                    && argumentTypes.length >= 2) {
                injectCallArgumentCheck(opcode, owner, argumentTypes, 0, 1,
                    "checkFileTransfer", "(Ljava/lang/Object;Ljava/lang/Object;)V");
            } else if (owner.equals("java/net/URL") && methodName.equals("openConnection")) {
                if (argumentTypes.length <= 1) {
                    injectReceiverObjectCheck("checkNetworkTarget");
                } else {
                    injectReceiverCheckForCall(opcode, owner, argumentTypes,
                        "checkNetworkTarget", "(Ljava/lang/Object;)V");
                }
            } else if (owner.equals("java/net/URL") && methodName.equals("openStream")) {
                injectReceiverObjectCheck("checkNetworkTarget");
            } else if (owner.equals("java/net/URLConnection") && methodName.equals("connect")) {
                injectReceiverObjectCheck("checkNetworkTarget");
            } else if ((owner.equals("java/net/Socket")
                    || owner.equals("java/net/DatagramSocket")
                    || owner.equals("java/nio/channels/SocketChannel")
                    || owner.equals("java/nio/channels/AsynchronousSocketChannel"))
                    && methodName.equals("connect")) {
                if (argumentTypes.length == 1) {
                    injectTopObjectCheck("checkNetworkTarget");
                } else if (argumentTypes.length >= 2) {
                    injectCallArgumentCheck(opcode, owner, argumentTypes, 0,
                        "checkNetworkTarget", "(Ljava/lang/Object;)V");
                }
            } else if (owner.equals("java/net/http/HttpClient")
                    && (methodName.equals("send") || methodName.equals("sendAsync"))
                    && argumentTypes.length >= 1) {
                injectCallArgumentCheck(opcode, owner, argumentTypes, 0,
                    "checkNetworkTarget", "(Ljava/lang/Object;)V");
            } else if (owner.equals("java/lang/ProcessBuilder")
                    && (methodName.equals("<init>") || methodName.equals("start"))) {
                injectSecurityCheck(Capability.PROCESS_SPAWN);
            } else if (owner.equals("java/lang/Runtime") && methodName.equals("exec")) {
                injectSecurityCheck(Capability.PROCESS_SPAWN);
            } else if (owner.equals("java/lang/System")
                    && (methodName.equals("loadLibrary") || methodName.equals("load"))) {
                injectSecurityCheck(Capability.NATIVE_LIBRARY);
                redirectStaticNativeLoad(methodName);
                return;
            } else if (owner.equals("java/lang/Runtime")
                    && (methodName.equals("loadLibrary") || methodName.equals("load"))) {
                injectSecurityCheck(Capability.NATIVE_LIBRARY);
                redirectRuntimeNativeLoad(methodName);
                return;
            }
            maybeWrapAsyncContext(owner, methodName, argumentTypes);

            super.visitMethodInsn(opcode, owner, methodName, descriptor, isInterface);
        }

        private void redirectStaticNativeLoad(String methodName) {
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                NATIVE_LINKER_NODE,
                methodName,
                "(Ljava/lang/String;)V",
                false
            );
        }

        private void redirectRuntimeNativeLoad(String methodName) {
            super.visitInsn(Opcodes.SWAP);
            super.visitInsn(Opcodes.POP);
            redirectStaticNativeLoad(methodName);
        }

        private void maybeWrapAsyncContext(String owner, String methodName, Type[] argumentTypes) {
            if (argumentTypes.length == 0 || owner.equals(TCCL_INTERCEPTOR)) {
                return;
            }
            Type last = argumentTypes[argumentTypes.length - 1];
            if (owner.equals("java/lang/Thread")
                    && methodName.equals("<init>")
                    && argumentTypes.length == 1
                    && last.equals(RUNNABLE_TYPE)) {
                wrapTopAsyncArgument(last);
                return;
            }
            if (owner.equals("java/lang/Thread")
                    && methodName.equals("startVirtualThread")
                    && argumentTypes.length == 1
                    && last.equals(RUNNABLE_TYPE)) {
                wrapTopAsyncArgument(last);
                return;
            }
            if (owner.startsWith("java/util/concurrent/")
                    && (methodName.equals("execute") || methodName.equals("submit"))
                    && argumentTypes.length == 1
                    && (last.equals(RUNNABLE_TYPE) || last.equals(CALLABLE_TYPE))) {
                wrapTopAsyncArgument(last);
                return;
            }
            if (owner.equals("java/util/concurrent/CompletableFuture")
                    && (methodName.equals("runAsync") || methodName.equals("supplyAsync"))
                    && argumentTypes.length == 1
                    && (last.equals(RUNNABLE_TYPE) || last.equals(SUPPLIER_TYPE))) {
                wrapTopAsyncArgument(last);
            }
        }

        private void wrapTopAsyncArgument(Type argumentType) {
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                TCCL_INTERCEPTOR,
                "propagating",
                "(" + argumentType.getDescriptor() + ")" + argumentType.getDescriptor(),
                false
            );
        }

        private void injectSecurityCheck(Capability cap) {
            super.visitFieldInsn(
                Opcodes.GETSTATIC,
                CAPABILITY_OWNER,
                cap.name(),
                "Lorg/intermed/core/security/Capability;"
            );
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                CAPABILITY_MANAGER,
                "checkPermission",
                "(Lorg/intermed/core/security/Capability;)V",
                false
            );
        }

        private void injectTopObjectCheck(String helperMethod) {
            super.visitInsn(Opcodes.DUP);
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                CAPABILITY_MANAGER,
                helperMethod,
                "(Ljava/lang/Object;)V",
                false
            );
        }

        private void injectFirstOfTwoObjectArgsCheck(String helperMethod) {
            super.visitInsn(Opcodes.DUP2);
            super.visitInsn(Opcodes.POP);
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                CAPABILITY_MANAGER,
                helperMethod,
                "(Ljava/lang/Object;)V",
                false
            );
        }

        private void injectTwoArgCheck(String helperMethod, String helperDescriptor) {
            super.visitInsn(Opcodes.DUP2);
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                CAPABILITY_MANAGER,
                helperMethod,
                helperDescriptor,
                false
            );
        }

        private void injectReceiverObjectCheck(String helperMethod) {
            super.visitInsn(Opcodes.DUP);
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                CAPABILITY_MANAGER,
                helperMethod,
                "(Ljava/lang/Object;)V",
                false
            );
        }

        private void injectStringConstantCheck(String helperMethod, String value) {
            super.visitLdcInsn(value);
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                CAPABILITY_MANAGER,
                helperMethod,
                "(Ljava/lang/String;)V",
                false
            );
        }

        private void injectReceiverCheckForCall(int opcode,
                                                String owner,
                                                Type[] argumentTypes,
                                                String helperMethod,
                                                String helperDescriptor) {
            injectCallCheck(opcode, owner, argumentTypes, new int[] {0}, helperMethod, helperDescriptor);
        }

        private void injectCallArgumentCheck(int opcode,
                                             String owner,
                                             Type[] argumentTypes,
                                             int targetArgIndex,
                                             String helperMethod,
                                             String helperDescriptor) {
            int stackIndex = opcode == Opcodes.INVOKESTATIC ? targetArgIndex : targetArgIndex + 1;
            injectCallCheck(opcode, owner, argumentTypes, new int[] {stackIndex}, helperMethod, helperDescriptor);
        }

        private void injectCallArgumentCheck(int opcode,
                                             String owner,
                                             Type[] argumentTypes,
                                             int firstArgIndex,
                                             int secondArgIndex,
                                             String helperMethod,
                                             String helperDescriptor) {
            int offset = opcode == Opcodes.INVOKESTATIC ? 0 : 1;
            injectCallCheck(opcode, owner, argumentTypes,
                new int[] {firstArgIndex + offset, secondArgIndex + offset},
                helperMethod,
                helperDescriptor);
        }

        private void injectCallCheck(int opcode,
                                     String owner,
                                     Type[] argumentTypes,
                                     int[] targetStackIndexes,
                                     String helperMethod,
                                     String helperDescriptor) {
            Type[] stackTypes = callStackTypes(opcode, owner, argumentTypes);
            int[] locals = new int[stackTypes.length];
            for (int i = 0; i < stackTypes.length; i++) {
                locals[i] = newLocal(stackTypes[i]);
            }

            for (int i = stackTypes.length - 1; i >= 0; i--) {
                visitVarInsn(stackTypes[i].getOpcode(Opcodes.ISTORE), locals[i]);
            }
            for (int targetStackIndex : targetStackIndexes) {
                visitVarInsn(stackTypes[targetStackIndex].getOpcode(Opcodes.ILOAD), locals[targetStackIndex]);
            }
            super.visitMethodInsn(Opcodes.INVOKESTATIC, CAPABILITY_MANAGER, helperMethod, helperDescriptor, false);
            for (int i = 0; i < stackTypes.length; i++) {
                visitVarInsn(stackTypes[i].getOpcode(Opcodes.ILOAD), locals[i]);
            }
        }

        private Type[] callStackTypes(int opcode, String owner, Type[] argumentTypes) {
            boolean hasReceiver = opcode != Opcodes.INVOKESTATIC;
            Type[] stackTypes = new Type[argumentTypes.length + (hasReceiver ? 1 : 0)];
            int index = 0;
            if (hasReceiver) {
                stackTypes[index++] = Type.getObjectType(owner);
            }
            System.arraycopy(argumentTypes, 0, stackTypes, index, argumentTypes.length);
            return stackTypes;
        }

        private boolean isReference(Type type) {
            return type != null
                && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY);
        }

        private boolean isBenignVarHandleMethod(String methodName) {
            return methodName.equals("toMethodHandle")
                || methodName.equals("accessModeType")
                || methodName.equals("coordinateTypes")
                || methodName.equals("varType")
                || methodName.equals("isAccessModeSupported")
                || methodName.equals("hasInvokeExactBehavior")
                || methodName.equals("withInvokeExactBehavior")
                || methodName.equals("withInvokeBehavior")
                || methodName.equals("toString")
                || methodName.equals("describeConstable");
        }

        private boolean isBenignForeignMemoryMethod(String methodName) {
            return methodName.equals("toString")
                || methodName.equals("hashCode")
                || methodName.equals("equals")
                || methodName.equals("byteSize")
                || methodName.equals("scope")
                || methodName.equals("isAccessible")
                || methodName.equals("isReadOnly")
                || methodName.equals("isNative")
                || methodName.equals("close");
        }

        private String dottedOwner(String owner) {
            return owner == null ? "" : owner.replace('/', '.');
        }
    }
}
