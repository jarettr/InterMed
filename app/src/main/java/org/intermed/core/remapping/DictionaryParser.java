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
                else if (line.startsWith("\tm\t") && currentClass != null) {
                    TinyMemberLine member = parseTinyMemberLine(p);
                    if (member != null) {
                        dict.addMethod(currentClass, member.intermediaryName(), member.descriptor(), member.officialName());
                        mCount++;
                    }
                }
                else if (line.startsWith("\tf\t") && currentClass != null) {
                    TinyMemberLine member = parseTinyMemberLine(p);
                    if (member != null) {
                        dict.addField(currentClass, member.intermediaryName(), member.descriptor(), member.officialName());
                        fCount++;
                    }
                }
            }
            System.out.println("\033[1;32m[Dictionary] Pure Logic Fusion: " + cCount + " classes, " + mCount + " methods, " + fCount + " fields.\033[0m");
        }
    }

    /**
     * Loads only the Mojang (ProGuard) mapping file — no Tiny intermediary file
     * required.  Populates {@code dict} with obfuscated → Mojang class-name
     * mappings, which is sufficient for Forge environments where Fabric mods
     * with Intermediary names are not present.
     *
     * @param srgPath path to {@code client-<version>-mappings.txt}
     * @param dict    dictionary to populate
     */
    public static void parseSrgOnly(Path srgPath, MappingDictionary dict) throws java.io.IOException {
        if (srgPath == null || !Files.exists(srgPath)) return;
        int count = 0;
        try (BufferedReader r = Files.newBufferedReader(srgPath)) {
            String line;
            while ((line = r.readLine()) != null) {
                // ProGuard format: "net.minecraft.SomeClass -> obf.Name:"
                if (line.isEmpty() || line.startsWith(" ") || line.startsWith("#")) continue;
                if (line.contains(" -> ")) {
                    String[] p = line.split(" -> ");
                    if (p.length < 2) continue;
                    String mojang = p[0].trim().replace('.', '/');
                    String obf    = p[1].trim().replace(":", "").replace('.', '/');
                    dict.addClass(obf, mojang);
                    count++;
                }
            }
        }
        System.out.println("\033[1;32m[Dictionary] SRG-only load: " + count + " class entries.\033[0m");
    }

    private static TinyMemberLine parseTinyMemberLine(String[] parts) {
        if (parts == null || parts.length == 0) {
            return null;
        }

        int offset = parts[0].isEmpty() ? 1 : 0;
        int descriptorIndex = offset + 1;
        int intermediaryNameIndex = offset + 3;
        int officialNameIndex = offset + 4;

        if (parts.length <= intermediaryNameIndex) {
            return null;
        }

        String descriptor = parts[descriptorIndex];
        String intermediaryName = parts[intermediaryNameIndex];
        String officialName = parts.length > officialNameIndex ? parts[officialNameIndex] : intermediaryName;
        return new TinyMemberLine(descriptor, intermediaryName, officialName);
    }

    private record TinyMemberLine(String descriptor, String intermediaryName, String officialName) {}
}
