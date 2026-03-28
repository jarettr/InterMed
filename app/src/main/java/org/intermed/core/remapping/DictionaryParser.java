package org.intermed.core.remapping;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class DictionaryParser {
    public static void parse(Path tinyPath, Path srgPath, MappingDictionary dict) throws java.io.IOException {
        if (!Files.exists(tinyPath)) return;
        
        Map<String, String> obfToMoj = new HashMap<>();
        
        // 1. Читаем клиентские маппинги Forge (obfuscated -> Mojang)
        if (srgPath != null && Files.exists(srgPath)) {
            try (BufferedReader r = Files.newBufferedReader(srgPath)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.contains(" -> ") && !line.startsWith(" ")) {
                        String[] p = line.split(" -> ");
                        String mojang = p[0].replace('.', '/');
                        String obf = p[1].trim().replace(":", "").replace('.', '/');
                        obfToMoj.put(obf, mojang);
                    }
                }
            }
        }

        // 2. Читаем Tiny v2 и делаем ИДЕАЛЬНЫЙ мост (Intermediary -> Obfuscated -> Mojang)
        try (BufferedReader r = Files.newBufferedReader(tinyPath)) {
            String line;
            String currentClass = null;
            int cCount = 0, mCount = 0, fCount = 0;

            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty() || line.startsWith("tiny")) continue;
                String[] p = line.split("\t");

                if (line.startsWith("c\t") && p.length >= 3) {
                    String obfName = p[1]; // Обфусцированное имя (a, b, c...)
                    currentClass = p[2];   // Intermediary (class_2960)
                    
                    // Гениальная логика: ищем чистое имя Forge по обфусцированному ключу!
                    String cleanForgeName = obfToMoj.get(obfName);
                    
                    if (cleanForgeName != null) {
                        dict.addClass(currentClass, cleanForgeName);
                    } else {
                        // Если это класс не из Minecraft (например, библиотека), берем его названное имя
                        String named = (p.length > 3) ? p[3] : currentClass;
                        dict.addClass(currentClass, named);
                    }
                    cCount++;
                } 
                else if (line.startsWith("\tm\t") && currentClass != null && p.length >= 4) {
                    dict.addMethod(currentClass, p[3], p[2], (p.length > 4) ? p[4] : p[3]);
                    mCount++;
                }
                else if (line.startsWith("\tf\t") && currentClass != null && p.length >= 4) {
                    dict.addField(currentClass, p[3], p[2], (p.length > 4) ? p[4] : p[3]);
                    fCount++;
                }
            }
            System.out.println("\033[1;32m[Dictionary] Pure Logic Fusion: " + cCount + " classes, " + mCount + " methods, " + fCount + " fields.\033[0m");
        }
    }
}