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

/**
 * Links a compatibility corpus manifest to stored harness outcomes.
 *
 * <p>The output is intentionally a normalization layer. It does not execute
 * Minecraft and it does not upgrade harness evidence to field-tested evidence.</p>
 */
public final class CompatibilitySweepMatrixGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, Integer> OUTCOME_RANK = outcomeRank();

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
        Map<String, List<JsonObject>> resultsByKey = indexResults(resultRows);
        Set<String> matchedResultIds = new LinkedHashSet<>();

        JsonArray candidates = new JsonArray();
        MatrixSummary summary = new MatrixSummary();
        for (JsonObject candidate : candidateRows(safeCorpus)) {
            JsonObject enriched = candidate.deepCopy();
            List<JsonObject> matches = matchesFor(candidate, resultsByKey);
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

    private static Map<String, List<JsonObject>> indexResults(List<JsonObject> results) {
        Map<String, List<JsonObject>> index = new LinkedHashMap<>();
        for (JsonObject result : results) {
            for (String key : resultKeys(result)) {
                index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(result);
            }
        }
        return index;
    }

    private static List<JsonObject> matchesFor(JsonObject candidate, Map<String, List<JsonObject>> resultsByKey) {
        LinkedHashMap<String, JsonObject> matches = new LinkedHashMap<>();
        for (String key : candidateKeys(candidate)) {
            for (JsonObject result : resultsByKey.getOrDefault(key, List.of())) {
                matches.put(resultId(result), result);
            }
        }
        return List.copyOf(matches.values());
    }

    private static Set<String> candidateKeys(JsonObject candidate) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        addKey(keys, string(candidate, "id", ""));
        addKey(keys, string(candidate, "name", ""));
        addKey(keys, fileStem(string(candidate, "file", "")));
        return keys;
    }

    private static Set<String> resultKeys(JsonObject result) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        JsonArray mods = result.has("mods") && result.get("mods").isJsonArray()
            ? result.getAsJsonArray("mods")
            : new JsonArray();
        for (JsonElement element : mods) {
            if (element == null || !element.isJsonObject()) {
                continue;
            }
            JsonObject mod = element.getAsJsonObject();
            addKey(keys, string(mod, "slug", ""));
            addKey(keys, string(mod, "name", ""));
        }
        return keys;
    }

    private static void addKey(Set<String> keys, String raw) {
        String normalized = normalizeKey(raw);
        if (!normalized.isBlank()) {
            keys.add(normalized);
        }
    }

    private static String normalizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String fileStem(String file) {
        if (file == null) {
            return "";
        }
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        String name = slash >= 0 ? file.substring(slash + 1) : file;
        return name.endsWith(".jar") ? name.substring(0, name.length() - 4) : name;
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
