package org.intermed.harness.discovery;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Client for the Modrinth v2 REST API.
 *
 * <p>No API key is required for read-only access at the standard rate limit
 * (300 requests / minute). The client adds a descriptive User-Agent as
 * requested by the Modrinth API guidelines.
 *
 * @see <a href="https://docs.modrinth.com/api/">Modrinth API docs</a>
 */
public final class ModrinthClient {

    private static final String BASE = "https://api.modrinth.com/v2";
    private static final String USER_AGENT =
        "InterMedTestHarness/8.0 (github.com/intermed; testing-only)";
    private static final int PAGE_SIZE = 100; // Modrinth max per request

    private final HttpClient http;

    public ModrinthClient() {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * Fetches a diverse set of up to {@code limit} mods for the given MC version
     * by combining three Modrinth sort indices:
     * <ul>
     *   <li><b>downloads</b> — most popular mods (½ of limit)</li>
     *   <li><b>follows</b>   — community-starred mods, tend to be larger/heavier (¼)</li>
     *   <li><b>updated</b>   — recently updated mods, catches new large releases (¼)</li>
     * </ul>
     * Results are deduplicated by project ID before returning.
     */
    public List<ModCandidate> fetchTopMods(String mcVersion,
                                           List<String> loaders,
                                           int limit)
            throws IOException, InterruptedException {

        // Allocate slots: 50% downloads, 25% follows, 25% updated
        int byDownloads = (int) Math.ceil(limit * 0.50);
        int byFollows   = (int) Math.ceil(limit * 0.25);
        int byUpdated   = limit - byDownloads - byFollows;

        java.util.LinkedHashMap<String, ModCandidate> seen = new java.util.LinkedHashMap<>();

        System.out.println("[Discover] Fetching ~" + limit + " diverse mods for MC " + mcVersion);
        System.out.println("[Discover]   by downloads: " + byDownloads
            + "  by follows: " + byFollows + "  by updated: " + byUpdated);

        fetchBySortIndex(seen, mcVersion, loaders, "downloads", byDownloads);
        Thread.sleep(300);
        fetchBySortIndex(seen, mcVersion, loaders, "follows",   byFollows);
        Thread.sleep(300);
        fetchBySortIndex(seen, mcVersion, loaders, "updated",   byUpdated);

        List<ModCandidate> results = new ArrayList<>(seen.values());
        System.out.println("[Discover] Resolved " + results.size() + " unique mods.");
        return results;
    }

    private void fetchBySortIndex(java.util.LinkedHashMap<String, ModCandidate> seen,
                                   String mcVersion, List<String> loaders,
                                   String sortIndex, int limit)
            throws IOException, InterruptedException {

        int fetched = 0, offset = 0;
        System.out.println("[Discover]   → index=" + sortIndex + " want=" + limit);

        while (fetched < limit) {
            int pageLimit = Math.min(PAGE_SIZE, limit - fetched + 20); // over-fetch to account for filtered
            String url = buildSearchUrl(mcVersion, loaders, pageLimit, offset, sortIndex);

            JsonObject page = JsonParser.parseString(getJson(url)).getAsJsonObject();
            JsonArray hits = page.getAsJsonArray("hits");
            if (hits.isEmpty()) break;

            int totalHits = page.get("total_hits").getAsInt();

            for (JsonElement elem : hits) {
                JsonObject hit = elem.getAsJsonObject();
                String projectId = hit.get("project_id").getAsString();
                if (seen.containsKey(projectId)) continue; // already resolved

                ModCandidate candidate = resolveCandidate(hit, mcVersion, loaders);
                if (candidate != null) {
                    seen.put(projectId, candidate);
                    fetched++;
                    if (fetched >= limit) break;
                }
            }

            offset += hits.size();
            if (offset >= totalHits) break;
            Thread.sleep(250);
        }
    }

    // ── private helpers ────────────────────────────────────────────────────────

    private String buildSearchUrl(String mcVersion, List<String> loaders,
                                   int limit, int offset, String sortIndex) {
        // Facets must be encoded as JSON arrays
        StringBuilder facets = new StringBuilder("[");
        facets.append("[\"versions:").append(mcVersion).append("\"]");
        facets.append(",[\"project_type:mod\"]");
        if (!loaders.isEmpty()) {
            facets.append(",[");
            for (int i = 0; i < loaders.size(); i++) {
                if (i > 0) facets.append(",");
                facets.append("\"categories:").append(loaders.get(i)).append("\"");
            }
            facets.append("]");
        }
        facets.append("]");

        return BASE + "/search"
            + "?limit=" + limit
            + "&offset=" + offset
            + "&index=" + sortIndex
            + "&facets=" + URLEncoder.encode(facets.toString(), StandardCharsets.UTF_8);
    }

    /**
     * Given a search hit, fetches the specific version entry for the given MC
     * version and builds the {@link ModCandidate}. Returns {@code null} if no
     * compatible version file is found.
     */
    private ModCandidate resolveCandidate(JsonObject hit, String mcVersion,
                                           List<String> loaders)
            throws IOException, InterruptedException {

        String projectId  = hit.get("project_id").getAsString();
        String slug       = hit.get("slug").getAsString();
        String name       = hit.get("title").getAsString();
        long downloads    = hit.get("downloads").getAsLong();
        List<String> categories = extractCategories(hit);

        // Build loader list from hit
        List<String> hitLoaders = new ArrayList<>();
        for (String category : categories) {
            if ((category.equals("forge") || category.equals("fabric") || category.equals("neoforge"))
                    && !hitLoaders.contains(category)) {
                hitLoaders.add(category);
            }
        }

        String clientSide = hit.has("client_side")
            ? hit.get("client_side").getAsString()
            : "unknown";
        String serverSideScope = hit.has("server_side")
            ? hit.get("server_side").getAsString()
            : "unknown";
        boolean serverSide = !serverSideScope.equalsIgnoreCase("unsupported");

        // Fetch version list for this project to get a download URL
        VersionInfo vi = resolveVersion(projectId, mcVersion, loaders.isEmpty() ? null : loaders);
        if (vi == null) return null;

        return new ModCandidate(
            projectId, slug, name, downloads,
            vi.loaders().isEmpty() ? hitLoaders : vi.loaders(),
            vi.versionId(), vi.versionNumber(),
            vi.downloadUrl(), vi.fileName(), serverSide,
            categories,
            "modrinth",
            "https://modrinth.com/mod/" + slug,
            clientSide,
            serverSideScope
        );
    }

    /**
     * Fetches the versions list for a project and returns the best match for
     * the given MC version + loaders.
     */
    private VersionInfo resolveVersion(String projectId, String mcVersion,
                                        List<String> loaders)
            throws IOException, InterruptedException {

        StringBuilder url = new StringBuilder(BASE + "/project/" + projectId + "/version");
        url.append("?game_versions=").append(encodeJsonArray(List.of(mcVersion)));
        if (loaders != null && !loaders.isEmpty()) {
            url.append("&loaders=").append(encodeJsonArray(loaders));
        }

        String json;
        try {
            json = getJson(url.toString());
        } catch (IOException e) {
            // 404 = no version for this MC version; skip gracefully
            return null;
        }

        JsonArray versions = JsonParser.parseString(json).getAsJsonArray();
        if (versions.isEmpty()) return null;

        // Pick the first (most recent) version with at least one primary file
        for (JsonElement vElem : versions) {
            JsonObject v = vElem.getAsJsonObject();
            String versionId = v.get("id").getAsString();
            String versionNumber = v.get("version_number").getAsString();

            List<String> vLoaders = new ArrayList<>();
            for (JsonElement l : v.getAsJsonArray("loaders")) vLoaders.add(l.getAsString());

            JsonArray files = v.getAsJsonArray("files");
            for (JsonElement fElem : files) {
                JsonObject file = fElem.getAsJsonObject();
                boolean primary = file.has("primary") && file.get("primary").getAsBoolean();
                if (primary || files.size() == 1) {
                    String downloadUrl = file.get("url").getAsString();
                    String fileName    = file.get("filename").getAsString();
                    return new VersionInfo(versionId, versionNumber, downloadUrl, fileName, vLoaders);
                }
            }
        }
        return null;
    }

    private String getJson(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() == 404) {
            throw new IOException("404 Not Found: " + url);
        }
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + ": " + url);
        }
        return resp.body();
    }

    private String encodeJsonArray(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append("\"").append(values.get(i)).append("\"");
        }
        json.append("]");
        return URLEncoder.encode(json.toString(), StandardCharsets.UTF_8);
    }

    private List<String> extractCategories(JsonObject hit) {
        LinkedHashSet<String> categories = new LinkedHashSet<>();
        if (hit.has("categories")) {
            for (JsonElement c : hit.getAsJsonArray("categories")) {
                categories.add(c.getAsString());
            }
        }
        if (hit.has("display_categories")) {
            for (JsonElement c : hit.getAsJsonArray("display_categories")) {
                categories.add(c.getAsString());
            }
        }
        return List.copyOf(categories);
    }

    private record VersionInfo(
        String versionId, String versionNumber,
        String downloadUrl, String fileName, List<String> loaders
    ) {}
}
