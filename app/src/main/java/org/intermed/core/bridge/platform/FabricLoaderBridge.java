package org.intermed.core.bridge.platform;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import net.fabricmc.loader.api.metadata.CustomValue;
import net.fabricmc.loader.api.metadata.ModMetadata;
import org.intermed.core.bridge.BridgeRuntime;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.metadata.RuntimeModIndex;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class FabricLoaderBridge {
    private static final FabricLoaderBridge INSTANCE = new FabricLoaderBridge();
    private static final ConcurrentHashMap<Path, FileSystem> JAR_FILE_SYSTEMS = new ConcurrentHashMap<>();
    private static final Set<String> PLATFORM_PROVIDED_MODS = Set.of(
        "fabric",
        "fabricloader",
        "fabric-api",
        "forge",
        "javafml",
        "neoforge",
        "neoforged",
        "minecraft"
    );

    public static FabricLoaderBridge getInstance() {
        return INSTANCE;
    }

    public Path getConfigDir() {
        return RuntimeConfig.get().getConfigDir();
    }

    public Path getGameDir() {
        return RuntimeConfig.get().getGameDir();
    }

    public boolean isModLoaded(String id) {
        if (id == null) {
            return false;
        }
        return PLATFORM_PROVIDED_MODS.contains(id) || RuntimeModIndex.isLoaded(id);
    }

    public Optional<ModContainer> getModContainer(String id) {
        if (id == null) {
            return Optional.empty();
        }
        if (RuntimeModIndex.isLoaded(id)) {
            return RuntimeModIndex.get(id).map(BridgeModContainer::new);
        }
        if (PLATFORM_PROVIDED_MODS.contains(id)) {
            return Optional.of(new BridgeModContainer(platformMetadata(id)));
        }
        return Optional.empty();
    }

    public Collection<ModContainer> getAllMods() {
        LinkedHashMap<String, ModContainer> containers = new LinkedHashMap<>();
        RuntimeModIndex.allMods().stream()
            .sorted(Comparator.comparing(NormalizedModMetadata::id))
            .forEach(metadata -> containers.put(metadata.id(), new BridgeModContainer(metadata)));
        PLATFORM_PROVIDED_MODS.stream()
            .sorted()
            .forEach(id -> containers.putIfAbsent(id, new BridgeModContainer(platformMetadata(id))));
        return List.copyOf(containers.values());
    }

    public <T> List<T> getEntrypoints(String key, Class<T> type) {
        List<T> entrypoints = new ArrayList<>();
        for (BridgeRuntime.LoadedEntrypoint loaded : BridgeRuntime.getEntrypoints(key)) {
            if (type.isInstance(loaded.instance())) {
                entrypoints.add(type.cast(loaded.instance()));
            }
        }
        return List.copyOf(entrypoints);
    }

    public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
        List<EntrypointContainer<T>> containers = new ArrayList<>();
        for (BridgeRuntime.LoadedEntrypoint loaded : BridgeRuntime.getEntrypoints(key)) {
            if (type.isInstance(loaded.instance())) {
                ModContainer provider = getModContainer(loaded.modId()).orElse(null);
                if (provider != null) {
                    containers.add(new BridgeEntrypointContainer<>(
                        type.cast(loaded.instance()),
                        provider,
                        loaded.definition()
                    ));
                }
            }
        }
        return List.copyOf(containers);
    }

    private static final class BridgeEntrypointContainer<T> implements EntrypointContainer<T> {
        private final T entrypoint;
        private final ModContainer provider;
        private final String definition;

        private BridgeEntrypointContainer(T entrypoint, ModContainer provider, String definition) {
            this.entrypoint = entrypoint;
            this.provider = provider;
            this.definition = definition;
        }

        @Override
        public T getEntrypoint() {
            return entrypoint;
        }

        @Override
        public ModContainer getProvider() {
            return provider;
        }

        @Override
        public String getDefinition() {
            return definition;
        }
    }

    private static final class BridgeModContainer implements ModContainer {
        private final NormalizedModMetadata metadata;
        private final ModMetadata bridgeMetadata;

        private BridgeModContainer(NormalizedModMetadata metadata) {
            this.metadata = metadata;
            this.bridgeMetadata = new BridgeModMetadata(
                metadata.id(),
                metadata.version(),
                metadata.name()
            );
        }

        @Override
        public ModMetadata getMetadata() {
            return bridgeMetadata;
        }

        @Override
        public Optional<Path> findPath(String file) {
            if (metadata.sourceJar() == null || file == null || file.isBlank()) {
                return Optional.empty();
            }
            try {
                Path root = jarRoot(metadata.sourceJar().toPath());
                String normalized = file.startsWith("/") ? file : "/" + file;
                Path candidate = root.resolve("." + normalized).normalize();
                if (java.nio.file.Files.exists(candidate)) {
                    return Optional.of(candidate);
                }
            } catch (Exception e) {
                return Optional.empty();
            }
            return Optional.empty();
        }

        @Override
        public List<Path> getRootPaths() {
            if (metadata.sourceJar() == null) {
                return List.of();
            }
            try {
                return List.of(jarRoot(metadata.sourceJar().toPath()));
            } catch (Exception e) {
                return List.of();
            }
        }
    }

    private static Path jarRoot(Path jarPath) throws Exception {
        Path normalized = jarPath.toAbsolutePath().normalize();
        FileSystem fileSystem = JAR_FILE_SYSTEMS.get(normalized);
        if (fileSystem == null || !fileSystem.isOpen()) {
            URI uri = URI.create("jar:" + normalized.toUri());
            try {
                fileSystem = FileSystems.newFileSystem(uri, Map.of());
            } catch (FileSystemAlreadyExistsException ignored) {
                fileSystem = FileSystems.getFileSystem(uri);
            }
            JAR_FILE_SYSTEMS.put(normalized, fileSystem);
        }
        return fileSystem.getPath("/");
    }

    private static NormalizedModMetadata platformMetadata(String id) {
        org.intermed.core.metadata.ModPlatform platform = switch (id) {
            case "forge", "javafml" -> org.intermed.core.metadata.ModPlatform.FORGE;
            case "neoforge", "neoforged" -> org.intermed.core.metadata.ModPlatform.NEOFORGE;
            default -> org.intermed.core.metadata.ModPlatform.FABRIC;
        };
        return new NormalizedModMetadata(
            id,
            "intermed-runtime",
            null,
            platform,
            new JsonObject(),
            java.util.Map.of()
        );
    }

    private static final class BridgeModMetadata implements ModMetadata {
        private final String id;
        private final Version version;
        private final String name;
        private final Map<String, CustomValue> customValues;

        private BridgeModMetadata(String id, String version, String name) {
            this.id = id;
            this.version = parseVersion(version);
            this.name = name;
            this.customValues = RuntimeModIndex.get(id)
                .map(NormalizedModMetadata::manifest)
                .map(BridgeModMetadata::readCustomValues)
                .orElseGet(Map::of);
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public Version getVersion() {
            return version;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Map<String, CustomValue> getCustomValues() {
            return customValues;
        }

        @Override
        public boolean containsCustomValue(String key) {
            return customValues.containsKey(key);
        }

        @Override
        public CustomValue getCustomValue(String key) {
            return customValues.get(key);
        }

        private static Version parseVersion(String value) {
            try {
                return Version.parse(value);
            } catch (VersionParsingException e) {
                try {
                    return Version.parse("0.0.0");
                } catch (VersionParsingException impossible) {
                    throw new IllegalStateException(impossible);
                }
            }
        }

        private static Map<String, CustomValue> readCustomValues(JsonObject manifest) {
            if (manifest == null || !manifest.has("custom") || !manifest.get("custom").isJsonObject()) {
                return Map.of();
            }
            LinkedHashMap<String, CustomValue> values = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : manifest.getAsJsonObject("custom").entrySet()) {
                values.put(entry.getKey(), JsonBackedCustomValue.of(entry.getValue()));
            }
            return Map.copyOf(values);
        }
    }

    private static class JsonBackedCustomValue implements CustomValue {
        private final JsonElement element;

        private JsonBackedCustomValue(JsonElement element) {
            this.element = element == null ? com.google.gson.JsonNull.INSTANCE : element;
        }

        static CustomValue of(JsonElement element) {
            if (element != null && element.isJsonObject()) {
                return new JsonBackedCustomObject(element.getAsJsonObject());
            }
            if (element != null && element.isJsonArray()) {
                return new JsonBackedCustomArray(element.getAsJsonArray());
            }
            return new JsonBackedCustomValue(element);
        }

        @Override
        public CvType getType() {
            if (element == null || element.isJsonNull()) {
                return CvType.NULL;
            }
            if (element.isJsonObject()) {
                return CvType.OBJECT;
            }
            if (element.isJsonArray()) {
                return CvType.ARRAY;
            }
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return CvType.BOOLEAN;
            }
            if (primitive.isNumber()) {
                return CvType.NUMBER;
            }
            return CvType.STRING;
        }

        @Override
        public CvObject getAsObject() {
            throw new ClassCastException("Custom value is not an object: " + getType());
        }

        @Override
        public CvArray getAsArray() {
            throw new ClassCastException("Custom value is not an array: " + getType());
        }

        @Override
        public String getAsString() {
            return element == null || element.isJsonNull() ? null : element.getAsString();
        }

        @Override
        public Number getAsNumber() {
            return element == null || element.isJsonNull() ? null : element.getAsNumber();
        }

        @Override
        public boolean getAsBoolean() {
            return element != null && !element.isJsonNull() && element.getAsBoolean();
        }
    }

    private static final class JsonBackedCustomObject extends JsonBackedCustomValue implements CustomValue.CvObject {
        private final JsonObject object;

        private JsonBackedCustomObject(JsonObject object) {
            super(object);
            this.object = object;
        }

        @Override
        public CvObject getAsObject() {
            return this;
        }

        @Override
        public int size() {
            return object.size();
        }

        @Override
        public boolean containsKey(String key) {
            return object.has(key);
        }

        @Override
        public CustomValue get(String key) {
            return JsonBackedCustomValue.of(object.get(key));
        }

        @Override
        public Iterator<Map.Entry<String, CustomValue>> iterator() {
            Iterator<Map.Entry<String, JsonElement>> delegate = object.entrySet().iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return delegate.hasNext();
                }

                @Override
                public Map.Entry<String, CustomValue> next() {
                    Map.Entry<String, JsonElement> next = delegate.next();
                    return Map.entry(next.getKey(), JsonBackedCustomValue.of(next.getValue()));
                }
            };
        }
    }

    private static final class JsonBackedCustomArray extends JsonBackedCustomValue implements CustomValue.CvArray {
        private final JsonArray array;

        private JsonBackedCustomArray(JsonArray array) {
            super(array);
            this.array = array;
        }

        @Override
        public CvArray getAsArray() {
            return this;
        }

        @Override
        public int size() {
            return array.size();
        }

        @Override
        public CustomValue get(int index) {
            return JsonBackedCustomValue.of(array.get(index));
        }

        @Override
        public Iterator<CustomValue> iterator() {
            Iterator<JsonElement> delegate = array.iterator();
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return delegate.hasNext();
                }

                @Override
                public CustomValue next() {
                    return JsonBackedCustomValue.of(delegate.next());
                }
            };
        }
    }
}
