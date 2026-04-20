package org.intermed.harness.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.intermed.harness.analysis.IssueRecord;
import org.intermed.harness.discovery.ModCandidate;
import org.intermed.harness.runner.TestCase;
import org.intermed.harness.runner.TestResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Restores a {@link CompatibilityMatrix} from a previously written
 * {@code results.json}.
 */
public final class JsonReportReader {

    public CompatibilityMatrix read(Path jsonFile) throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(jsonFile)).getAsJsonObject();
        CompatibilityMatrix matrix = new CompatibilityMatrix();
        JsonArray results = root.getAsJsonArray("results");
        if (results == null) {
            return matrix;
        }
        for (var element : results) {
            matrix.add(parseResult(element.getAsJsonObject()));
        }
        return matrix;
    }

    private TestResult parseResult(JsonObject object) {
        TestCase testCase = new TestCase(
            object.get("id").getAsString(),
            object.get("description").getAsString(),
            parseMods(object.getAsJsonArray("mods")),
            TestCase.Loader.valueOf(object.get("loader").getAsString())
        );
        return new TestResult(
            testCase,
            TestResult.Outcome.valueOf(object.get("outcome").getAsString()),
            object.get("startupMs").getAsLong(),
            object.get("exitCode").getAsInt(),
            object.has("logSnippet") ? object.get("logSnippet").getAsString() : "",
            parseIssues(object.getAsJsonArray("issues")),
            Instant.parse(object.get("executedAt").getAsString())
        );
    }

    private List<ModCandidate> parseMods(JsonArray mods) {
        List<ModCandidate> parsed = new ArrayList<>();
        if (mods == null) {
            return parsed;
        }
        for (var element : mods) {
            JsonObject mod = element.getAsJsonObject();
            parsed.add(new ModCandidate(
                "",
                mod.get("slug").getAsString(),
                mod.get("name").getAsString(),
                mod.get("downloads").getAsLong(),
                List.of(),
                "",
                mod.get("version").getAsString(),
                "",
                "",
                true
            ));
        }
        return parsed;
    }

    private List<IssueRecord> parseIssues(JsonArray issues) {
        List<IssueRecord> parsed = new ArrayList<>();
        if (issues == null) {
            return parsed;
        }
        for (var element : issues) {
            JsonObject issue = element.getAsJsonObject();
            parsed.add(new IssueRecord(
                IssueRecord.Severity.valueOf(issue.get("severity").getAsString()),
                issue.get("tag").getAsString(),
                issue.get("description").getAsString(),
                issue.get("evidence").getAsString()
            ));
        }
        return parsed;
    }
}
