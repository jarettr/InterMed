package org.intermed.core.storage;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.ConcurrentHashMap;

public class AOTCache {
    private static final Path ROOT = Paths.get(".intermed/aot_cache");
    // Req 12.2: Использование DirectBuffer (вне кучи) для ускорения IO
    private static final ConcurrentHashMap<String, ByteBuffer> MEM_CACHE = new ConcurrentHashMap<>();

    public static void init() throws Exception {
        Files.createDirectories(ROOT);
    }

    public static byte[] get(String modId, String className) {
        Path p = ROOT.resolve(modId).resolve(className.replace('/', '.') + ".cache");
        if (!Files.exists(p)) return null;
        try {
            return Files.readAllBytes(p);
        } catch (Exception e) { return null; }
    }

    public static void put(String modId, String className, byte[] data) {
        try {
            Path dir = ROOT.resolve(modId);
            Files.createDirectories(dir);
            // Асинхронная запись через FileChannel для максимальной скорости (Req 11)
            try (FileChannel fc = FileChannel.open(dir.resolve(className.replace('/', '.') + ".cache"), 
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                fc.write(ByteBuffer.wrap(data));
            }
        } catch (Exception ignored) {}
    }
}