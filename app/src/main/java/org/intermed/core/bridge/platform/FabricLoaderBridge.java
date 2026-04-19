package org.intermed.core.bridge.platform;

import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
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
            org.intermed.core.metadata.ModPlatform platform = switch (id) {
                case "forge", "javafml" -> org.intermed.core.metadata.ModPlatform.FORGE;
                case "neoforge", "neoforged" -> org.intermed.core.metadata.ModPlatform.NEOFORGE;
                default -> org.intermed.core.metadata.ModPlatform.FABRIC;
            };
            return Optional.of(new BridgeModContainer(new NormalizedModMetadata(
                id,
                "intermed-runtime",
                null,
                platform,
                new com.google.gson.JsonObject(),
                java.util.Map.of()
            )));
        }
        return Optional.empty();
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

    private static final class BridgeModMetadata implements ModMetadata {
        private final String id;
        private final String version;
        private final String name;

        private BridgeModMetadata(String id, String version, String name) {
            this.id = id;
            this.version = version;
            this.name = name;
        }

        @Override
        public String getId() {
            return id;
        }

        @Override
        public String getVersion() {
            return version;
        }

        @Override
        public String getName() {
            return name;
        }
    }
}
