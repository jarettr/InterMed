package org.intermed.harness;

import org.intermed.harness.bootstrap.EnvironmentBootstrap;
import org.intermed.harness.discovery.CorpusLockIO;
import org.intermed.harness.discovery.ModRegistry;
import org.intermed.harness.discovery.ResolvedCorpus;
import org.intermed.harness.performance.AlphaPerformanceBaselineRunner;
import org.intermed.harness.report.CompatibilityMatrix;
import org.intermed.harness.report.AlphaCompatibilityProofReport;
import org.intermed.harness.report.HarnessRunMetadata;
import org.intermed.harness.report.HtmlReportWriter;
import org.intermed.harness.report.JsonReportReader;
import org.intermed.harness.report.JsonReportWriter;
import org.intermed.harness.runner.PlanSelectionPlanner;
import org.intermed.harness.runner.ServerProcessRunner;
import org.intermed.harness.runner.TestCase;
import org.intermed.harness.runner.TestPlan;
import org.intermed.harness.runner.TestResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Command-line entry point for the InterMed compatibility test harness.
 *
 * <pre>
 * Usage:
 *   java -jar intermed-test-harness.jar &lt;command&gt; [flags]
 *
 * Commands:
 *   bootstrap   Download Minecraft 1.20.1 server + Forge/Fabric/NeoForge base environments
 *   discover    Fetch and cache the top-N mods from Modrinth
 *   run         Execute the test plan (uses cached mods + base environments)
 *   report      Generate HTML + JSON report from the most recent run
 *   alpha-proof Generate a conservative Phase 2 pass/fail/not-run plan report
 *   performance-baseline Capture clean Fabric/Forge + InterMed attached alpha baseline
 *   full        Run all phases end-to-end (bootstrap → discover → run → report)
 *   help        Print this message
 *
 * Flags:
 *   --intermed-jar=&lt;path&gt;   Path to the InterMed fat JAR (required)
 *   --output=&lt;dir&gt;          Root output directory (default: ./harness-output)
 *   --top=&lt;n&gt;              Number of mods to test (default: 1000)
 *   --mode=single|pairs|slices|full  Test mode (default: single)
 *   --loader=all|forge|fabric|neoforge  Loader filter (default: all)
 *   --timeout=&lt;seconds&gt;     Per-test timeout (default: 120)
 *   --concurrency=&lt;n&gt;       Parallel slots (default: 4)
 *   --heap=&lt;mb&gt;             JVM heap per test server in MB (default: 2048)
 *   --java=&lt;exe&gt;            Java executable (default: java)
 *   --mc-version=&lt;v&gt;        Minecraft version (default: 1.20.1)
 *   --forge-version=&lt;v&gt;     Forge build (default: 47.3.0)
 *   --neoforge-version=&lt;v&gt;  NeoForge build (default: 47.1.106 on 1.20.1)
 *   --pairs-top=&lt;k&gt;         Top-K mods for pairs phase (default: 50)
 *   --shard-count=&lt;n&gt;       Deterministic shard count (default: 1)
 *   --shard-index=&lt;n&gt;       Zero-based shard index (default: 0)
 *   --resume-failed         Reuse prior passes and rerun failed/missing only
 *   --retry-flaky[=&lt;n&gt;]     Retry transient failures (default: 1 when present)
 *   --evidence-level=&lt;lane&gt; Namespace output artifacts by evidence lane
 *   --skip-bootstrap        Skip bootstrap phase (already done)
 *   --skip-discover         Skip discovery phase (already done)
 *   --skip-run              Skip run phase (generate report from existing results)
 *   --exclude=&lt;slug&gt;        Exclude a mod slug (repeatable)
 * </pre>
 */
public final class HarnessMain {

    public static void main(String[] args) throws Exception {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help")) {
            printHelp();
            return;
        }

        String command = args[0];
        HarnessConfig.Builder builder = HarnessConfig.builder();
        parseFlags(args, 1, builder);
        HarnessConfig config = builder.build();

        switch (command) {
            case "bootstrap" -> runBootstrap(config);
            case "discover"  -> runDiscover(config);
            case "run"       -> runTests(config);
            case "report"    -> runReport(config, loadMatrix(config));
            case "alpha-proof" -> runAlphaProof(config);
            case "performance-baseline" -> runPerformanceBaseline(config);
            case "full"      -> runFull(config);
            default          -> {
                System.err.println("Unknown command: " + command);
                printHelp();
                System.exit(1);
            }
        }
    }

    // ── Phase implementations ─────────────────────────────────────────────────

    private static void runBootstrap(HarnessConfig config)
            throws EnvironmentBootstrap.BootstrapException {
        new EnvironmentBootstrap(config).run();
    }

    private static ResolvedCorpus runDiscover(HarnessConfig config)
            throws IOException, InterruptedException {
        ResolvedCorpus corpus = resolveCorpusForRun(config);
        publishCorpus(config, corpus);
        System.out.printf(
            Locale.ROOT,
            "[Discover] Corpus ready: %d total candidates, %d server-runnable, fingerprint=%s%n",
            corpus.lock().summary().totalCandidates(),
            corpus.lock().summary().runnableCandidates(),
            corpus.lock().corpusFingerprint()
        );
        return corpus;
    }

    private static CompatibilityMatrix runTests(HarnessConfig config)
            throws IOException, InterruptedException {
        return runTests(config, resolveCorpusForRun(config));
    }

    private static CompatibilityMatrix runTests(HarnessConfig config, ResolvedCorpus corpus)
            throws IOException, InterruptedException {
        publishCorpus(config, corpus);

        TestPlan plan = new TestPlan(config, corpus.runnableMods());
        List<TestCase> generatedCases = plan.generate();
        CompatibilityMatrix existing = config.resumeFailed ? loadMatrix(config) : new CompatibilityMatrix();
        PlanSelectionPlanner.Selection selection =
            new PlanSelectionPlanner().select(config, generatedCases, existing);

        System.out.printf(
            Locale.ROOT,
            "[Run] Lane=%s (effective=%s), shard %d/%d, corpus=%d total (%d runnable).%n",
            config.evidenceLevel.name(),
            HarnessEvidenceLevel.BOOTED.name(),
            config.shardIndex + 1,
            config.shardCount,
            corpus.lock().summary().totalCandidates(),
            corpus.lock().summary().runnableCandidates()
        );
        System.out.printf(
            Locale.ROOT,
            "[Run] Executing %d/%d sharded test cases with %d parallel slots…%n",
            selection.selectedCount(),
            selection.shardPlannedCases(),
            config.concurrency
        );
        if (config.resumeFailed) {
            System.out.printf(
                Locale.ROOT,
                "[Run] Resume mode: carried forward %d passing result(s), rerunning %d prior failing and %d missing case(s).%n",
                selection.carriedForwardCount(),
                selection.previousFailingCount(),
                selection.missingCount()
            );
        }

        CompatibilityMatrix matrix = selection.carriedForwardResults();
        Files.createDirectories(config.runsDir());

        AtomicInteger completed = new AtomicInteger(0);
        int total = selection.selectedCount();
        Instant runStart = Instant.now();

        try (ExecutorService pool = Executors.newFixedThreadPool(config.concurrency)) {
            ServerProcessRunner runner = new ServerProcessRunner(config);
            List<Future<TestResult>> futures = new ArrayList<>(selection.selectedCount());

            for (TestCase tc : selection.selectedCases()) {
                int startingAttempt = selection.nextAttemptFor(tc);
                futures.add(pool.submit(() -> {
                    TestResult result = executeWithRetries(config, runner, tc, startingAttempt);
                    int done = completed.incrementAndGet();
                    printProgress(done, total, result, runStart);
                    return result;
                }));
            }

            for (Future<TestResult> f : futures) {
                try {
                    matrix.add(f.get());
                } catch (Exception e) {
                    System.err.println("[Run] ERROR collecting result: " + e.getMessage());
                }
            }
        }

        System.out.println("\n" + matrix.summaryLine());
        saveMatrix(config, matrix, HarnessRunMetadata.from(
            config,
            corpus.lock(),
            selection.totalPlannedCases(),
            selection.shardPlannedCases(),
            selection.selectedCount(),
            selection.carriedForwardCount(),
            selection.previousFailingCount(),
            selection.missingCount(),
            config.reportCorpusPath().toAbsolutePath().normalize().toString()
        ));
        maybeGenerateDiagnosticsBundle(config, matrix);
        return matrix;
    }

    private static void runReport(HarnessConfig config, CompatibilityMatrix matrix)
            throws IOException {
        Path reportDir = config.reportDir();
        if (!Files.exists(config.reportCorpusPath()) && Files.exists(config.corpusLockPath())) {
            Files.createDirectories(reportDir);
            Files.copy(config.corpusLockPath(), config.reportCorpusPath());
        }
        new HtmlReportWriter().write(matrix, reportDir);
        System.out.println("[Report] HTML report ready at: " + reportDir.resolve("index.html").toAbsolutePath());
    }

    private static void runAlphaProof(HarnessConfig config)
            throws IOException, InterruptedException {
        ResolvedCorpus corpus = resolveCorpusForRun(config);
        publishCorpus(config, corpus);
        CompatibilityMatrix existing = loadMatrix(config);
        Path output = config.reportDir().resolve("alpha-compatibility-proof-plan.json");
        new AlphaCompatibilityProofReport().write(config, corpus, existing, output);
    }

    private static void runPerformanceBaseline(HarnessConfig config) throws Exception {
        new AlphaPerformanceBaselineRunner(config).run();
    }

    private static void runFull(HarnessConfig config) throws Exception {
        if (!config.skipBootstrap) {
            runBootstrap(config);
        }
        CompatibilityMatrix matrix = config.skipRun
            ? loadMatrix(config)
            : runTests(config, resolveCorpusForRun(config));
        runReport(config, matrix);
    }

    private static ResolvedCorpus resolveCorpusForRun(HarnessConfig config)
            throws IOException, InterruptedException {
        ModRegistry registry = new ModRegistry(config);
        return config.skipDiscover ? registry.loadCachedCorpus() : registry.discoverAndLock();
    }

    // ── Progress ──────────────────────────────────────────────────────────────

    private static void printProgress(int done, int total, TestResult result, Instant runStart) {
        Duration elapsed = Duration.between(runStart, Instant.now());
        double pct = done * 100.0 / total;
        String eta = "";
        if (done > 0) {
            long msPerCase = elapsed.toMillis() / done;
            long remaining = msPerCase * (total - done);
            eta = " ETA ~" + formatDuration(Duration.ofMillis(remaining));
        }
        System.out.printf("[%3d/%d %5.1f%%%s] %-12s %s%n",
            done, total, pct, eta,
            result.outcome().name(),
            result.testCase().description());
    }

    private static String formatDuration(Duration d) {
        long h = d.toHours();
        long m = d.toMinutesPart();
        long s = d.toSecondsPart();
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + s + "s";
        return s + "s";
    }

    // ── Persistence of matrix (for resume / report-only runs) ─────────────────

    private static void saveMatrix(HarnessConfig config,
                                   CompatibilityMatrix matrix,
                                   HarnessRunMetadata metadata)
            throws IOException {
        Files.createDirectories(config.reportDir());
        new JsonReportWriter().write(matrix, config.resultsJsonPath(), metadata);
        if (config.evidenceLevel == HarnessEvidenceLevel.BOOTED) {
            Files.copy(
                config.resultsJsonPath(),
                config.legacyResultsJsonPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private static void maybeGenerateDiagnosticsBundle(HarnessConfig config,
                                                       CompatibilityMatrix matrix) {
        if (matrix == null || matrix.failCount() <= 0) {
            return;
        }
        Path output = config.reportDir().resolve(
            "diagnostics-on-failure-" + config.evidenceLevel.fileToken() + ".zip");
        Path log = config.reportDir().resolve(
            "diagnostics-on-failure-" + config.evidenceLevel.fileToken() + ".log");
        List<String> command = List.of(
            config.javaExecutable,
            "-cp",
            config.intermedJar.toString(),
            "org.intermed.launcher.InterMedLauncher",
            "diagnostics-bundle",
            "--mods-dir",
            config.modsCache().resolve("jars").toString(),
            "--game-dir",
            config.outputDir.toString(),
            "--harness-results",
            config.resultsJsonPath().toString(),
            "--output",
            output.toString()
        );
        try {
            Files.createDirectories(config.reportDir());
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
            boolean finished = process.waitFor(5, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                System.err.println("[Diagnostics] Timed out generating failure diagnostics bundle; log: " + log);
                return;
            }
            if (process.exitValue() == 0) {
                System.out.println("[Diagnostics] Failure diagnostics bundle: " + output.toAbsolutePath());
            } else {
                System.err.println("[Diagnostics] Failed to generate diagnostics bundle (exit "
                    + process.exitValue() + "); log: " + log);
            }
        } catch (Exception e) {
            System.err.println("[Diagnostics] Unable to generate failure diagnostics bundle: " + e.getMessage());
        }
    }

    private static CompatibilityMatrix loadMatrix(HarnessConfig config) throws IOException {
        Path jsonFile = config.resultsJsonPath();
        if (!Files.exists(jsonFile)
                && config.evidenceLevel == HarnessEvidenceLevel.BOOTED
                && Files.exists(config.legacyResultsJsonPath())) {
            jsonFile = config.legacyResultsJsonPath();
        }
        if (!Files.exists(jsonFile)) {
            System.err.println("[Report] No results artifact found at: " + jsonFile);
            System.err.println("         Run 'run' or 'full' first to produce lane-specific test results.");
            return new CompatibilityMatrix();
        }
        return new JsonReportReader().read(jsonFile);
    }

    private static void publishCorpus(HarnessConfig config, ResolvedCorpus corpus) throws IOException {
        Path target = config.reportCorpusPath();
        new CorpusLockIO().write(corpus.lock(), target);
    }

    private static TestResult executeWithRetries(HarnessConfig config,
                                                 ServerProcessRunner runner,
                                                 TestCase testCase,
                                                 int startingAttempt) {
        int attempt = Math.max(1, startingAttempt);
        TestResult latest = runner.run(testCase).withAttempt(attempt, false);
        int retriesRemaining = config.retryFlaky;
        while (retriesRemaining > 0 && latest.retryRecommended()) {
            retriesRemaining--;
            attempt++;
            System.out.printf(
                Locale.ROOT,
                "[Run] Retrying flaky/transient case %s (attempt %d of %d)%n",
                testCase.id(),
                attempt,
                Math.max(attempt, startingAttempt + config.retryFlaky)
            );
            latest = runner.run(testCase).withAttempt(attempt, true);
        }
        return latest;
    }

    // ── CLI flag parsing ──────────────────────────────────────────────────────

    private static void parseFlags(String[] args, int start, HarnessConfig.Builder b) {
        for (int i = start; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--intermed-jar=")) {
                b.intermedJar(Paths.get(value(arg)));
            } else if (arg.startsWith("--output=")) {
                b.outputDir(Paths.get(value(arg)));
            } else if (arg.startsWith("--top=")) {
                b.topN(Integer.parseInt(value(arg)));
            } else if (arg.startsWith("--mode=")) {
                b.mode(HarnessConfig.TestMode.valueOf(value(arg).toUpperCase()));
            } else if (arg.startsWith("--loader=")) {
                b.loaderFilter(HarnessConfig.LoaderFilter.valueOf(value(arg).toUpperCase()));
            } else if (arg.startsWith("--timeout=")) {
                b.timeoutSeconds(Integer.parseInt(value(arg)));
            } else if (arg.startsWith("--concurrency=")) {
                b.concurrency(Integer.parseInt(value(arg)));
            } else if (arg.startsWith("--heap=")) {
                b.heapMb(Integer.parseInt(value(arg)));
            } else if (arg.startsWith("--java=")) {
                b.javaExecutable(value(arg));
            } else if (arg.startsWith("--mc-version=")) {
                b.mcVersion(value(arg));
            } else if (arg.startsWith("--forge-version=")) {
                b.forgeVersion(value(arg));
            } else if (arg.startsWith("--neoforge-version=")) {
                b.neoforgeVersion(value(arg));
            } else if (arg.startsWith("--pairs-top=")) {
                b.pairsTopK(Integer.parseInt(value(arg)));
            } else if (arg.startsWith("--shard-count=")) {
                b.shardCount(Integer.parseInt(value(arg)));
            } else if (arg.startsWith("--shard-index=")) {
                b.shardIndex(Integer.parseInt(value(arg)));
            } else if (arg.equals("--resume-failed")) {
                b.resumeFailed(true);
            } else if (arg.equals("--retry-flaky")) {
                b.retryFlaky(1);
            } else if (arg.startsWith("--retry-flaky=")) {
                b.retryFlaky(Integer.parseInt(value(arg)));
            } else if (arg.startsWith("--evidence-level=")) {
                b.evidenceLevel(HarnessEvidenceLevel.valueOf(
                    value(arg).toUpperCase(Locale.ROOT).replace('-', '_')));
            } else if (arg.equals("--skip-bootstrap")) {
                b.skipBootstrap(true);
            } else if (arg.equals("--skip-discover")) {
                b.skipDiscover(true);
            } else if (arg.equals("--skip-run")) {
                b.skipRun(true);
            } else if (arg.startsWith("--exclude=")) {
                b.exclude(value(arg));
            } else {
                System.err.println("[WARN] Unknown flag: " + arg);
            }
        }
    }

    private static String value(String arg) {
        int eq = arg.indexOf('=');
        return arg.substring(eq + 1);
    }

    private static void printHelp() {
        System.out.println("""
InterMed Compatibility Test Harness v8.0
=========================================

Usage:
  java -jar intermed-test-harness.jar <command> [flags]

Commands:
  bootstrap   Download MC 1.20.1 server + Forge/Fabric/NeoForge base environments
  discover    Fetch and cache the top-N mods from Modrinth
  run         Execute the test plan (uses cached mods + base environments)
  report      Re-generate HTML report from the selected lane results JSON
  alpha-proof Generate conservative Phase 2 pass/fail/not-run accounting without launching servers
  performance-baseline Capture clean Fabric/Forge + InterMed attached alpha performance snapshot
  full        Run all phases end-to-end (bootstrap → discover → run → report)
  help        Print this message

Flags:
  --intermed-jar=<path>   Path to the InterMed fat JAR  [REQUIRED for run/full]
  --output=<dir>          Root output directory         [default: ./harness-output]
  --top=<n>               Number of mods to test        [default: 1000]
  --mode=single|pairs|slices|full Test mode             [default: single]
  --loader=all|forge|fabric|neoforge Loader filter      [default: all]
  --timeout=<seconds>     Per-test timeout              [default: 120]
  --concurrency=<n>       Parallel test slots           [default: 4]
  --heap=<mb>             JVM heap per test server      [default: 2048]
  --java=<exe>            Java executable               [default: java]
  --mc-version=<v>        Minecraft version             [default: 1.20.1]
  --forge-version=<v>     Forge build number            [default: 47.3.0]
  --neoforge-version=<v>  NeoForge build number         [default: 47.1.106]
  --pairs-top=<k>         Top-K for pair phase          [default: 50]
  --shard-count=<n>       Deterministic shard count     [default: 1]
  --shard-index=<n>       Zero-based shard index        [default: 0]
  --resume-failed         Keep existing passes; rerun failed/missing cases only
  --retry-flaky[=<n>]     Retry transient failures      [default: 1 when flag is present]
  --evidence-level=<lane> Artifact lane namespace       [default: booted]
  --skip-bootstrap        Skip bootstrap (already done)
  --skip-discover         Skip discovery (already done)
  --skip-run              Skip execution and reuse lane-specific results JSON
  --exclude=<slug>        Exclude mod by Modrinth slug (repeatable)

Note:
  --mode=slices runs fixed curated alpha slices. --mode=full still exists for
  future pack-style Phase 3 work, but is not part of the current alpha proof.

Quick start (server with 8 cores, test top 1000 mods):
  java -jar intermed-test-harness.jar full \\
    --intermed-jar=./InterMedCore.jar \\
    --top=1000 \\
    --concurrency=8 \\
    --output=./harness-output

Sharded nightly example:
  java -jar intermed-test-harness.jar run \\
    --skip-bootstrap --skip-discover \\
    --concurrency=8 \\
    --shard-count=8 --shard-index=3 \\
    --retry-flaky

Discovery emits a reproducible corpus lock under:
  ./harness-output/cache/mods/corpus-lock.json
and copies the active run corpus to:
  ./harness-output/report/corpus-lock.json

The HTML report will be at: ./harness-output/report/index.html
""");
    }
}
