package org.intermed.harness;

import org.intermed.harness.bootstrap.EnvironmentBootstrap;
import org.intermed.harness.discovery.ModCandidate;
import org.intermed.harness.discovery.ModRegistry;
import org.intermed.harness.report.CompatibilityMatrix;
import org.intermed.harness.report.HtmlReportWriter;
import org.intermed.harness.report.JsonReportReader;
import org.intermed.harness.report.JsonReportWriter;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Command-line entry point for the InterMed compatibility test harness.
 *
 * <pre>
 * Usage:
 *   java -jar intermed-test-harness.jar &lt;command&gt; [flags]
 *
 * Commands:
 *   bootstrap   Download Minecraft 1.20.1 server + Forge/Fabric base environments
 *   discover    Fetch and cache the top-N mods from Modrinth
 *   run         Execute the test plan (uses cached mods + base environments)
 *   report      Generate HTML + JSON report from the most recent run
 *   full        Run all phases end-to-end (bootstrap → discover → run → report)
 *   help        Print this message
 *
 * Flags:
 *   --intermed-jar=&lt;path&gt;   Path to the InterMed fat JAR (required)
 *   --output=&lt;dir&gt;          Root output directory (default: ./harness-output)
 *   --top=&lt;n&gt;              Number of mods to test (default: 1000)
 *   --mode=single|pairs|full  Test mode (default: single)
 *   --loader=all|forge|fabric  Loader filter (default: all)
 *   --timeout=&lt;seconds&gt;     Per-test timeout (default: 120)
 *   --concurrency=&lt;n&gt;       Parallel slots (default: 4)
 *   --heap=&lt;mb&gt;             JVM heap per test server in MB (default: 2048)
 *   --java=&lt;exe&gt;            Java executable (default: java)
 *   --mc-version=&lt;v&gt;        Minecraft version (default: 1.20.1)
 *   --forge-version=&lt;v&gt;     Forge build (default: 47.3.0)
 *   --pairs-top=&lt;k&gt;         Top-K mods for pairs phase (default: 50)
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

    private static List<ModCandidate> runDiscover(HarnessConfig config)
            throws IOException, InterruptedException {
        return new ModRegistry(config).discoverAndDownload();
    }

    private static CompatibilityMatrix runTests(HarnessConfig config)
            throws IOException, InterruptedException {
        return runTests(config, resolveModsForRun(config));
    }

    private static CompatibilityMatrix runTests(HarnessConfig config, List<ModCandidate> mods)
            throws IOException, InterruptedException {
        TestPlan plan = new TestPlan(config, mods);
        List<TestCase> cases = plan.generate();

        System.out.printf("[Run] Executing %d test cases with %d parallel slots…%n",
            cases.size(), config.concurrency);

        CompatibilityMatrix matrix = new CompatibilityMatrix();
        Files.createDirectories(config.runsDir());

        AtomicInteger completed = new AtomicInteger(0);
        int total = cases.size();
        Instant runStart = Instant.now();

        try (ExecutorService pool = Executors.newFixedThreadPool(config.concurrency)) {
            ServerProcessRunner runner = new ServerProcessRunner(config);
            List<Future<TestResult>> futures = new ArrayList<>(cases.size());

            for (TestCase tc : cases) {
                futures.add(pool.submit(() -> {
                    TestResult result = runner.run(tc);
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
        saveMatrix(config, matrix);
        return matrix;
    }

    private static void runReport(HarnessConfig config, CompatibilityMatrix matrix)
            throws IOException {
        Path reportDir = config.reportDir();
        new HtmlReportWriter().write(matrix, reportDir);
        new JsonReportWriter().write(matrix, reportDir);
        System.out.println("[Report] Report ready at: " + reportDir.resolve("index.html").toAbsolutePath());
    }

    private static void runFull(HarnessConfig config) throws Exception {
        if (!config.skipBootstrap) {
            runBootstrap(config);
        }
        CompatibilityMatrix matrix = config.skipRun
            ? loadMatrix(config)
            : runTests(config, resolveModsForRun(config));
        runReport(config, matrix);
    }

    private static List<ModCandidate> resolveModsForRun(HarnessConfig config)
            throws IOException, InterruptedException {
        ModRegistry registry = new ModRegistry(config);
        return config.skipDiscover ? registry.loadCached() : registry.discoverAndDownload();
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

    private static void saveMatrix(HarnessConfig config, CompatibilityMatrix matrix)
            throws IOException {
        Path dir = config.reportDir();
        Files.createDirectories(dir);
        new JsonReportWriter().write(matrix, dir);
    }

    private static CompatibilityMatrix loadMatrix(HarnessConfig config) throws IOException {
        Path jsonFile = config.reportDir().resolve("results.json");
        if (!Files.exists(jsonFile)) {
            System.err.println("[Report] No results.json found at: " + jsonFile);
            System.err.println("         Run 'run' or 'full' first to produce test results.");
            return new CompatibilityMatrix();
        }
        return new JsonReportReader().read(jsonFile);
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
            } else if (arg.startsWith("--pairs-top=")) {
                b.pairsTopK(Integer.parseInt(value(arg)));
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
  bootstrap   Download MC 1.20.1 server + Forge/Fabric base environments
  discover    Fetch and cache the top-N mods from Modrinth
  run         Execute the test plan (uses cached mods + base environments)
  report      Re-generate HTML report from the last results.json
  full        Run all phases end-to-end (bootstrap → discover → run → report)
  help        Print this message

Flags:
  --intermed-jar=<path>   Path to the InterMed fat JAR  [REQUIRED for run/full]
  --output=<dir>          Root output directory         [default: ./harness-output]
  --top=<n>               Number of mods to test        [default: 1000]
  --mode=single|pairs|full Test mode                    [default: single]
  --loader=all|forge|fabric Loader filter               [default: all]
  --timeout=<seconds>     Per-test timeout              [default: 120]
  --concurrency=<n>       Parallel test slots           [default: 4]
  --heap=<mb>             JVM heap per test server      [default: 2048]
  --java=<exe>            Java executable               [default: java]
  --mc-version=<v>        Minecraft version             [default: 1.20.1]
  --forge-version=<v>     Forge build number            [default: 47.3.0]
  --pairs-top=<k>         Top-K for pair phase          [default: 50]
  --skip-bootstrap        Skip bootstrap (already done)
  --skip-discover         Skip discovery (already done)
  --skip-run              Skip execution and reuse existing results.json
  --exclude=<slug>        Exclude mod by Modrinth slug (repeatable)

Quick start (server with 8 cores, test top 1000 mods):
  java -jar intermed-test-harness.jar full \\
    --intermed-jar=./InterMedCore.jar \\
    --top=1000 \\
    --concurrency=8 \\
    --output=./harness-output

The HTML report will be at: ./harness-output/report/index.html
""");
    }
}
