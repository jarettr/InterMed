package net.fabricmc.loader.api;

public interface Version extends Comparable<Version> {
    String getFriendlyString();

    static Version parse(String version) throws VersionParsingException {
        return new SimpleVersion(version);
    }
}

class SimpleVersion implements Version {
    private final String value;

    SimpleVersion(String value) {
        this.value = value == null || value.isBlank() ? "0.0.0" : value;
    }

    @Override
    public String getFriendlyString() {
        return value;
    }

    @Override
    public int compareTo(Version other) {
        return getFriendlyString().compareTo(other == null ? "" : other.getFriendlyString());
    }

    @Override
    public String toString() {
        return value;
    }
}
