package org.intermed.core.classloading;

import java.io.File;
import java.util.Objects;

/**
 * Represents a library JAR as a vertex in the {@link LibraryConflictGraph}.
 *
 * <p>Identity is based on Maven artifact coordinates ({@code groupId:artifactId}
 * + {@code version}).  Two nodes with the same coordinates are considered equal
 * regardless of their on-disk path.
 *
 * <p>Used by {@link WelshPowellClusterer} to group conflict-free libraries into
 * shared {@link ShaderClassLoader} instances (ТЗ 3.2.1, Requirement 1).
 */
public final class LibraryNode {

    /**
     * Artifact identifier in Maven {@code groupId:artifactId} form,
     * e.g. {@code "com.google.guava:guava"} or {@code "unknown:asm"}.
     */
    public final String artifactId;

    /** Parsed version string, e.g. {@code "32.1.3-jre"} or {@code "9.6"}. */
    public final String version;

    /** Path to the JAR file on disk (may be {@code null} for synthetic nodes). */
    public final File jar;

    /** Visibility scope used to decide which runtime nodes may reference this library. */
    public final String visibilityDomain;

    private final String jarFingerprint;

    public LibraryNode(String artifactId, String version, File jar) {
        this(artifactId, version, jar, LibraryDiscovery.GLOBAL_VISIBILITY_DOMAIN);
    }

    public LibraryNode(String artifactId, String version, File jar, String visibilityDomain) {
        this.artifactId = Objects.requireNonNull(artifactId, "artifactId");
        this.version    = Objects.requireNonNull(version,    "version");
        this.jar        = jar;
        this.visibilityDomain = LibraryDiscovery.normalizeVisibilityDomain(visibilityDomain);
        this.jarFingerprint = jar == null ? "synthetic" : jar.toPath().toAbsolutePath().normalize().toString();
    }

    /**
     * Stable node ID used as the {@link LazyInterMedClassLoader} node name
     * when this library is hosted in a {@link ShaderClassLoader}.
     */
    public String nodeId() {
        return "lib:" + visibilityDomain.replace(':', '_') + ":" + artifactId + ":" + version;
    }

    public boolean isGlobalVisibility() {
        return LibraryDiscovery.GLOBAL_VISIBILITY_DOMAIN.equals(visibilityDomain);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LibraryNode that)) return false;
        return artifactId.equals(that.artifactId)
            && version.equals(that.version)
            && visibilityDomain.equals(that.visibilityDomain)
            && jarFingerprint.equals(that.jarFingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(artifactId, version, visibilityDomain, jarFingerprint);
    }

    @Override
    public String toString() {
        String base = artifactId + "@" + version;
        return isGlobalVisibility() ? base : base + " [" + visibilityDomain + "]";
    }
}
