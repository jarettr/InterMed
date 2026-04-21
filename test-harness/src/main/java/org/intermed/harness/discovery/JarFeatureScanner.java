package org.intermed.harness.discovery;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

/**
 * Lightweight jar introspection for corpus stratification.
 *
 * <p>This intentionally avoids depending on the runtime's richer metadata
 * parser; the harness only needs enough signal to build a reproducible corpus
 * lockfile and plan test coverage.</p>
 */
final class JarFeatureScanner {

    private static final Set<String> FRAMEWORK_IDS = Set.of(
        "architectury",
        "architectury_api",
        "balm",
        "bookshelf",
        "cardinal-components-base",
        "cardinal-components-entity",
        "cloth-config",
        "collective",
        "curios",
        "fabric-api",
        "fabric-language-kotlin",
        "forgeconfigapiport",
        "framework",
        "geckolib",
        "libraryferret",
        "moonlight",
        "owo",
        "puzzlelib",
        "resourcefullib",
        "selene",
        "supermartijn642corelib",
        "trinkets",
        "yacl"
    );

    private static final Pattern TOML_DEP_SECTION =
        Pattern.compile("^\\s*\\[\\[dependencies\\.([A-Za-z0-9_.-]+)]]\\s*$");
    private static final Pattern TOML_KEY_VALUE =
        Pattern.compile("^\\s*([A-Za-z0-9_.-]+)\\s*=\\s*(.+?)\\s*$");

    private JarFeatureScanner() {}

    static Features scan(Path jarPath) throws IOException {
        Set<String> dependencies = new LinkedHashSet<>();
        Set<String> mixins = new LinkedHashSet<>();
        Set<String> nativeLibraries = new LinkedHashSet<>();
        boolean hasDataPack = false;
        boolean hasResourcePack = false;
        boolean hasPackMetadata = false;

        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Attributes attrs = jar.getManifest() == null ? null : jar.getManifest().getMainAttributes();
            if (attrs != null) {
                String mixinConfigs = attrs.getValue("MixinConfigs");
                if (mixinConfigs != null && !mixinConfigs.isBlank()) {
                    for (String value : mixinConfigs.split(",")) {
                        String trimmed = value.trim();
                        if (!trimmed.isEmpty()) {
                            mixins.add(trimmed);
                        }
                    }
                }
            }

            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);

                if (name.equals("fabric.mod.json")) {
                    parseFabricModJson(jar.getInputStream(entry), dependencies, mixins);
                } else if (name.equals("META-INF/mods.toml") || name.equals("META-INF/neoforge.mods.toml")) {
                    parseTomlDependencies(jar.getInputStream(entry), dependencies);
                } else if (lower.endsWith(".mixins.json")) {
                    mixins.add(name);
                }

                if (name.startsWith("data/")) {
                    hasDataPack = true;
                } else if (name.startsWith("assets/")) {
                    hasResourcePack = true;
                } else if (name.equals("pack.mcmeta")) {
                    hasPackMetadata = true;
                }

                if (looksNative(lower)) {
                    nativeLibraries.add(name);
                }
            }
        }

        List<String> declaredDependencies = dependencies.stream()
            .map(JarFeatureScanner::normalizeId)
            .filter(value -> !value.isBlank())
            .distinct()
            .sorted()
            .toList();
        List<String> frameworkDependencies = declaredDependencies.stream()
            .filter(JarFeatureScanner::isFrameworkDependency)
            .sorted()
            .toList();
        List<String> mixinConfigs = mixins.stream().sorted().toList();
        List<String> nativeFiles = nativeLibraries.stream()
            .sorted(Comparator.naturalOrder())
            .limit(24)
            .toList();

        return new Features(
            declaredDependencies,
            frameworkDependencies,
            mixinConfigs,
            nativeFiles,
            hasDataPack,
            hasResourcePack,
            hasPackMetadata
        );
    }

    private static void parseFabricModJson(InputStream stream,
                                           Set<String> dependencies,
                                           Set<String> mixins) throws IOException {
        String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        collectDependencyKeys(root, "depends", dependencies);
        collectDependencyKeys(root, "recommends", dependencies);
        if (root.has("mixins") && root.get("mixins").isJsonArray()) {
            JsonArray array = root.getAsJsonArray("mixins");
            for (JsonElement element : array) {
                if (element.isJsonPrimitive()) {
                    mixins.add(element.getAsString());
                } else if (element.isJsonObject()) {
                    JsonObject mixin = element.getAsJsonObject();
                    if (mixin.has("config")) {
                        mixins.add(mixin.get("config").getAsString());
                    }
                }
            }
        }
    }

    private static void collectDependencyKeys(JsonObject root,
                                              String field,
                                              Set<String> dependencies) {
        if (!root.has(field) || !root.get(field).isJsonObject()) {
            return;
        }
        for (String key : root.getAsJsonObject(field).keySet()) {
            String normalized = normalizeId(key);
            if (!normalized.isBlank()) {
                dependencies.add(normalized);
            }
        }
    }

    private static void parseTomlDependencies(InputStream stream,
                                              Set<String> dependencies) throws IOException {
        String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        String currentDependencyId = null;
        boolean mandatory = true;

        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = stripComment(line).trim();
                if (trimmed.isEmpty()) {
                    continue;
                }

                Matcher section = TOML_DEP_SECTION.matcher(trimmed);
                if (section.matches()) {
                    if (mandatory && currentDependencyId != null && !currentDependencyId.isBlank()) {
                        dependencies.add(normalizeId(currentDependencyId));
                    }
                    currentDependencyId = null;
                    mandatory = true;
                    continue;
                }

                Matcher keyValue = TOML_KEY_VALUE.matcher(trimmed);
                if (!keyValue.matches()) {
                    continue;
                }

                String key = keyValue.group(1);
                String value = unquote(keyValue.group(2));
                if ("modId".equals(key)) {
                    currentDependencyId = value;
                } else if ("mandatory".equals(key)) {
                    mandatory = Boolean.parseBoolean(value);
                }
            }
        }

        if (mandatory && currentDependencyId != null && !currentDependencyId.isBlank()) {
            dependencies.add(normalizeId(currentDependencyId));
        }
    }

    private static boolean looksNative(String lowerCaseName) {
        return lowerCaseName.endsWith(".dll")
            || lowerCaseName.endsWith(".so")
            || lowerCaseName.endsWith(".dylib")
            || lowerCaseName.endsWith(".jnilib")
            || lowerCaseName.contains("/natives/")
            || lowerCaseName.contains("/native/");
    }

    private static boolean isFrameworkDependency(String dependencyId) {
        if (FRAMEWORK_IDS.contains(dependencyId)) {
            return true;
        }
        return dependencyId.endsWith("-api")
            || dependencyId.endsWith("-lib")
            || dependencyId.endsWith("lib")
            || dependencyId.endsWith("library");
    }

    private static String normalizeId(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT).trim();
    }

    private static String stripComment(String line) {
        int idx = line.indexOf('#');
        return idx >= 0 ? line.substring(0, idx) : line;
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        if (trimmed.startsWith("'") && trimmed.endsWith("'") && trimmed.length() >= 2) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    record Features(
        List<String> declaredDependencies,
        List<String> frameworkDependencies,
        List<String> mixinConfigs,
        List<String> nativeLibraries,
        boolean hasDataPack,
        boolean hasResourcePack,
        boolean hasPackMetadata
    ) {
        boolean hasNativeLibraries() {
            return !nativeLibraries.isEmpty();
        }
    }
}
