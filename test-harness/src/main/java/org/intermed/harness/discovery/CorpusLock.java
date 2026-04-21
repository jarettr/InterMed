package org.intermed.harness.discovery;

import java.util.List;
import java.util.Map;

/**
 * Reproducible discovery lockfile for a harness corpus.
 *
 * <p>This artifact answers "what exactly are we testing?" and intentionally
 * does not contain pass/fail compatibility outcomes.</p>
 */
public record CorpusLock(
    String schema,
    String generatedAt,
    String mcVersion,
    String loaderFilter,
    int topN,
    List<String> excludeSlugs,
    String corpusFingerprint,
    Summary summary,
    List<Entry> entries
) {
    public static final String SCHEMA = "intermed-harness-corpus-lock-v1";

    public CorpusLock {
        excludeSlugs = excludeSlugs == null ? List.of() : List.copyOf(excludeSlugs);
        entries = entries == null ? List.of() : List.copyOf(entries);
    }

    public List<ModCandidate> runnableMods() {
        return entries.stream()
            .filter(Entry::serverSideCompatible)
            .map(Entry::toModCandidate)
            .toList();
    }

    public record Entry(
        String projectId,
        String slug,
        String name,
        long downloads,
        double downloadWeight,
        String popularityTier,
        List<String> loaders,
        List<String> categories,
        String versionId,
        String versionNumber,
        String source,
        String sourcePageUrl,
        String downloadUrl,
        String fileName,
        String jarPath,
        String sha256,
        long sizeBytes,
        boolean serverSideCompatible,
        String clientSide,
        String serverSide,
        List<String> declaredDependencies,
        List<String> frameworkDependencies,
        List<String> mixinConfigs,
        int mixinConfigCount,
        boolean hasNativeLibraries,
        List<String> nativeLibraries,
        boolean hasDataPack,
        boolean hasResourcePack,
        boolean hasPackMetadata
    ) {
        public Entry {
            loaders = loaders == null ? List.of() : List.copyOf(loaders);
            categories = categories == null ? List.of() : List.copyOf(categories);
            declaredDependencies = declaredDependencies == null ? List.of() : List.copyOf(declaredDependencies);
            frameworkDependencies = frameworkDependencies == null ? List.of() : List.copyOf(frameworkDependencies);
            mixinConfigs = mixinConfigs == null ? List.of() : List.copyOf(mixinConfigs);
            nativeLibraries = nativeLibraries == null ? List.of() : List.copyOf(nativeLibraries);
            source = source == null || source.isBlank() ? "modrinth" : source;
            sourcePageUrl = sourcePageUrl == null || sourcePageUrl.isBlank()
                ? "https://modrinth.com/mod/" + slug
                : sourcePageUrl;
            clientSide = normalizeSide(clientSide);
            serverSide = normalizeSide(serverSide);
            popularityTier = popularityTier == null || popularityTier.isBlank() ? "tail" : popularityTier;
        }

        public ModCandidate toModCandidate() {
            return new ModCandidate(
                projectId,
                slug,
                name,
                downloads,
                loaders,
                versionId,
                versionNumber,
                downloadUrl,
                fileName,
                serverSideCompatible,
                categories,
                source,
                sourcePageUrl,
                clientSide,
                serverSide
            );
        }

        private static String normalizeSide(String side) {
            if (side == null || side.isBlank()) {
                return "unknown";
            }
            return side.toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record Summary(
        int totalCandidates,
        int runnableCandidates,
        int clientOnlyCandidates,
        int withMixins,
        int withNativeLibraries,
        int withDataPacks,
        int withResourcePacks,
        int withFrameworkDependencies,
        Map<String, Integer> byLoader,
        Map<String, Integer> byPopularityTier,
        Map<String, Integer> byCategory,
        Map<String, Integer> bySide
    ) {}
}
