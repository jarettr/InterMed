package org.intermed.core.util;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MappingManager {
    // Хранит цепочку: net/minecraft/class_123 -> net/minecraft/world/entity/player/Player
    private static final Map<String, String> classes = new ConcurrentHashMap<>();

    public static void loadMappings() {
        System.out.println("\033[1;34m[Mappings] Starting Atomic Fusion Engine (Dynamic Hybrid Mode)...\033[0m");
        
        File mappingsDir = findMappingsDirectory();
        
        if (mappingsDir == null || !mappingsDir.exists()) {
            System.err.println("\033[1;31m[Mappings] КРИТИЧЕСКАЯ ОШИБКА: Папка 'mappings' не найдена!\033[0m");
            return;
        }

        File tinyFile = new File(mappingsDir, "mappings.tiny");
        File mojMapFile = new File(mappingsDir, "client.txt");

        System.out.println("[Mappings] Loading from: " + mappingsDir.getAbsolutePath());

        if (!tinyFile.exists() || !mojMapFile.exists()) {
            System.err.println("\033[1;31m[Mappings] ОШИБКА: Не хватает файлов mappings.tiny или client.txt!\033[0m");
            return;
        }

        try {
            // 1. Mojang Mappings (Official -> MojMap)
            // Формат: net.minecraft.world.entity.player.Player -> a:
            Map<String, String> offToMojClass = new HashMap<>(35000);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(mojMapFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#") || line.startsWith(" ")) continue;
                    if (line.contains(" -> ")) {
                        String[] parts = line.split(" -> ");
                        String moj = parts[0].replace('.', '/');
                        String off = parts[1].replace(':', ' ').trim().replace('.', '/');
                        offToMojClass.put(off, moj);
                    }
                }
            }

            // 2. Tiny Fusion (Intermediary -> MojMap)
            int count = 0;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(tinyFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Поддержка Tiny v1 (CLASS) и v2 (c)
                    if (line.startsWith("CLASS\t") || line.startsWith("c\t")) {
                        String[] parts = line.split("\t");
                        if (parts.length >= 3) {
                            String off = parts[1];
                            String inter = parts[2];
                            
                            String finalName = offToMojClass.get(off);
                            if (finalName != null) {
                                // КРИТИЧЕСКИЙ ФИКС: Если в tiny имя без префикса, добавляем его
                                if (inter.startsWith("class_")) {
                                    String fullInter = "net/minecraft/" + inter;
                                    classes.put(fullInter, finalName);
                                }
                                
                                // Сохраняем имя как есть на случай, если оно уже полное
                                classes.put(inter, finalName);
                                count++;
                            }
                        }
                    }
                }
            }
            
            // Ручной мост для критических классов (JEI / AppleSkin)
            classes.put("net/minecraft/class_1657", "net/minecraft/world/entity/player/Player");
            classes.put("net/minecraft/class_310", "net/minecraft/client/Minecraft");
            
            System.out.println("\033[1;32m[Mappings] Success! Fusion complete. " + count + " classes indexed.\033[0m");

        } catch (Exception e) {
            System.err.println("[Mappings] Ошибка парсинга: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static File findMappingsDirectory() {
        // Проверяем папку игры (AppData/Roaming/.minecraft/mappings)
        File dir1 = new File("mappings");
        if (dir1.exists()) return dir1;

        // Проверяем папку рядом с JAR агента
        try {
            java.security.CodeSource cs = MappingManager.class.getProtectionDomain().getCodeSource();
            if (cs != null) {
                File agentDir = new File(cs.getLocation().toURI()).getParentFile();
                File dir2 = new File(agentDir, "mappings");
                if (dir2.exists()) return dir2;
            }
        } catch (Exception ignored) {}

        // Резервный абсолютный путь
        File dir3 = new File("C:/InterMed/mappings");
        if (dir3.exists()) return dir3;

        return null;
    }

    public static String translate(String internalName) {
        if (internalName == null) return null;
        return classes.get(internalName);
    }
}