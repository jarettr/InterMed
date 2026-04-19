package org.intermed.security;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import org.intermed.core.registry.RegistryCompatibilityContract;
import org.intermed.core.registry.RegistryGetAdvice;
import org.intermed.core.registry.RegistryOptionalGetAdvice;
import org.intermed.core.registry.RegistryRawIdAdvice;
import org.intermed.core.registry.RegistryRegisterAdvice;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main Java Agent for the InterMed platform.
 */
public final class InterMedAgent {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private InterMedAgent() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        install(inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        install(inst);
    }

    public static void install(Instrumentation inst) {
        if (inst == null) {
            throw new IllegalArgumentException("Instrumentation must not be null");
        }
        if (!INSTALLED.compareAndSet(false, true)) {
            System.out.println("\033[1;33m[InterMed Agent] Intercepts already installed, skipping duplicate bootstrap.\033[0m");
            return;
        }

        System.out.println("\033[1;36m[InterMed Agent] Initialising ByteBuddy intercepts...\033[0m");

        new AgentBuilder.Default()
            .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
            .disableClassFormatChanges()
            .ignore(ElementMatchers.nameStartsWith("net.bytebuddy."))

            .type(ElementMatchers.named("java.io.FileInputStream"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(FileReadSecurityAdvice.class)
                    .on(ElementMatchers.isConstructor())))

            .type(ElementMatchers.named("java.io.FileOutputStream"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(FileWriteSecurityAdvice.class)
                    .on(ElementMatchers.isConstructor())))

            .type(ElementMatchers.named("java.io.File"))
            .transform((builder, td, cl, module, pd) ->
                builder
                    .visit(Advice.to(FileDirectoryReadSecurityAdvice.class)
                        .on(ElementMatchers.namedOneOf("list", "listFiles")
                            .and(ElementMatchers.not(ElementMatchers.isStatic()))))
                    .visit(Advice.to(FileMutationSecurityAdvice.class)
                        .on(ElementMatchers.namedOneOf(
                            "createNewFile",
                            "delete",
                            "deleteOnExit",
                            "mkdir",
                            "mkdirs"
                        ).and(ElementMatchers.not(ElementMatchers.isStatic()))))
                    .visit(Advice.to(FileRenameSecurityAdvice.class)
                        .on(ElementMatchers.named("renameTo")
                            .and(ElementMatchers.takesArgument(0, java.io.File.class))
                            .and(ElementMatchers.not(ElementMatchers.isStatic()))))
                    .visit(Advice.to(TempFileSecurityAdvice.class)
                        .on(ElementMatchers.named("createTempFile")
                            .and(ElementMatchers.isStatic()))))

            .type(ElementMatchers.named("java.io.RandomAccessFile"))
            .transform((builder, td, cl, module, pd) ->
                builder
                    .visit(Advice.to(FileReadSecurityAdvice.class)
                        .on(ElementMatchers.isConstructor()))
                    .visit(Advice.to(FileWriteSecurityAdvice.class)
                    .on(ElementMatchers.isConstructor())))

            .type(ElementMatchers.named("java.nio.file.Files"))
            .transform((builder, td, cl, module, pd) ->
                builder
                    .visit(Advice.to(FileReadSecurityAdvice.class)
                        .on(ElementMatchers.namedOneOf("newInputStream", "readAllBytes", "readString")
                            .and(ElementMatchers.takesArgument(0, java.nio.file.Path.class))))
                    .visit(Advice.to(FileWriteSecurityAdvice.class)
                        .on(ElementMatchers.namedOneOf("newOutputStream", "write", "writeString",
                                "createFile", "createDirectories", "createDirectory", "delete", "deleteIfExists")
                            .and(ElementMatchers.takesArgument(0, java.nio.file.Path.class)))))

            .type(ElementMatchers.named("java.nio.file.Files"))
            .transform((builder, td, cl, module, pd) ->
                builder
                    .visit(Advice.to(FileReadSecurityAdvice.class)
                        .on(ElementMatchers.namedOneOf(
                                "lines",
                                "readAllLines",
                                "newDirectoryStream",
                                "list",
                                "walk",
                                "find"
                            )
                            .and(ElementMatchers.takesArgument(0, java.nio.file.Path.class))))
                    .visit(Advice.to(FileChannelSecurityAdvice.class)
                        .on(ElementMatchers.named("newByteChannel")
                            .and(ElementMatchers.takesArgument(0, java.nio.file.Path.class))))
                    .visit(Advice.to(TempFileSecurityAdvice.class)
                        .on(ElementMatchers.namedOneOf("createTempFile", "createTempDirectory")))
                    .visit(Advice.to(FileCopyMoveSecurityAdvice.class)
                        .on(ElementMatchers.namedOneOf("copy", "move"))))

            .type(ElementMatchers.namedOneOf(
                "java.nio.channels.FileChannel",
                "java.nio.channels.AsynchronousFileChannel"
            ))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(FileChannelSecurityAdvice.class)
                    .on(ElementMatchers.named("open")
                        .and(ElementMatchers.isStatic())
                        .and(ElementMatchers.takesArgument(0, java.nio.file.Path.class)))))

            .type(ElementMatchers.named("java.net.Socket"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(SocketSecurityAdvice.class)
                    .on(ElementMatchers.named("connect")
                        .and(ElementMatchers.takesArgument(0, java.net.SocketAddress.class)))))

            .type(ElementMatchers.named("java.net.DatagramSocket"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(SocketSecurityAdvice.class)
                    .on(ElementMatchers.named("connect")
                        .and(ElementMatchers.takesArgument(0, java.net.SocketAddress.class)))))

            .type(ElementMatchers.named("java.nio.channels.SocketChannel"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(SocketSecurityAdvice.class)
                    .on(ElementMatchers.named("connect")
                        .and(ElementMatchers.takesArgument(0, java.net.SocketAddress.class)))))

            .type(ElementMatchers.named("java.nio.channels.AsynchronousSocketChannel"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(SocketSecurityAdvice.class)
                    .on(ElementMatchers.named("connect")
                        .and(ElementMatchers.takesArgument(0, java.net.SocketAddress.class)))))

            .type(ElementMatchers.named("java.net.URL"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(UrlSecurityAdvice.class)
                    .on(ElementMatchers.namedOneOf("openConnection", "openStream")
                        .and(ElementMatchers.not(ElementMatchers.isStatic())))))

            .type(ElementMatchers.named("java.net.URLConnection"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(UrlConnectionSecurityAdvice.class)
                    .on(ElementMatchers.named("connect")
                        .and(ElementMatchers.takesArguments(0)))))

            .type(ElementMatchers.named("java.net.http.HttpClient"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(HttpRequestSecurityAdvice.class)
                    .on(ElementMatchers.namedOneOf("send", "sendAsync")
                        .and(ElementMatchers.takesArgument(0, ElementMatchers.named("java.net.http.HttpRequest"))))))

            .type(ElementMatchers.named("java.lang.reflect.AccessibleObject"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(ReflectionSecurityAdvice.class)
                    .on(ElementMatchers.named("setAccessible"))))

            .type(ElementMatchers.namedOneOf("sun.misc.Unsafe", "jdk.internal.misc.Unsafe"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(UnsafeSecurityAdvice.class)
                    .on(ElementMatchers.namedOneOf(
                        "allocateInstance",
                        "putObject",
                        "putInt",
                        "putLong",
                        "putBoolean",
                        "putByte",
                        "putShort",
                        "putChar",
                        "putFloat",
                        "putDouble",
                        "putAddress"
                    ))))

            .type(ElementMatchers.named("java.lang.invoke.VarHandle"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(VarHandleSecurityAdvice.class)
                    .on(dangerousVarHandleMethods())))

            .type(ElementMatchers.named("java.lang.invoke.MethodHandles$Lookup"))
            .transform((builder, td, cl, module, pd) ->
                builder
                    .visit(Advice.to(VarHandleLookupSecurityAdvice.class)
                        .on(ElementMatchers.namedOneOf(
                            "findVarHandle",
                            "findStaticVarHandle",
                            "unreflectVarHandle"
                        ).and(ElementMatchers.not(ElementMatchers.isAbstract()))))
                    .visit(Advice.to(DynamicClassDefinitionSecurityAdvice.class)
                        .on(ElementMatchers.namedOneOf(
                            "defineClass",
                            "defineHiddenClass",
                            "defineHiddenClassWithClassData"
                        ).and(ElementMatchers.not(ElementMatchers.isAbstract())))))

            .type(hasForeignSuperType("java.lang.foreign.MemorySegment"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(ForeignMemorySecurityAdvice.class)
                    .on(dangerousForeignMemoryMethods())))

            .type(hasForeignSuperType("java.lang.foreign.Arena"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(ForeignMemorySecurityAdvice.class)
                    .on(dangerousForeignMemoryMethods())))

            .type(hasForeignSuperType("java.lang.foreign.Linker"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(ForeignLinkerSecurityAdvice.class)
                    .on(ElementMatchers.namedOneOf(
                        "nativeLinker",
                        "downcallHandle",
                        "upcallStub",
                        "defaultLookup"
                    ).and(ElementMatchers.not(ElementMatchers.isAbstract())))))

            .type(hasForeignSuperType("java.lang.foreign.SymbolLookup"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(ForeignLinkerSecurityAdvice.class)
                    .on(ElementMatchers.namedOneOf(
                        "libraryLookup",
                        "loaderLookup",
                        "find"
                    ).and(ElementMatchers.not(ElementMatchers.isAbstract())))))

            .type(registryPayloadTypes())
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(RegistryRegisterAdvice.class)
                    .on(ElementMatchers.named("register")
                        .and(ElementMatchers.not(ElementMatchers.isAbstract())))))

            .type(registryReadableTypes())
            .transform((builder, td, cl, module, pd) ->
                builder
                    .visit(Advice.to(RegistryGetAdvice.class)
                        .on(objectReturningRegistryReadMethods()))
                    .visit(Advice.to(RegistryOptionalGetAdvice.class)
                        .on(optionalReturningRegistryReadMethods()))
                    .visit(Advice.to(RegistryRawIdAdvice.class)
                        .on(rawIdReturningRegistryReadMethods())))

            .type(ElementMatchers.named("java.lang.ProcessBuilder"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(ProcessSecurityAdvice.class)
                    .on(ElementMatchers.namedOneOf("start", "<init>"))))

            .type(ElementMatchers.named("java.lang.Runtime"))
            .transform((builder, td, cl, module, pd) ->
                builder
                    .visit(Advice.to(ProcessSecurityAdvice.class)
                        .on(ElementMatchers.named("exec")))
                    .visit(Advice.to(NativeLibrarySecurityAdvice.class)
                        .on(ElementMatchers.namedOneOf("loadLibrary", "load"))))

            .type(ElementMatchers.named("java.lang.System"))
            .transform((builder, td, cl, module, pd) ->
                builder.visit(Advice.to(NativeLibrarySecurityAdvice.class)
                    .on(ElementMatchers.namedOneOf("loadLibrary", "load"))))

            .installOn(inst);

        System.out.println("\033[1;32m[InterMed Agent] All intercepts installed (security + registry).\033[0m");
    }

    private static ElementMatcher.Junction<? super TypeDescription> registryPayloadTypes() {
        return namedOwnersMatcher(RegistryCompatibilityContract.payloadLookupBinaryOwners());
    }

    private static ElementMatcher.Junction<? super TypeDescription> registryReadableTypes() {
        return registryPayloadTypes().or(namedOwnersMatcher(RegistryCompatibilityContract.facadeBinaryOwners()));
    }

    private static ElementMatcher.Junction<? super TypeDescription> namedOwnersMatcher(Iterable<String> binaryNames) {
        ElementMatcher.Junction<? super TypeDescription> matcher = ElementMatchers.none();
        for (String binaryName : binaryNames) {
            matcher = matcher.or(ElementMatchers.named(binaryName));
        }
        return matcher;
    }

    private static ElementMatcher.Junction<? super MethodDescription> objectReturningRegistryReadMethods() {
        return ElementMatchers.namedOneOf(
                "get",
                "getValue",
                "method_17966",
                "registryOrThrow",
                "registry",
                "byName"
            )
            .and(ElementMatchers.not(ElementMatchers.isAbstract()))
            .and(ElementMatchers.not(ElementMatchers.returns(ElementMatchers.named("java.util.Optional"))))
            .and(ElementMatchers.not(ElementMatchers.returns(ElementMatchers.named("net.minecraft.core.Registry"))))
            .and(ElementMatchers.not(ElementMatchers.returns(ElementMatchers.nameStartsWith("net.minecraft.core.Holder"))))
            .and(ElementMatchers.not(ElementMatchers.returns(ElementMatchers.nameStartsWith("net.minecraft.core.HolderLookup"))))
            .and(ElementMatchers.not(ElementMatchers.returns(ElementMatchers.nameStartsWith("net.minecraft.resources.ResourceKey"))))
            .and(ElementMatchers.not(ElementMatchers.returns(ElementMatchers.nameStartsWith("com.mojang.serialization.Codec"))));
    }

    private static ElementMatcher.Junction<? super MethodDescription> optionalReturningRegistryReadMethods() {
        return ElementMatchers.namedOneOf("getOptional", "method_36376")
            .and(ElementMatchers.not(ElementMatchers.isAbstract()))
            .and(ElementMatchers.returns(ElementMatchers.named("java.util.Optional")));
    }

    private static ElementMatcher.Junction<? super MethodDescription> rawIdReturningRegistryReadMethods() {
        return ElementMatchers.namedOneOf("getRawId", "getId", "method_10176")
            .and(ElementMatchers.not(ElementMatchers.isAbstract()))
            .and(
                ElementMatchers.returns(int.class)
                    .or(ElementMatchers.returns(ElementMatchers.named("java.lang.Integer")))
            );
    }

    private static ElementMatcher.Junction<? super TypeDescription> hasForeignSuperType(String binaryName) {
        return ElementMatchers.hasSuperType(ElementMatchers.named(binaryName));
    }

    private static ElementMatcher.Junction<? super MethodDescription> dangerousVarHandleMethods() {
        return ElementMatchers.not(ElementMatchers.isConstructor())
            .and(ElementMatchers.not(ElementMatchers.isAbstract()))
            .and(ElementMatchers.not(ElementMatchers.namedOneOf(
                "toMethodHandle",
                "accessModeType",
                "coordinateTypes",
                "varType",
                "isAccessModeSupported",
                "hasInvokeExactBehavior",
                "withInvokeExactBehavior",
                "withInvokeBehavior",
                "toString",
                "describeConstable"
            )));
    }

    private static ElementMatcher.Junction<? super MethodDescription> dangerousForeignMemoryMethods() {
        return ElementMatchers.not(ElementMatchers.isConstructor())
            .and(ElementMatchers.not(ElementMatchers.isAbstract()))
            .and(ElementMatchers.not(ElementMatchers.namedOneOf(
                "toString",
                "hashCode",
                "equals",
                "byteSize",
                "scope",
                "isAccessible",
                "isReadOnly",
                "isNative",
                "close"
            )));
    }
}
