package org.intermed.harness.runner;

import org.intermed.harness.HarnessConfig;
import org.intermed.harness.report.CompatibilityMatrix;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies deterministic sharding and resume semantics to a generated test plan.
 */
public final class PlanSelectionPlanner {

    public Selection select(HarnessConfig config,
                            List<TestCase> generatedPlan,
                            CompatibilityMatrix existingResults) {
        List<TestCase> orderedPlan = (generatedPlan == null ? List.<TestCase>of() : generatedPlan).stream()
            .sorted(Comparator.comparing(TestCase::id))
            .toList();
        List<TestCase> shardCases = applyShard(config, orderedPlan);

        Map<String, TestResult> previousById = new LinkedHashMap<>();
        CompatibilityMatrix carryForward = new CompatibilityMatrix();
        Set<String> shardIds = new LinkedHashSet<>();
        shardCases.forEach(testCase -> shardIds.add(testCase.id()));

        if (existingResults != null) {
            for (TestResult result : existingResults.all()) {
                if (shardIds.contains(result.testCase().id())) {
                    previousById.put(result.testCase().id(), result);
                }
            }
        }

        List<TestCase> selected = new ArrayList<>();
        int carriedForwardCount = 0;
        int previousFailingCount = 0;
        int missingCount = 0;
        for (TestCase testCase : shardCases) {
            TestResult previous = previousById.get(testCase.id());
            if (config.resumeFailed && previous != null && previous.passed()) {
                carryForward.add(previous);
                carriedForwardCount++;
                continue;
            }
            if (previous == null) {
                missingCount++;
            } else if (previous.failed()) {
                previousFailingCount++;
            }
            selected.add(testCase);
        }

        return new Selection(
            orderedPlan.size(),
            shardCases.size(),
            List.copyOf(selected),
            carryForward,
            Map.copyOf(previousById),
            carriedForwardCount,
            previousFailingCount,
            missingCount
        );
    }

    private List<TestCase> applyShard(HarnessConfig config, List<TestCase> orderedPlan) {
        if (config.shardCount <= 1) {
            return orderedPlan;
        }
        List<TestCase> shard = new ArrayList<>();
        for (int index = 0; index < orderedPlan.size(); index++) {
            if (index % config.shardCount == config.shardIndex) {
                shard.add(orderedPlan.get(index));
            }
        }
        return List.copyOf(shard);
    }

    public record Selection(
        int totalPlannedCases,
        int shardPlannedCases,
        List<TestCase> selectedCases,
        CompatibilityMatrix carriedForwardResults,
        Map<String, TestResult> previousResultsById,
        int carriedForwardCount,
        int previousFailingCount,
        int missingCount
    ) {
        public int selectedCount() {
            return selectedCases.size();
        }

        public int nextAttemptFor(TestCase testCase) {
            TestResult previous = previousResultsById.get(testCase.id());
            return previous == null ? 1 : Math.max(1, previous.attempt() + 1);
        }
    }
}
