package org.intermed.harness.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.intermed.harness.analysis.IssueRecord;
import org.intermed.harness.runner.TestResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Writes the full compatibility matrix as a machine-readable {@code results.json}.
 * The file can be consumed by CI systems, dashboards, or diff tools to track
 * regressions between InterMed releases.
 */
public final class JsonReportWriter {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .create();

    /**
     * Writes {@code results.json} to the given directory.
     *
     * @return path to the written file
     */
    public Path write(CompatibilityMatrix matrix, Path reportDir) throws IOException {
        return write(matrix, reportDir.resolve("results.json"), null);
    }

    public Path write(CompatibilityMatrix matrix,
                      Path outputFile,
                      HarnessRunMetadata metadata) throws IOException {
        Path reportDir = outputFile.toAbsolutePath().normalize().getParent();
        if (reportDir == null) {
            throw new IOException("results output file must have a parent directory");
        }
        Files.createDirectories(reportDir);

        JsonObject root = new JsonObject();
        root.addProperty("schema", metadata == null
            ? "intermed-harness-results-v1"
            : "intermed-harness-results-v2");
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("totalCount",   matrix.totalCount());
        root.addProperty("passCount",    matrix.passCount());
        root.addProperty("failCount",    matrix.failCount());
        root.addProperty("bridgedCount", matrix.bridgedCount());
        root.addProperty("perfWarnCount",matrix.perfWarnCount());
        root.addProperty("passRate",     matrix.passRate());
        root.addProperty("avgStartupMs", matrix.avgStartupMs());
        root.addProperty("maxStartupMs", matrix.maxStartupMs());
        if (metadata != null) {
            root.add("runMetadata", metadata(metadata));
        }

        JsonArray results = new JsonArray();
        for (TestResult r : matrix.all()) {
            results.add(toJson(r));
        }
        root.add("results", results);

        Files.writeString(outputFile, GSON.toJson(root));
        System.out.println("[Report] JSON written: " + outputFile);
        return outputFile;
    }

    private JsonObject toJson(TestResult r) {
        JsonObject o = new JsonObject();
        o.addProperty("id",          r.testCase().id());
        o.addProperty("description", r.testCase().description());
        o.addProperty("loader",      r.testCase().loader().name());
        o.addProperty("modCount",    r.testCase().modCount());
        o.addProperty("outcome",     r.outcome().name());
        o.addProperty("passed",      r.passed());
        o.addProperty("startupMs",   r.startupMs());
        o.addProperty("exitCode",    r.exitCode());
        o.addProperty("executedAt",  r.executedAt().toString());
        o.addProperty("attempt",     r.attempt());
        o.addProperty("flakyRetry",  r.flakyRetry());

        JsonArray mods = new JsonArray();
        for (var mod : r.testCase().mods()) {
            JsonObject m = new JsonObject();
            m.addProperty("projectId",     mod.projectId());
            m.addProperty("slug",          mod.slug());
            m.addProperty("name",          mod.name());
            m.addProperty("version",       mod.versionNumber());
            m.addProperty("versionId",     mod.versionId());
            m.addProperty("downloads",     mod.downloads());
            m.addProperty("source",        mod.source());
            m.addProperty("modrinthUrl",   mod.modrinthUrl());
            m.addProperty("serverSideCompatible", mod.serverSide());
            m.addProperty("clientSide",    mod.clientSide());
            m.addProperty("serverSide",    mod.serverSideScope());
            JsonArray categories = new JsonArray();
            for (String category : mod.categories()) {
                categories.add(category);
            }
            m.add("categories", categories);
            mods.add(m);
        }
        o.add("mods", mods);

        JsonArray issues = new JsonArray();
        for (IssueRecord issue : r.issues()) {
            JsonObject iss = new JsonObject();
            iss.addProperty("severity",    issue.severity().name());
            iss.addProperty("tag",         issue.tag());
            iss.addProperty("description", issue.description());
            iss.addProperty("evidence",    issue.evidence());
            issues.add(iss);
        }
        o.add("issues", issues);

        // Include a truncated log snippet (no full log in JSON to keep file small)
        o.addProperty("logSnippet", r.logSnippet(2000));
        return o;
    }

    private JsonObject metadata(HarnessRunMetadata metadata) {
        JsonObject root = new JsonObject();
        root.addProperty("evidenceLevel", metadata.evidenceLevel().name());
        root.addProperty("effectiveExecutionLane", metadata.effectiveExecutionLane());
        root.addProperty("mode", metadata.mode().name());
        root.addProperty("loaderFilter", metadata.loaderFilter().name());
        root.addProperty("mcVersion", metadata.mcVersion());
        root.addProperty("shardCount", metadata.shardCount());
        root.addProperty("shardIndex", metadata.shardIndex());
        root.addProperty("resumeFailed", metadata.resumeFailed());
        root.addProperty("retryFlaky", metadata.retryFlaky());
        root.addProperty("totalPlannedCases", metadata.totalPlannedCases());
        root.addProperty("shardPlannedCases", metadata.shardPlannedCases());
        root.addProperty("selectedCases", metadata.selectedCases());
        root.addProperty("carriedForwardCases", metadata.carriedForwardCases());
        root.addProperty("previousFailingCases", metadata.previousFailingCases());
        root.addProperty("missingCases", metadata.missingCases());

        JsonObject corpus = new JsonObject();
        corpus.addProperty("schema", metadata.corpusSchema());
        corpus.addProperty("fingerprint", metadata.corpusFingerprint());
        corpus.addProperty("totalCandidates", metadata.corpusTotalCandidates());
        corpus.addProperty("runnableCandidates", metadata.corpusRunnableCandidates());
        corpus.addProperty("lockFile", metadata.corpusLockFile());
        root.add("corpus", corpus);
        return root;
    }
}
