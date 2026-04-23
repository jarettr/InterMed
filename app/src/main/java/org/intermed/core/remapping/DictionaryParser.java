package org.intermed.core.remapping;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class DictionaryParser {
    public static void parse(Path tinyPath, Path srgPath, MappingDictionary dict) throws java.io.IOException {
        parse(tinyPath, srgPath, resolveTsrgPath(tinyPath, srgPath, null), dict);
    }

    public static void parse(Path tinyPath, Path srgPath, Path tsrgPath, MappingDictionary dict) throws java.io.IOException {
        if (!Files.exists(tinyPath)) return;
        
        Map<String, String> obfToMoj = new HashMap<>();
        SrgMemberMappings srgMembers = parseTsrgMembers(tsrgPath);
        if (tsrgPath != null && Files.exists(tsrgPath)) {
            System.out.println("[Dictionary] Using member bridge: " + tsrgPath);
        } else {
            System.out.println("[Dictionary] Member bridge unavailable; falling back to named/heuristic member remap.");
        }
        
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

        // 2. Читаем Tiny v2 и делаем мост Intermediary -> Official -> Mojang.
        try (BufferedReader r = Files.newBufferedReader(tinyPath)) {
            String line;
            String currentClass = null;
            String currentOfficialClass = null;
            String currentTargetClass = null;
            TinyNamespaces namespaces = TinyNamespaces.legacy();
            int cCount = 0, mCount = 0, fCount = 0;

            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                if (line.startsWith("tiny")) {
                    namespaces = TinyNamespaces.fromHeader(line);
                    continue;
                }
                String[] p = line.split("\t");

                if (line.startsWith("c\t") && p.length >= 1 + namespaces.namespaceCount()) {
                    String officialName = namespaces.className(p, "official");
                    currentOfficialClass = officialName;
                    currentClass = namespaces.className(p, "intermediary");
                    String targetName = obfToMoj.get(officialName);
                    if (targetName == null) {
                        targetName = namespaces.preferredNamedClassName(p, officialName);
                    }
                    currentTargetClass = targetName;

                    if (currentClass != null && targetName != null) {
                        dict.addClass(currentClass, targetName);
                    }
                    cCount++;
                } 
                else if (line.startsWith("\tm\t") && currentClass != null) {
                    TinyMemberLine member = parseTinyMemberLine(p, namespaces, currentOfficialClass, srgMembers);
                    if (member != null) {
                        dict.addMethod(currentClass, member.intermediaryName(), member.descriptor(), member.targetName());
                        if (currentTargetClass != null) {
                            dict.addMethod(currentTargetClass, member.intermediaryName(), member.descriptor(), member.targetName());
                        }
                        mCount++;
                    }
                }
                else if (line.startsWith("\tf\t") && currentClass != null) {
                    TinyMemberLine member = parseTinyMemberLine(p, namespaces, currentOfficialClass, srgMembers);
                    if (member != null) {
                        dict.addField(currentClass, member.intermediaryName(), member.descriptor(), member.targetName());
                        if (currentTargetClass != null) {
                            dict.addField(currentTargetClass, member.intermediaryName(), member.descriptor(), member.targetName());
                        }
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

    private static TinyMemberLine parseTinyMemberLine(
        String[] parts,
        TinyNamespaces namespaces,
        String officialOwner,
        SrgMemberMappings srgMembers
    ) {
        if (parts == null || parts.length == 0) {
            return null;
        }

        int offset = parts[0].isEmpty() ? 1 : 0;
        String memberKind = parts[offset];
        int descriptorIndex = offset + 1;
        int namesStart = offset + 2;
        int officialNameIndex = namesStart + namespaces.indexOf("official");
        int intermediaryNameIndex = namesStart + namespaces.indexOf("intermediary");

        if (parts.length <= intermediaryNameIndex || parts.length <= officialNameIndex) {
            return null;
        }

        String descriptor = parts[descriptorIndex];
        String officialName = parts[officialNameIndex];
        String intermediaryName = parts[intermediaryNameIndex];
        String targetName = srgMembers.map(officialOwner, officialName, descriptor, memberKind);
        if (targetName == null) {
            targetName = namespaces.preferredNamedMemberName(parts, namesStart);
        }
        if (targetName == null) {
            return null;
        }
        return new TinyMemberLine(descriptor, intermediaryName, targetName);
    }

    private static SrgMemberMappings parseTsrgMembers(Path tsrgPath) throws IOException {
        if (tsrgPath == null || !Files.exists(tsrgPath)) {
            return SrgMemberMappings.empty();
        }

        Map<String, String> fields = new HashMap<>();
        Map<String, String> methods = new HashMap<>();
        try (BufferedReader r = Files.newBufferedReader(tsrgPath)) {
            String line;
            String owner = null;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("tsrg")) {
                    continue;
                }
                if (!line.startsWith("\t")) {
                    String[] parts = line.split(" ");
                    owner = parts.length > 0 ? parts[0] : null;
                    continue;
                }
                if (owner == null) {
                    continue;
                }
                String[] parts = line.trim().split(" ");
                if (parts.length >= 4 && parts[1].startsWith("(")) {
                    methods.put(owner + '\n' + parts[0] + '\n' + parts[1], parts[2]);
                } else if (parts.length >= 2 && !parts[0].matches("\\d+")) {
                    fields.put(owner + '\n' + parts[0], parts[1]);
                }
            }
        }
        return new SrgMemberMappings(fields, methods);
    }

    public static Path resolveTsrgPath(Path tinyPath, Path srgPath, Path autoSrgPath) {
        Path direct = locateTsrgMappings(tinyPath, srgPath);
        if (direct != null) {
            return direct;
        }
        if (autoSrgPath != null && !autoSrgPath.equals(srgPath)) {
            return locateTsrgMappings(tinyPath, autoSrgPath);
        }
        return null;
    }

    private static Path locateTsrgMappings(Path tinyPath, Path srgPath) {
        String override = System.getProperty("intermed.mappings.tsrg", "").trim();
        if (!override.isEmpty()) {
            Path candidate = Path.of(override);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        Path sibling = firstExisting(
            tinyPath != null && tinyPath.getParent() != null ? tinyPath.getParent().resolve("joined.tsrg") : null,
            srgPath != null && srgPath.getParent() != null ? srgPath.getParent().resolve("joined.tsrg") : null
        );
        if (sibling != null) {
            return sibling;
        }

        VersionPair version = VersionPair.fromMojangMappingPath(srgPath);
        if (version == null) {
            return null;
        }
        return firstExisting(
            Path.of(System.getProperty("user.home"), ".gradle", "caches", "fabric-loom",
                version.minecraft(), "mcp", version.minecraft() + "-" + version.mcp(),
                "unpacked", "config", "joined.tsrg")
        );
    }

    private static Path firstExisting(Path... candidates) {
        for (Path candidate : candidates) {
            if (candidate != null && Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private record TinyMemberLine(String descriptor, String intermediaryName, String targetName) {}

    private record SrgMemberMappings(Map<String, String> fields, Map<String, String> methods) {
        static SrgMemberMappings empty() {
            return new SrgMemberMappings(Map.of(), Map.of());
        }

        String map(String owner, String officialName, String descriptor, String kind) {
            if (owner == null || officialName == null || kind == null) {
                return null;
            }
            if ("m".equals(kind)) {
                return methods.get(owner + '\n' + officialName + '\n' + descriptor);
            }
            if ("f".equals(kind)) {
                return fields.get(owner + '\n' + officialName);
            }
            return null;
        }
    }

    private record VersionPair(String minecraft, String mcp) {
        static VersionPair fromMojangMappingPath(Path path) {
            if (path == null) {
                return null;
            }
            String fileName = path.getFileName().toString();
            if (!fileName.startsWith("client-") || !fileName.endsWith("-mappings.txt")) {
                return null;
            }
            String version = fileName.substring("client-".length(), fileName.length() - "-mappings.txt".length());
            int split = version.indexOf('-');
            if (split <= 0 || split >= version.length() - 1) {
                return null;
            }
            return new VersionPair(version.substring(0, split), version.substring(split + 1));
        }
    }

    private record TinyNamespaces(String[] names, Map<String, Integer> indices) {
        static TinyNamespaces legacy() {
            return new TinyNamespaces(
                new String[] {"official", "intermediary", "named"},
                Map.of("official", 0, "intermediary", 1, "named", 2)
            );
        }

        static TinyNamespaces fromHeader(String headerLine) {
            String[] parts = headerLine.split("\t");
            if (parts.length <= 3 || !"tiny".equals(parts[0])) {
                return legacy();
            }
            String[] names = Arrays.copyOfRange(parts, 3, parts.length);
            Map<String, Integer> indices = new HashMap<>();
            for (int i = 0; i < names.length; i++) {
                indices.put(names[i], i);
            }
            if (!indices.containsKey("intermediary")) {
                return legacy();
            }
            return new TinyNamespaces(names, indices);
        }

        int namespaceCount() {
            return names.length;
        }

        int indexOf(String namespace) {
            return indices.getOrDefault(namespace, -1);
        }

        String className(String[] parts, String namespace) {
            int index = indexOf(namespace);
            if (index < 0) {
                return null;
            }
            int partIndex = 1 + index;
            return parts.length > partIndex ? parts[partIndex] : null;
        }

        String preferredNamedClassName(String[] parts, String fallback) {
            String named = className(parts, "named");
            if (named != null) {
                return named;
            }
            String mojang = className(parts, "mojang");
            if (mojang != null) {
                return mojang;
            }
            return fallback;
        }

        String preferredNamedMemberName(String[] parts, int namesStart) {
            String named = memberName(parts, namesStart, "named");
            if (named != null) {
                return named;
            }
            return memberName(parts, namesStart, "mojang");
        }

        private String memberName(String[] parts, int namesStart, String namespace) {
            int index = indexOf(namespace);
            if (index < 0) {
                return null;
            }
            int partIndex = namesStart + index;
            return parts.length > partIndex ? parts[partIndex] : null;
        }
    }
}
