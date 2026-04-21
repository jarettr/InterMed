package org.intermed.harness.runner;

import org.intermed.harness.HarnessConfig;
import org.intermed.harness.report.CompatibilityMatrix;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlanSelectionPlannerTest {

    @Test
    void shardsDeterministicallyAndCarriesForwardPassingResults() {
        HarnessConfig config = HarnessConfig.builder()
            .shardCount(2)
            .shardIndex(1)
            .resumeFailed(true)
            .build();

        List<TestCase> plan = List.of(
            testCase("case-a"),
            testCase("case-b"),
            testCase("case-c"),
            testCase("case-d")
        );

        CompatibilityMatrix existing = new CompatibilityMatrix();
        existing.add(new TestResult(
            testCase("case-b"),
            TestResult.Outcome.PASS,
            1000L,
            0,
            "Done (1.0s)!",
            List.of(),
            Instant.parse("2026-04-20T10:00:00Z"),
            1,
            false
        ));
        existing.add(new TestResult(
            testCase("case-d"),
            TestResult.Outcome.FAIL_TIMEOUT,
            0L,
            -1,
            "",
            List.of(),
            Instant.parse("2026-04-20T10:02:00Z"),
            2,
            false
        ));

        PlanSelectionPlanner.Selection selection =
            new PlanSelectionPlanner().select(config, plan, existing);

        assertEquals(4, selection.totalPlannedCases());
        assertEquals(2, selection.shardPlannedCases());
        assertEquals(1, selection.carriedForwardCount());
        assertEquals(1, selection.previousFailingCount());
        assertEquals(0, selection.missingCount());
        assertEquals(List.of("case-d"),
            selection.selectedCases().stream().map(TestCase::id).toList());
        assertEquals(3, selection.nextAttemptFor(testCase("case-d")));
        assertEquals(1, selection.carriedForwardResults().totalCount());
        assertEquals(TestResult.Outcome.PASS, selection.carriedForwardResults().find("case-b").outcome());
    }

    private static TestCase testCase(String id) {
        return new TestCase(id, id, List.of(), TestCase.Loader.FORGE);
    }
}
