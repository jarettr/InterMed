package org.intermed.core.sandbox;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.FileSystem;
import org.graalvm.polyglot.io.IOAccess;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strict Espresso host integration used by Phase 4 diagnostics and sandbox planning.
 */
public final class GraalVMSandbox implements AutoCloseable {

    /*
     * Secure-by-default posture: if Espresso cannot start under a constrained policy
     * without native host access, we treat the runtime as unavailable and let the
     * planner fall back rather than silently relaxing isolation guarantees.
     */
    private static final SandboxPolicy SANDBOX_POLICY = SandboxPolicy.CONSTRAINED;
    private static final boolean NATIVE_ACCESS_ALLOWED = false;
    private final String modId;
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private String guestClasspath = "";
    private List<Path> guestClasspathEntries = List.of();
    private Path guestArchiveFile;
    private Path stdoutCaptureFile;
    private Path stderrCaptureFile;
    private Engine engine;
    private Context context;
    private Value guestEntrypointClassLoader;
    private boolean javaLanguageAvailable;
    private String initializationMessage = "not-initialized";
    private int guestPropertyCount;
    private static volatile HostStatus cachedHostStatus;

    public GraalVMSandbox() {
        this("unknown");
    }

    public GraalVMSandbox(String modId) {
        this.modId = modId == null || modId.isBlank() ? "unknown" : modId;
    }

    public static boolean isHostAvailable() {
        return classPresent("org.graalvm.polyglot.Context")
            && classPresent("org.graalvm.polyglot.Engine");
    }

    public static HostStatus probeAvailability() {
        HostStatus cached = cachedHostStatus;
        if (cached != null) {
            return cached;
        }
        if (!isHostAvailable()) {
            HostStatus unavailable = new HostStatus(false, false, "polyglot-host-missing");
            cachedHostStatus = unavailable;
            return unavailable;
        }
        try (GraalVMSandbox sandbox = new GraalVMSandbox("__probe__")) {
            boolean initialized = sandbox.initialize(probeClasspathEntries(), Map.of());
            HostStatus probed = new HostStatus(
                true,
                initialized
                    && sandbox.isJavaLanguageAvailable()
                    && sandbox.canResolveGuestClass(GraalVMSandbox.class.getName()),
                sandbox.initializationMessage()
            );
            cachedHostStatus = probed;
            return probed;
        }
    }

    public boolean initialize() {
        return initialize(List.of(), Map.of());
    }

    public boolean initialize(File modJar) {
        return initialize(modJar == null ? List.of() : List.of(modJar.toPath()), Map.of());
    }

    public boolean initialize(byte[] modArchiveBytes) {
        if (modArchiveBytes == null || modArchiveBytes.length == 0) {
            return initialize();
        }
        try {
            guestArchiveFile = prepareGuestArchive(modArchiveBytes);
            return initialize(List.of(guestArchiveFile), Map.of());
        } catch (IOException exception) {
            logFailure("archive-prepare", exception);
            initializationMessage = "mod-archive-unavailable:" + exception.getClass().getSimpleName();
            deleteGuestArchiveFile();
            return false;
        }
    }

    public boolean initialize(List<Path> additionalClasspathEntries, Map<String, String> guestProperties) {
        if (!isHostAvailable()) {
            initializationMessage = "polyglot-host-missing";
            return false;
        }
        try {
            stdout.reset();
            stderr.reset();
            Set<Path> guestClasspathEntries = collectGuestClasspathEntries(additionalClasspathEntries);
            guestClasspath = buildGuestClasspath(guestClasspathEntries);
            this.guestClasspathEntries = List.copyOf(guestClasspathEntries);
            guestEntrypointClassLoader = null;
            recordGuestPropertyCount(guestProperties);
            prepareCaptureFiles();
            IOAccess ioAccess = buildGuestClasspathIoAccess(guestClasspathEntries, stdoutCaptureFile, stderrCaptureFile);
            Context.Builder builder = Context.newBuilder("java")
                .allowExperimentalOptions(true)
                .sandbox(SANDBOX_POLICY)
                .allowAllAccess(false)
                .allowHostAccess(HostAccess.CONSTRAINED)
                .allowHostClassLookup(name -> false)
                .allowHostClassLoading(false)
                .allowNativeAccess(NATIVE_ACCESS_ALLOWED)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowPolyglotAccess(PolyglotAccess.NONE)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .allowIO(ioAccess)
                .in(new ByteArrayInputStream(new byte[0]))
                .out(stdout)
                .err(stderr)
                .option("engine.WarnInterpreterOnly", "false");
            applyGuestProperties(builder, guestProperties, guestClasspath);
            context = builder.build();
            engine = context.getEngine();
            javaLanguageAvailable = engine.getLanguages().containsKey("java");
            if (!javaLanguageAvailable) {
                initializationMessage = "espresso-language-unavailable";
                safeClose(context);
                context = null;
                safeClose(engine);
                engine = null;
                return false;
            }
            initializationMessage = "ready";
            return true;
        } catch (Throwable t) {
            logFailure("initialize", t);
            initializationMessage = describeInitializationFailure(t);
            safeClose(context);
            safeClose(engine);
            context = null;
            engine = null;
            return false;
        }
    }

    public EspressoExecutionResult executeEntrypoint(File modJar,
                                                     String entrypointClass,
                                                     String lifecycleMethod,
                                                     boolean constructOnly) {
        return executeEntrypoint(modJar, entrypointClass, lifecycleMethod, constructOnly, Map.of());
    }

    public EspressoExecutionResult executeEntrypoint(File modJar,
                                                     String entrypointClass,
                                                     String lifecycleMethod,
                                                     boolean constructOnly,
                                                     Map<String, String> guestProperties) {
        Objects.requireNonNull(entrypointClass, "entrypointClass");
        String normalizedEntrypointClass = entrypointClass.trim();
        if (normalizedEntrypointClass.isBlank()) {
            return new EspressoExecutionResult(
                modId,
                "",
                lifecycleMethod == null ? "" : lifecycleMethod.trim(),
                false,
                "entrypoint-class-missing",
                stdoutText(),
                stderrText()
            );
        }

        if (!isInitialized() && !initialize(
            modJar == null ? List.of() : List.of(modJar.toPath()),
            mergeGuestProperties(
                Map.of(
                    "intermed.sandbox.modId", modId,
                    "intermed.sandbox.entrypoint", normalizedEntrypointClass,
                    "intermed.sandbox.hostContract.sha256", WitContractCatalog.contractDigest(),
                    "intermed.sandbox.hostContract.exports", Integer.toString(WitContractCatalog.hostExportCount())
                ),
                guestProperties
            )
        )) {
            return new EspressoExecutionResult(
                modId,
                normalizedEntrypointClass,
                lifecycleMethod == null ? "" : lifecycleMethod,
                false,
                initializationMessage,
                stdoutText(),
                stderrText()
            );
        }

        try {
            Value bindings = context.getBindings("java");
            redirectGuestOutput(bindings);
            try {
                ClassLookupResult guestClassLookup = resolveGuestClass(bindings, normalizedEntrypointClass);
                Value guestClass = guestClassLookup.classAccessor();
                if (guestClass == null || guestClass.isNull()) {
                    String classpathDetail = describeGuestClasspath(modJar);
                    return new EspressoExecutionResult(
                        modId,
                        normalizedEntrypointClass,
                        lifecycleMethod == null ? "" : lifecycleMethod,
                        false,
                        (guestClassLookup.failure() == null
                            ? "entrypoint-class-not-found"
                            : "entrypoint-class-not-found:" + guestClassLookup.failure())
                            + classpathDetail,
                        stdoutText(),
                        stderrText()
                    );
                }

                Value instance = guestClass.canInstantiate() ? guestClass.newInstance() : null;
                if (!constructOnly) {
                    String method = lifecycleMethod == null ? "" : lifecycleMethod.trim();
                    boolean invoked = false;
                    if (!method.isBlank() && instance != null && instance.canInvokeMember(method)) {
                        instance.invokeMember(method);
                        invoked = true;
                    } else if (!method.isBlank() && guestClass.canInvokeMember(method)) {
                        guestClass.invokeMember(method);
                        invoked = true;
                    }
                    if (!invoked && !method.isBlank() && instance == null) {
                        return new EspressoExecutionResult(
                            modId,
                            normalizedEntrypointClass,
                            method,
                            false,
                            "entrypoint-not-instantiable",
                            stdoutText(),
                            stderrText()
                        );
                    }
                    if (!invoked && !method.isBlank()) {
                        return new EspressoExecutionResult(
                            modId,
                            normalizedEntrypointClass,
                            method,
                            false,
                            "entrypoint-method-not-found:" + method,
                            stdoutText(),
                            stderrText()
                        );
                    }
                }

                String message = constructOnly
                    ? "constructed"
                    : (lifecycleMethod == null || lifecycleMethod.isBlank() ? "constructed" : "invoked:" + lifecycleMethod);
                return new EspressoExecutionResult(
                    modId,
                    normalizedEntrypointClass,
                    lifecycleMethod == null ? "" : lifecycleMethod,
                    true,
                    message,
                    stdoutText(),
                    stderrText()
                );
            } finally {
                flushGuestOutput(bindings);
            }
        } catch (Throwable t) {
            logFailure("execute", t);
            return new EspressoExecutionResult(
                modId,
                normalizedEntrypointClass,
                lifecycleMethod == null ? "" : lifecycleMethod,
                false,
                "failed:" + t,
                stdoutText(),
                stderrText()
            );
        }
    }

    private boolean canResolveGuestClass(String className) {
        if (className == null || className.isBlank() || context == null) {
            return false;
        }
        try {
            ClassLookupResult lookup = resolveGuestClass(context.getBindings("java"), className);
            return lookup.classAccessor() != null && !lookup.classAccessor().isNull();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private ClassLookupResult resolveGuestClass(Value bindings, String entrypointClass) {
        if (bindings == null) {
            return new ClassLookupResult(null, "java-bindings-unavailable");
        }
        Value directAccessor = bindings.getMember(entrypointClass);
        if (directAccessor != null && !directAccessor.isNull()) {
            return new ClassLookupResult(directAccessor, null);
        }
        return resolveViaClassForName(bindings, entrypointClass);
    }

    private ClassLookupResult resolveViaClassForName(Value bindings, String entrypointClass) {
        Value classAccessor = bindings.getMember("java.lang.Class");
        if (classAccessor == null || classAccessor.isNull()) {
            return new ClassLookupResult(null, "java.lang.Class-unavailable");
        }
        try {
            Value classLoader = ensureGuestEntrypointClassLoader(bindings);
            Value classInstance = (classLoader == null || classLoader.isNull())
                ? classAccessor.invokeMember("forName", entrypointClass)
                : classAccessor.invokeMember("forName", entrypointClass, true, classLoader);
            if (classInstance == null || classInstance.isNull()) {
                return new ClassLookupResult(null, "forName-returned-null");
            }
            Value staticAccessor = classInstance.getMember("static");
            if (staticAccessor == null || staticAccessor.isNull()) {
                return new ClassLookupResult(null, "class-static-accessor-unavailable");
            }
            return new ClassLookupResult(staticAccessor, null);
        } catch (Throwable t) {
            String detail = t.getClass().getSimpleName();
            String message = t.getMessage();
            if (message != null && !message.isBlank()) {
                detail += ":" + message;
            }
            return new ClassLookupResult(null, detail);
        }
    }

    private String describeGuestClasspath(File modJar) {
        boolean hasJar = modJar != null
            && guestClasspathEntries.stream().anyMatch(path -> path.equals(modJar.toPath().toAbsolutePath().normalize()));
        return "|guestCpEntries=" + guestClasspathEntryCount() + "|guestCpHasModJar=" + hasJar;
    }

    private void redirectGuestOutput(Value bindings) {
        if (bindings == null || stdoutCaptureFile == null || stderrCaptureFile == null) {
            return;
        }
        try {
            Value systemAccessor = bindings.getMember("java.lang.System");
            Value fileOutputStreamClass = bindings.getMember("java.io.FileOutputStream");
            Value printStreamClass = bindings.getMember("java.io.PrintStream");
            if (systemAccessor == null || systemAccessor.isNull()
                || fileOutputStreamClass == null || fileOutputStreamClass.isNull()
                || printStreamClass == null || printStreamClass.isNull()) {
                return;
            }
            Value outStream = fileOutputStreamClass.newInstance(stdoutCaptureFile.toString());
            Value errStream = fileOutputStreamClass.newInstance(stderrCaptureFile.toString());
            Value outPrintStream = printStreamClass.newInstance(outStream, true, "UTF-8");
            Value errPrintStream = printStreamClass.newInstance(errStream, true, "UTF-8");
            systemAccessor.invokeMember("setOut", outPrintStream);
            systemAccessor.invokeMember("setErr", errPrintStream);
        } catch (Throwable t) {
            System.err.printf("[GraalVMSandbox] redirectGuestOutput failed for mod '%s': %s: %s%n",
                modId, t.getClass().getSimpleName(), t.getMessage());
        }
    }

    private static void flushGuestOutput(Value bindings) {
        if (bindings == null) {
            return;
        }
        try {
            Value systemAccessor = bindings.getMember("java.lang.System");
            if (systemAccessor == null || systemAccessor.isNull()) {
                return;
            }
            Value out = systemAccessor.getMember("out");
            Value err = systemAccessor.getMember("err");
            if (out != null && !out.isNull() && out.canInvokeMember("flush")) {
                out.invokeMember("flush");
            }
            if (err != null && !err.isNull() && err.canInvokeMember("flush")) {
                err.invokeMember("flush");
            }
        } catch (Throwable t) {
            System.err.printf("[GraalVMSandbox] flushGuestOutput failed: %s: %s%n",
                t.getClass().getSimpleName(), t.getMessage());
        }
    }

    public boolean isInitialized() {
        return context != null;
    }

    public boolean isJavaLanguageAvailable() {
        return javaLanguageAvailable;
    }

    public String diagnostics() {
        return "mod=" + modId
            + ", host=" + isHostAvailable()
            + ", javaLanguage=" + javaLanguageAvailable
            + ", state=" + initializationMessage
            + ", policy=" + SANDBOX_POLICY
            + ", nativeAccess=" + NATIVE_ACCESS_ALLOWED
            + ", classpathEntries=" + guestClasspathEntryCount()
            + ", guestProperties=" + guestPropertyCount
            + ", hostContractDigest=" + WitContractCatalog.contractDigest();
    }

    public String stdoutText() {
        return readCaptureText(stdoutCaptureFile, stdout);
    }

    public String stderrText() {
        return readCaptureText(stderrCaptureFile, stderr);
    }

    public String initializationMessage() {
        return initializationMessage;
    }

    @Override
    public void close() {
        safeClose(context);
        safeClose(engine);
        context = null;
        engine = null;
        guestClasspath = "";
        guestClasspathEntries = List.of();
        guestEntrypointClassLoader = null;
        guestPropertyCount = 0;
        deleteCaptureFiles();
        deleteGuestArchiveFile();
    }

    private static boolean classPresent(String className) {
        try {
            Class.forName(className, false, GraalVMSandbox.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static List<Path> probeClasspathEntries() {
        Path codeSource = resolveOwnCodeSource();
        return codeSource == null ? List.of() : List.of(codeSource);
    }

    private Path prepareGuestArchive(byte[] modArchiveBytes) throws IOException {
        deleteGuestArchiveFile();
        Path archive = Files.createTempFile("intermed-espresso-" + sanitizeForFileName(modId) + "-", ".jar");
        Files.write(archive, modArchiveBytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        return archive;
    }

    private static Path resolveOwnCodeSource() {
        try {
            if (GraalVMSandbox.class.getProtectionDomain() == null
                || GraalVMSandbox.class.getProtectionDomain().getCodeSource() == null
                || GraalVMSandbox.class.getProtectionDomain().getCodeSource().getLocation() == null) {
                return null;
            }
            return Path.of(GraalVMSandbox.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .toAbsolutePath()
                .normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void safeClose(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }

    private int guestClasspathEntryCount() {
        if (guestClasspath == null || guestClasspath.isBlank()) {
            return 0;
        }
        return guestClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator)).length;
    }

    private static Set<Path> collectGuestClasspathEntries(List<Path> additionalClasspathEntries) {
        Set<Path> entries = new LinkedHashSet<>();
        String currentClasspath = System.getProperty("java.class.path", "");
        if (!currentClasspath.isBlank()) {
            for (String entry : currentClasspath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                if (!entry.isBlank()) {
                    tryAddClasspathEntry(entries, Path.of(entry));
                }
            }
        }
        if (additionalClasspathEntries != null) {
            for (Path path : additionalClasspathEntries) {
                if (path != null) {
                    tryAddClasspathEntry(entries, path);
                }
            }
        }
        return entries;
    }

    private static void tryAddClasspathEntry(Set<Path> entries, Path path) {
        if (entries == null || path == null) {
            return;
        }
        entries.add(path.toAbsolutePath().normalize());
    }

    private static String buildGuestClasspath(Set<Path> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        return entries.stream()
            .map(Path::toString)
            .collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }

    private void prepareCaptureFiles() throws IOException {
        deleteCaptureFiles();
        stdoutCaptureFile = Files.createTempFile("intermed-espresso-" + modId + "-", ".stdout.log");
        stderrCaptureFile = Files.createTempFile("intermed-espresso-" + modId + "-", ".stderr.log");
    }

    private void deleteCaptureFiles() {
        deleteCaptureFile(stdoutCaptureFile);
        deleteCaptureFile(stderrCaptureFile);
        stdoutCaptureFile = null;
        stderrCaptureFile = null;
    }

    private static void deleteCaptureFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private void deleteGuestArchiveFile() {
        deleteCaptureFile(guestArchiveFile);
        guestArchiveFile = null;
    }

    private static String readCaptureText(Path captureFile, ByteArrayOutputStream fallback) {
        if (captureFile != null) {
            try {
                String capture = Files.readString(captureFile, StandardCharsets.UTF_8);
                if (!capture.isBlank()) {
                    return capture;
                }
            } catch (IOException ignored) {
            }
        }
        return fallback.toString(StandardCharsets.UTF_8);
    }

    private void logFailure(String phase, Throwable throwable) {
        System.err.printf("[GraalVMSandbox] %s failed for mod '%s': %s%n",
            phase, modId, conciseError(throwable));
    }

    private static String conciseError(Throwable throwable) {
        if (throwable == null) {
            return "unknown";
        }
        String detail = throwable.getClass().getSimpleName();
        String message = throwable.getMessage();
        if (message != null && !message.isBlank()) {
            detail += ": " + message.trim();
        }
        return detail;
    }

    private static String sanitizeForFileName(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static IOAccess buildGuestClasspathIoAccess(Set<Path> guestClasspathEntries,
                                                        Path stdoutCaptureFile,
                                                        Path stderrCaptureFile) {
        FileSystem baseFileSystem = FileSystem.newDefaultFileSystem();
        FileSystem restrictedFileSystem = new GuestClasspathFileSystem(
            baseFileSystem,
            guestClasspathEntries,
            Set.of(stdoutCaptureFile, stderrCaptureFile)
        );
        return IOAccess.newBuilder()
            .allowHostFileAccess(false)
            .allowHostSocketAccess(false)
            .fileSystem(FileSystem.allowLanguageHomeAccess(restrictedFileSystem))
            .build();
    }

    private static void applyGuestProperties(Context.Builder builder,
                                             Map<String, String> guestProperties,
                                             String guestClasspath) {
        if (builder == null) {
            return;
        }
        if (guestClasspath != null && !guestClasspath.isBlank()) {
            builder.option("java.Properties.java.class.path", guestClasspath);
        }
        if (guestProperties == null || guestProperties.isEmpty()) {
            return;
        }
        guestProperties.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                return;
            }
            builder.option("java.Properties." + key.trim(), value);
        });
    }

    private static Map<String, String> mergeGuestProperties(Map<String, String> base,
                                                            Map<String, String> additional) {
        if ((base == null || base.isEmpty()) && (additional == null || additional.isEmpty())) {
            return Map.of();
        }
        java.util.LinkedHashMap<String, String> merged = new java.util.LinkedHashMap<>();
        if (base != null && !base.isEmpty()) {
            merged.putAll(base);
        }
        if (additional != null && !additional.isEmpty()) {
            additional.forEach((key, value) -> {
                if (key != null && !key.isBlank() && value != null) {
                    merged.put(key, value);
                }
            });
        }
        return Map.copyOf(merged);
    }

    private void recordGuestPropertyCount(Map<String, String> guestProperties) {
        if (guestProperties == null || guestProperties.isEmpty()) {
            guestPropertyCount = 0;
            return;
        }
        int applied = 0;
        for (Map.Entry<String, String> entry : guestProperties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || key.isBlank() || value == null) {
                continue;
            }
            applied++;
        }
        guestPropertyCount = applied;
    }

    private Value ensureGuestEntrypointClassLoader(Value bindings) {
        if (guestEntrypointClassLoader != null && !guestEntrypointClassLoader.isNull()) {
            return guestEntrypointClassLoader;
        }
        if (bindings == null || guestClasspathEntries.isEmpty()) {
            return null;
        }
        try {
            Value fileClass = bindings.getMember("java.io.File");
            Value urlClass = bindings.getMember("java.net.URL");
            Value urlClassLoaderClass = bindings.getMember("java.net.URLClassLoader");
            Value classLoaderClass = bindings.getMember("java.lang.ClassLoader");
            Value reflectArrayClass = bindings.getMember("java.lang.reflect.Array");
            if (fileClass == null || fileClass.isNull()
                || urlClass == null || urlClass.isNull()
                || urlClassLoaderClass == null || urlClassLoaderClass.isNull()
                || classLoaderClass == null || classLoaderClass.isNull()
                || reflectArrayClass == null || reflectArrayClass.isNull()) {
                return null;
            }

            Value urls = reflectArrayClass.invokeMember("newInstance", urlClass, guestClasspathEntries.size());
            for (int index = 0; index < guestClasspathEntries.size(); index++) {
                Value file = fileClass.newInstance(guestClasspathEntries.get(index).toString());
                Value uri = file.invokeMember("toURI");
                Value url = uri.invokeMember("toURL");
                urls.setArrayElement(index, url);
            }

            Value systemClassLoader = classLoaderClass.invokeMember("getSystemClassLoader");
            guestEntrypointClassLoader = urlClassLoaderClass.newInstance(urls, systemClassLoader);
            return guestEntrypointClassLoader;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void resetForTests() {
        cachedHostStatus = null;
    }

    static SandboxPolicy sandboxPolicy() {
        return SANDBOX_POLICY;
    }

    static boolean isNativeAccessAllowed() {
        return NATIVE_ACCESS_ALLOWED;
    }

    private static String describeInitializationFailure(Throwable throwable) {
        if (throwable == null) {
            return "failed:unknown";
        }
        String message = throwable.getMessage();
        if (message != null) {
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("sandbox")
                || normalized.contains("trusted")
                || normalized.contains("native access")
                || normalized.contains("experimental option")) {
                return "secure-policy-rejected:" + throwable.getClass().getSimpleName();
            }
        }
        return "failed:" + throwable.getClass().getSimpleName();
    }

    private static final class GuestClasspathFileSystem implements FileSystem {

        private final FileSystem delegate;
        private final Set<Path> readableRoots;
        private final Set<Path> writableFiles;

        private GuestClasspathFileSystem(FileSystem delegate, Set<Path> readableRoots, Set<Path> writableFiles) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            Set<Path> normalizedReadableRoots = new LinkedHashSet<>();
            if (readableRoots != null) {
                for (Path root : readableRoots) {
                    if (root != null) {
                        normalizedReadableRoots.add(root.toAbsolutePath().normalize());
                    }
                }
            }
            Set<Path> normalizedWritableFiles = new LinkedHashSet<>();
            if (writableFiles != null) {
                for (Path path : writableFiles) {
                    if (path != null) {
                        normalizedWritableFiles.add(path.toAbsolutePath().normalize());
                    }
                }
            }
            this.readableRoots = Set.copyOf(normalizedReadableRoots);
            this.writableFiles = Set.copyOf(normalizedWritableFiles);
        }

        @Override
        public Path parsePath(URI uri) {
            return delegate.parsePath(uri);
        }

        @Override
        public Path parsePath(String path) {
            return delegate.parsePath(path);
        }

        @Override
        public void checkAccess(Path path,
                                Set<? extends AccessMode> modes,
                                LinkOption... linkOptions) throws IOException {
            Path normalized = normalize(path);
            if (hasWriteRequest(modes)) {
                ensureWritable(normalized);
            } else {
                ensureReadable(normalized);
            }
            delegate.checkAccess(normalized, modes, linkOptions);
        }

        @Override
        public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
            throw new SecurityException("sandbox-fs-read-only");
        }

        @Override
        public void delete(Path path) throws IOException {
            throw new SecurityException("sandbox-fs-read-only");
        }

        @Override
        public SeekableByteChannel newByteChannel(Path path,
                                                  Set<? extends OpenOption> options,
                                                  FileAttribute<?>... attrs) throws IOException {
            Path normalized = normalize(path);
            if (hasWriteRequest(options)) {
                ensureWritable(normalized);
            } else {
                ensureReadable(normalized);
            }
            return delegate.newByteChannel(normalized, options, attrs);
        }

        @Override
        public DirectoryStream<Path> newDirectoryStream(Path dir,
                                                        DirectoryStream.Filter<? super Path> filter) throws IOException {
            Path normalized = normalize(dir);
            ensureReadable(normalized);
            return delegate.newDirectoryStream(normalized, filter);
        }

        @Override
        public Path toAbsolutePath(Path path) {
            return normalize(path);
        }

        @Override
        public Path toRealPath(Path path, LinkOption... linkOptions) throws IOException {
            Path normalized = normalize(path);
            ensureReadableOrWritable(normalized);
            Path realPath = delegate.toRealPath(normalized, linkOptions).toAbsolutePath().normalize();
            ensureReadableOrWritable(realPath);
            return realPath;
        }

        @Override
        public Map<String, Object> readAttributes(Path path,
                                                  String attributes,
                                                  LinkOption... options) throws IOException {
            Path normalized = normalize(path);
            ensureReadableOrWritable(normalized);
            return delegate.readAttributes(normalized, attributes, options);
        }

        @Override
        public void setAttribute(Path path,
                                 String attribute,
                                 Object value,
                                 LinkOption... options) throws IOException {
            throw new SecurityException("sandbox-fs-read-only");
        }

        @Override
        public void copy(Path source, Path target, java.nio.file.CopyOption... options) throws IOException {
            throw new SecurityException("sandbox-fs-read-only");
        }

        @Override
        public void move(Path source, Path target, java.nio.file.CopyOption... options) throws IOException {
            throw new SecurityException("sandbox-fs-read-only");
        }

        @Override
        public void createLink(Path link, Path existing) throws IOException {
            throw new SecurityException("sandbox-fs-read-only");
        }

        @Override
        public void createSymbolicLink(Path link,
                                       Path target,
                                       FileAttribute<?>... attrs) throws IOException {
            throw new SecurityException("sandbox-fs-read-only");
        }

        private static boolean hasWriteRequest(Set<?> options) {
            if (options == null) {
                return false;
            }
            for (Object option : options) {
                if (!(option instanceof OpenOption openOption)) {
                    if (option instanceof AccessMode accessMode && accessMode != AccessMode.READ) {
                        return true;
                    }
                    continue;
                }
                if (openOption != StandardOpenOption.READ) {
                    return true;
                }
            }
            return false;
        }

        private Path normalize(Path path) {
            return delegate.toAbsolutePath(path).normalize();
        }

        private void ensureReadable(Path path) {
            if (writableFiles.contains(path)) {
                return;
            }
            for (Path root : readableRoots) {
                if (path.startsWith(root)) {
                    return;
                }
            }
            throw new SecurityException("sandbox-fs-denied:" + path);
        }

        private void ensureWritable(Path path) {
            if (writableFiles.contains(path)) {
                return;
            }
            throw new SecurityException("sandbox-fs-read-only");
        }

        private void ensureReadableOrWritable(Path path) {
            if (writableFiles.contains(path)) {
                return;
            }
            ensureReadable(path);
        }
    }

    private record ClassLookupResult(Value classAccessor, String failure) {}

    public record HostStatus(boolean hostAvailable,
                             boolean javaLanguageAvailable,
                             String state) {

        public boolean isReady() {
            return hostAvailable && javaLanguageAvailable && "ready".equals(state);
        }
    }

    public record EspressoExecutionResult(String modId,
                                          String entrypointClass,
                                          String lifecycleMethod,
                                          boolean success,
                                          String message,
                                          String stdout,
                                          String stderr) {}
}
