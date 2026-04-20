package net.fabricmc.loader.api.metadata;

public interface ModMetadata {
    String getId();

    default String getVersion() {
        return "0.0.0";
    }

    default String getName() {
        return getId();
    }
}
