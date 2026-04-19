package org.intermed.core.metadata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Normalizes upstream Fabric and Forge manifests into one internal representation.
 */
public final class ModMetadataParser {

    private static final String FABRIC_MANIFEST = "fabric.mod.json";
    private static final String FORGE_MANIFEST = "META-INF/mods.toml";
    private static final String NEOFORGE_MANIFEST = "META-INF/neoforge.mods.toml";
    private static final String INTERMED_OVERLAY = "META-INF/intermed.mod.json";
    private static final String FORGE_MOD_ANNOTATION = "Lnet/minecraftforge/fml/common/Mod;";
    private static final String NEOFORGE_MOD_ANNOTATION = "Lnet/neoforged/fml/common/Mod;";

    private ModMetadataParser() {}

    public static Optional<NormalizedModMetadata> parse(File jarFile) {
        if (jarFile == null || !jarFile.isFile()) {
            return Optional.empty();
        }

        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry fabricManifest = jar.getJarEntry(FABRIC_MANIFEST);
            if (fabricManifest != null) {
                return Optional.of(parseFabric(jarFile, jar, fabricManifest));
            }

            JarEntry neoforgeManifest = jar.getJarEntry(NEOFORGE_MANIFEST);
            if (neoforgeManifest != null) {
                return Optional.of(parseNeoForge(jarFile, jar, neoforgeManifest));
            }

            JarEntry forgeManifest = jar.getJarEntry(FORGE_MANIFEST);
            if (forgeManifest != null) {
                return Optional.of(parseForge(jarFile, jar, forgeManifest));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse metadata for " + jarFile.getName(), e);
        }

        return Optional.empty();
    }

    private static NormalizedModMetadata parseFabric(File sourceJar, JarFile jar, JarEntry manifestEntry) throws Exception {
        JsonObject json;
        try (InputStream input = jar.getInputStream(manifestEntry);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            json = JsonParser.parseReader(reader).getAsJsonObject();
        }

        String modId = json.get("id").getAsString();
        String version = json.has("version") ? json.get("version").getAsString() : "0.0.0";
        Map<String, String> dependencyConstraints = extractDependencies(json);
        ensurePreProdSupported(dependencyConstraints);
        JarFeatures features = scanJarFeatures(jar);

        JsonObject normalized = new JsonObject();
        normalized.addProperty("id", modId);
        normalized.addProperty("version", version);
        normalized.addProperty("intermed:platform", "fabric");
        copyIfPresent(json, normalized, "name");
        copyIfPresent(json, normalized, "depends");
        copyIfPresent(json, normalized, "suggests");
        copyIfPresent(json, normalized, "recommends");
        copyIfPresent(json, normalized, "entrypoints");
        copyIfPresent(json, normalized, "mixins");
        copyIfPresent(json, normalized, "intermed:permissions");
        copyIfPresent(json, normalized, "intermed:security");
        copyIfPresent(json, normalized, "intermed:sandbox");
        copyIfPresent(json, normalized, "intermed:classloader");
        mergeInterMedOverlay(jar, normalized);

        if (!normalized.has("depends")) {
            normalized.add("depends", new JsonObject());
        }
        if (!normalized.has("entrypoints")) {
            normalized.add("entrypoints", new JsonObject());
        }
        applyJarFeatures(normalized, features);

        return new NormalizedModMetadata(modId, version, sourceJar, ModPlatform.FABRIC, normalized, dependencyConstraints);
    }

    private static NormalizedModMetadata parseForge(File sourceJar, JarFile jar, JarEntry manifestEntry) throws Exception {
        ForgeManifestData data;
        try (InputStream input = jar.getInputStream(manifestEntry);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            data = parseForgeToml(reader, FORGE_MANIFEST);
        }

        return buildLoaderStyleMetadata(
            sourceJar,
            jar,
            data,
            ModPlatform.FORGE,
            "forge",
            new String[]{FORGE_MOD_ANNOTATION}
        );
    }

    private static NormalizedModMetadata parseNeoForge(File sourceJar, JarFile jar, JarEntry manifestEntry) throws Exception {
        ForgeManifestData data;
        try (InputStream input = jar.getInputStream(manifestEntry);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            data = parseForgeToml(reader, NEOFORGE_MANIFEST);
        }

        return buildLoaderStyleMetadata(
            sourceJar,
            jar,
            data,
            ModPlatform.NEOFORGE,
            "neoforge",
            new String[]{NEOFORGE_MOD_ANNOTATION, FORGE_MOD_ANNOTATION}
        );
    }

    // ── Minimal TOML parser (no external dependencies) ──────────────────────────

    private static ForgeManifestData parseForgeToml(InputStreamReader reader, String sourceName) throws Exception {
        Map<String, Object> toml = parseToml(reader);
        ForgeManifestData data = new ForgeManifestData();
        readModsArray(data, getTomlArray(toml, "mods"));
        readTopLevelDependencyTables(data, getTomlTable(toml, "dependencies"));
        return data;
    }

    /**
     * Parses a subset of TOML sufficient for Forge/NeoForge {@code mods.toml}:
     * key=value pairs, {@code [table]} headers, and {@code [[array.of.tables]]}
     * headers.  Inline arrays of tables and multi-line strings are not supported
     * because they do not appear in standard mod manifests.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseToml(Reader reader) throws java.io.IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> currentContext = root;

        BufferedReader br = (reader instanceof BufferedReader)
            ? (BufferedReader) reader
            : new BufferedReader(reader);

        String line;
        while ((line = br.readLine()) != null) {
            line = stripTomlComment(line).trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("[[") && line.contains("]]")) {
                // Array-of-tables header: [[key]] or [[outer.inner]]
                int end = line.indexOf("]]");
                String path = line.substring(2, end).trim();
                Map<String, Object> entry = new LinkedHashMap<>();
                tomlInsertAot(root, path, entry);
                currentContext = entry;
            } else if (line.startsWith("[") && line.contains("]")) {
                // Regular table header: [key] or [outer.inner]
                int end = line.lastIndexOf(']');
                String path = line.substring(1, end).trim();
                currentContext = tomlGetOrCreateTable(root, path);
            } else if (line.contains("=")) {
                int eqIdx = line.indexOf('=');
                String key = line.substring(0, eqIdx).trim();
                String valueStr = line.substring(eqIdx + 1).trim();
                currentContext.put(key, parseTomlValue(valueStr));
            }
        }
        return root;
    }

    /**
     * Navigates {@code root} along {@code path} (dot-separated) and appends
     * {@code entry} to the list found (or created) at the final key.
     * When traversing an intermediate segment that is a List, enters the last
     * element — this handles {@code [[fruits.varieties]]} after {@code [[fruits]]}.
     */
    @SuppressWarnings("unchecked")
    private static void tomlInsertAot(Map<String, Object> root, String path, Map<String, Object> entry) {
        String[] parts = path.split("\\.", -1);
        Map<String, Object> current = root;

        for (int i = 0; i < parts.length - 1; i++) {
            String part = unquoteTomlKey(parts[i]);
            Object existing = current.get(part);
            if (existing instanceof List) {
                List<Map<String, Object>> list = (List<Map<String, Object>>) existing;
                if (!list.isEmpty()) {
                    current = list.get(list.size() - 1);
                } else {
                    Map<String, Object> newMap = new LinkedHashMap<>();
                    list.add(newMap);
                    current = newMap;
                }
            } else if (existing instanceof Map) {
                current = (Map<String, Object>) existing;
            } else {
                Map<String, Object> newMap = new LinkedHashMap<>();
                current.put(part, newMap);
                current = newMap;
            }
        }

        String lastPart = unquoteTomlKey(parts[parts.length - 1]);
        Object existing = current.get(lastPart);
        List<Map<String, Object>> list;
        if (existing instanceof List) {
            list = (List<Map<String, Object>>) existing;
        } else {
            list = new ArrayList<>();
            current.put(lastPart, list);
        }
        list.add(entry);
    }

    /**
     * Navigates (or creates) a nested table at the given dot-separated path.
     * If an intermediate key maps to a List, enters its last element.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> tomlGetOrCreateTable(Map<String, Object> root, String path) {
        Map<String, Object> current = root;
        for (String rawPart : path.split("\\.", -1)) {
            String part = unquoteTomlKey(rawPart);
            Object existing = current.get(part);
            if (existing instanceof List) {
                List<?> list = (List<?>) existing;
                if (!list.isEmpty() && list.get(list.size() - 1) instanceof Map) {
                    current = (Map<String, Object>) list.get(list.size() - 1);
                } else {
                    Map<String, Object> newMap = new LinkedHashMap<>();
                    current.put(part, newMap);
                    current = newMap;
                }
            } else if (existing instanceof Map) {
                current = (Map<String, Object>) existing;
            } else {
                Map<String, Object> newMap = new LinkedHashMap<>();
                current.put(part, newMap);
                current = newMap;
            }
        }
        return current;
    }

    private static String unquoteTomlKey(String raw) {
        String s = raw.trim();
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static Object parseTomlValue(String raw) {
        if (raw.isEmpty()) return "";
        if ("true".equalsIgnoreCase(raw)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(raw)) return Boolean.FALSE;
        // Double-quoted string
        if (raw.startsWith("\"") && raw.endsWith("\"") && raw.length() >= 2) {
            return unescapeTomlString(raw.substring(1, raw.length() - 1));
        }
        // Single-quoted literal string
        if (raw.startsWith("'") && raw.endsWith("'") && raw.length() >= 2) {
            return raw.substring(1, raw.length() - 1);
        }
        // Numeric (best-effort; we only need strings and booleans for mods.toml)
        try { return Long.parseLong(raw); } catch (NumberFormatException ignored) {}
        try { return Double.parseDouble(raw); } catch (NumberFormatException ignored) {}
        return raw; // unquoted string
    }

    private static String unescapeTomlString(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"'  -> { sb.append('"');  i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case 'n'  -> { sb.append('\n'); i++; }
                    case 't'  -> { sb.append('\t'); i++; }
                    case 'r'  -> { sb.append('\r'); i++; }
                    default   -> sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Strips a TOML line comment ({@code #}) while respecting quoted strings. */
    private static String stripTomlComment(String line) {
        boolean inStr = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inStr) {
                if (c == '\\') { i++; continue; } // escaped char — skip next
                if (c == quote) inStr = false;
            } else if (c == '"' || c == '\'') {
                inStr = true;
                quote = c;
            } else if (c == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    // ── TOML accessor helpers ────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getTomlArray(Map<String, Object> table, String key) {
        if (table == null) return null;
        Object val = table.get(key);
        return (val instanceof List) ? (List<Map<String, Object>>) val : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getTomlTable(Map<String, Object> table, String key) {
        if (table == null) return null;
        Object val = table.get(key);
        return (val instanceof Map) ? (Map<String, Object>) val : null;
    }

    private static String getTomlString(Map<String, Object> table, String key) {
        if (table == null) return null;
        Object val = table.get(key);
        if (val == null) return null;
        return val.toString();
    }

    private static boolean getTomlBoolean(Map<String, Object> table, String key, boolean defaultValue) {
        if (table == null) return defaultValue;
        Object val = table.get(key);
        return (val instanceof Boolean) ? (Boolean) val : defaultValue;
    }

    // ── ForgeManifestData readers ────────────────────────────────────────────────

    private static void readModsArray(ForgeManifestData data, List<Map<String, Object>> mods) {
        if (mods == null || mods.isEmpty()) return;
        for (Map<String, Object> modTable : mods) {
            ForgeModEntry mod = data.addMod();
            mod.modId = normalizedTomlString(getTomlString(modTable, "modId"));
            mod.version = defaultTomlString(getTomlString(modTable, "version"), "0.0.0");
            mod.displayName = normalizedTomlString(getTomlString(modTable, "displayName"));
            readDependencyArray(data, mod.modId, getTomlArray(modTable, "dependencies"));
        }
    }

    private static void readTopLevelDependencyTables(ForgeManifestData data, Map<String, Object> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) return;
        for (Map.Entry<String, Object> entry : dependencies.entrySet()) {
            if (!(entry.getValue() instanceof List)) continue;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> array = (List<Map<String, Object>>) entry.getValue();
            readDependencyArray(data, entry.getKey(), array);
        }
    }

    private static void readDependencyArray(ForgeManifestData data, String ownerModId,
                                             List<Map<String, Object>> dependencies) {
        if (ownerModId == null || ownerModId.isBlank() || dependencies == null || dependencies.isEmpty()) return;
        for (Map<String, Object> dependency : dependencies) {
            String dependencyId = normalizedTomlString(getTomlString(dependency, "modId"));
            if (dependencyId == null || dependencyId.isBlank()) continue;
            boolean mandatory = getTomlBoolean(dependency, "mandatory", true);
            if (!mandatory) continue;
            String versionRange = firstTomlString(dependency, "versionRange", "version", "constraint");
            data.dependenciesByModId
                .computeIfAbsent(ownerModId, ignored -> new LinkedHashMap<>())
                .put(dependencyId, defaultTomlString(versionRange, "*"));
        }
    }

    private static String firstTomlString(Map<String, Object> table, String... keys) {
        if (table == null || keys == null) return null;
        for (String key : keys) {
            String value = normalizedTomlString(getTomlString(table, key));
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    // ── rest of parsing helpers (unchanged) ────────────────────────────────────

    private static Map<String, String> extractDependencies(JsonObject json) {
        Map<String, String> dependencies = new LinkedHashMap<>();
        if (!json.has("depends") || !json.get("depends").isJsonObject()) {
            return dependencies;
        }
        json.getAsJsonObject("depends").entrySet().forEach(entry -> {
            dependencies.put(entry.getKey(), NormalizedModMetadata.dependencyConstraint(entry.getValue()));
        });
        return dependencies;
    }

    private static List<String> discoverAnnotatedEntrypoints(JarFile jar,
                                                             String modId,
                                                             String... annotationDescs) throws Exception {
        LinkedHashSet<String> entrypoints = new LinkedHashSet<>();
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                continue;
            }

            try (InputStream input = jar.getInputStream(entry)) {
                ClassReader reader = new ClassReader(input);
                ClassNode node = new ClassNode();
                reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                if (matchesLoaderModAnnotation(node.visibleAnnotations, modId, annotationDescs)
                    || matchesLoaderModAnnotation(node.invisibleAnnotations, modId, annotationDescs)) {
                    entrypoints.add(node.name.replace('/', '.'));
                }
            }
        }
        return new ArrayList<>(entrypoints);
    }

    private static boolean matchesLoaderModAnnotation(List<AnnotationNode> annotations,
                                                      String modId,
                                                      String... annotationDescs) {
        if (annotations == null) {
            return false;
        }
        for (AnnotationNode annotation : annotations) {
            if (!matchesAnnotationDesc(annotation.desc, annotationDescs) || annotation.values == null) {
                continue;
            }
            for (int i = 0; i < annotation.values.size(); i += 2) {
                Object name = annotation.values.get(i);
                Object value = annotation.values.get(i + 1);
                if ("value".equals(name) && modId.equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void ensurePreProdSupported(Map<String, String> dependencies) {
        // Phase 3 lifts the temporary support gate for NeoForge.
    }

    private static NormalizedModMetadata buildLoaderStyleMetadata(File sourceJar,
                                                                  JarFile jar,
                                                                  ForgeManifestData data,
                                                                  ModPlatform platform,
                                                                  String platformName,
                                                                  String[] annotationDescs) throws Exception {
        ForgeModEntry selectedMod = data.selectPrimaryMod(jar, annotationDescs);
        if (selectedMod.modId == null || selectedMod.modId.isBlank()) {
            throw new IllegalStateException(platformName + " manifest did not declare [[mods]].modId");
        }
        Map<String, String> dependencies = data.dependenciesFor(selectedMod.modId);
        ensurePreProdSupported(dependencies);

        List<String> entrypoints = discoverAnnotatedEntrypoints(jar, selectedMod.modId, annotationDescs);
        JarFeatures features = scanJarFeatures(jar);

        JsonObject normalized = new JsonObject();
        normalized.addProperty("id", selectedMod.modId);
        normalized.addProperty("version", selectedMod.version);
        normalized.addProperty("intermed:platform", platformName);
        if (selectedMod.displayName != null && !selectedMod.displayName.isBlank()) {
            normalized.addProperty("name", selectedMod.displayName);
        }

        JsonObject depends = new JsonObject();
        dependencies.forEach(depends::addProperty);
        normalized.add("depends", depends);

        JsonObject entrypointRoot = new JsonObject();
        JsonArray main = new JsonArray();
        for (String entrypoint : entrypoints) {
            JsonObject point = new JsonObject();
            point.addProperty("value", entrypoint);
            point.addProperty("mode", "construct");
            main.add(point);
        }
        entrypointRoot.add("main", main);
        normalized.add("entrypoints", entrypointRoot);
        mergeInterMedOverlay(jar, normalized);
        applyJarFeatures(normalized, features);

        return new NormalizedModMetadata(
            selectedMod.modId,
            selectedMod.version,
            sourceJar,
            platform,
            normalized,
            dependencies
        );
    }

    private static boolean matchesAnnotationDesc(String annotationDesc, String... annotationDescs) {
        if (annotationDesc == null || annotationDescs == null) {
            return false;
        }
        for (String expected : annotationDescs) {
            if (annotationDesc.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private static JarFeatures scanJarFeatures(JarFile jar) throws Exception {
        JarFeatures features = new JarFeatures();
        if (jar.getManifest() != null) {
            String mixinConfigs = jar.getManifest().getMainAttributes().getValue("MixinConfigs");
            if (mixinConfigs != null && !mixinConfigs.isBlank()) {
                for (String raw : mixinConfigs.split(",")) {
                    String config = raw.trim();
                    if (!config.isEmpty()) {
                        features.mixinConfigs.add(config);
                    }
                }
            }
        }

        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            String name = entry.getName();
            if (name.startsWith("assets/")) {
                features.hasClientResources = true;
            } else if (name.startsWith("data/")) {
                features.hasServerData = true;
            }
        }
        return features;
    }

    private static void applyJarFeatures(JsonObject normalized, JarFeatures features) {
        if (features.hasClientResources) {
            normalized.addProperty("intermed:has_client_resources", true);
        }
        if (features.hasServerData) {
            normalized.addProperty("intermed:has_server_data", true);
        }
        if (!features.mixinConfigs.isEmpty() && !normalized.has("mixins")) {
            JsonArray mixins = new JsonArray();
            features.mixinConfigs.forEach(mixins::add);
            normalized.add("mixins", mixins);
        }
    }

    private static void copyIfPresent(JsonObject source, JsonObject destination, String key) {
        if (source.has(key)) {
            destination.add(key, source.get(key).deepCopy());
        }
    }

    private static void mergeInterMedOverlay(JarFile jar, JsonObject normalized) throws Exception {
        JarEntry overlayEntry = jar.getJarEntry(INTERMED_OVERLAY);
        if (overlayEntry == null) {
            return;
        }
        try (InputStream input = jar.getInputStream(overlayEntry);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            JsonObject overlay = JsonParser.parseReader(reader).getAsJsonObject();
            copyIfPresent(overlay, normalized, "name");
            mergeDependsIfPresent(overlay, normalized);
            copyIfPresent(overlay, normalized, "entrypoints");
            copyIfPresent(overlay, normalized, "mixins");
            copyIfPresent(overlay, normalized, "intermed:permissions");
            copyIfPresent(overlay, normalized, "intermed:security");
            copyIfPresent(overlay, normalized, "intermed:sandbox");
            copyIfPresent(overlay, normalized, "intermed:classloader");
        }
    }

    private static void mergeDependsIfPresent(JsonObject overlay, JsonObject normalized) {
        if (!overlay.has("depends") || !overlay.get("depends").isJsonObject()) {
            return;
        }

        JsonObject merged = normalized.has("depends") && normalized.get("depends").isJsonObject()
            ? normalized.getAsJsonObject("depends").deepCopy()
            : new JsonObject();
        JsonObject overrides = overlay.getAsJsonObject("depends");

        overrides.entrySet().forEach(entry -> {
            JsonElement existing = merged.get(entry.getKey());
            JsonElement override = entry.getValue();

            if (override == null || override.isJsonNull()) {
                return;
            }

            if (override.isJsonPrimitive()) {
                merged.add(entry.getKey(), override.deepCopy());
                return;
            }

            if (!override.isJsonObject()) {
                merged.add(entry.getKey(), override.deepCopy());
                return;
            }

            JsonObject mergedSpec = new JsonObject();
            String baseConstraint = NormalizedModMetadata.dependencyConstraint(existing);
            if (baseConstraint != null && !baseConstraint.isBlank() && !"*".equals(baseConstraint)) {
                mergedSpec.addProperty("version", baseConstraint);
            }
            if (existing != null && existing.isJsonObject()) {
                existing.getAsJsonObject().entrySet()
                    .forEach(baseEntry -> mergedSpec.add(baseEntry.getKey(), baseEntry.getValue().deepCopy()));
            }
            override.getAsJsonObject().entrySet()
                .forEach(overrideEntry -> mergedSpec.add(overrideEntry.getKey(), overrideEntry.getValue().deepCopy()));
            if (!mergedSpec.has("version")) {
                mergedSpec.addProperty("version", baseConstraint == null || baseConstraint.isBlank() ? "*" : baseConstraint);
            }
            merged.add(entry.getKey(), mergedSpec);
        });

        normalized.add("depends", merged);
    }

    private static String normalizedTomlString(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String defaultTomlString(String value, String fallback) {
        String normalized = normalizedTomlString(value);
        return normalized == null ? fallback : normalized;
    }

    private static final class ForgeManifestData {
        private final List<ForgeModEntry> mods = new ArrayList<>();
        private final Map<String, Map<String, String>> dependenciesByModId = new LinkedHashMap<>();

        private ForgeModEntry addMod() {
            ForgeModEntry entry = new ForgeModEntry();
            mods.add(entry);
            return entry;
        }

        private ForgeModEntry selectPrimaryMod(JarFile jar, String[] annotationDescs) throws Exception {
            if (mods.isEmpty()) {
                return new ForgeModEntry();
            }
            if (mods.size() == 1) {
                return mods.get(0);
            }

            ForgeModEntry annotatedMatch = null;
            for (ForgeModEntry mod : mods) {
                if (mod.modId == null || mod.modId.isBlank()) {
                    continue;
                }
                if (!discoverAnnotatedEntrypoints(jar, mod.modId, annotationDescs).isEmpty()) {
                    if (annotatedMatch != null) {
                        return firstNamedMod();
                    }
                    annotatedMatch = mod;
                }
            }
            return annotatedMatch != null ? annotatedMatch : firstNamedMod();
        }

        private ForgeModEntry firstNamedMod() {
            for (ForgeModEntry mod : mods) {
                if (mod.modId != null && !mod.modId.isBlank()) {
                    return mod;
                }
            }
            return mods.get(0);
        }

        private Map<String, String> dependenciesFor(String modId) {
            if (modId == null || modId.isBlank()) {
                return Map.of();
            }
            Map<String, String> dependencies = dependenciesByModId.get(modId);
            return dependencies == null ? Map.of() : Map.copyOf(dependencies);
        }
    }

    private static final class ForgeModEntry {
        private String modId;
        private String version = "0.0.0";
        private String displayName;
    }

    private static final class JarFeatures {
        private boolean hasClientResources;
        private boolean hasServerData;
        private final List<String> mixinConfigs = new ArrayList<>();
    }
}
