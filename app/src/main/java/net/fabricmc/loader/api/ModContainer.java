package net.fabricmc.loader.api;

import net.fabricmc.loader.api.metadata.ModMetadata;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public interface ModContainer {
    ModMetadata getMetadata();

    default Optional<Path> findPath(String file) {
        return Optional.empty();
    }

    default List<Path> getRootPaths() {
        return List.of();
    }
}
