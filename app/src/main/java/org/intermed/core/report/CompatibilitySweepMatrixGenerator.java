package org.intermed.core.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.intermed.core.InterMedVersion;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links a compatibility corpus manifest to stored harness outcomes.
 *
 * <p>The output is intentionally a normalization layer. It does not execute
 * Minecraft and it does not upgrade harness evidence to field-tested evidence.</p>
 */
public final class CompatibilitySweepMatrixGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Integer> OUTCOME_RANK = outcomeRank();
    private static final Pattern VERSION_START = Pattern.compile("(?i)(^|[-_+.])(?:mc)?\\d");
    private static final Pattern SEMANTIC_VERSION = Pattern.compile("(?i)(\\d+(?:\\.\\d+){1,3}(?:-[a-z0-9]+)?)");
    private static final Set<String> GENERIC_KEYS = Set.of(
        "api",
        "base",
        "bukkit",
        "client",
        "common",
        "core",
        "fabric",
        "forge",
        "lib",
        "library",
        "mc",
        "minecraft",
        "mod",
        "neoforge",
        "paper",
        "quilt",
        "server"
    );
    private static final List<String> TRAILING_ALIAS_SUFFIXES = List.of(
        "neoforge",
        "minecraft",
        "library",
        "fabric",
        "bukkit",
        "common",
        "server",
        "client",
        "forge",
        "paper",
        "quilt",
        "base",
        "core",
        "api",
        "lib",
        "mod"
    );
    private static final List<String> LEADING_ALIAS_PREFIXES = List.of(
        "simple"
    );

    private CompatibilitySweepMatrixGenerator() {}

    public static void writeReport(Path output, Path corpusPath, Path resultsPath) throws Exception {
        writeReport(output, readRequiredJson(corpusPath), resultsPath);
    }

    public static void writeReport(Path output, JsonObject corpus, Path resultsPath) throws Exception {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, GSON.toJson(generate(corpus, resultsPath)), StandardCharsets.UTF_8);
    }

    public static JsonObject generate(JsonObject corpus, Path resultsPath) throws Exception {
        return generate(corpus, readOptionalJson(resultsPath));
    }

    public static JsonObject generate(JsonObject corpus, JsonObject harnessResults) {
        JsonObject safeCorpus = corpus == null ? new JsonObject() : corpus;
        List<JsonObject> resultRows = resultRows(harnessResults);
        ResultIndex resultIndex = indexResults(resultRows);
        Set<String> matchedResultIds = new LinkedHashSet<>();

        JsonArray candidates = new JsonArray();
        MatrixSummary summary = new MatrixSummary();
        for (JsonObject candidate : candidateRows(safeCorpus)) {
            JsonObject enriched = candidate.deepCopy();
            List<JsonObject> matches = matchesFor(candidate, resultIndex);
            JsonArray matched = new JsonArray();
            for (JsonObject result : matches) {
                JsonObject reference = resultReference(result);
                matched.add(reference);
                matchedResultIds.add(resultId(result));
            }
            String status = sweepStatus(matches);
            enriched.addProperty("sweepStatus", status);
            enriched.addProperty("fieldTested", false);
            if (!matches.isEmpty()) {
                enriched.addProperty("bestOutcome", bestOutcome(matches));
                enriched.addProperty("worstOutcome", worstOutcome(matches));
            }
            enriched.add("matchedResults", matched);
            candidates.add(enriched);
            summary.addCandidate(enriched, !matches.isEmpty());
        }

        JsonArray results = new JsonArray();
        JsonArray unmatched = new JsonArray();
        for (JsonObject result : resultRows) {
            JsonObject normalized = normalizeResult(result);
            results.add(normalized);
            summary.addResult(result);
            if (!matchedResultIds.contains(resultId(result))) {
                unmatched.add(normalized);
            }
        }
        summary.unmatchedResults = unmatched.size();

        JsonObject root = new JsonObject();
        root.addProperty("schema", "intermed-compatibility-sweep-matrix-v1");
        root.addProperty("intermedVersion", InterMedVersion.BUILD_VERSION);
        root.addProperty("generatedAt", Instant.now().toString());
        root.add("scope", scope(!resultRows.isEmpty()));
        root.add("sources", sources(safeCorpus, harnessResults));
        root.add("summary", summary.toJson());
        root.add("candidates", candidates);
        root.add("results", results);
        root.add("unmatchedResults", unmatched);
        return root;
    }

    private static JsonObject scope(boolean resultsAvailable) {
        JsonObject scope = new JsonObject();
        scope.addProperty("minecraft", "1.20.1");
        scope.addProperty("evidenceLevel", resultsAvailable ? "harness-result-normalization" : "corpus-only-not-run");
        scope.addProperty("claim",
            "Links corpus candidates to stored harness outcomes; this is not gameplay, multiplayer, security, or field evidence.");
        return scope;
    }

    private static JsonObject sources(JsonObject corpus, JsonObject harnessResults) {
        JsonObject sources = new JsonObject();
        sources.addProperty("corpusSchema", string(corpus, "schema", "unknown"));
        sources.addProperty("corpusGeneratedAt", string(corpus, "generatedAt", ""));
        sources.addProperty("resultsAvailable", harnessResults != null && harnessResults.has("results"));
        if (harnessResults != null) {
            sources.addProperty("harnessGeneratedAt", string(harnessResults, "generatedAt", ""));
            sources.addProperty("harnessTotalCount", integer(harnessResults, "totalCount", resultRows(harnessResults).size()));
        }
        return sources;
    }

    private static List<JsonObject> candidateRows(JsonObject corpus) {
        JsonArray array = corpus.has("candidates") && corpus.get("candidates").isJsonArray()
            ? corpus.getAsJsonArray("candidates")
            : new JsonArray();
        List<JsonObject> rows = new ArrayList<>();
        for (JsonElement element : array) {
            if (element != null && element.isJsonObject()) {
                rows.add(element.getAsJsonObject());
            }
        }
        rows.sort(Comparator
            .comparing((JsonObject row) -> string(row, "id", ""))
            .thenComparing(row -> string(row, "file", "")));
        return rows;
    }

    private static List<JsonObject> resultRows(JsonObject harnessResults) {
        if (harnessResults == null || !harnessResults.has("results") || !harnessResults.get("results").isJsonArray()) {
            return List.of();
        }
        List<JsonObject> rows = new ArrayList<>();
        for (JsonElement element : harnessResults.getAsJsonArray("results")) {
            if (element != null && element.isJsonObject()) {
                rows.add(element.getAsJsonObject());
            }
        }
        rows.sort(Comparator.comparing(row -> string(row, "id", "")));
        return rows;
    }

    private static ResultIndex indexResults(List<JsonObject> results) {
        Map<String, List<JsonObject>> index = new LinkedHashMap<>();
        for (JsonObject result : results) {
            for (String key : resultAliases(result)) {
                index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(result);
            }
        }
        return new ResultIndex(index);
    }

    private static List<JsonObject> matchesFor(JsonObject candidate, ResultIndex resultIndex) {
        LinkedHashMap<String, JsonObject> matches = new LinkedHashMap<>();
        for (String key : candidateAliases(candidate)) {
            for (JsonObject result : resultIndex.byAlias().getOrDefault(key, List.of())) {
                if (candidateCompatibleWithResult(candidate, result)) {
                    matches.put(resultId(result), result);
                }
            }
        }
        return List.copyOf(matches.values());
    }

    private static Set<String> candidateAliases(JsonObject candidate) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        addAliases(keys, string(candidate, "id", ""));
        addAliases(keys, string(candidate, "name", ""));
        addAliases(keys, fileStem(string(candidate, "file", "")));
        artifactAliases(string(candidate, "file", "")).forEach(alias -> addAliases(keys, alias));
        return keys;
    }

    private static Set<String> resultAliases(JsonObject result) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        JsonArray mods = result.has("mods") && result.get("mods").isJsonArray()
            ? result.getAsJsonArray("mods")
            : new JsonArray();
        for (JsonElement element : mods) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject mod = element.getAsJsonObject();
            addAliases(keys, string(mod, "slug", ""));
            addAliases(keys, string(mod, "name", ""));
            addAliases(keys, modrinthSlug(mod));
        }
        return keys;
    }

    private static void addAliases(Set<String> keys, String raw) {
        String normalized = normalizeKey(raw);
        addNormalizedAlias(keys, normalized);
        for (String variant : aliasVariants(normalized)) {
            addNormalizedAlias(keys, variant);
        }
        for (String token : rawTokens(raw)) {
            addNormalizedAlias(keys, token);
            for (String variant : aliasVariants(token)) {
                addNormalizedAlias(keys, variant);
            }
        }
    }

    private static void addNormalizedAlias(Set<String> keys, String normalized) {
        if (normalized == null || normalized.isBlank() || normalized.length() < 3 || GENERIC_KEYS.contains(normalized)) {
            return;
        }
        keys.add(normalized);
    }

    private static Set<String> aliasVariants(String normalized) {
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        if (normalized == null || normalized.isBlank()) {
            return variants;
        }
        for (String prefix : LEADING_ALIAS_PREFIXES) {
            if (normalized.startsWith(prefix) && normalized.length() > prefix.length() + 2) {
                variants.add(normalized.substring(prefix.length()));
            }
        }
        String current = normalized;
        boolean stripped;
        do {
            stripped = false;
            for (String suffix : TRAILING_ALIAS_SUFFIXES) {
                if (current.endsWith(suffix) && current.length() > suffix.length() + 2) {
                    current = current.substring(0, current.length() - suffix.length());
                    variants.add(current);
                    stripped = true;
                    break;
                }
            }
        } while (stripped);
        return variants;
    }

    private static List<String> rawTokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String token : raw.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            String normalized = normalizeKey(token);
            if (!normalized.isBlank()) {
                tokens.add(normalized);
            }
        }
        return tokens;
    }

    private static String normalizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static List<String> artifactAliases(String file) {
        String stem = embeddedJarStem(fileStem(file));
        if (stem.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        String artifact = artifactPrefix(stem);
        if (!artifact.isBlank()) {
            aliases.add(artifact);
        }
        aliases.add(stem);
        return List.copyOf(aliases);
    }

    private static String embeddedJarStem(String stem) {
        if (stem == null || stem.isBlank()) {
            return "";
        }
        String[] nested = stem.split("(?i)\\.jar-[a-f0-9]{8,}-");
        return nested.length == 0 ? stem : nested[nested.length - 1];
    }

    private static String artifactPrefix(String stem) {
        if (stem == null || stem.isBlank()) {
            return "";
        }
        Matcher matcher = VERSION_START.matcher(stem);
        if (!matcher.find()) {
            return stem;
        }
        int cut = matcher.start();
        if (cut == 0) {
            return "";
        }
        return stem.substring(0, cut);
    }

    private static String modrinthSlug(JsonObject mod) {
        String url = string(mod, "modrinthUrl", "");
        if (url.isBlank()) {
            return "";
        }
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }

    private static boolean candidateCompatibleWithResult(JsonObject candidate, JsonObject result) {
        return loaderCompatible(candidate, result) && versionCompatible(candidate, result);
    }

    private static boolean loaderCompatible(JsonObject candidate, JsonObject result) {
        String platform = normalizeKey(string(candidate, "platform", ""));
        String loader = normalizeKey(string(result, "loader", ""));
        return platform.isBlank()
            || loader.isBlank()
            || platform.equals(loader)
            || isDataDrivenPlatform(platform)
            || candidateDeclaresLoader(candidate, loader)
            || resultDeclaresLoader(result, platform) && resultDeclaresLoader(result, loader);
    }

    private static boolean isDataDrivenPlatform(String platform) {
        return platform.equals("datapack")
            || platform.equals("resourcepack")
            || platform.equals("dataresourcepack");
    }

    private static boolean candidateDeclaresLoader(JsonObject candidate, String loader) {
        if (loader == null || loader.isBlank()) {
            return false;
        }
        String haystack = normalizeKey(
            string(candidate, "file", "") + " "
                + string(candidate, "version", "") + " "
                + string(candidate, "name", "")
        );
        return haystack.contains(loader) || haystack.contains("merged") || haystack.contains("multiloader");
    }

    private static boolean resultDeclaresLoader(JsonObject result, String loader) {
        if (loader == null || loader.isBlank()) {
            return false;
        }
        JsonArray mods = result.has("mods") && result.get("mods").isJsonArray()
            ? result.getAsJsonArray("mods")
            : new JsonArray();
        for (JsonElement element : mods) {
            if (element != null && element.isJsonObject()) {
                JsonObject mod = element.getAsJsonObject();
                String haystack = normalizeKey(
                    string(mod, "slug", "") + " "
                        + string(mod, "name", "") + " "
                        + string(mod, "version", "")
                );
                if (haystack.contains(loader) || haystack.contains("merged") || haystack.contains("multiloader")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean versionCompatible(JsonObject candidate, JsonObject result) {
        String candidateVersion = string(candidate, "version", "");
        if (!hasMeaningfulVersion(candidateVersion)) {
            return true;
        }

        JsonArray mods = result.has("mods") && result.get("mods").isJsonArray()
            ? result.getAsJsonArray("mods")
            : new JsonArray();
        if (mods.isEmpty()) {
            return true;
        }

        for (JsonElement element : mods) {
            if (element != null && element.isJsonObject()) {
                String resultVersion = string(element.getAsJsonObject(), "version", "");
                if (versionMatches(candidateVersion, resultVersion)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasMeaningfulVersion(String version) {
        String normalized = version == null ? "" : version.trim();
        return !normalized.isBlank()
            && !"null".equalsIgnoreCase(normalized)
            && !normalized.startsWith("${");
    }

    private static boolean versionMatches(String left, String right) {
        if (!hasMeaningfulVersion(right)) {
            return true;
        }
        String normalizedLeft = normalizeVersion(left);
        String normalizedRight = normalizeVersion(right);
        if (normalizedLeft.isBlank() || normalizedRight.isBlank()) {
            return true;
        }
        String semanticLeft = semanticBaseVersion(left);
        String semanticRight = semanticBaseVersion(right);
        return normalizedLeft.equals(normalizedRight)
            || normalizedLeft.startsWith(normalizedRight)
            || normalizedRight.startsWith(normalizedLeft)
            || normalizedLeft.contains(normalizedRight)
            || normalizedRight.contains(normalizedLeft)
            || (!semanticLeft.isBlank() && semanticLeft.equals(semanticRight));
    }

    private static String normalizeVersion(String version) {
        if (version == null) {
            return "";
        }
        return version.toLowerCase(Locale.ROOT)
            .replaceAll("\\$\\{[^}]+}", "")
            .replaceAll("(?i)(minecraft|fabric|forge|neoforge|quilt|bukkit|paper|mc)", "")
            .replaceAll("[^a-z0-9]", "");
    }

    private static String semanticBaseVersion(String version) {
        if (version == null) {
            return "";
        }
        Matcher matcher = SEMANTIC_VERSION.matcher(version);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String fileStem(String file) {
        if (file == null) {
            return "";
        }
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        String name = slash >= 0 ? file.substring(slash + 1) : file;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".zip")
            ? name.substring(0, name.length() - 4)
            : name;
    }

    private static String sweepStatus(List<JsonObject> matches) {
        if (matches.isEmpty()) {
            return "not-run";
        }
        boolean anyPass = matches.stream().anyMatch(CompatibilitySweepMatrixGenerator::passed);
        boolean anyFail = matches.stream().anyMatch(result -> !passed(result));
        if (anyPass && anyFail) {
            return "mixed";
        }
        return anyPass ? "passed" : "failed";
    }

    private static boolean passed(JsonObject result) {
        if (result.has("passed") && result.get("passed").isJsonPrimitive()) {
            return result.get("passed").getAsBoolean();
        }
        String outcome = string(result, "outcome", "");
        return outcome.equals("PASS") || outcome.equals("PASS_BRIDGED") || outcome.equals("PERF_WARN");
    }

    private static String bestOutcome(List<JsonObject> matches) {
        return matches.stream()
            .map(result -> string(result, "outcome", "UNKNOWN"))
            .min(Comparator.comparingInt(CompatibilitySweepMatrixGenerator::outcomeRank))
            .orElse("UNKNOWN");
    }

    private static String worstOutcome(List<JsonObject> matches) {
        return matches.stream()
            .map(result -> string(result, "outcome", "UNKNOWN"))
            .max(Comparator.comparingInt(CompatibilitySweepMatrixGenerator::outcomeRank))
            .orElse("UNKNOWN");
    }

    private static JsonObject resultReference(JsonObject result) {
        JsonObject reference = new JsonObject();
        reference.addProperty("id", resultId(result));
        reference.addProperty("loader", string(result, "loader", ""));
        reference.addProperty("outcome", string(result, "outcome", "UNKNOWN"));
        reference.addProperty("passed", passed(result));
        reference.addProperty("startupMs", longValue(result, "startupMs", 0L));
        reference.addProperty("modCount", integer(result, "modCount", 0));
        return reference;
    }

    private static JsonObject normalizeResult(JsonObject result) {
        JsonObject normalized = resultReference(result);
        normalized.addProperty("description", string(result, "description", ""));
        normalized.addProperty("exitCode", integer(result, "exitCode", 0));
        normalized.addProperty("executedAt", string(result, "executedAt", ""));
        normalized.add("mods", copyArray(result, "mods"));
        normalized.add("issues", copyArray(result, "issues"));
        return normalized;
    }

    private static JsonArray copyArray(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            return new JsonArray();
        }
        return object.getAsJsonArray(key).deepCopy();
    }

    private static String resultId(JsonObject result) {
        String id = string(result, "id", "");
        return id.isBlank()
            ? "result-" + Integer.toHexString(result.toString().hashCode())
            : id;
    }

    private static JsonObject readRequiredJson(Path path) throws Exception {
        if (path == null) {
            throw new IllegalArgumentException("corpus path is required");
        }
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject readOptionalJson(Path path) throws Exception {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static String string(JsonObject object, String key, String fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        return object.get(key).getAsString();
    }

    private static int integer(JsonObject object, String key, int fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static long longValue(JsonObject object, String key, long fallback) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) {
            return fallback;
        }
        try {
            return object.get(key).getAsLong();
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int outcomeRank(String outcome) {
        return OUTCOME_RANK.getOrDefault(outcome, 99);
    }

    private static Map<String, Integer> outcomeRank() {
        LinkedHashMap<String, Integer> rank = new LinkedHashMap<>();
        rank.put("PASS", 0);
        rank.put("PASS_BRIDGED", 1);
        rank.put("PERF_WARN", 2);
        rank.put("FAIL_TIMEOUT", 3);
        rank.put("FAIL_CRASH", 4);
        rank.put("FAIL_MIXIN", 5);
        rank.put("FAIL_DEPENDENCY", 6);
        rank.put("FAIL_CAPABILITY", 7);
        rank.put("FAIL_OTHER", 8);
        return Map.copyOf(rank);
    }

    private record ResultIndex(Map<String, List<JsonObject>> byAlias) {}

    private static final class MatrixSummary {
        private int corpusTotal;
        private int corpusParsed;
        private int corpusUnsupported;
        private int corpusFailed;
        private int linkedCandidates;
        private int untestedCandidates;
        private int resultsTotal;
        private int passCount;
        private int failCount;
        private int unmatchedResults;
        private long startupTotal;
        private int startupSamples;
        private final Map<String, Integer> byOutcome = new LinkedHashMap<>();
        private final Map<String, Integer> byLoader = new LinkedHashMap<>();
        private final Map<String, Integer> byCandidateSweepStatus = new LinkedHashMap<>();

        private void addCandidate(JsonObject candidate, boolean linked) {
            corpusTotal++;
            String status = string(candidate, "status", "");
            if ("parsed".equals(status)) {
                corpusParsed++;
            } else if ("unsupported".equals(status)) {
                corpusUnsupported++;
            } else if ("failed".equals(status)) {
                corpusFailed++;
            }
            if (linked) {
                linkedCandidates++;
            } else {
                untestedCandidates++;
            }
            byCandidateSweepStatus.merge(string(candidate, "sweepStatus", "unknown"), 1, Integer::sum);
        }

        private void addResult(JsonObject result) {
            resultsTotal++;
            if (passed(result)) {
                passCount++;
            } else {
                failCount++;
            }
            byOutcome.merge(string(result, "outcome", "UNKNOWN"), 1, Integer::sum);
            byLoader.merge(string(result, "loader", "UNKNOWN"), 1, Integer::sum);
            long startupMs = longValue(result, "startupMs", 0L);
            if (startupMs > 0) {
                startupTotal += startupMs;
                startupSamples++;
            }
        }

        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("corpusTotal", corpusTotal);
            json.addProperty("corpusParsed", corpusParsed);
            json.addProperty("corpusUnsupported", corpusUnsupported);
            json.addProperty("corpusFailed", corpusFailed);
            json.addProperty("linkedCandidates", linkedCandidates);
            json.addProperty("untestedCandidates", untestedCandidates);
            json.addProperty("resultsTotal", resultsTotal);
            json.addProperty("passCount", passCount);
            json.addProperty("failCount", failCount);
            json.addProperty("passRate", resultsTotal == 0 ? 0.0 : Math.round(passCount * 1000.0 / resultsTotal) / 10.0);
            json.addProperty("avgStartupMs", startupSamples == 0 ? 0.0 : startupTotal * 1.0 / startupSamples);
            json.addProperty("unmatchedResults", unmatchedResults);
            json.add("byOutcome", intMap(byOutcome));
            json.add("byLoader", intMap(byLoader));
            json.add("byCandidateSweepStatus", intMap(byCandidateSweepStatus));
            return json;
        }

        private static JsonObject intMap(Map<String, Integer> values) {
            JsonObject json = new JsonObject();
            values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> json.addProperty(entry.getKey(), entry.getValue()));
            return json;
        }
    }
}
