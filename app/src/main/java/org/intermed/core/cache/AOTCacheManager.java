package org.intermed.core.cache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

public class AOTCacheManager {

    private static final Path CACHE_ROOT = Paths.get(System.getProperty("user.home"), ".intermed", "aot_v8");
    private static final Path BYTECODE_DIR = CACHE_ROOT.resolve("bytecode");
    private static final Path METADATA_DIR = CACHE_ROOT.resolve("metadata");

    // Быстрый in-memory индекс, чтобы не дергать диск (IO) зря
    private static final ConcurrentHashMap<String, Boolean> CACHE_INDEX = new ConcurrentHashMap<>();

    public static void initialize() {
        try {
            Files.createDirectories(BYTECODE_DIR);
            Files.createDirectories(METADATA_DIR);
            System.out.println("[AOT Cache] Дисковая подсистема инициализирована: " + CACHE_ROOT);
        } catch (Exception e) {
            System.err.println("[AOT Cache] ОШИБКА инициализации I/O: " + e.getMessage());
        }
    }

    public static byte[] getCachedClass(String className, String mixinHash) {
        String key = generateKey(className, mixinHash);
        
        // Fast-path: если мы знаем, что файла нет, даже не обращаемся к диску
        if (CACHE_INDEX.containsKey(key) && !CACHE_INDEX.get(key)) return null;

        try {
            Path classFile = BYTECODE_DIR.resolve(key + ".class");
            if (Files.exists(classFile)) {
                CACHE_INDEX.put(key, true);
                return Files.readAllBytes(classFile);
            }
        } catch (Exception e) {
            System.err.println("[AOT Cache] Ошибка чтения: " + className);
        }
        
        CACHE_INDEX.put(key, false);
        return null;
    }

    public static void saveToCache(String className, String mixinHash, byte[] transformedBytes) {
        String key = generateKey(className, mixinHash);
        try {
            Path classFile = BYTECODE_DIR.resolve(key + ".class");
            Files.write(classFile, transformedBytes);
            CACHE_INDEX.put(key, true);
        } catch (Exception e) {
            System.err.println("[AOT Cache] Ошибка записи в кэш: " + className);
        }
    }

    // SHA-256 хеширование ключей для избежания коллизий имен файлов Windows/Linux
    private static String generateKey(String className, String hash) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest((className + ":" + hash).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            return Integer.toHexString((className + hash).hashCode()); // Безопасный фоллбэк
        }
    }
}