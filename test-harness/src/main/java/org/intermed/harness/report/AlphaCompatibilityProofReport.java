package org.intermed.harness.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.intermed.harness.HarnessConfig;
import org.intermed.harness.discovery.CorpusLock;
import org.intermed.harness.discovery.ModCandidate;
import org.intermed.harness.discovery.ResolvedCorpus;
import org.intermed.harness.runner.TestCase;
import org.intermed.harness.runner.TestPlan;
import org.intermed.harness.runner.TestResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a conservative open-alpha compatibility proof plan.
 *
 * <p>This is intentionally not a compatibility percentage. It combines the
 * current corpus, the planned Phase 2 lanes, and any existing harness
 * {@code results.json} into pass/fail/not-run accounting.</p>
 */
public final class AlphaCompatibilityProofReport {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int SINGLE_CANDIDATE_TARGET = 500;
    private static final int PAIR_CANDIDATE_TARGET = 500;
    private static final int PAIRS_TOP_K = 35;
    private static final int NOT_RUN_SAMPLE_LIMIT = 50;

    public Path write(HarnessConfig config,
                      ResolvedCorpus corpus,
                      CompatibilityMatrix existingResults,
                      Path outputFile) throws IOException {
        if (outputFile.getParent() != null) {
            Files.createDirectories(outputFile.getParent());
        }
        JsonObject report = build(config, corpus, existingResults, outputFile);
        Files.writeString(outputFile, GSON.toJson(report));
        System.out.println("[AlphaProof] JSON written: " + outputFile);
        return outputFile;
    }

    public JsonObject build(HarnessConfig config,
                            ResolvedCorpus corpus,
                            CompatibilityMatrix existingResults,
                            Path outputFile) {
        ResolvedCorpus safeCorpus = corpus == null
            ? new ResolvedCorpus(null, List.of())
            : corpus;
        CompatibilityMatrix safeResults = existingResults == null
            ? new CompatibilityMatrix()
            : existingResults;

        List<ModCandidate> runnable = safeCorpus.runnableMods();
        List<ModCandidate> singleMods = runnable.stream()
            .limit(Math.min(SINGLE_CANDIDATE_TARGET, runnable.size()))
            .toList();
        List<ModCandidate> pairMods = runnable.stream()
            .limit(Math.min(PAIR_CANDIDATE_TARGET, runnable.size()))
            .toList();

        List<TestCase> singleCases = plannedCases(config, HarnessConfig.TestMode.SINGLE, 0, singleMods).stream()
            .filter(testCase -> testCase.id().startsWith("single-"))
            .toList();
        List<TestCase> pairCases = plannedCases(config, HarnessConfig.TestMode.PAIRS, PAIRS_TOP_K, pairMods).stream()
            .filter(testCase -> testCase.id().startsWith("pair-"))
            .toList();
        List<TestCase> sliceCases = plannedCases(config, HarnessConfig.TestMode.SLICES, 0, runnable);

        JsonObject root = new JsonObject();
        root.addProperty("schema", "intermed-alpha-compatibility-proof-plan-v1");
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("outputFile", outputFile == null ? "" : outputFile.toAbsolutePath().normalize().toString());
        root.add("scope", scope(config));
        root.add("claimGuardrails", guardrails());
        root.add("corpus", corpus(safeCorpus.lock(), runnable.size()));
        root.add("existingResults", existingResults(safeResults));

        JsonArray lanes = new JsonArray();
        lanes.add(lane(
            "single-mod-boot",
            "300-500 single-mod boot candidates; loader-specific cases may exceed candidate count",
            "./test-harness/run.sh full --skip-bootstrap --skip-discover --mode=single --top=500 --concurrency=1 --heap=768 --shard-count=10 --shard-index=<0..9> --retry-flaky",
            singleCases,
            safeResults
        ));
        lanes.add(lane(
            "pair-boot",
            "200-400 pair boot cases",
            "./test-harness/run.sh full --skip-bootstrap --skip-discover --mode=pairs --top=500 --pairs-top=35 --concurrency=1 --heap=768 --shard-count=12 --shard-index=<0..11> --resume-failed --retry-flaky",
            pairCases,
            safeResults
        ));
        lanes.add(lane(
            "curated-slices",
            "Curated alpha slices: Fabric foundation, Forge foundation, NeoForge minimal, data/resource-heavy, Mixin-heavy, network-heavy",
            "./test-harness/run.sh full --skip-bootstrap --skip-discover --mode=slices --concurrency=1 --heap=768 --retry-flaky",
            sliceCases,
            safeResults
        ));
        root.add("lanes", lanes);
        root.add("curatedSlices", curatedSlices(sliceCases, safeResults));
        root.add("phase3", phase3NotRun());
        root.add("summary", summary(lanes));
        return root;
    }

    private static List<TestCase> plannedCases(HarnessConfig base,
                                               HarnessConfig.TestMode mode,
                                               int pairsTopK,
                                               List<ModCandidate> mods) {
        HarnessConfig.Builder builder = HarnessConfig.builder()
            .mcVersion(base.mcVersion)
            .forgeVersion(base.forgeVersion)
            .neoforgeVersion(base.neoforgeVersion)
            .loaderFilter(base.loaderFilter)
            .mode(mode)
            .outputDir(base.outputDir)
            .intermedJar(base.intermedJar)
            .javaExecutable(base.javaExecutable)
            .pairsTopK(pairsTopK <= 0 ? base.pairsTopK : pairsTopK)
            .shardCount(1)
            .shardIndex(0);
        return new TestPlan(builder.build(), mods).generate();
    }

    private static JsonObject scope(HarnessConfig config) {
        JsonObject json = new JsonObject();
        json.addProperty("minecraft", config.mcVersion);
        json.addProperty("evidenceLevel", "BOOTED");
        json.addProperty("loaderFilter", config.loaderFilter.name());
        json.addProperty("strictSecurity", false);
        json.addProperty("fieldTested", false);
        return json;
    }

    private static JsonArray guardrails() {
        JsonArray array = new JsonArray();
        array.add("This artifact is pass/fail/unsupported/not-run accounting, not a compatibility percentage.");
        array.add("PASS means dedicated-server boot reached the harness marker under permissive compatibility settings.");
        array.add("PASS does not prove gameplay, multiplayer, strict-security, or long-session behavior.");
        array.add("Unexecuted Phase 2 work remains not-run. Phase 3 full pack mode remains not-run.");
        return array;
    }

    private static JsonObject corpus(CorpusLock lock, int runnableMods) {
        JsonObject json = new JsonObject();
        json.addProperty("schema", lock == null ? "unknown" : lock.schema());
        json.addProperty("generatedAt", lock == null ? "" : lock.generatedAt());
        json.addProperty("fingerprint", lock == null ? "unavailable" : lock.corpusFingerprint());
        json.addProperty("topN", lock == null ? 0 : lock.topN());
        json.addProperty("runnableMods", runnableMods);
        if (lock != null && lock.summary() != null) {
            CorpusLock.Summary summary = lock.summary();
            json.addProperty("totalCandidates", summary.totalCandidates());
            json.addProperty("runnableCandidates", summary.runnableCandidates());
            json.addProperty("unsupportedCandidates", summary.clientOnlyCandidates());
            json.addProperty("withMixins", summary.withMixins());
            json.addProperty("withDataPacks", summary.withDataPacks());
            json.addProperty("withResourcePacks", summary.withResourcePacks());
            json.add("byLoader", intMap(summary.byLoader()));
        }
        return json;
    }

    private static JsonObject existingResults(CompatibilityMatrix matrix) {
        JsonObject json = new JsonObject();
        json.addProperty("totalCount", matrix.totalCount());
        json.addProperty("passCount", matrix.passCount());
        json.addProperty("failCount", matrix.failCount());
        json.addProperty("singleCount", matrix.all().stream()
            .filter(result -> result.testCase().id().startsWith("single-"))
            .count());
        json.addProperty("pairCount", matrix.all().stream()
            .filter(result -> result.testCase().id().startsWith("pair-"))
            .count());
        json.addProperty("sliceCount", matrix.all().stream()
            .filter(result -> result.testCase().id().startsWith("slice-"))
            .count());
        json.add("byOutcome", outcomeMap(matrix));
        return json;
    }

    private static JsonObject lane(String name,
                                   String target,
                                   String safeCommand,
                                   List<TestCase> planned,
                                   CompatibilityMatrix existingResults) {
        LaneCounts counts = new LaneCounts();
        JsonArray notRunSample = new JsonArray();
        JsonArray failureSample = new JsonArray();

        planned.stream()
            .sorted(Comparator.comparing(TestCase::id))
            .forEach(testCase -> {
                TestResult result = existingResults.find(testCase.id());
                if (result == null) {
                    counts.notRun++;
                    if (notRunSample.size() < NOT_RUN_SAMPLE_LIMIT) {
                        notRunSample.add(testCase.id());
                    }
                    return;
                }
                counts.executed++;
                if (result.passed()) {
                    counts.pass++;
                } else {
                    counts.fail++;
                    if (failureSample.size() < NOT_RUN_SAMPLE_LIMIT) {
                        JsonObject failure = new JsonObject();
                        failure.addProperty("id", testCase.id());
                        failure.addProperty("outcome", result.outcome().name());
                        failure.add("issues", issueTags(result));
                        failureSample.add(failure);
                    }
                }
                counts.byOutcome.merge(result.outcome().name(), 1, Integer::sum);
                counts.byLoader.merge(testCase.loader().name(), 1, Integer::sum);
            });

        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("target", target);
        json.addProperty("safeCommand", safeCommand);
        json.addProperty("plannedCases", planned.size());
        json.addProperty("executedCases", counts.executed);
        json.addProperty("pass", counts.pass);
        json.addProperty("fail", counts.fail);
        json.addProperty("unsupported", 0);
        json.addProperty("notRun", counts.notRun);
        json.addProperty("runtimeStatus", counts.notRun == 0 ? "complete" : "partial-or-not-run");
        json.add("byOutcome", intMap(counts.byOutcome));
        json.add("byLoader", intMap(counts.byLoader));
        json.add("notRunSample", notRunSample);
        json.add("failureSample", failureSample);
        return json;
    }

    private static JsonArray curatedSlices(List<TestCase> sliceCases, CompatibilityMatrix existingResults) {
        JsonArray array = new JsonArray();
        sliceCases.stream()
            .sorted(Comparator.comparing(TestCase::id))
            .forEach(testCase -> {
                TestResult result = existingResults.find(testCase.id());
                JsonObject json = new JsonObject();
                json.addProperty("id", testCase.id());
                json.addProperty("description", testCase.description());
                json.addProperty("loader", testCase.loader().name());
                json.addProperty("modCount", testCase.modCount());
                json.addProperty("runtimeStatus", result == null ? "not-run" : result.outcome().name());
                JsonArray mods = new JsonArray();
                for (ModCandidate mod : testCase.mods()) {
                    mods.add(mod.slug());
                }
                json.add("mods", mods);
                array.add(json);
            });
        return array;
    }

    private static JsonObject phase3NotRun() {
        JsonObject json = new JsonObject();
        json.addProperty("mode", "full");
        json.addProperty("runtimeStatus", "not-run");
        json.addProperty("codeRetained", true);
        json.addProperty("reason", "Phase 3 pack-style combos were explicitly excluded from this local alpha proof pass.");
        return json;
    }

    private static JsonObject summary(JsonArray lanes) {
        int planned = 0;
        int executed = 0;
        int pass = 0;
        int fail = 0;
        int unsupported = 0;
        int notRun = 0;
        for (var element : lanes) {
            JsonObject lane = element.getAsJsonObject();
            planned += lane.get("plannedCases").getAsInt();
            executed += lane.get("executedCases").getAsInt();
            pass += lane.get("pass").getAsInt();
            fail += lane.get("fail").getAsInt();
            unsupported += lane.get("unsupported").getAsInt();
            notRun += lane.get("notRun").getAsInt();
        }
        JsonObject json = new JsonObject();
        json.addProperty("plannedCases", planned);
        json.addProperty("executedCases", executed);
        json.addProperty("pass", pass);
        json.addProperty("fail", fail);
        json.addProperty("unsupported", unsupported);
        json.addProperty("notRun", notRun);
        json.addProperty("compatibilityPercentClaimed", false);
        return json;
    }

    private static JsonObject outcomeMap(CompatibilityMatrix matrix) {
        LinkedHashMap<String, Integer> map = new LinkedHashMap<>();
        for (TestResult result : matrix.all()) {
            map.merge(result.outcome().name(), 1, Integer::sum);
        }
        return intMap(map);
    }

    private static JsonArray issueTags(TestResult result) {
        JsonArray tags = new JsonArray();
        result.issues().stream()
            .map(issue -> issue.tag())
            .distinct()
            .sorted()
            .forEach(tags::add);
        return tags;
    }

    private static JsonObject intMap(Map<String, ? extends Number> values) {
        JsonObject json = new JsonObject();
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> json.addProperty(entry.getKey(), entry.getValue()));
        return json;
    }

    private static final class LaneCounts {
        private int executed;
        private int pass;
        private int fail;
        private int notRun;
        private final Map<String, Integer> byOutcome = new LinkedHashMap<>();
        private final Map<String, Integer> byLoader = new LinkedHashMap<>();
    }
}
