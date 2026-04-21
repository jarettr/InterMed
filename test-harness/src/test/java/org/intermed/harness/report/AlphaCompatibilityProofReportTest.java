package org.intermed.harness.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.intermed.harness.HarnessConfig;
import org.intermed.harness.discovery.ModCandidate;
import org.intermed.harness.discovery.ResolvedCorpus;
import org.intermed.harness.runner.TestCase;
import org.intermed.harness.runner.TestResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlphaCompatibilityProofReportTest {

    @Test
    void reportsPassFailNotRunAccountingWithoutClaimingCompatibilityPercent() {
        HarnessConfig config = HarnessConfig.builder()
            .mode(HarnessConfig.TestMode.SLICES)
            .build();
        ResolvedCorpus corpus = new ResolvedCorpus(null, List.of(
            mod("fabric-api", List.of("fabric")),
            mod("lithium", List.of("fabric")),
            mod("architectury-api", List.of("forge")),
            mod("cloth-config", List.of("forge")),
            mod("collective", List.of("fabric", "forge", "neoforge")),
            mod("ferrite-core", List.of("forge", "neoforge"))
        ));

        CompatibilityMatrix existing = new CompatibilityMatrix();
        ModCandidate fabricApi = corpus.runnableMods().get(0);
        existing.add(new TestResult(
            new TestCase(
                "single-fabric-api-fabric",
                "Single: fabric-api@1.0.0 [Fabric]",
                List.of(fabricApi),
                TestCase.Loader.FABRIC
            ),
            TestResult.Outcome.PASS,
            1000L,
            0,
            "Done (1.0s)!",
            List.of(),
            Instant.parse("2026-04-20T10:00:00Z")
        ));

        JsonObject report = new AlphaCompatibilityProofReport()
            .build(config, corpus, existing, null);

        JsonObject summary = report.getAsJsonObject("summary");
        assertTrue(summary.get("plannedCases").getAsInt() > 1);
        assertEquals(1, summary.get("pass").getAsInt());
        assertTrue(summary.get("notRun").getAsInt() > 0);
        assertEquals(false, summary.get("compatibilityPercentClaimed").getAsBoolean());
        assertEquals("not-run", report.getAsJsonObject("phase3").get("runtimeStatus").getAsString());

        JsonArray lanes = report.getAsJsonArray("lanes");
        assertTrue(lanes.size() >= 3);
        assertEquals("single-mod-boot", lanes.get(0).getAsJsonObject().get("name").getAsString());
    }

    private static ModCandidate mod(String slug, List<String> loaders) {
        return new ModCandidate(
            "proj-" + slug,
            slug,
            slug,
            100L,
            loaders,
            "ver-" + slug,
            "1.0.0",
            "https://example.invalid/" + slug + ".jar",
            slug + ".jar",
            true
        );
    }
}
