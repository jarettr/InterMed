package org.intermed.harness.report;

import org.intermed.harness.runner.TestResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Thread-safe in-memory store of all {@link TestResult}s produced during a
 * test run. Provides aggregate statistics and per-outcome queries used by the
 * report writers.
 */
public final class CompatibilityMatrix {

    private final CopyOnWriteArrayList<TestResult> results = new CopyOnWriteArrayList<>();

    // ── Mutation ──────────────────────────────────────────────────────────────

    public void add(TestResult result) {
        upsert(result);
    }

    public void addAll(List<TestResult> batch) {
        if (batch == null) {
            return;
        }
        batch.forEach(this::upsert);
    }

    public void upsert(TestResult result) {
        if (result == null) {
            return;
        }
        results.removeIf(existing -> existing.testCase().id().equals(result.testCase().id()));
        results.add(result);
    }

    // ── Queries ────────────────────────────────────────────────────────────────

    public List<TestResult> all() {
        return Collections.unmodifiableList(new ArrayList<>(results));
    }

    public TestResult find(String testId) {
        return results.stream()
            .filter(result -> result.testCase().id().equals(testId))
            .findFirst()
            .orElse(null);
    }

    public int totalCount() { return results.size(); }

    public long passCount() {
        return results.stream().filter(TestResult::passed).count();
    }

    public long failCount() {
        return results.stream().filter(TestResult::failed).count();
    }

    public long bridgedCount() {
        return results.stream()
            .filter(r -> r.outcome() == TestResult.Outcome.PASS_BRIDGED)
            .count();
    }

    public long perfWarnCount() {
        return results.stream()
            .filter(r -> r.outcome() == TestResult.Outcome.PERF_WARN)
            .count();
    }

    /** Pass rate as a percentage (0-100), rounded to one decimal. */
    public double passRate() {
        if (results.isEmpty()) return 0.0;
        return Math.round(passCount() * 1000.0 / results.size()) / 10.0;
    }

    /** Results grouped by outcome. */
    public Map<TestResult.Outcome, List<TestResult>> byOutcome() {
        return results.stream().collect(
            Collectors.groupingBy(TestResult::outcome, LinkedHashMap::new, Collectors.toList()));
    }

    /** Results for single-mod tests only, sorted by outcome then mod name. */
    public List<TestResult> singleModResults() {
        return results.stream()
            .filter(r -> r.testCase().modCount() == 1)
            .sorted(java.util.Comparator
                .comparing((TestResult r) -> r.outcome().name())
                .thenComparing(r -> r.testCase().mods().get(0).slug()))
            .toList();
    }

    /** Average startup time across all PASS/PASS_BRIDGED/PERF_WARN results in ms. */
    public double avgStartupMs() {
        return results.stream()
            .filter(r -> r.outcome().isPassing() && r.startupMs() > 0)
            .mapToLong(TestResult::startupMs)
            .average()
            .orElse(0.0);
    }

    /** Slowest startup time across all passing results. */
    public long maxStartupMs() {
        return results.stream()
            .filter(r -> r.outcome().isPassing() && r.startupMs() > 0)
            .mapToLong(TestResult::startupMs)
            .max()
            .orElse(0L);
    }

    /** Minimum startup time across all passing results. */
    public long minStartupMs() {
        return results.stream()
            .filter(r -> r.outcome().isPassing() && r.startupMs() > 0)
            .mapToLong(TestResult::startupMs)
            .min()
            .orElse(0L);
    }

    /** Startup time percentile (0-100) across all passing results. */
    public long startupPercentileMs(int pct) {
        long[] times = results.stream()
            .filter(r -> r.outcome().isPassing() && r.startupMs() > 0)
            .mapToLong(TestResult::startupMs)
            .sorted()
            .toArray();
        if (times.length == 0) return 0L;
        int idx = (int) Math.ceil(pct / 100.0 * times.length) - 1;
        return times[Math.max(0, Math.min(idx, times.length - 1))];
    }

    /** Count results by outcome. Sorted by outcome ordinal. */
    public Map<TestResult.Outcome, Long> countByOutcome() {
        Map<TestResult.Outcome, Long> map = new LinkedHashMap<>();
        for (TestResult.Outcome o : TestResult.Outcome.values()) map.put(o, 0L);
        results.forEach(r -> map.merge(r.outcome(), 1L, Long::sum));
        return map;
    }

    /** Pass rate for a specific loader. */
    public double passRateForLoader(org.intermed.harness.runner.TestCase.Loader loader) {
        long total = results.stream().filter(r -> r.testCase().loader() == loader).count();
        if (total == 0) return 0.0;
        long pass = results.stream()
            .filter(r -> r.testCase().loader() == loader && r.passed())
            .count();
        return Math.round(pass * 1000.0 / total) / 10.0;
    }

    /** Count of results for a specific loader. */
    public long countForLoader(org.intermed.harness.runner.TestCase.Loader loader) {
        return results.stream().filter(r -> r.testCase().loader() == loader).count();
    }

    /** Top N slowest passing mods, sorted by startupMs descending. */
    public List<TestResult> topSlowest(int n) {
        return results.stream()
            .filter(r -> r.outcome().isPassing() && r.startupMs() > 0)
            .sorted(java.util.Comparator.comparingLong(TestResult::startupMs).reversed())
            .limit(n)
            .toList();
    }

    /** Most-frequent issue tags across all results, excluding INFO severity. */
    public Map<String, Long> topIssueTags(int n) {
        return results.stream()
            .flatMap(r -> r.issues().stream())
            .filter(i -> i.severity() != org.intermed.harness.analysis.IssueRecord.Severity.INFO)
            .collect(Collectors.groupingBy(
                org.intermed.harness.analysis.IssueRecord::tag,
                Collectors.counting()
            ))
            .entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(n)
            .collect(Collectors.toMap(
                Map.Entry::getKey, Map.Entry::getValue,
                (a, b) -> a, LinkedHashMap::new
            ));
    }

    /**
     * Startup time histogram: returns bucket counts for the given upper-bound
     * edges (in seconds). Edge values are exclusive upper bounds.
     * The last bucket captures everything above the last edge.
     */
    public long[] startupHistogram(int... edgesSecs) {
        long[] buckets = new long[edgesSecs.length + 1];
        results.stream()
            .filter(r -> r.outcome().isPassing() && r.startupMs() > 0)
            .mapToLong(r -> r.startupMs() / 1000)
            .forEach(sec -> {
                for (int i = 0; i < edgesSecs.length; i++) {
                    if (sec < edgesSecs[i]) { buckets[i]++; return; }
                }
                buckets[edgesSecs.length]++;
            });
        return buckets;
    }

    /** Returns a summary string suitable for console output. */
    public String summaryLine() {
        return String.format(
            "Results: %d total | %d pass (%.1f%%) | %d bridged | %d perf-warn | %d fail | avg startup %.1fs",
            totalCount(), passCount(), passRate(), bridgedCount(), perfWarnCount(), failCount(),
            avgStartupMs() / 1000.0
        );
    }
}
