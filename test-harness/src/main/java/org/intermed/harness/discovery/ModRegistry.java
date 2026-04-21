package org.intermed.harness.discovery;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.intermed.harness.HarnessConfig;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Manages the local mod cache: fetching the top-N mod list from Modrinth,
 * persisting it to disk, and downloading the individual JAR files.
 *
 * <p>Disk layout under {@code config.modsCache()}:
 * <pre>
 *   mods/
 *   ├── registry.json        — serialised List&lt;ModCandidate&gt;
 *   └── jars/
 *       ├── jei-19.2.0.232.jar
 *       └── ...
 * </pre>
 */
public final class ModRegistry {

    private static final String REGISTRY_FILE = "registry.json";
    private static final String CORPUS_LOCK_FILE = "corpus-lock.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final HarnessConfig config;
    private final HttpClient http;

    public ModRegistry(HarnessConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();
    }

    // ── Discovery ──────────────────────────────────────────────────────────────

    /**
     * Fetches (or loads from cache) the top-N mod list, downloads missing jars,
     * and emits a reproducible corpus lockfile.
     */
    public ResolvedCorpus discoverAndLock() throws IOException, InterruptedException {
        return resolveCorpus(false);
    }

    /**
     * Loads the cached discovery registry without hitting the network, then
     * rebuilds the corpus lock from the current filtered selection.
     */
    public ResolvedCorpus loadCachedCorpus() throws IOException, InterruptedException {
        return resolveCorpus(true);
    }

    /**
     * Returns the most recent cached lockfile if it exists.
     */
    public Path corpusLockPath() {
        return config.modsCache().resolve(CORPUS_LOCK_FILE);
    }

    /**
     * Returns the local path where a mod's JAR is (or will be) stored.
     */
    public Path localJarPath(ModCandidate mod) {
        return config.modsCache().resolve("jars").resolve(mod.fileName());
    }

    // ── private ────────────────────────────────────────────────────────────────

    private ResolvedCorpus resolveCorpus(boolean cachedOnly)
            throws IOException, InterruptedException {
        Path registryFile = config.modsCache().resolve(REGISTRY_FILE);

        List<ModCandidate> candidates;
        if (Files.exists(registryFile)) {
            System.out.println("[Discover] Loading mod registry from cache: " + registryFile);
            candidates = loadRegistry(registryFile);
            System.out.println("[Discover] Loaded " + candidates.size() + " cached mod entries.");
            if (!cachedOnly && candidates.size() < config.topN) {
                System.out.println("[Discover] Cached registry is smaller than requested top="
                    + config.topN + "; refreshing from Modrinth.");
                candidates = fetchFromModrinth();
                saveRegistry(registryFile, candidates);
            }
        } else if (cachedOnly) {
            throw new IOException("Cached registry not found: " + registryFile
                + " — run 'discover' first or omit --skip-discover");
        } else {
            candidates = fetchFromModrinth();
            saveRegistry(registryFile, candidates);
        }

        candidates = applyFilters(candidates);

        // Download any missing JARs
        downloadMissingJars(candidates);

        CorpusLock lock = buildCorpusLock(candidates);
        new CorpusLockIO().write(lock, corpusLockPath());
        System.out.println("[Discover] Saved corpus lock to: " + corpusLockPath());
        return new ResolvedCorpus(lock, lock.runnableMods());
    }

    private List<ModCandidate> fetchFromModrinth() throws IOException, InterruptedException {
        List<String> loaders = switch (config.loaderFilter) {
            case FORGE  -> List.of("forge");
            case FABRIC -> List.of("fabric");
            case NEOFORGE -> List.of("neoforge");
            case ALL    -> List.of();
        };
        ModrinthClient client = new ModrinthClient();
        return client.fetchTopMods(config.mcVersion, loaders, config.topN);
    }

    private List<ModCandidate> applyFilters(List<ModCandidate> candidates) {
        if (config.loaderFilter != HarnessConfig.LoaderFilter.ALL) {
            String filterLoader = config.loaderFilter.name().toLowerCase();
            candidates = candidates.stream()
                .filter(m -> m.supportsAnyLoader(List.of(filterLoader)))
                .toList();
            System.out.println("[Discover] After loader filter (" + filterLoader + "): "
                + candidates.size() + " mods.");
        }

        if (!config.excludeSlugs.isEmpty()) {
            candidates = candidates.stream()
                .filter(m -> !config.excludeSlugs.contains(m.slug()))
                .toList();
            System.out.println("[Discover] After exclusions: " + candidates.size() + " mods.");
        }

        LinkedHashMap<String, ModCandidate> deduped = new LinkedHashMap<>();
        for (ModCandidate candidate : candidates) {
            deduped.putIfAbsent(candidate.projectId() + ":" + candidate.versionId(), candidate);
        }
        if (deduped.size() != candidates.size()) {
            System.out.println("[Discover] After dedupe: " + deduped.size() + " mods.");
        }

        return deduped.values().stream()
            .sorted(Comparator
                .comparingLong(ModCandidate::downloads).reversed()
                .thenComparing(ModCandidate::slug)
                .thenComparing(ModCandidate::versionNumber))
            .limit(config.topN)
            .toList();
    }

    private void downloadMissingJars(List<ModCandidate> mods)
            throws IOException, InterruptedException {

        Path jarsDir = config.modsCache().resolve("jars");
        Files.createDirectories(jarsDir);

        int total = mods.size();
        int downloaded = 0;
        int skipped = 0;

        for (ModCandidate mod : mods) {
            Path dest = jarsDir.resolve(mod.fileName());
            if (Files.exists(dest)) {
                skipped++;
                continue;
            }
            System.out.printf("[Discover] Downloading (%d/%d) %s ...%n",
                downloaded + skipped + 1, total, mod.label());
            try {
                downloadFile(mod.downloadUrl(), dest);
                downloaded++;
            } catch (IOException e) {
                System.err.println("[Discover] WARNING: failed to download " + mod.label()
                    + ": " + e.getMessage());
            }
            // Polite rate limiting
            Thread.sleep(100);
        }

        System.out.printf("[Discover] JARs: %d downloaded, %d already cached.%n",
            downloaded, skipped);
    }

    private void downloadFile(String url, Path dest) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(5))
            .header("User-Agent", "InterMedTestHarness/8.0")
            .GET()
            .build();
        HttpResponse<Path> resp = http.send(req,
            HttpResponse.BodyHandlers.ofFile(
                dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE));
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(dest);
            throw new IOException("HTTP " + resp.statusCode());
        }
    }

    private List<ModCandidate> loadRegistry(Path file) throws IOException {
        String json = Files.readString(file);
        return GSON.fromJson(json, new TypeToken<ArrayList<ModCandidate>>() {}.getType());
    }

    private void saveRegistry(Path file, List<ModCandidate> mods) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(mods));
        System.out.println("[Discover] Saved mod registry to: " + file);
    }

    private CorpusLock buildCorpusLock(List<ModCandidate> candidates) throws IOException {
        try {
            return CorpusLockBuilder.build(config, candidates, this::localJarPath);
        } catch (IOException e) {
            throw e;
        }
    }
}
