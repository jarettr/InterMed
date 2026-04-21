package org.intermed.harness.discovery;

import java.util.List;

/**
 * A single mod as returned by the Modrinth API, enriched with the resolved
 * download URL for the specific MC version under test.
 */
public record ModCandidate(
    /** Modrinth project ID (e.g. "AANobbMI"). */
    String projectId,
    /** Human-readable slug used in URLs (e.g. "jei"). */
    String slug,
    /** Display name. */
    String name,
    /** Total downloads on Modrinth — used for ranking. */
    long downloads,
    /** Loaders this mod supports (e.g. ["forge","fabric","neoforge"]). */
    List<String> loaders,
    /** Modrinth version ID of the specific build chosen for testing. */
    String versionId,
    /** Exact version string (e.g. "19.2.0.232"). */
    String versionNumber,
    /** Direct download URL for the primary JAR file. */
    String downloadUrl,
    /** Filename of the JAR (used for the cached copy). */
    String fileName,
    /** Whether this mod is server-side compatible (false = client-only, skip on server). */
    boolean serverSide,
    /** Additional Modrinth categories/tags captured during discovery. */
    List<String> categories,
    /** Discovery source (currently "modrinth"). */
    String source,
    /** Human-readable project/source page URL. */
    String sourcePageUrl,
    /** Declared client-side scope from Modrinth (required/optional/unsupported/unknown). */
    String clientSide,
    /** Declared server-side scope from Modrinth (required/optional/unsupported/unknown). */
    String serverSideScope
) {
    public ModCandidate {
        loaders = loaders == null ? List.of() : List.copyOf(loaders);
        categories = categories == null ? List.of() : List.copyOf(categories);
        source = source == null || source.isBlank() ? "modrinth" : source;
        sourcePageUrl = sourcePageUrl == null || sourcePageUrl.isBlank()
            ? "https://modrinth.com/mod/" + slug
            : sourcePageUrl;
        clientSide = normalizeSide(clientSide);
        serverSideScope = normalizeSide(serverSideScope);
    }

    public ModCandidate(
        String projectId,
        String slug,
        String name,
        long downloads,
        List<String> loaders,
        String versionId,
        String versionNumber,
        String downloadUrl,
        String fileName,
        boolean serverSide
    ) {
        this(
            projectId,
            slug,
            name,
            downloads,
            loaders,
            versionId,
            versionNumber,
            downloadUrl,
            fileName,
            serverSide,
            List.of(),
            "modrinth",
            "https://modrinth.com/mod/" + slug,
            "unknown",
            serverSide ? "optional" : "unsupported"
        );
    }

    /** Returns true if this mod targets at least one of the given loader names. */
    public boolean supportsAnyLoader(List<String> targetLoaders) {
        for (String l : loaders) {
            for (String t : targetLoaders) {
                if (l.equalsIgnoreCase(t)) return true;
            }
        }
        return false;
    }

    /** Short label for logging (slug + version). */
    public String label() {
        return slug + "@" + versionNumber;
    }

    /** Modrinth project page URL. */
    public String modrinthUrl() {
        return sourcePageUrl;
    }

    public boolean clientOnly() {
        return !serverSide;
    }

    private static String normalizeSide(String side) {
        if (side == null || side.isBlank()) {
            return "unknown";
        }
        return side.toLowerCase(java.util.Locale.ROOT);
    }
}
