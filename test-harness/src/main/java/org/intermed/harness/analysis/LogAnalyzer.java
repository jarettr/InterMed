package org.intermed.harness.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the raw stdout/stderr log captured during a test run and produces a
 * structured list of {@link IssueRecord}s.
 *
 * <p>Pattern matching is intentionally conservative: each pattern must appear
 * in the log before its corresponding issue is recorded. Patterns are ordered
 * from most specific to most general so that specific failure modes are tagged
 * before the generic CRASH fallback fires.
 */
public final class LogAnalyzer {

    private static final String BENIGN_FABRIC_LOG4J_SELECTOR =
        "cpw.mods.modlauncher.log.MLClassLoaderContextSelector";

    // ── Fatal patterns ────────────────────────────────────────────────────────

    private static final Pattern MIXIN_CONFLICT = Pattern.compile(
        "MixinConflictException", Pattern.CASE_INSENSITIVE);

    private static final Pattern CAPABILITY_DENIED = Pattern.compile(
        "CapabilityDeniedException", Pattern.CASE_INSENSITIVE);

    private static final Pattern CLASS_NOT_FOUND = Pattern.compile(
        "ClassNotFoundException:\\s*(\\S+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern NO_CLASS_DEF = Pattern.compile(
        "NoClassDefFoundError:\\s*(\\S+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern JVM_CRASH = Pattern.compile(
        "(A fatal error has been detected|EXCEPTION_ACCESS_VIOLATION|SIGSEGV|hs_err_pid)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern EXCEPTION_MAIN = Pattern.compile(
        "Exception in thread \"(main|Server thread|main/ERROR)\"");

    private static final Pattern OOM = Pattern.compile(
        "OutOfMemoryError", Pattern.CASE_INSENSITIVE);

    private static final Pattern STACK_OVERFLOW = Pattern.compile(
        "StackOverflowError", Pattern.CASE_INSENSITIVE);

    private static final Pattern PORT_IN_USE = Pattern.compile(
        "(FAILED TO BIND TO PORT|Address already in use|Perhaps a server is already running on that port)",
        Pattern.CASE_INSENSITIVE);

    // ── InterMed-specific patterns ────────────────────────────────────────────

    private static final Pattern MIXIN_BRIDGE = Pattern.compile(
        "\\[AST\\] Generated priority bridge for", Pattern.CASE_INSENSITIVE);

    private static final Pattern PERF_WARN = Pattern.compile(
        "\\[PERF\\] Slow bridge call:", Pattern.CASE_INSENSITIVE);

    private static final Pattern AOT_MISS = Pattern.compile(
        "\\[AOT\\] Cache MISS", Pattern.CASE_INSENSITIVE);

    private static final Pattern AOT_HIT = Pattern.compile(
        "\\[AOT\\] Cache HIT", Pattern.CASE_INSENSITIVE);

    private static final Pattern WEAK_EDGE = Pattern.compile(
        "\\[CL-WEAK\\] installed edge", Pattern.CASE_INSENSITIVE);

    private static final Pattern CAPABILITY_WARN = Pattern.compile(
        "\\[Security\\].*DENIED|CapabilityDeniedException", Pattern.CASE_INSENSITIVE);

    private static final Pattern VFS_CONFLICT = Pattern.compile(
        "\\[VFS\\].*conflict|\\[VFS\\].*override", Pattern.CASE_INSENSITIVE);

    // ── Forge/Fabric/general patterns ─────────────────────────────────────────

    private static final Pattern SERVER_DONE = Pattern.compile(
        "Done \\(([0-9.]+)s\\)!", Pattern.CASE_INSENSITIVE);

    private static final Pattern CRASH_REPORT = Pattern.compile(
        "---- Minecraft Crash Report ----", Pattern.CASE_INSENSITIVE);

    private static final Pattern LOADING_ERROR = Pattern.compile(
        "(ERROR|FATAL).*(?:mod|plugin|loading)", Pattern.CASE_INSENSITIVE);

    /** Fast check used by {@link org.intermed.harness.runner.ServerProcessRunner}
     *  to detect a fatal line in real time (avoids waiting for full log). */
    public boolean isFatalLine(String line) {
        return MIXIN_CONFLICT.matcher(line).find()
            || CAPABILITY_DENIED.matcher(line).find()
            || JVM_CRASH.matcher(line).find()
            || OOM.matcher(line).find()
            || STACK_OVERFLOW.matcher(line).find()
            || CRASH_REPORT.matcher(line).find()
            || (line.contains("FATAL") && line.contains("Exception"));
    }

    /**
     * Analyses the full captured log and returns all detected issues.
     * Multiple occurrences of the same tag are deduplicated.
     */
    public List<IssueRecord> analyze(String rawLog) {
        if (rawLog == null || rawLog.isBlank()) return List.of();

        List<IssueRecord> issues = new ArrayList<>();

        // Process each pattern exactly once (first match only for dedupe)
        checkOnce(issues, rawLog, MIXIN_CONFLICT, IssueRecord.Severity.ERROR,
            "MIXIN_CONFLICT", "MixinConflictException detected — unresolvable Mixin conflict");

        checkOnce(issues, rawLog, CAPABILITY_DENIED, IssueRecord.Severity.FATAL,
            "CAPABILITY_DENIED", "CapabilityDeniedException — mod blocked by security layer");

        checkClassNotFound(issues, rawLog);

        checkOnce(issues, rawLog, JVM_CRASH, IssueRecord.Severity.FATAL,
            "JVM_CRASH", "JVM fatal error / native crash");

        checkOnce(issues, rawLog, CRASH_REPORT, IssueRecord.Severity.FATAL,
            "MC_CRASH", "Minecraft crash report generated");

        checkOnce(issues, rawLog, EXCEPTION_MAIN, IssueRecord.Severity.FATAL,
            "EXCEPTION_MAIN", "Uncaught exception on main/server thread");

        checkOnce(issues, rawLog, OOM, IssueRecord.Severity.FATAL,
            "OOM", "OutOfMemoryError — increase heapMb in harness config");

        checkOnce(issues, rawLog, STACK_OVERFLOW, IssueRecord.Severity.FATAL,
            "STACK_OVERFLOW", "StackOverflowError");

        checkOnce(issues, rawLog, PORT_IN_USE, IssueRecord.Severity.FATAL,
            "PORT_IN_USE", "Harness port collision / server bind failure");

        // InterMed-specific informational / warning signals
        checkOnce(issues, rawLog, MIXIN_BRIDGE, IssueRecord.Severity.INFO,
            "MIXIN_BRIDGE", "Mixin conflict bridged by InterMed priority bridge");

        checkCounted(issues, rawLog, PERF_WARN, IssueRecord.Severity.WARN,
            "PERF_WARN", "Slow bridge call detected");

        checkCounted(issues, rawLog, VFS_CONFLICT, IssueRecord.Severity.INFO,
            "VFS_CONFLICT", "VFS resource conflict resolved");

        checkOnce(issues, rawLog, WEAK_EDGE, IssueRecord.Severity.INFO,
            "WEAK_EDGE", "Dynamic ClassLoader weak edge installed");

        // Startup time extraction (INFO only)
        extractStartupTime(issues, rawLog);

        // AOT cache stats
        long aotMisses = countMatches(rawLog, AOT_MISS);
        long aotHits   = countMatches(rawLog, AOT_HIT);
        if (aotMisses > 0 || aotHits > 0) {
            issues.add(new IssueRecord(IssueRecord.Severity.INFO, "AOT_STATS",
                "AOT cache: " + aotHits + " hits, " + aotMisses + " misses",
                ""));
        }

        return List.copyOf(issues);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void checkOnce(List<IssueRecord> issues, String log, Pattern p,
                            IssueRecord.Severity sev, String tag, String desc) {
        Matcher m = p.matcher(log);
        if (m.find()) {
            // Extract the matching line as evidence
            String evidence = extractLine(log, m.start());
            issues.add(new IssueRecord(sev, tag, desc, evidence));
        }
    }

    /** Records the issue with a count suffix when there are multiple matches. */
    private void checkCounted(List<IssueRecord> issues, String log, Pattern p,
                               IssueRecord.Severity sev, String tag, String baseDesc) {
        long count = countMatches(log, p);
        if (count > 0) {
            Matcher first = p.matcher(log);
            String evidence = first.find() ? extractLine(log, first.start()) : "";
            issues.add(new IssueRecord(sev, tag,
                baseDesc + " (×" + count + ")", evidence));
        }
    }

    private void checkClassNotFound(List<IssueRecord> issues, String log) {
        Matcher m = CLASS_NOT_FOUND.matcher(log);
        if (m.find()) {
            String className = m.groupCount() > 0 ? m.group(1) : "unknown";
            if (isBenignMissingClass(className, extractLine(log, m.start()))) {
                return;
            }
            issues.add(new IssueRecord(IssueRecord.Severity.FATAL, "CLASS_NOT_FOUND",
                "ClassNotFoundException: " + className, extractLine(log, m.start())));
            return;
        }
        Matcher m2 = NO_CLASS_DEF.matcher(log);
        if (m2.find()) {
            String className = m2.groupCount() > 0 ? m2.group(1) : "unknown";
            if (isBenignMissingClass(className, extractLine(log, m2.start()))) {
                return;
            }
            issues.add(new IssueRecord(IssueRecord.Severity.FATAL, "CLASS_NOT_FOUND",
                "NoClassDefFoundError: " + className, extractLine(log, m2.start())));
        }
    }

    private boolean isBenignMissingClass(String className, String evidence) {
        return BENIGN_FABRIC_LOG4J_SELECTOR.equals(className)
            || (evidence != null && evidence.contains(BENIGN_FABRIC_LOG4J_SELECTOR));
    }

    private void extractStartupTime(List<IssueRecord> issues, String log) {
        Matcher m = SERVER_DONE.matcher(log);
        if (m.find()) {
            String secs = m.group(1);
            issues.add(new IssueRecord(IssueRecord.Severity.INFO, "STARTUP_TIME",
                "Server started in " + secs + "s", m.group(0)));
        }
    }

    private long countMatches(String log, Pattern p) {
        return p.matcher(log).results().count();
    }

    /** Extracts the full line containing the character at {@code pos}. */
    private String extractLine(String log, int pos) {
        int start = log.lastIndexOf('\n', pos - 1) + 1;
        int end   = log.indexOf('\n', pos);
        if (end < 0) end = log.length();
        String line = log.substring(start, end).trim();
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }
}
