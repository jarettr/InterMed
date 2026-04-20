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
        Files.createDirectories(reportDir);

        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("totalCount",   matrix.totalCount());
        root.addProperty("passCount",    matrix.passCount());
        root.addProperty("failCount",    matrix.failCount());
        root.addProperty("bridgedCount", matrix.bridgedCount());
        root.addProperty("perfWarnCount",matrix.perfWarnCount());
        root.addProperty("passRate",     matrix.passRate());
        root.addProperty("avgStartupMs", matrix.avgStartupMs());
        root.addProperty("maxStartupMs", matrix.maxStartupMs());

        JsonArray results = new JsonArray();
        for (TestResult r : matrix.all()) {
            results.add(toJson(r));
        }
        root.add("results", results);

        Path dest = reportDir.resolve("results.json");
        Files.writeString(dest, GSON.toJson(root));
        System.out.println("[Report] JSON written: " + dest);
        return dest;
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

        JsonArray mods = new JsonArray();
        for (var mod : r.testCase().mods()) {
            JsonObject m = new JsonObject();
            m.addProperty("slug",          mod.slug());
            m.addProperty("name",          mod.name());
            m.addProperty("version",       mod.versionNumber());
            m.addProperty("downloads",     mod.downloads());
            m.addProperty("modrinthUrl",   mod.modrinthUrl());
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
}
