package org.intermed.core.security;

import org.intermed.core.metadata.RuntimeModIndex;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.OpenOption;
import java.nio.file.StandardOpenOption;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.SocketAddress;
import java.net.URL;
import java.net.URLConnection;
import java.net.http.HttpRequest;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Capability-based security enforcer with StackWalker-based caller resolution and
 * per-mod decision caching.
 */
public final class CapabilityManager {

    private static final StackWalker WALKER =
        StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static final ThreadLocal<String> FORCED_MOD_CONTEXT = new ThreadLocal<>();
    private static final ConcurrentHashMap<ClassLoader, String> CALLER_MOD_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Path, String> CODE_SOURCE_MOD_CACHE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<DecisionKey, Boolean> DECISION_CACHE = new ConcurrentHashMap<>();
    private static final String UNKNOWN_CALLER_ID = "<unknown>";

    /**
     * Maximum number of entries kept in {@link #DECISION_CACHE}.
     * The key includes the exact file path, so without a cap the cache grows
     * without bound as the game accesses thousands of unique asset paths.
     * When the limit is reached the cache is fully cleared (cheap, lock-free
     * re-population on the next access).
     */
    private static final int DECISION_CACHE_MAX = 20_000;

    private CapabilityManager() {}

    public static void executeAsMod(String modId, Runnable action) {
        Objects.requireNonNull(action, "action");
        executeAsMod(modId, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T executeAsMod(String modId, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        String previous = FORCED_MOD_CONTEXT.get();
        boolean replace = modId != null && !modId.isBlank();
        if (replace) {
            FORCED_MOD_CONTEXT.set(modId.trim());
        }
        try {
            return action.get();
        } finally {
            if (replace) {
                if (previous == null || previous.isBlank()) {
                    FORCED_MOD_CONTEXT.remove();
                } else {
                    FORCED_MOD_CONTEXT.set(previous);
                }
            }
        }
    }

    public static void checkPermission(Capability capability) {
        check(SecurityPolicy.SecurityRequest.capabilityOnly(capability));
    }

    public static void checkFileRead(Path path) {
        check(SecurityPolicy.SecurityRequest.fileRead(path));
    }

    public static void checkFileReadTarget(Object target) {
        Path path = toPath(target);
        if (path != null) {
            checkFileRead(path);
        }
    }

    public static void checkFileWrite(Path path) {
        check(SecurityPolicy.SecurityRequest.fileWrite(path));
    }

    public static void checkFileWriteTarget(Object target) {
        Path path = toPath(target);
        if (path != null) {
            checkFileWrite(path);
        }
    }

    public static void checkNetworkHost(String host) {
        check(SecurityPolicy.SecurityRequest.network(host));
    }

    public static void checkNetworkTarget(Object target) {
        if (target instanceof URL url) {
            checkNetworkHost(url.getHost());
            return;
        }
        if (target instanceof URLConnection connection) {
            URL url = connection.getURL();
            if (url != null) {
                checkNetworkHost(url.getHost());
            }
            return;
        }
        if (target instanceof URI uri) {
            if (uri.getHost() != null && !uri.getHost().isBlank()) {
                checkNetworkHost(uri.getHost());
            }
            return;
        }
        if (target instanceof HttpRequest request) {
            URI uri = request.uri();
            if (uri != null && uri.getHost() != null && !uri.getHost().isBlank()) {
                checkNetworkHost(uri.getHost());
            }
            return;
        }
        if (target instanceof SocketAddress socketAddress) {
            String host = extractHost(socketAddress);
            if (host != null) {
                checkNetworkHost(host);
            }
            return;
        }
        if (target instanceof String host && !host.isBlank()) {
            checkNetworkHost(host);
        }
    }

    public static void checkReflectionAccess() {
        checkReflectionAccess("AccessibleObject.setAccessible");
    }

    public static void checkReflectionAccess(String detail) {
        check(SecurityPolicy.SecurityRequest.reflection(normalizeDetail("reflection", detail)));
    }

    public static void checkUnsafeOperation(String memberName) {
        checkMemoryOperation(qualifyDetail("Unsafe", memberName));
    }

    public static void checkMemoryOperation(String detail) {
        check(SecurityPolicy.SecurityRequest.memory(normalizeDetail("memory", detail)));
    }

    public static void checkVarHandleOperation(String memberName) {
        checkMemoryOperation(qualifyDetail("VarHandle", memberName));
    }

    public static void checkVarHandleLookup(String operation) {
        checkMemoryOperation(qualifyDetail("MethodHandles.Lookup", operation));
    }

    public static void checkDynamicClassDefinition(String operation) {
        checkReflectionAccess(qualifyDetail("MethodHandles.Lookup", operation));
    }

    public static void checkForeignMemoryAccess(String operation) {
        checkMemoryOperation(normalizeDetail("ffm", operation));
    }

    public static void checkForeignLinkerOperation(String operation) {
        checkForeignLinkerOperation(operation, null);
    }

    public static void checkForeignLinkerOperation(String operation, Object target) {
        String detail = formatDetail(operation, target);
        check(SecurityPolicy.SecurityRequest.nativeLibrary(detail));
        checkMemoryOperation(detail);
    }

    public static void checkNativeLibraryAccess() {
        checkNativeLibraryOperation("native-library", null);
    }

    public static void checkNativeLibraryTarget(Object target) {
        checkNativeLibraryOperation("native-library", target);
    }

    public static void checkNativeLibraryOperation(String operation, Object target) {
        check(SecurityPolicy.SecurityRequest.nativeLibrary(formatDetail(operation, target)));
    }

    public static void checkRandomAccess(String path, String mode) {
        checkRandomAccessTarget(path, mode);
    }

    public static void checkRandomAccessTarget(Object target, String mode) {
        if (target == null || mode == null) {
            return;
        }
        if (mode.contains("w")) {
            checkFileWriteTarget(target);
        } else {
            checkFileReadTarget(target);
        }
    }

    public static void checkFileChannel(Path path, Object optionsSpec) {
        if (path == null) {
            return;
        }
        if (requiresWriteAccess(optionsSpec)) {
            checkFileWrite(path);
        } else {
            checkFileRead(path);
        }
    }

    public static void checkFileCopyOrMove(Path source, Path target) {
        if (source != null) {
            checkFileRead(source);
        }
        if (target != null) {
            checkFileWrite(target);
        }
    }

    public static void checkFileTransfer(Object source, Object target) {
        Path sourcePath = toPath(source);
        Path targetPath = toPath(target);
        if (sourcePath != null) {
            checkFileRead(sourcePath);
        }
        if (targetPath != null) {
            checkFileWrite(targetPath);
        }
    }

    public static void checkTempFileTarget(Object directoryHint) {
        Path directory = toExplicitPath(directoryHint);
        if (directory == null) {
            String tmpDir = System.getProperty("java.io.tmpdir");
            if (tmpDir == null || tmpDir.isBlank()) {
                checkFileWrite(Paths.get("."));
                return;
            }
            directory = Paths.get(tmpDir);
        }
        checkFileWrite(directory);
    }

    public static String currentModId() {
        return resolveCallerModId();
    }

    public static String currentModIdOr(String fallback) {
        String modId = resolveCallerModId();
        return modId != null ? modId : fallback;
    }

    public static void invalidateMod(String modId) {
        DECISION_CACHE.keySet().removeIf(key -> Objects.equals(key.modId(), modId));
        CALLER_MOD_CACHE.entrySet().removeIf(entry -> Objects.equals(entry.getValue(), modId));
        CODE_SOURCE_MOD_CACHE.entrySet().removeIf(entry -> Objects.equals(entry.getValue(), modId));
    }

    public static void invalidateDecisionCache() {
        DECISION_CACHE.clear();
    }

    public static void resetForTests() {
        invalidateDecisionCache();
        CALLER_MOD_CACHE.clear();
        CODE_SOURCE_MOD_CACHE.clear();
        FORCED_MOD_CONTEXT.remove();
    }

    static String resolveCallerModId() {
        String forced = FORCED_MOD_CONTEXT.get();
        if (forced != null && !forced.isBlank()) {
            return forced;
        }
        Optional<String> modId = WALKER.walk(frames ->
            frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .map(CapabilityManager::resolveModIdFromClass)
                .filter(Objects::nonNull)
                .findFirst()
        );
        if (modId.isPresent()) {
            return modId.get();
        }
        return resolveModIdFromLoader(Thread.currentThread().getContextClassLoader());
    }

    private static void check(SecurityPolicy.SecurityRequest request) {
        String modId = resolveCallerModId();
        if (modId == null) {
            if (SecurityPolicy.hasPermission(null, request) || isTrustedHostCall()) {
                return;
            }
            throw denied(UNKNOWN_CALLER_ID, request, "unattributed caller outside trusted host stack");
        }
        DecisionKey key = DecisionKey.of(modId, request);
        // Prevent unbounded growth: the key includes the exact file path, so
        // one entry per unique asset path accumulates to hundreds of thousands
        // of entries over a play session, eventually triggering OOM.
        if (DECISION_CACHE.size() > DECISION_CACHE_MAX) {
            DECISION_CACHE.clear();
        }
        boolean allowed = DECISION_CACHE.computeIfAbsent(key, ignored -> SecurityPolicy.hasPermission(modId, request));
        if (!allowed) {
            throw denied(modId, request, null);
        }
    }

    private static SecurityException denied(String subject,
                                            SecurityPolicy.SecurityRequest request,
                                            String reason) {
        String suffix = reason == null || reason.isBlank() ? "" : " reason=" + reason;
        return new SecurityException(
            "\033[1;31m[InterMed Security] Mod '" + subject + "' DENIED " + request.capability()
                + " [path=" + request.path()
                + ", host=" + request.host()
                + ", detail=" + request.detail()
                + suffix + "]\033[0m"
        );
    }

    private static boolean isTrustedHostCall() {
        if (KernelContext.isActive()) {
            return true;
        }
        return WALKER.walk(frames -> {
            boolean sawTrustedHostFrame = false;
            var iterator = frames.iterator();
            while (iterator.hasNext()) {
                String name = iterator.next().getDeclaringClass().getName();
                if (isIgnoredStackFrame(name) || isTrustedHostLibraryClass(name)) {
                    continue;
                }
                if (isTrustedHostClass(name)) {
                    sawTrustedHostFrame = true;
                    continue;
                }
                return false;
            }
            return sawTrustedHostFrame;
        });
    }

    private static boolean isIgnoredStackFrame(String className) {
        return className.startsWith("java.")
            || className.startsWith("jdk.")
            || className.startsWith("sun.")
            || className.startsWith("net.bytebuddy.");
    }

    private static boolean isTrustedHostLibraryClass(String className) {
        return className.startsWith("com.google.")
            || className.startsWith("com.llamalad7.")
            || className.startsWith("io.netty.")
            || className.startsWith("it.unimi.")
            || className.startsWith("org.apache.")
            || className.startsWith("org.gradle.")
            || className.startsWith("org.junit.")
            || className.startsWith("org.objectweb.asm.")
            || className.startsWith("org.opentest4j.")
            || className.startsWith("org.spongepowered.")
            || className.startsWith("org.slf4j.")
            || className.startsWith("worker.org.gradle.");
    }

    private static boolean isTrustedHostClass(String className) {
        return className.startsWith("org.intermed.")
            || className.startsWith("net.minecraft.")
            || className.startsWith("net.minecraftforge.")
            || className.startsWith("cpw.mods.")
            || className.startsWith("com.mojang.")
            || className.startsWith("net.fabricmc.");
    }

    private static String resolveModIdFromLoader(ClassLoader classLoader) {
        if (classLoader == null) {
            return null;
        }
        return CALLER_MOD_CACHE.computeIfAbsent(classLoader, loader -> {
            if (loader == null || !loader.getClass().getName().startsWith("org.intermed.core.classloading.")) {
                return null;
            }
            try {
                Method getNodeId = loader.getClass().getMethod("getNodeId");
                Object value = getNodeId.invoke(loader);
                return value instanceof String modId && !modId.isBlank() ? modId : null;
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        });
    }

    private static String resolveModIdFromClass(Class<?> declaringClass) {
        if (declaringClass == null) {
            return null;
        }
        String viaLoader = resolveModIdFromLoader(declaringClass.getClassLoader());
        if (viaLoader != null) {
            return viaLoader;
        }
        return resolveModIdFromCodeSource(declaringClass);
    }

    private static String resolveModIdFromCodeSource(Class<?> declaringClass) {
        Path codeSourcePath = codeSourcePath(declaringClass);
        if (codeSourcePath == null) {
            return null;
        }
        String cached = CODE_SOURCE_MOD_CACHE.get(codeSourcePath);
        if (cached != null) {
            return cached;
        }
        String resolved = RuntimeModIndex.allMods().stream()
            .filter(metadata -> metadata.sourceJar() != null)
            .filter(metadata -> codeSourcePath.equals(
                metadata.sourceJar().toPath().toAbsolutePath().normalize()))
            .map(metadata -> metadata.id())
            .findFirst()
            .orElse(null);
        if (resolved != null) {
            CODE_SOURCE_MOD_CACHE.putIfAbsent(codeSourcePath, resolved);
        }
        return resolved;
    }

    private static Path toPath(Object target) {
        if (target instanceof Path path) {
            return path;
        }
        if (target instanceof File file) {
            return file.toPath();
        }
        if (target instanceof String stringPath && !stringPath.isBlank()) {
            return Paths.get(stringPath);
        }
        if (target instanceof URI uri && "file".equalsIgnoreCase(uri.getScheme())) {
            try {
                return Path.of(uri);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean requiresWriteAccess(Object optionsSpec) {
        if (optionsSpec == null) {
            return false;
        }
        if (optionsSpec instanceof OpenOption option) {
            return isWriteOption(option);
        }
        if (optionsSpec instanceof OpenOption[] options) {
            for (OpenOption option : options) {
                if (isWriteOption(option)) {
                    return true;
                }
            }
            return false;
        }
        if (optionsSpec instanceof Collection<?> options) {
            for (Object option : options) {
                if (option instanceof OpenOption openOption && isWriteOption(openOption)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private static boolean isWriteOption(OpenOption option) {
        return option == StandardOpenOption.WRITE
            || option == StandardOpenOption.APPEND
            || option == StandardOpenOption.CREATE
            || option == StandardOpenOption.CREATE_NEW
            || option == StandardOpenOption.DELETE_ON_CLOSE
            || option == StandardOpenOption.TRUNCATE_EXISTING
            || option == StandardOpenOption.SYNC
            || option == StandardOpenOption.DSYNC;
    }

    private static String extractHost(SocketAddress address) {
        if (address == null) {
            return null;
        }
        if (address instanceof java.net.InetSocketAddress inetSocketAddress) {
            return inetSocketAddress.getHostString();
        }
        return address.toString();
    }

    private static Path codeSourcePath(Class<?> declaringClass) {
        if (declaringClass == null
            || declaringClass.getProtectionDomain() == null
            || declaringClass.getProtectionDomain().getCodeSource() == null
            || declaringClass.getProtectionDomain().getCodeSource().getLocation() == null) {
            return null;
        }
        try {
            URI uri = declaringClass.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Path.of(uri).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Path toExplicitPath(Object target) {
        if (target instanceof Path path) {
            return path;
        }
        if (target instanceof File file) {
            return file.toPath();
        }
        if (target instanceof String stringPath && looksLikePath(stringPath)) {
            try {
                return Paths.get(stringPath);
            } catch (InvalidPathException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String qualifyDetail(String prefix, String detail) {
        String normalizedDetail = normalizeDetail("", detail);
        if (normalizedDetail == null || normalizedDetail.isBlank()) {
            return prefix;
        }
        if (normalizedDetail.startsWith(prefix + ".")) {
            return normalizedDetail;
        }
        return prefix + "." + normalizedDetail;
    }

    private static String formatDetail(String operation, Object target) {
        String normalizedOperation = normalizeDetail("operation", operation);
        String targetDetail = targetDetail(target);
        if (targetDetail == null || targetDetail.isBlank()) {
            return normalizedOperation;
        }
        return normalizedOperation + ":" + targetDetail;
    }

    private static String normalizeDetail(String fallback, String detail) {
        if (detail == null || detail.isBlank()) {
            return fallback;
        }
        return detail.trim();
    }

    private static String targetDetail(Object target) {
        if (target == null) {
            return null;
        }
        Path path = toPath(target);
        if (path != null) {
            return path.toAbsolutePath().normalize().toString();
        }
        if (target instanceof URI uri) {
            return uri.toString();
        }
        if (target instanceof CharSequence sequence) {
            String value = sequence.toString().trim();
            return value.isEmpty() ? null : value;
        }
        return target.getClass().getName();
    }

    private record DecisionKey(String modId, Capability capability, String path, String host, String detail) {
        static DecisionKey of(String modId, SecurityPolicy.SecurityRequest request) {
            return new DecisionKey(
                modId,
                request.capability(),
                request.path() == null ? null : request.path().toString(),
                request.host(),
                request.detail()
            );
        }
    }

    private static boolean looksLikePath(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.contains("/")
            || value.contains("\\")
            || value.startsWith(".")
            || value.startsWith("~")
            || value.matches("^[A-Za-z]:.*");
    }
}
