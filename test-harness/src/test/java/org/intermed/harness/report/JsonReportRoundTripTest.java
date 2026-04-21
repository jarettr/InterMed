package org.intermed.harness.report;

import org.intermed.harness.analysis.IssueRecord;
import org.intermed.harness.discovery.ModCandidate;
import org.intermed.harness.runner.TestCase;
import org.intermed.harness.runner.TestResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonReportRoundTripTest {

    @Test
    void roundTripsResultsJsonIntoCompatibilityMatrix() throws Exception {
        CompatibilityMatrix matrix = new CompatibilityMatrix();
        ModCandidate mod = new ModCandidate(
            "proj-1",
            "sample-mod",
            "Sample Mod",
            42L,
            List.of("forge"),
            "ver-1",
            "1.2.3",
            "https://example.invalid/sample-mod.jar",
            "sample-mod.jar",
            true
        );
        TestResult result = new TestResult(
            new TestCase("single-sample-mod-forge", "Single: sample-mod@1.2.3", List.of(mod), TestCase.Loader.FORGE),
            TestResult.Outcome.PASS_BRIDGED,
            12345L,
            0,
            "Done (12.345s)! For help, type \"help\"",
            List.of(new IssueRecord(IssueRecord.Severity.ERROR, "MIXIN_BRIDGE", "Bridge generated", "evidence")),
            Instant.parse("2026-04-12T10:15:30Z"),
            2,
            true
        );
        matrix.add(result);

        Path reportDir = Files.createTempDirectory("intermed-harness-report");
        Path jsonFile = new JsonReportWriter().write(matrix, reportDir);

        CompatibilityMatrix loaded = new JsonReportReader().read(jsonFile);

        assertEquals(1, loaded.totalCount());
        TestResult restored = loaded.all().get(0);
        assertEquals("single-sample-mod-forge", restored.testCase().id());
        assertEquals(TestResult.Outcome.PASS_BRIDGED, restored.outcome());
        assertEquals(12345L, restored.startupMs());
        assertEquals("sample-mod", restored.testCase().mods().get(0).slug());
        assertEquals("1.2.3", restored.testCase().mods().get(0).versionNumber());
        assertEquals("MIXIN_BRIDGE", restored.issues().get(0).tag());
        assertEquals(2, restored.attempt());
        assertEquals(true, restored.flakyRetry());
    }

    @Test
    void roundTripsNeoForgeLoaderResults() throws Exception {
        CompatibilityMatrix matrix = new CompatibilityMatrix();
        ModCandidate mod = new ModCandidate(
            "proj-neo",
            "neo-sample",
            "Neo Sample",
            7L,
            List.of("neoforge"),
            "ver-neo",
            "2.0.0",
            "https://example.invalid/neo-sample.jar",
            "neo-sample.jar",
            true
        );
        matrix.add(new TestResult(
            new TestCase("single-neo-sample-neoforge", "Single: neo-sample@2.0.0", List.of(mod), TestCase.Loader.NEOFORGE),
            TestResult.Outcome.PASS,
            6789L,
            0,
            "Done (6.789s)! For help, type \"help\"",
            List.of(),
            Instant.parse("2026-04-12T10:17:30Z")
        ));

        Path reportDir = Files.createTempDirectory("intermed-harness-neoforge-report");
        Path jsonFile = new JsonReportWriter().write(matrix, reportDir);

        CompatibilityMatrix loaded = new JsonReportReader().read(jsonFile);

        assertEquals(1, loaded.totalCount());
        assertEquals(TestCase.Loader.NEOFORGE, loaded.all().get(0).testCase().loader());
        assertEquals("single-neo-sample-neoforge", loaded.all().get(0).testCase().id());
    }
}
