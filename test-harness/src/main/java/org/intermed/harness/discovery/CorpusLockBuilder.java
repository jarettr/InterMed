package org.intermed.harness.discovery;

import org.intermed.harness.HarnessConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds the reproducible corpus lockfile from discovered candidates and cached
 * jars on disk.
 */
final class CorpusLockBuilder {

    private CorpusLockBuilder() {}

    static CorpusLock build(HarnessConfig config,
                            List<ModCandidate> candidates,
                            java.util.function.Function<ModCandidate, Path> jarResolver)
            throws IOException {
        List<ModCandidate> ordered = (candidates == null ? List.<ModCandidate>of() : candidates).stream()
            .sorted(Comparator
                .comparingLong(ModCandidate::downloads).reversed()
                .thenComparing(ModCandidate::slug)
                .thenComparing(ModCandidate::versionNumber))
            .toList();

        long maxDownloads = ordered.stream()
            .mapToLong(ModCandidate::downloads)
            .max()
            .orElse(1L);

        List<CorpusLock.Entry> entries = new ArrayList<>(ordered.size());
        CorpusSummaryBuilder summary = new CorpusSummaryBuilder();
        for (int index = 0; index < ordered.size(); index++) {
            ModCandidate candidate = ordered.get(index);
            Path jarPath = jarResolver.apply(candidate);
            JarFeatureScanner.Features features = Files.isRegularFile(jarPath)
                ? JarFeatureScanner.scan(jarPath)
                : new JarFeatureScanner.Features(List.of(), List.of(), List.of(), List.of(), false, false, false);
            double downloadWeight = candidate.downloads() <= 0L
                ? 0.0
                : roundWeight(candidate.downloads() / (double) maxDownloads);
            String popularityTier = popularityTier(index, ordered.size());

            CorpusLock.Entry entry = new CorpusLock.Entry(
                candidate.projectId(),
                candidate.slug(),
                candidate.name(),
                candidate.downloads(),
                downloadWeight,
                popularityTier,
                candidate.loaders(),
                candidate.categories(),
                candidate.versionId(),
                candidate.versionNumber(),
                candidate.source(),
                candidate.sourcePageUrl(),
                candidate.downloadUrl(),
                candidate.fileName(),
                jarPath.toAbsolutePath().normalize().toString(),
                sha256(jarPath),
                sizeBytes(jarPath),
                candidate.serverSide(),
                candidate.clientSide(),
                candidate.serverSideScope(),
                features.declaredDependencies(),
                features.frameworkDependencies(),
                features.mixinConfigs(),
                features.mixinConfigs().size(),
                features.hasNativeLibraries(),
                features.nativeLibraries(),
                features.hasDataPack(),
                features.hasResourcePack(),
                features.hasPackMetadata()
            );
            entries.add(entry);
            summary.add(entry);
        }

        return new CorpusLock(
            CorpusLock.SCHEMA,
            Instant.now().toString(),
            config.mcVersion,
            config.loaderFilter.name(),
            config.topN,
            config.excludeSlugs,
            fingerprint(entries),
            summary.build(),
            entries
        );
    }

    private static double roundWeight(double value) {
        return Math.round(value * 10_000d) / 10_000d;
    }

    private static String popularityTier(int index, int size) {
        if (size <= 0) {
            return "tail";
        }
        double pct = index / (double) size;
        if (pct < 0.10d) {
            return "head";
        }
        if (pct < 0.40d) {
            return "mid";
        }
        return "tail";
    }

    private static long sizeBytes(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }

    private static String sha256(Path path) {
        if (!Files.isRegularFile(path)) {
            return "missing";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static String fingerprint(List<CorpusLock.Entry> entries) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (CorpusLock.Entry entry : entries) {
                update(digest, entry.slug());
                update(digest, entry.versionId());
                update(digest, entry.sha256());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '\n');
    }

    private static final class CorpusSummaryBuilder {
        private int totalCandidates;
        private int runnableCandidates;
        private int clientOnlyCandidates;
        private int withMixins;
        private int withNativeLibraries;
        private int withDataPacks;
        private int withResourcePacks;
        private int withFrameworkDependencies;
        private final Map<String, Integer> byLoader = new LinkedHashMap<>();
        private final Map<String, Integer> byPopularityTier = new LinkedHashMap<>();
        private final Map<String, Integer> byCategory = new LinkedHashMap<>();
        private final Map<String, Integer> bySide = new LinkedHashMap<>();

        private void add(CorpusLock.Entry entry) {
            totalCandidates++;
            if (entry.serverSideCompatible()) {
                runnableCandidates++;
            } else {
                clientOnlyCandidates++;
            }
            if (entry.mixinConfigCount() > 0) {
                withMixins++;
            }
            if (entry.hasNativeLibraries()) {
                withNativeLibraries++;
            }
            if (entry.hasDataPack()) {
                withDataPacks++;
            }
            if (entry.hasResourcePack()) {
                withResourcePacks++;
            }
            if (!entry.frameworkDependencies().isEmpty()) {
                withFrameworkDependencies++;
            }

            byPopularityTier.merge(entry.popularityTier(), 1, Integer::sum);
            bySide.merge(entry.serverSideCompatible() ? "server-compatible" : "client-only", 1, Integer::sum);
            for (String loader : entry.loaders()) {
                byLoader.merge(loader.toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
            for (String category : entry.categories()) {
                byCategory.merge(category.toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }

        private CorpusLock.Summary build() {
            return new CorpusLock.Summary(
                totalCandidates,
                runnableCandidates,
                clientOnlyCandidates,
                withMixins,
                withNativeLibraries,
                withDataPacks,
                withResourcePacks,
                withFrameworkDependencies,
                immutableSorted(byLoader),
                immutableSorted(byPopularityTier),
                immutableSorted(byCategory),
                immutableSorted(bySide)
            );
        }

        private static Map<String, Integer> immutableSorted(Map<String, Integer> input) {
            return input.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .collect(LinkedHashMap::new,
                    (map, entry) -> map.put(entry.getKey(), entry.getValue()),
                    LinkedHashMap::putAll);
        }
    }
}
