package org.intermed.harness.runner;

import org.intermed.harness.analysis.IssueRecord;

import java.time.Instant;
import java.util.List;

/**
 * The outcome of executing a single {@link TestCase}.
 */
public record TestResult(
    TestCase testCase,
    Outcome outcome,
    /** Wall-clock milliseconds from process start to "Done (" detection (0 if failed). */
    long startupMs,
    /** JVM process exit code. */
    int exitCode,
    /** Full captured stdout+stderr output (truncated to 64 KB if larger). */
    String rawLog,
    /** Structured issues extracted from the log. */
    List<IssueRecord> issues,
    /** When the test was executed. */
    Instant executedAt
) {
    /** Possible test outcomes, ordered from best to worst. */
    public enum Outcome {
        /** Server started cleanly with no conflicts or warnings. */
        PASS,
        /** Server started; Mixin conflicts were automatically bridged. */
        PASS_BRIDGED,
        /** Server started but startup time or PERF warnings exceeded threshold. */
        PERF_WARN,
        /** Server failed to start within the timeout window. */
        FAIL_TIMEOUT,
        /** Server crashed (non-zero exit + crash pattern in log). */
        FAIL_CRASH,
        /** {@code MixinConflictException} was thrown (policy=fail or unresolvable). */
        FAIL_MIXIN,
        /** A required dependency class was not found. */
        FAIL_DEPENDENCY,
        /** {@code CapabilityDeniedException} blocked startup. */
        FAIL_CAPABILITY,
        /** Any other failure mode. */
        FAIL_OTHER;

        public boolean isPassing() {
            return this == PASS || this == PASS_BRIDGED || this == PERF_WARN;
        }

        public boolean isFailing() { return !isPassing(); }

        /** CSS class name used in the HTML report. */
        public String cssClass() {
            return switch (this) {
                case PASS            -> "pass";
                case PASS_BRIDGED    -> "bridged";
                case PERF_WARN       -> "perf";
                case FAIL_TIMEOUT    -> "timeout";
                case FAIL_CRASH      -> "crash";
                case FAIL_MIXIN      -> "mixin";
                case FAIL_DEPENDENCY -> "dependency";
                case FAIL_CAPABILITY -> "capability";
                case FAIL_OTHER      -> "other";
            };
        }
    }

    public boolean passed()  { return outcome.isPassing(); }
    public boolean failed()  { return outcome.isFailing(); }

    /** How many FATAL issues were recorded. */
    public long fatalCount() {
        return issues.stream().filter(IssueRecord::isFatal).count();
    }

    /** Truncate raw log to avoid enormous JSON/HTML. */
    public String logSnippet(int maxChars) {
        if (rawLog == null || rawLog.length() <= maxChars) return rawLog;
        int half = maxChars / 2;
        return rawLog.substring(0, half)
            + "\n... [truncated] ...\n"
            + rawLog.substring(rawLog.length() - half);
    }
}
