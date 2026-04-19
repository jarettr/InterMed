package org.intermed.core.cache;

import org.intermed.core.config.RuntimeConfig;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class AOTCacheManager {

    private static final String CACHE_FORMAT_VERSION = "4";
    private static final Path CACHE_ROOT = Paths.get(System.getProperty("user.home"), ".intermed", "aot_v8");
    private static final Path BYTECODE_DIR = CACHE_ROOT.resolve("bytecode");
    private static final Path METADATA_DIR = CACHE_ROOT.resolve("metadata");

    // Быстрый in-memory индекс, чтобы не дергать диск (IO) зря
    private static final ConcurrentHashMap<String, Boolean> CACHE_INDEX = new ConcurrentHashMap<>();
    private static final AtomicLong CACHE_HITS = new AtomicLong();
    private static final AtomicLong CACHE_MISSES = new AtomicLong();
    private static final AtomicLong CACHE_SAVES = new AtomicLong();

    private AOTCacheManager() {}

    public static void initialize() {
        if (!RuntimeConfig.get().isAotCacheEnabled()) {
            CACHE_INDEX.clear();
            return;
        }
        try {
            Files.createDirectories(BYTECODE_DIR);
            Files.createDirectories(METADATA_DIR);
            System.out.println("[AOT Cache] Дисковая подсистема инициализирована: " + CACHE_ROOT);
        } catch (Exception e) {
            System.err.println("[AOT Cache] ОШИБКА инициализации I/O: " + e.getMessage());
        }
    }

    public static byte[] getCachedClass(String className, String inputHash) {
        CachedClass cachedClass = getCachedEntry(className, inputHash);
        return cachedClass != null ? cachedClass.bytecode() : null;
    }

    public static CachedClass getCachedEntry(String className, String inputHash) {
        if (!RuntimeConfig.get().isAotCacheEnabled()) {
            return null;
        }
        String key = generateKey(className, inputHash);

        if (Boolean.FALSE.equals(CACHE_INDEX.get(key))) {
            CACHE_MISSES.incrementAndGet();
            return null;
        }

        Path classFile = classFileForKey(key);
        Path metadataFile = metadataFileForKey(key);

        try {
            if (!Files.exists(classFile) || !Files.exists(metadataFile)) {
                CACHE_MISSES.incrementAndGet();
                invalidateEntry(key, classFile, metadataFile);
                return null;
            }

            CacheMetadata metadata = readMetadata(metadataFile);
            if (!metadata.matches(className, inputHash)) {
                CACHE_MISSES.incrementAndGet();
                invalidateEntry(key, classFile, metadataFile);
                return null;
            }

            byte[] bytecode = Files.readAllBytes(classFile);
            if (!metadata.transformedHash().equals(sha256(bytecode))) {
                CACHE_MISSES.incrementAndGet();
                invalidateEntry(key, classFile, metadataFile);
                return null;
            }

            CACHE_INDEX.put(key, true);
            CACHE_HITS.incrementAndGet();
            return new CachedClass(bytecode, metadata);
        } catch (Exception e) {
            System.err.println("[AOT Cache] Ошибка чтения: " + className + " - " + e.getMessage());
            CACHE_MISSES.incrementAndGet();
            invalidateEntry(key, classFile, metadataFile);
            return null;
        }
    }

    public static void saveToCache(String className, String inputHash, byte[] transformedBytes) {
        saveToCache(className, inputHash, transformedBytes, Map.of());
    }

    public static void saveToCache(String className,
                                   String inputHash,
                                   byte[] transformedBytes,
                                   Map<String, String> extraMetadata) {
        if (!RuntimeConfig.get().isAotCacheEnabled()) {
            return;
        }
        Objects.requireNonNull(transformedBytes, "transformedBytes");

        String key = generateKey(className, inputHash);
        Path classFile = classFileForKey(key);
        Path metadataFile = metadataFileForKey(key);

        try {
            Files.createDirectories(BYTECODE_DIR);
            Files.createDirectories(METADATA_DIR);

            Path tempClassFile = Files.createTempFile(BYTECODE_DIR, key, ".tmp");
            Files.write(tempClassFile, transformedBytes);
            Files.move(tempClassFile, classFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            CacheMetadata metadata = new CacheMetadata(
                CACHE_FORMAT_VERSION,
                className,
                inputHash,
                sha256(transformedBytes),
                transformedBytes.length,
                Instant.now().toEpochMilli(),
                sanitizeExtraMetadata(extraMetadata)
            );
            writeMetadata(metadataFile, metadata);
            pruneSiblingEntries(className, key);
            CACHE_INDEX.put(key, true);
            CACHE_SAVES.incrementAndGet();
        } catch (Exception e) {
            System.err.println("[AOT Cache] Ошибка записи в кэш: " + className + " - " + e.getMessage());
            invalidateEntry(key, classFile, metadataFile);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            return Integer.toHexString(java.util.Arrays.hashCode(bytes));
        }
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String runtimeComponentFingerprint(Class<?>... components) {
        if (components == null || components.length == 0) {
            return sha256("no-components");
        }
        StringBuilder descriptor = new StringBuilder();
        for (Class<?> component : components) {
            if (component == null) {
                continue;
            }
            descriptor.append(component.getName())
                .append('=')
                .append(componentFingerprint(component))
                .append('|');
        }
        return sha256(descriptor.toString());
    }

    static void resetForTests() {
        CACHE_INDEX.clear();
        CACHE_HITS.set(0);
        CACHE_MISSES.set(0);
        CACHE_SAVES.set(0);
    }

    static Path metadataPathForTesting(String className, String inputHash) {
        return metadataFileForKey(generateKey(className, inputHash));
    }

    static Path classPathForTesting(String className, String inputHash) {
        return classFileForKey(generateKey(className, inputHash));
    }

    static long cacheHitCountForTests() {
        return CACHE_HITS.get();
    }

    static long cacheMissCountForTests() {
        return CACHE_MISSES.get();
    }

    static long cacheSaveCountForTests() {
        return CACHE_SAVES.get();
    }

    private static Map<String, String> sanitizeExtraMetadata(Map<String, String> extraMetadata) {
        if (extraMetadata == null || extraMetadata.isEmpty()) {
            return Map.of();
        }
        Map<String, String> sanitized = new LinkedHashMap<>();
        extraMetadata.forEach((key, value) -> {
            if (key == null || key.isBlank() || value == null) {
                return;
            }
            sanitized.put(key.trim(), value);
        });
        return Collections.unmodifiableMap(sanitized);
    }

    private static void writeMetadata(Path metadataFile, CacheMetadata metadata) throws Exception {
        Path tempMetadataFile = Files.createTempFile(METADATA_DIR, metadataFile.getFileName().toString(), ".tmp");
        Properties properties = new Properties();
        properties.setProperty("formatVersion", metadata.formatVersion());
        properties.setProperty("className", metadata.className());
        properties.setProperty("inputHash", metadata.inputHash());
        properties.setProperty("transformedHash", metadata.transformedHash());
        properties.setProperty("byteLength", Integer.toString(metadata.byteLength()));
        properties.setProperty("createdAtEpochMs", Long.toString(metadata.createdAtEpochMs()));
        metadata.extraMetadata().forEach((key, value) -> properties.setProperty("extra." + key, value));

        try (OutputStream output = Files.newOutputStream(tempMetadataFile)) {
            properties.store(output, "InterMed AOT metadata");
        }

        Files.move(tempMetadataFile, metadataFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static CacheMetadata readMetadata(Path metadataFile) throws Exception {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(metadataFile)) {
            properties.load(input);
        }
        Map<String, String> extraMetadata = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("extra.")) {
                extraMetadata.put(key.substring("extra.".length()), properties.getProperty(key, ""));
            }
        }
        return new CacheMetadata(
            properties.getProperty("formatVersion", ""),
            properties.getProperty("className", ""),
            properties.getProperty("inputHash", ""),
            properties.getProperty("transformedHash", ""),
            Integer.parseInt(properties.getProperty("byteLength", "0")),
            Long.parseLong(properties.getProperty("createdAtEpochMs", "0")),
            Collections.unmodifiableMap(extraMetadata)
        );
    }

    private static void invalidateEntry(String key, Path classFile, Path metadataFile) {
        tryDelete(classFile);
        tryDelete(metadataFile);
        CACHE_INDEX.put(key, false);
    }

    private static void pruneSiblingEntries(String className, String keepKey) {
        try {
            if (!Files.isDirectory(METADATA_DIR)) {
                return;
            }
            try (var paths = Files.list(METADATA_DIR)) {
                for (Path metadataPath : paths.filter(path -> path.getFileName().toString().endsWith(".properties")).toList()) {
                    String fileName = metadataPath.getFileName().toString();
                    String key = fileName.substring(0, fileName.length() - ".properties".length());
                    if (keepKey.equals(key)) {
                        continue;
                    }
                    try {
                        CacheMetadata metadata = readMetadata(metadataPath);
                        if (className.equals(metadata.className())) {
                            invalidateEntry(key, classFileForKey(key), metadataPath);
                        }
                    } catch (Exception ignored) {
                        invalidateEntry(key, classFileForKey(key), metadataPath);
                    }
                }
            }
        } catch (Exception ignored) {
            // Best-effort pruning only.
        }
    }

    private static void tryDelete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
            // Best-effort cleanup only.
        }
    }

    private static Path classFileForKey(String key) {
        return BYTECODE_DIR.resolve(key + ".class");
    }

    private static Path metadataFileForKey(String key) {
        return METADATA_DIR.resolve(key + ".properties");
    }

    private static String generateKey(String className, String hash) {
        return sha256(className + ":" + hash);
    }

    private static String componentFingerprint(Class<?> component) {
        Package pkg = component.getPackage();
        String implementationVersion = pkg != null ? pkg.getImplementationVersion() : null;
        if (implementationVersion != null && !implementationVersion.isBlank()) {
            return implementationVersion;
        }

        String resourceName = component.getSimpleName() + ".class";
        try (InputStream input = component.getResourceAsStream(resourceName)) {
            if (input == null) {
                return component.getName();
            }
            return sha256(input.readAllBytes());
        } catch (Exception e) {
            return component.getName();
        }
    }

    public record CachedClass(byte[] bytecode, CacheMetadata metadata) {}

    public record CacheMetadata(String formatVersion,
                                String className,
                                String inputHash,
                                String transformedHash,
                                int byteLength,
                                long createdAtEpochMs,
                                Map<String, String> extraMetadata) {
        boolean matches(String requestedClassName, String requestedInputHash) {
            return CACHE_FORMAT_VERSION.equals(formatVersion)
                && className.equals(requestedClassName)
                && inputHash.equals(requestedInputHash)
                && byteLength > 0
                && createdAtEpochMs > 0;
        }
    }
}
