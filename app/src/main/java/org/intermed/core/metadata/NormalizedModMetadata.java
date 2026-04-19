package org.intermed.core.metadata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ecosystem-neutral manifest representation used by the runtime, bridges and launcher diagnostics.
 */
public final class NormalizedModMetadata {

    private final String id;
    private final String version;
    private final File sourceJar;
    private final ModPlatform platform;
    private final JsonObject manifest;
    private final Map<String, String> dependencyConstraints;

    public NormalizedModMetadata(String id,
                                 String version,
                                 File sourceJar,
                                 ModPlatform platform,
                                 JsonObject manifest,
                                 Map<String, String> dependencyConstraints) {
        this.id = Objects.requireNonNull(id, "id");
        this.version = version == null || version.isBlank() ? "0.0.0" : version;
        this.sourceJar = sourceJar;
        this.platform = Objects.requireNonNull(platform, "platform");
        this.manifest = manifest == null ? new JsonObject() : manifest.deepCopy();
        this.dependencyConstraints = Map.copyOf(new LinkedHashMap<>(dependencyConstraints));
    }

    public String id() {
        return id;
    }

    public String version() {
        return version;
    }

    public File sourceJar() {
        return sourceJar;
    }

    public ModPlatform platform() {
        return platform;
    }

    public JsonObject manifest() {
        return manifest.deepCopy();
    }

    public Map<String, String> dependencyConstraints() {
        return dependencyConstraints;
    }

    public boolean reexportsDependency(String dependencyId) {
        if (dependencyId == null || dependencyId.isBlank()) {
            return false;
        }
        if (!manifest.has("depends") || !manifest.get("depends").isJsonObject()) {
            return false;
        }
        JsonObject depends = manifest.getAsJsonObject("depends");
        if (!depends.has(dependencyId)) {
            return false;
        }
        return dependencyReexport(depends.get(dependencyId));
    }

    public boolean reexportsPrivateLibraries() {
        return readClassLoaderBoolean("reexportPrivateLibraries", false);
    }

    public List<String> allowedPeerIds() {
        JsonObject classloader = classLoaderConfig();
        return classloader == null
            ? List.of()
            : readStringList(classloader, "peers", "allowedPeers");
    }

    public List<String> softDependencyIds() {
        ArrayList<String> softDependencies = new ArrayList<>();
        JsonObject classloader = classLoaderConfig();
        if (classloader != null) {
            mergeUnique(softDependencies, readStringList(classloader, "softDepends", "weakPeers", "softDependencies"));
        }
        mergeUnique(softDependencies, readStringList(manifest, "suggests", "recommends"));
        return List.copyOf(softDependencies);
    }

    public List<String> weakApiPrefixes() {
        JsonObject classloader = classLoaderConfig();
        if (classloader == null) {
            return List.of();
        }
        ArrayList<String> prefixes = new ArrayList<>();
        mergeUnique(prefixes, normalizePrefixes(readStringList(
            classloader,
            "apiPrefixes",
            "weakApiPrefixes",
            "publicApiPackages",
            "exports"
        )));
        return List.copyOf(prefixes);
    }

    public String name() {
        if (manifest.has("name") && manifest.get("name").isJsonPrimitive()) {
            return manifest.get("name").getAsString();
        }
        return id;
    }

    public boolean hasClientResources() {
        return manifest.has("intermed:has_client_resources")
            && manifest.get("intermed:has_client_resources").getAsBoolean();
    }

    public boolean hasServerData() {
        return manifest.has("intermed:has_server_data")
            && manifest.get("intermed:has_server_data").getAsBoolean();
    }

    public List<String> mixinConfigs() {
        List<String> configs = new ArrayList<>();
        if (!manifest.has("mixins") || !manifest.get("mixins").isJsonArray()) {
            return configs;
        }
        for (JsonElement mixin : manifest.getAsJsonArray("mixins")) {
            if (mixin.isJsonPrimitive()) {
                configs.add(mixin.getAsString());
            } else if (mixin.isJsonObject() && mixin.getAsJsonObject().has("config")) {
                configs.add(mixin.getAsJsonObject().get("config").getAsString());
            }
        }
        return configs;
    }

    public List<String> entrypoints(String key) {
        List<String> entrypoints = new ArrayList<>();
        if (!manifest.has("entrypoints") || !manifest.get("entrypoints").isJsonObject()) {
            return entrypoints;
        }
        JsonObject entrypointRoot = manifest.getAsJsonObject("entrypoints");
        if (!entrypointRoot.has(key) || !entrypointRoot.get(key).isJsonArray()) {
            return entrypoints;
        }
        JsonArray values = entrypointRoot.getAsJsonArray(key);
        for (JsonElement value : values) {
            if (value.isJsonPrimitive()) {
                entrypoints.add(value.getAsString());
            } else if (value.isJsonObject() && value.getAsJsonObject().has("value")) {
                entrypoints.add(value.getAsJsonObject().get("value").getAsString());
            }
        }
        return entrypoints;
    }

    public JsonObject sandboxConfig() {
        if (!manifest.has("intermed:sandbox") || !manifest.get("intermed:sandbox").isJsonObject()) {
            return new JsonObject();
        }
        return manifest.getAsJsonObject("intermed:sandbox").deepCopy();
    }

    public String requestedSandboxMode() {
        return readSandboxString("mode", "native");
    }

    public boolean sandboxHotPath() {
        boolean defaultHotPath = !mixinConfigs().isEmpty();
        return readSandboxBoolean("hotPath", defaultHotPath);
    }

    public boolean sandboxAllowNativeFallback() {
        return readSandboxBoolean("allowNativeFallback", false);
    }

    public String sandboxModulePath() {
        return readSandboxString("modulePath", null);
    }

    public String sandboxEntrypoint() {
        return readSandboxString("entrypoint", "init_mod");
    }

    public String sandboxReasonHint() {
        return readSandboxString("reason", "");
    }

    private String readSandboxString(String key, String defaultValue) {
        JsonObject sandbox = sandboxConfig();
        if (!sandbox.has(key) || !sandbox.get(key).isJsonPrimitive()) {
            return defaultValue;
        }
        String value = sandbox.get(key).getAsString();
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private boolean readSandboxBoolean(String key, boolean defaultValue) {
        JsonObject sandbox = sandboxConfig();
        if (!sandbox.has(key) || !sandbox.get(key).isJsonPrimitive()) {
            return defaultValue;
        }
        return sandbox.get(key).getAsBoolean();
    }

    private boolean readClassLoaderBoolean(String key, boolean defaultValue) {
        JsonObject classloader = classLoaderConfig();
        if (classloader == null) {
            return defaultValue;
        }
        if (!classloader.has(key) || !classloader.get(key).isJsonPrimitive()) {
            return defaultValue;
        }
        return classloader.get(key).getAsBoolean();
    }

    private JsonObject classLoaderConfig() {
        if (!manifest.has("intermed:classloader") || !manifest.get("intermed:classloader").isJsonObject()) {
            return null;
        }
        return manifest.getAsJsonObject("intermed:classloader");
    }

    private static List<String> readStringList(JsonObject object, String... keys) {
        ArrayList<String> values = new ArrayList<>();
        if (object == null || keys == null) {
            return values;
        }
        for (String key : keys) {
            if (key == null || !object.has(key)) {
                continue;
            }
            JsonElement value = object.get(key);
            if (value == null || value.isJsonNull()) {
                continue;
            }
            if (value.isJsonPrimitive()) {
                for (String candidate : value.getAsString().split(",")) {
                    addUnique(values, candidate);
                }
                continue;
            }
            if (value.isJsonArray()) {
                for (JsonElement element : value.getAsJsonArray()) {
                    if (element != null && element.isJsonPrimitive()) {
                        addUnique(values, element.getAsString());
                    }
                }
                continue;
            }
            if (value.isJsonObject()) {
                value.getAsJsonObject().entrySet().forEach(entry -> addUnique(values, entry.getKey()));
            }
        }
        return values;
    }

    private static List<String> normalizePrefixes(List<String> rawPrefixes) {
        ArrayList<String> normalized = new ArrayList<>();
        if (rawPrefixes == null) {
            return normalized;
        }
        for (String rawPrefix : rawPrefixes) {
            if (rawPrefix == null) {
                continue;
            }
            String prefix = rawPrefix.trim()
                .replace('/', '.')
                .replace(".*", ".");
            if (prefix.isEmpty()) {
                continue;
            }
            if (!prefix.endsWith(".")) {
                prefix = prefix + ".";
            }
            if (!normalized.contains(prefix)) {
                normalized.add(prefix);
            }
        }
        return normalized;
    }

    private static void mergeUnique(List<String> target, List<String> values) {
        if (target == null || values == null) {
            return;
        }
        for (String value : values) {
            addUnique(target, value);
        }
    }

    private static void addUnique(List<String> values, String rawValue) {
        if (values == null || rawValue == null) {
            return;
        }
        String value = rawValue.trim();
        if (!value.isEmpty() && !values.contains(value)) {
            values.add(value);
        }
    }

    public static String dependencyConstraint(JsonElement dependencySpec) {
        if (dependencySpec == null || dependencySpec.isJsonNull()) {
            return "*";
        }
        if (dependencySpec.isJsonPrimitive()) {
            String value = dependencySpec.getAsString();
            return value == null || value.isBlank() ? "*" : value;
        }
        if (!dependencySpec.isJsonObject()) {
            return "*";
        }
        JsonObject object = dependencySpec.getAsJsonObject();
        String explicit = firstString(object, "version", "versionRange", "constraint");
        return explicit == null || explicit.isBlank() ? "*" : explicit;
    }

    public static boolean dependencyReexport(JsonElement dependencySpec) {
        if (dependencySpec == null || !dependencySpec.isJsonObject()) {
            return false;
        }
        JsonObject object = dependencySpec.getAsJsonObject();
        return booleanFlag(object, "reexport") || booleanFlag(object, "export");
    }

    private static String firstString(JsonObject object, String... keys) {
        if (object == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (object.has(key) && object.get(key).isJsonPrimitive()) {
                return object.get(key).getAsString();
            }
        }
        return null;
    }

    private static boolean booleanFlag(JsonObject object, String key) {
        return object != null
            && key != null
            && object.has(key)
            && object.get(key).isJsonPrimitive()
            && object.get(key).getAsBoolean();
    }
}
