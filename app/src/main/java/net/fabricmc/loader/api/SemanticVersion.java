package net.fabricmc.loader.api;

import java.util.Optional;

public interface SemanticVersion extends Version {
    int COMPONENT_WILDCARD = -1;

    default int getVersionComponentCount() {
        return 0;
    }

    default int getVersionComponent(int pos) {
        return COMPONENT_WILDCARD;
    }

    default Optional<String> getPrereleaseKey() {
        return Optional.empty();
    }

    default Optional<String> getBuildKey() {
        return Optional.empty();
    }

    default boolean hasWildcard() {
        return false;
    }

    default int compareTo(SemanticVersion other) {
        return getFriendlyString().compareTo(other == null ? "" : other.getFriendlyString());
    }

    static SemanticVersion parse(String version) throws VersionParsingException {
        return new SimpleSemanticVersion(version);
    }
}

final class SimpleSemanticVersion extends SimpleVersion implements SemanticVersion {
    SimpleSemanticVersion(String value) {
        super(value);
    }
}
