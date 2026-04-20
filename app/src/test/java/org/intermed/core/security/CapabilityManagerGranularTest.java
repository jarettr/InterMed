package org.intermed.core.security;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.intermed.core.classloading.LazyInterMedClassLoader;
import org.intermed.core.config.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.net.URL;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("strict-security")
class CapabilityManagerGranularTest {

    private ClassLoader originalContextClassLoader;

    @BeforeEach
    void setUp() {
        originalContextClassLoader = Thread.currentThread().getContextClassLoader();
        SecurityPolicy.resetForTests();
        CapabilityManager.resetForTests();
    }

    @AfterEach
    void tearDown() {
        Thread.currentThread().setContextClassLoader(originalContextClassLoader);
        System.clearProperty("security.strict.mode");
        RuntimeConfig.resetForTests();
        SecurityPolicy.resetForTests();
        CapabilityManager.resetForTests();
    }

    @Test
    void fileChannelCheckDistinguishesReadAndWriteOptions() throws Exception {
        Path allowedFile = Files.createTempFile("intermed-channel-", ".txt");

        withModContext("channel_mod", securityProfile(
            capabilities("FILE_READ"),
            paths("fileReadPaths", allowedFile.toString())
        ), () -> {
            assertDoesNotThrow(() ->
                CapabilityManager.checkFileChannel(allowedFile, Set.of(StandardOpenOption.READ)));
            assertThrows(SecurityException.class, () ->
                CapabilityManager.checkFileChannel(allowedFile, Set.of(StandardOpenOption.WRITE)));
        });
    }

    @Test
    void fileCopyOrMoveChecksReadSourceAndWriteTargetSeparately() throws Exception {
        Path source = Files.createTempFile("intermed-source-", ".txt");
        Path allowedTarget = Files.createTempFile("intermed-target-allowed-", ".txt");
        Path deniedTarget = Files.createTempFile("intermed-target-denied-", ".txt");

        withModContext("copy_mod", securityProfile(
            capabilities("FILE_READ", "FILE_WRITE"),
            paths("fileReadPaths", source.toString()),
            paths("fileWritePaths", allowedTarget.toString())
        ), () -> {
            assertDoesNotThrow(() ->
                CapabilityManager.checkFileCopyOrMove(source, allowedTarget));
            assertThrows(SecurityException.class, () ->
                CapabilityManager.checkFileCopyOrMove(source, deniedTarget));
        });
    }

    @Test
    void networkTargetUsesURLConnectionHost() throws Exception {
        withModContext("network_mod", securityProfile(
            capabilities("NETWORK_CONNECT"),
            paths("networkHosts", "api.example.org")
        ), () -> {
            assertDoesNotThrow(() ->
                CapabilityManager.checkNetworkTarget(new URL("https://api.example.org/data").openConnection()));
            assertThrows(SecurityException.class, () ->
                CapabilityManager.checkNetworkTarget(new URL("https://evil.example.org/data").openConnection()));
        });
    }

    @Test
    void randomAccessTargetUsesModeToSelectReadOrWritePolicy() throws Exception {
        Path file = Files.createTempFile("intermed-random-access-", ".txt");

        withModContext("raf_mod", securityProfile(
            capabilities("FILE_READ"),
            paths("fileReadPaths", file.toString())
        ), () -> {
            assertDoesNotThrow(() ->
                CapabilityManager.checkRandomAccessTarget(file.toString(), "r"));
            assertThrows(SecurityException.class, () ->
                CapabilityManager.checkRandomAccessTarget(file.toString(), "rw"));
        });
    }

    @Test
    void fileTransferInfersDirectionFromPathArguments() throws Exception {
        Path readableSource = Files.createTempFile("intermed-transfer-source-", ".txt");
        Path writableTarget = Files.createTempFile("intermed-transfer-target-", ".txt");

        withModContext("transfer_mod", securityProfile(
            capabilities("FILE_READ", "FILE_WRITE"),
            paths("fileReadPaths", readableSource.toString()),
            paths("fileWritePaths", writableTarget.toString())
        ), () -> {
            assertDoesNotThrow(() ->
                CapabilityManager.checkFileTransfer(readableSource, new ByteArrayOutputStream()));
            assertDoesNotThrow(() ->
                CapabilityManager.checkFileTransfer(new ByteArrayInputStream(new byte[0]), writableTarget));
            assertThrows(SecurityException.class, () ->
                CapabilityManager.checkFileTransfer(writableTarget, new ByteArrayOutputStream()));
            assertThrows(SecurityException.class, () ->
                CapabilityManager.checkFileTransfer(new ByteArrayInputStream(new byte[0]), readableSource));
        });
    }

    @Test
    void executeAsModProvidesExplicitSecurityContextOutsideIntermedClassLoader() throws Exception {
        Path readable = Files.createTempFile("intermed-explicit-context-read-", ".txt");

        SecurityPolicy.registerModCapabilities("scoped_mod", securityProfile(
            capabilities("FILE_READ"),
            paths("fileReadPaths", readable.toString())
        ));

        assertDoesNotThrow(() -> CapabilityManager.executeAsMod("scoped_mod", () -> {
            assertEquals("scoped_mod", CapabilityManager.currentModId());
            CapabilityManager.checkFileRead(readable);
        }));
        assertThrows(SecurityException.class, () -> CapabilityManager.executeAsMod("scoped_mod", () ->
            CapabilityManager.checkFileWrite(readable)));
    }

    @Test
    void trustedHostCallWithoutModContextIsAllowed() throws Exception {
        Path hostFile = Files.createTempFile("intermed-host-read-", ".txt");

        assertDoesNotThrow(() -> CapabilityManager.checkFileRead(hostFile));
    }

    @Test
    void strictModeDeniesUnattributedUntrustedCaller() throws Exception {
        Path target = Files.createTempFile("intermed-unattributed-denied-", ".txt");

        InvocationTargetException exception = assertThrows(
            InvocationTargetException.class,
            () -> invokeUnattributedProbe(target)
        );

        SecurityException securityException = assertInstanceOf(SecurityException.class, exception.getCause());
        assertTrue(securityException.getMessage().contains("<unknown>"));
        assertTrue(securityException.getMessage().contains("unattributed caller"));
    }

    @Test
    void permissiveModeStillAllowsUnattributedCallerForDiagnostics() throws Exception {
        Path target = Files.createTempFile("intermed-unattributed-permissive-", ".txt");
        System.setProperty("security.strict.mode", "false");
        RuntimeConfig.reload();

        assertDoesNotThrow(() -> invokeUnattributedProbe(target));
    }

    @Test
    void legacyUnsafeCapabilityCoversModernVarHandleAndFfmMemoryChecks() throws Exception {
        withModContext("memory_mod", securityProfile(
            capabilities("UNSAFE_ACCESS"),
            paths("memoryMembers", "VarHandle.setVolatile", "java.lang.foreign.MemorySegment.reinterpret")
        ), () -> {
            assertDoesNotThrow(() -> CapabilityManager.checkVarHandleOperation("setVolatile"));
            assertDoesNotThrow(() ->
                CapabilityManager.checkForeignMemoryAccess("java.lang.foreign.MemorySegment.reinterpret"));
            assertThrows(SecurityException.class, () ->
                CapabilityManager.checkForeignMemoryAccess("java.lang.foreign.MemorySegment.copyFrom"));
        });
    }

    @Test
    void foreignLinkerOperationsRequireNativeLibraryAndMemoryCapabilities() throws Exception {
        withModContext("ffm_denied_mod", securityProfile(
            capabilities("MEMORY_ACCESS"),
            paths("memoryMembers", "java.lang.foreign.Linker.nativeLinker")
        ), () -> assertThrows(SecurityException.class, () ->
            CapabilityManager.checkForeignLinkerOperation("java.lang.foreign.Linker.nativeLinker")));

        withModContext("ffm_allowed_mod", securityProfile(
            capabilities("MEMORY_ACCESS", "NATIVE_LIBRARY"),
            paths("memoryMembers", "java.lang.foreign.Linker.nativeLinker")
        ), () -> assertDoesNotThrow(() ->
            CapabilityManager.checkForeignLinkerOperation("java.lang.foreign.Linker.nativeLinker")));
    }

    @Test
    void dynamicClassDefinitionRequiresReflectionCapability() throws Exception {
        withModContext("lookup_denied_mod", securityProfile(capabilities()), () ->
            assertThrows(SecurityException.class, () ->
                CapabilityManager.checkDynamicClassDefinition("defineHiddenClass")));

        withModContext("lookup_allowed_mod", securityProfile(
            capabilities("REFLECTION_ACCESS")
        ), () -> assertDoesNotThrow(() ->
            CapabilityManager.checkDynamicClassDefinition("defineHiddenClass")));
    }

    private void withModContext(String modId, JsonObject manifest, ThrowingRunnable action) throws Exception {
        SecurityPolicy.registerModCapabilities(modId, manifest);
        LazyInterMedClassLoader loader = new LazyInterMedClassLoader(
            modId,
            null,
            Set.of(),
            getClass().getClassLoader()
        );
        Thread.currentThread().setContextClassLoader(loader);
        try {
            action.run();
        } finally {
            Thread.currentThread().setContextClassLoader(originalContextClassLoader);
            loader.close();
        }
    }

    private static void invokeUnattributedProbe(Path target) throws Exception {
        Class<?> probe = new PlainProbeClassLoader().define(buildUnattributedProbeClass());
        probe.getMethod("read", Path.class).invoke(null, target);
    }

    private static byte[] buildUnattributedProbeClass() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "untrusted/UnattributedProbe", null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor read = writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "read",
            "(Ljava/nio/file/Path;)V",
            null,
            null
        );
        read.visitCode();
        read.visitVarInsn(Opcodes.ALOAD, 0);
        read.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "org/intermed/core/security/CapabilityManager",
            "checkFileRead",
            "(Ljava/nio/file/Path;)V",
            false
        );
        read.visitInsn(Opcodes.RETURN);
        read.visitMaxs(1, 1);
        read.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static JsonObject securityProfile(JsonArray capabilities, JsonArray... extras) {
        JsonObject root = new JsonObject();
        JsonObject security = new JsonObject();
        security.add("capabilities", capabilities);
        for (JsonArray extra : extras) {
            String key = extra.get(0).getAsString();
            JsonArray values = new JsonArray();
            for (int i = 1; i < extra.size(); i++) {
                values.add(extra.get(i));
            }
            security.add(key, values);
        }
        root.add("intermed:security", security);
        return root;
    }

    private static JsonArray capabilities(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonArray paths(String key, String... values) {
        JsonArray array = new JsonArray();
        array.add(key);
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static final class PlainProbeClassLoader extends ClassLoader {
        private PlainProbeClassLoader() {
            super(CapabilityManagerGranularTest.class.getClassLoader());
        }

        private Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length);
        }
    }
}
