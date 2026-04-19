package org.intermed.core.classloading;

import org.intermed.core.security.CapabilityManager;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Singleton system-level ClassLoader that is the sole owner of all JNI / JNA
 * native library handles (ТЗ 3.5.1, Requirement 1).
 *
 * <h3>Problem</h3>
 * Each mod node in the DAG creates its own {@link LazyInterMedClassLoader}.
 * The JVM ties native library handles to the ClassLoader that first called
 * {@link System#load} / {@link System#loadLibrary}.  When two DAG nodes
 * both try to load the same {@code .so} / {@code .dll} (e.g. {@code lwjgl},
 * {@code openal}) the second call throws
 * {@link UnsatisfiedLinkError}: "already loaded in another classloader".
 *
 * <h3>Solution</h3>
 * Direct native-load calls from DAG nodes are redirected here by the bytecode
 * transformer. The first call loads the library into the NativeLinkerNode;
 * every subsequent call for the same canonical path is silently skipped
 * (idempotent). ClassLoader {@code findLibrary()} calls only claim ownership
 * and never pre-load, avoiding a second JVM load attempt by the requesting
 * ClassLoader.
 *
 * <h3>Integration</h3>
 * {@link LazyInterMedClassLoader} overrides {@code findLibrary()} and calls
 * {@link #claimResolvedLibrary} without pre-loading, while transformed direct
 * calls use {@link #load(String)} / {@link #loadLibrary(String)}.
 */
public final class NativeLinkerNode extends URLClassLoader {

    private static final NativeLinkerNode INSTANCE = new NativeLinkerNode();

    /** Canonical keys for libraries already owned by the native linker node. */
    private static final ConcurrentHashMap<String, NativeLibraryRecord> CLAIMS_BY_KEY = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, NativeLibraryRecord> CLAIMS_BY_LOGICAL_NAME = new ConcurrentHashMap<>();

    private static final AtomicInteger LOAD_COUNT      = new AtomicInteger();
    private static final AtomicInteger DEDUP_COUNT     = new AtomicInteger();
    private static final AtomicInteger FAILURE_COUNT   = new AtomicInteger();
    private static final AtomicInteger CONFLICT_COUNT  = new AtomicInteger();

    private static volatile NativeLoadOperations LOAD_OPERATIONS = new SystemNativeLoadOperations();

    private NativeLinkerNode() {
        super(new URL[0], ClassLoader.getSystemClassLoader());
    }

    /** Returns the process-wide singleton. */
    public static NativeLinkerNode getInstance() {
        return INSTANCE;
    }

    /**
     * Bytecode-transformer target for {@code System.load(String)} and
     * {@code Runtime.load(String)}.
     */
    public static void load(String absolutePath) {
        INSTANCE.tryLoad(absolutePath, currentRequesterId());
    }

    /**
     * Bytecode-transformer target for {@code System.loadLibrary(String)} and
     * {@code Runtime.loadLibrary(String)}.
     */
    public static void loadLibrary(String libName) {
        INSTANCE.tryLoadByName(libName, currentRequesterId());
    }

    /**
     * Loads a native library by absolute path exactly once across the JVM.
     *
     * @return {@code true} if the library was actually loaded by this call;
     *         {@code false} if it was already loaded (deduplication hit).
     * @throws UnsatisfiedLinkError if the library exists but cannot be linked.
     */
    public boolean tryLoad(String absolutePath) {
        return tryLoad(absolutePath, currentRequesterId());
    }

    public boolean tryLoad(String absolutePath, String requesterId) {
        String canonicalPath = canonicalize(absolutePath);
        String key = "path:" + canonicalPath;
        String logicalName = logicalNameFromPath(canonicalPath);
        NativeLibraryRecord claimed = claim(key, logicalName, canonicalPath, requesterId, true);
        if (claimed == null) {
            DEDUP_COUNT.incrementAndGet();
            return false;
        }
        try {
            LOAD_OPERATIONS.load(canonicalPath);
            LOAD_COUNT.incrementAndGet();
            return true;
        } catch (UnsatisfiedLinkError e) {
            removeClaim(claimed);
            FAILURE_COUNT.incrementAndGet();
            throw e;
        }
    }

    /**
     * Loads a native library by platform name (uses the system library
     * search path, e.g. {@code System.loadLibrary("lwjgl")}).
     *
     * @return {@code true} if actually loaded, {@code false} if deduped.
     */
    public boolean tryLoadByName(String libName) {
        return tryLoadByName(libName, currentRequesterId());
    }

    public boolean tryLoadByName(String libName, String requesterId) {
        String normalizedName = normalizeLibraryName(libName);
        String key = "name:" + normalizedName;
        String logicalName = logicalNameFromLibraryName(normalizedName);
        NativeLibraryRecord claimed = claim(key, logicalName, normalizedName, requesterId, true);
        if (claimed == null) {
            DEDUP_COUNT.incrementAndGet();
            return false;
        }
        try {
            LOAD_OPERATIONS.loadLibrary(libName);
            LOAD_COUNT.incrementAndGet();
            return true;
        } catch (UnsatisfiedLinkError e) {
            removeClaim(claimed);
            FAILURE_COUNT.incrementAndGet();
            throw e;
        }
    }

    /**
     * Records a library path returned from a DAG loader's {@code findLibrary}
     * without loading it. The JVM will perform the actual load for that caller.
     * A second DAG node resolving the same logical library through a different
     * path fails early with a clearer diagnostic instead of racing into the JVM's
     * "already loaded in another classloader" error.
     */
    public String claimResolvedLibrary(String absolutePath, String requesterId) {
        String canonicalPath = canonicalize(absolutePath);
        claim("resolved-path:" + canonicalPath, logicalNameFromPath(canonicalPath), canonicalPath, requesterId, false);
        return canonicalPath;
    }

    /** Returns {@code true} if the given absolute path has already been loaded. */
    public boolean isLoaded(String absolutePath) {
        NativeLibraryRecord record = CLAIMS_BY_KEY.get("path:" + canonicalize(absolutePath));
        return record != null && record.loaded();
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    public int loadCount()    { return LOAD_COUNT.get(); }
    public int dedupCount()   { return DEDUP_COUNT.get(); }
    public int failureCount() { return FAILURE_COUNT.get(); }
    public int conflictCount() { return CONFLICT_COUNT.get(); }

    public List<String> diagnostics() {
        return CLAIMS_BY_KEY.values().stream()
            .sorted(Comparator.comparing(NativeLibraryRecord::logicalName)
                .thenComparing(NativeLibraryRecord::key))
            .map(NativeLibraryRecord::describe)
            .toList();
    }

    static void resetForTests() {
        CLAIMS_BY_KEY.clear();
        CLAIMS_BY_LOGICAL_NAME.clear();
        LOAD_COUNT.set(0);
        DEDUP_COUNT.set(0);
        FAILURE_COUNT.set(0);
        CONFLICT_COUNT.set(0);
        LOAD_OPERATIONS = new SystemNativeLoadOperations();
    }

    static void setLoadOperationsForTests(NativeLoadOperations operations) {
        LOAD_OPERATIONS = operations == null ? new SystemNativeLoadOperations() : operations;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static NativeLibraryRecord claim(String key,
                                             String logicalName,
                                             String origin,
                                             String requesterId,
                                             boolean loaded) {
        NativeLibraryRecord candidate = new NativeLibraryRecord(
            key,
            logicalName,
            origin,
            normalizeRequester(requesterId),
            loaded,
            System.nanoTime()
        );
        NativeLibraryRecord existing = CLAIMS_BY_KEY.putIfAbsent(key, candidate);
        if (existing != null) {
            return null;
        }
        NativeLibraryRecord logicalOwner = CLAIMS_BY_LOGICAL_NAME.putIfAbsent(logicalName, candidate);
        if (logicalOwner == null || logicalOwner.key().equals(key)) {
            return candidate;
        }
        CLAIMS_BY_KEY.remove(key, candidate);
        CONFLICT_COUNT.incrementAndGet();
        throw conflict(logicalOwner, candidate);
    }

    private static void removeClaim(NativeLibraryRecord record) {
        CLAIMS_BY_KEY.remove(record.key(), record);
        CLAIMS_BY_LOGICAL_NAME.remove(record.logicalName(), record);
    }

    private static UnsatisfiedLinkError conflict(NativeLibraryRecord existing, NativeLibraryRecord incoming) {
        return new UnsatisfiedLinkError(
            "Native library logical name conflict for '" + incoming.logicalName() + "': existing "
                + existing.describe() + ", incoming " + incoming.describe()
        );
    }

    private static String canonicalize(String path) {
        try {
            return new File(path).getCanonicalPath();
        } catch (Exception ignored) {
            return path;
        }
    }

    private static String logicalNameFromPath(String path) {
        String fileName = new File(path).getName();
        if (fileName == null || fileName.isBlank()) {
            return normalizeLibraryName(path);
        }
        return normalizeLibraryName(fileName);
    }

    private static String logicalNameFromLibraryName(String libName) {
        return normalizeLibraryName(System.mapLibraryName(libName));
    }

    private static String normalizeLibraryName(String value) {
        if (value == null || value.isBlank()) {
            return "<unknown-native>";
        }
        return value.trim().replace('\\', '/').toLowerCase();
    }

    private static String normalizeRequester(String requesterId) {
        return requesterId == null || requesterId.isBlank() ? "<unknown>" : requesterId.trim();
    }

    private static String currentRequesterId() {
        return CapabilityManager.currentModIdOr("<unknown>");
    }

    interface NativeLoadOperations {
        void load(String absolutePath);

        void loadLibrary(String libName);
    }

    private static final class SystemNativeLoadOperations implements NativeLoadOperations {
        @Override
        public void load(String absolutePath) {
            System.load(absolutePath);
        }

        @Override
        public void loadLibrary(String libName) {
            System.loadLibrary(libName);
        }
    }

    private record NativeLibraryRecord(String key,
                                       String logicalName,
                                       String origin,
                                       String requesterId,
                                       boolean loaded,
                                       long createdAtNanos) {
        private String describe() {
            return "key=" + key
                + ", logicalName=" + logicalName
                + ", origin=" + origin
                + ", requester=" + requesterId
                + ", state=" + (loaded ? "loaded" : "resolved");
        }
    }
}
