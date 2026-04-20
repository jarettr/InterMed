package org.intermed.core.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("strict-security")
class SecurityHookTransformerTest {

    @Test
    void randomAccessFileReadModeUsesGranularTargetAwareCheck() {
        SecurityHookTransformer transformer = new SecurityHookTransformer();
        byte[] transformed = transformer.transform("demo.SecurityProbe", buildProbeClass());
        ClassNode node = read(transformed);

        assertUsesRandomAccessHelper(method(node, "openReadByPath"));
        assertUsesRandomAccessHelper(method(node, "openReadByFile"));
        assertUsesCapabilityHelper(method(node, "copyPathToPath"), "checkFileTransfer");
        assertUsesCapabilityHelper(method(node, "movePathToPath"), "checkFileTransfer");
        assertUsesCapabilityHelper(method(node, "openByteChannelWithAttrs"), "checkFileChannel");
        assertUsesCapabilityHelper(method(node, "writeStringWithCharset"), "checkFileWriteTarget");
        assertUsesCapabilityHelper(method(node, "socketConnectWithTimeout"), "checkNetworkTarget");
        assertUsesCapabilityHelper(method(node, "datagramConnect"), "checkNetworkTarget");
        assertUsesCapabilityHelper(method(node, "socketChannelConnect"), "checkNetworkTarget");
        assertUsesCapabilityHelper(method(node, "asyncSocketChannelConnect"), "checkNetworkTarget");
        assertUsesCapabilityHelper(method(node, "httpClientSend"), "checkNetworkTarget");
        assertUsesCapabilityHelper(method(node, "httpClientSendAsync"), "checkNetworkTarget");
        assertUsesCapabilityHelper(method(node, "urlOpenConnectionWithProxy"), "checkNetworkTarget");
        assertUsesCapabilityHelper(method(node, "urlOpenStream"), "checkNetworkTarget");
        assertUsesCapabilityHelper(method(node, "fileChannelOpen"), "checkFileChannel");
        assertUsesCapabilityHelper(method(node, "asyncFileChannelOpen"), "checkFileChannel");
        assertUsesCapabilityHelper(method(node, "varHandleSet"), "checkVarHandleOperation");
        assertUsesCapabilityHelper(method(node, "lookupFindVarHandle"), "checkVarHandleLookup");
        assertUsesCapabilityHelper(method(node, "lookupDefineClass"), "checkDynamicClassDefinition");
        assertUsesCapabilityHelper(method(node, "memorySegmentReinterpret"), "checkForeignMemoryAccess");
        assertUsesCapabilityHelper(method(node, "linkerNativeLinker"), "checkForeignLinkerOperation");
        assertUsesCapabilityHelper(method(node, "symbolLookupFind"), "checkForeignLinkerOperation");
        assertUsesCapabilityPermission(method(node, "processBuilderStart"), Capability.PROCESS_SPAWN);
        assertUsesCapabilityPermission(method(node, "runtimeExec"), Capability.PROCESS_SPAWN);
        assertUsesCapabilityPermission(method(node, "systemLoadLibrary"), Capability.NATIVE_LIBRARY);
        assertRoutesNativeLoad(method(node, "systemLoadLibrary"), "loadLibrary");
        assertDoesNotCall(method(node, "systemLoadLibrary"), "java/lang/System", "loadLibrary");
        assertUsesCapabilityPermission(method(node, "runtimeLoad"), Capability.NATIVE_LIBRARY);
        assertRoutesNativeLoad(method(node, "runtimeLoad"), "load");
        assertDoesNotCall(method(node, "runtimeLoad"), "java/lang/Runtime", "load");
        assertUsesAsyncContextWrapper(method(node, "executorExecute"), "java/lang/Runnable");
        assertUsesAsyncContextWrapper(method(node, "executorSubmitCallable"), "java/util/concurrent/Callable");
        assertUsesAsyncContextWrapper(method(node, "completableRunAsync"), "java/lang/Runnable");
        assertUsesAsyncContextWrapper(method(node, "completableSupplyAsync"), "java/util/function/Supplier");
        assertUsesAsyncContextWrapper(method(node, "threadWithRunnable"), "java/lang/Runnable");
    }

    private static void assertUsesRandomAccessHelper(MethodNode method) {
        List<MethodInsnNode> capabilityCalls = java.util.Arrays.stream(method.instructions.toArray())
            .filter(MethodInsnNode.class::isInstance)
            .map(MethodInsnNode.class::cast)
            .filter(insn -> "org/intermed/core/security/CapabilityManager".equals(insn.owner))
            .toList();

        assertTrue(capabilityCalls.stream().anyMatch(insn -> insn.name.equals("checkRandomAccessTarget")));
        assertFalse(capabilityCalls.stream().anyMatch(insn -> insn.name.equals("checkPermission")));
        assertEquals(1, capabilityCalls.size());
    }

    private static byte[] buildProbeClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "demo/SecurityProbe", null, "java/lang/Object", null);

        emitConstructor(writer);
        emitReadByPath(writer);
        emitReadByFile(writer);
        emitCopyPathToPath(writer);
        emitMovePathToPath(writer);
        emitOpenByteChannelWithAttrs(writer);
        emitWriteStringWithCharset(writer);
        emitSocketConnectWithTimeout(writer);
        emitDatagramConnect(writer);
        emitSocketChannelConnect(writer);
        emitAsyncSocketChannelConnect(writer);
        emitHttpClientSend(writer);
        emitHttpClientSendAsync(writer);
        emitUrlOpenConnectionWithProxy(writer);
        emitUrlOpenStream(writer);
        emitFileChannelOpen(writer);
        emitAsyncFileChannelOpen(writer);
        emitVarHandleSet(writer);
        emitLookupFindVarHandle(writer);
        emitLookupDefineClass(writer);
        emitMemorySegmentReinterpret(writer);
        emitLinkerNativeLinker(writer);
        emitSymbolLookupFind(writer);
        emitProcessBuilderStart(writer);
        emitRuntimeExec(writer);
        emitSystemLoadLibrary(writer);
        emitRuntimeLoad(writer);
        emitExecutorExecute(writer);
        emitExecutorSubmitCallable(writer);
        emitCompletableRunAsync(writer);
        emitCompletableSupplyAsync(writer);
        emitThreadWithRunnable(writer);

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitConstructor(ClassWriter writer) {
        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
    }

    private static void emitReadByPath(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "openReadByPath",
            "(Ljava/lang/String;)V",
            null,
            new String[] { "java/io/IOException" });
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/io/RandomAccessFile");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("r");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "java/io/RandomAccessFile",
            "<init>",
            "(Ljava/lang/String;Ljava/lang/String;)V",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 1);
        mv.visitEnd();
    }

    private static void emitReadByFile(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "openReadByFile",
            "(Ljava/io/File;)V",
            null,
            new String[] { "java/io/IOException" });
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/io/RandomAccessFile");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitLdcInsn("r");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "java/io/RandomAccessFile",
            "<init>",
            "(Ljava/io/File;Ljava/lang/String;)V",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 1);
        mv.visitEnd();
    }

    private static void emitCopyPathToPath(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "copyPathToPath",
            "(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)V",
            null,
            new String[] { "java/io/IOException" });
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
            "java/nio/file/Files",
            "copy",
            "(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)Ljava/nio/file/Path;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
    }

    private static void emitMovePathToPath(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "movePathToPath",
            "(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)V",
            null,
            new String[] { "java/io/IOException" });
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
            "java/nio/file/Files",
            "move",
            "(Ljava/nio/file/Path;Ljava/nio/file/Path;[Ljava/nio/file/CopyOption;)Ljava/nio/file/Path;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
    }

    private static void emitOpenByteChannelWithAttrs(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "openByteChannelWithAttrs",
            "(Ljava/nio/file/Path;Ljava/util/Set;[Ljava/nio/file/attribute/FileAttribute;)V",
            null,
            new String[] { "java/io/IOException" });
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
            "java/nio/file/Files",
            "newByteChannel",
            "(Ljava/nio/file/Path;Ljava/util/Set;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/channels/SeekableByteChannel;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
    }

    private static void emitWriteStringWithCharset(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "writeStringWithCharset",
            "(Ljava/nio/file/Path;Ljava/lang/String;Ljava/nio/charset/Charset;[Ljava/nio/file/OpenOption;)V",
            null,
            new String[] { "java/io/IOException" });
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
            "java/nio/file/Files",
            "writeString",
            "(Ljava/nio/file/Path;Ljava/lang/CharSequence;Ljava/nio/charset/Charset;[Ljava/nio/file/OpenOption;)Ljava/nio/file/Path;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();
    }

    private static void emitSocketConnectWithTimeout(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "socketConnectWithTimeout",
            "(Ljava/net/Socket;Ljava/net/SocketAddress;I)V",
            null,
            new String[] { "java/io/IOException" });
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/net/Socket",
            "connect",
            "(Ljava/net/SocketAddress;I)V",
            false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
    }

    private static void emitDatagramConnect(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "datagramConnect",
            "(Ljava/net/DatagramSocket;Ljava/net/SocketAddress;)V",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/net/DatagramSocket",
            "connect",
            "(Ljava/net/SocketAddress;)V",
            false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitSocketChannelConnect(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "socketChannelConnect",
            "(Ljava/nio/channels/SocketChannel;Ljava/net/SocketAddress;)V",
            null,
            new String[] { "java/io/IOException" });
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/nio/channels/SocketChannel",
            "connect",
            "(Ljava/net/SocketAddress;)Z",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitAsyncSocketChannelConnect(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "asyncSocketChannelConnect",
            "(Ljava/nio/channels/AsynchronousSocketChannel;Ljava/net/SocketAddress;)V",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/nio/channels/AsynchronousSocketChannel",
            "connect",
            "(Ljava/net/SocketAddress;)Ljava/util/concurrent/Future;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitHttpClientSend(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "httpClientSend",
            "(Ljava/net/http/HttpClient;Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)V",
            null,
            new String[] { "java/io/IOException", "java/lang/InterruptedException" });
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/net/http/HttpClient",
            "send",
            "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/net/http/HttpResponse;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
    }

    private static void emitHttpClientSendAsync(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "httpClientSendAsync",
            "(Ljava/net/http/HttpClient;Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)V",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/net/http/HttpClient",
            "sendAsync",
            "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/util/concurrent/CompletableFuture;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
    }

    private static void emitUrlOpenConnectionWithProxy(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "urlOpenConnectionWithProxy",
            "(Ljava/net/URL;Ljava/net/Proxy;)V",
            null,
            new String[] { "java/io/IOException" });
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/net/URL",
            "openConnection",
            "(Ljava/net/Proxy;)Ljava/net/URLConnection;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitUrlOpenStream(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "urlOpenStream",
            "(Ljava/net/URL;)V",
            null,
            new String[] { "java/io/IOException" });
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/net/URL",
            "openStream",
            "()Ljava/io/InputStream;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void emitFileChannelOpen(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "fileChannelOpen",
            "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)V",
            null,
            new String[] { "java/io/IOException" });
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
            "java/nio/channels/FileChannel",
            "open",
            "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/nio/channels/FileChannel;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitAsyncFileChannelOpen(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "asyncFileChannelOpen",
            "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)V",
            null,
            new String[] { "java/io/IOException" });
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
            "java/nio/channels/AsynchronousFileChannel",
            "open",
            "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/nio/channels/AsynchronousFileChannel;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitVarHandleSet(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "varHandleSet",
            "(Ljava/lang/invoke/VarHandle;Ljava/lang/Object;)V",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/lang/invoke/VarHandle",
            "set",
            "(Ljava/lang/Object;)V",
            false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitLookupFindVarHandle(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "lookupFindVarHandle",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)V",
            null,
            new String[] { "java/lang/NoSuchFieldException", "java/lang/IllegalAccessException" });
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/lang/invoke/MethodHandles$Lookup",
            "findVarHandle",
            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/VarHandle;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(4, 4);
        mv.visitEnd();
    }

    private static void emitLookupDefineClass(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "lookupDefineClass",
            "(Ljava/lang/invoke/MethodHandles$Lookup;[B)V",
            null,
            new String[] { "java/lang/IllegalAccessException" });
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/lang/invoke/MethodHandles$Lookup",
            "defineClass",
            "([B)Ljava/lang/Class;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitMemorySegmentReinterpret(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "memorySegmentReinterpret",
            "(Ljava/lang/foreign/MemorySegment;J)V",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.LLOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,
            "java/lang/foreign/MemorySegment",
            "reinterpret",
            "(J)Ljava/lang/foreign/MemorySegment;",
            true);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 3);
        mv.visitEnd();
    }

    private static void emitLinkerNativeLinker(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "linkerNativeLinker",
            "()V",
            null,
            null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
            "java/lang/foreign/Linker",
            "nativeLinker",
            "()Ljava/lang/foreign/Linker;",
            true);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
    }

    private static void emitSymbolLookupFind(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "symbolLookupFind",
            "(Ljava/lang/foreign/SymbolLookup;Ljava/lang/String;)V",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,
            "java/lang/foreign/SymbolLookup",
            "find",
            "(Ljava/lang/String;)Ljava/util/Optional;",
            true);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitProcessBuilderStart(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "processBuilderStart",
            "(Ljava/lang/ProcessBuilder;)V",
            null,
            new String[] { "java/io/IOException" });
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/lang/ProcessBuilder",
            "start",
            "()Ljava/lang/Process;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void emitRuntimeExec(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "runtimeExec",
            "(Ljava/lang/Runtime;Ljava/lang/String;)V",
            null,
            new String[] { "java/io/IOException" });
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/lang/Runtime",
            "exec",
            "(Ljava/lang/String;)Ljava/lang/Process;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitSystemLoadLibrary(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "systemLoadLibrary",
            "(Ljava/lang/String;)V",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
            "java/lang/System",
            "loadLibrary",
            "(Ljava/lang/String;)V",
            false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void emitRuntimeLoad(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "runtimeLoad",
            "(Ljava/lang/Runtime;Ljava/lang/String;)V",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
            "java/lang/Runtime",
            "load",
            "(Ljava/lang/String;)V",
            false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitExecutorExecute(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "executorExecute",
            "(Ljava/util/concurrent/Executor;Ljava/lang/Runnable;)V",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,
            "java/util/concurrent/Executor",
            "execute",
            "(Ljava/lang/Runnable;)V",
            true);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitExecutorSubmitCallable(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "executorSubmitCallable",
            "(Ljava/util/concurrent/ExecutorService;Ljava/util/concurrent/Callable;)V",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE,
            "java/util/concurrent/ExecutorService",
            "submit",
            "(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Future;",
            true);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    private static void emitCompletableRunAsync(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "completableRunAsync",
            "(Ljava/lang/Runnable;)V",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
            "java/util/concurrent/CompletableFuture",
            "runAsync",
            "(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void emitCompletableSupplyAsync(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "completableSupplyAsync",
            "(Ljava/util/function/Supplier;)V",
            null,
            null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC,
            "java/util/concurrent/CompletableFuture",
            "supplyAsync",
            "(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void emitThreadWithRunnable(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "threadWithRunnable",
            "(Ljava/lang/Runnable;)V",
            null,
            null);
        mv.visitCode();
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/Thread");
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL,
            "java/lang/Thread",
            "<init>",
            "(Ljava/lang/Runnable;)V",
            false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 1);
        mv.visitEnd();
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name) {
        return node.methods.stream()
            .filter(method -> method.name.equals(name))
            .findFirst()
            .orElseThrow();
    }

    private static void assertUsesCapabilityHelper(MethodNode method, String helperName) {
        List<MethodInsnNode> capabilityCalls = java.util.Arrays.stream(method.instructions.toArray())
            .filter(MethodInsnNode.class::isInstance)
            .map(MethodInsnNode.class::cast)
            .filter(insn -> "org/intermed/core/security/CapabilityManager".equals(insn.owner))
            .toList();

        assertTrue(capabilityCalls.stream().anyMatch(insn -> insn.name.equals(helperName)));
        assertFalse(capabilityCalls.stream().anyMatch(insn -> insn.name.equals("checkPermission")));
    }

    private static void assertUsesCapabilityPermission(MethodNode method, Capability expectedCapability) {
        List<MethodInsnNode> capabilityCalls = java.util.Arrays.stream(method.instructions.toArray())
            .filter(MethodInsnNode.class::isInstance)
            .map(MethodInsnNode.class::cast)
            .filter(insn -> "org/intermed/core/security/CapabilityManager".equals(insn.owner))
            .toList();
        List<FieldInsnNode> capabilityFields = java.util.Arrays.stream(method.instructions.toArray())
            .filter(FieldInsnNode.class::isInstance)
            .map(FieldInsnNode.class::cast)
            .filter(insn -> "org/intermed/core/security/Capability".equals(insn.owner))
            .toList();

        assertTrue(capabilityCalls.stream().anyMatch(insn -> insn.name.equals("checkPermission")));
        assertTrue(capabilityFields.stream().anyMatch(insn -> insn.name.equals(expectedCapability.name())));
    }

    private static void assertUsesAsyncContextWrapper(MethodNode method, String argumentInternalName) {
        String descriptor = "(L" + argumentInternalName + ";)L" + argumentInternalName + ";";
        List<MethodInsnNode> calls = java.util.Arrays.stream(method.instructions.toArray())
            .filter(MethodInsnNode.class::isInstance)
            .map(MethodInsnNode.class::cast)
            .filter(insn -> "org/intermed/core/classloading/TcclInterceptor".equals(insn.owner))
            .toList();

        assertTrue(calls.stream().anyMatch(insn ->
            insn.name.equals("propagating") && insn.desc.equals(descriptor)));
    }

    private static void assertRoutesNativeLoad(MethodNode method, String nativeMethodName) {
        List<MethodInsnNode> calls = java.util.Arrays.stream(method.instructions.toArray())
            .filter(MethodInsnNode.class::isInstance)
            .map(MethodInsnNode.class::cast)
            .filter(insn -> "org/intermed/core/classloading/NativeLinkerNode".equals(insn.owner))
            .toList();

        assertTrue(calls.stream().anyMatch(insn ->
            insn.name.equals(nativeMethodName) && insn.desc.equals("(Ljava/lang/String;)V")));
    }

    private static void assertDoesNotCall(MethodNode method, String owner, String name) {
        boolean found = java.util.Arrays.stream(method.instructions.toArray())
            .filter(MethodInsnNode.class::isInstance)
            .map(MethodInsnNode.class::cast)
            .anyMatch(insn -> owner.equals(insn.owner) && name.equals(insn.name));

        assertFalse(found, method.name + " must not retain direct " + owner + "." + name + " calls");
    }
}
