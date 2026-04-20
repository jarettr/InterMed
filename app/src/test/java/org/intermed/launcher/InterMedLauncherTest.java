package org.intermed.launcher;

import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.sandbox.PolyglotSandboxManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class InterMedLauncherTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("runtime.game.dir");
        System.clearProperty("runtime.mods.dir");
        System.clearProperty("intermed.modsDir");
        RuntimeConfig.resetForTests();
    }

    @Test
    void buildsJarLaunchCommandWithAgentAndRuntimeProperties() throws Exception {
        System.setProperty("runtime.game.dir", "/tmp/intermed-game");
        System.setProperty("runtime.mods.dir", "/tmp/intermed-mods");
        RuntimeConfig.reload();

        InterMedLauncher.LaunchRequest request = InterMedLauncher.parse(new String[] {
            "launch",
            "--jar", "client.jar",
            "--agent", "/tmp/intermed-agent.jar",
            "--dry-run"
        });

        List<String> command = InterMedLauncher.buildLaunchCommand(request);
        assertTrue(command.contains("-javaagent:/tmp/intermed-agent.jar"));
        assertTrue(command.contains("-jar"));
        assertTrue(command.contains("client.jar"));
        assertTrue(command.contains("-Druntime.game.dir=" + Path.of("/tmp/intermed-game")));
        assertTrue(command.contains("-Druntime.mods.dir=" + Path.of("/tmp/intermed-mods")));
        assertTrue(command.contains("-Dintermed.modsDir=" + Path.of("/tmp/intermed-mods")));
    }

    @Test
    void parsesPassthroughArgumentsAfterDoubleDash() {
        InterMedLauncher.LaunchRequest request = InterMedLauncher.parse(new String[] {
            "launch",
            "--main-class", "cpw.mods.bootstraplauncher.BootstrapLauncher",
            "--classpath", "game.jar",
            "--",
            "--username", "Mak"
        });

        assertEquals("launch", request.command());
        assertEquals("cpw.mods.bootstraplauncher.BootstrapLauncher", request.option("main-class").orElseThrow());
        assertEquals(List.of("--username", "Mak"), request.passthrough());
    }

    @Test
    void doctorReportsPhaseFourSandboxSurface() throws Exception {
        InterMedLauncher.DoctorReport report = InterMedLauncher.doctor(InterMedLauncher.parse(new String[] {"doctor"}));

        assertTrue(report.lines().stream().anyMatch(line -> line.contains("Espresso sandbox")));
        assertTrue(report.lines().stream().anyMatch(line -> line.contains("Chicory sandbox")));
        assertTrue(report.lines().stream().anyMatch(line -> line.contains("Capability security strict mode")));
        assertTrue(report.lines().stream().anyMatch(line -> line.contains("Native sandbox fallback")));
        assertTrue(report.lines().stream().anyMatch(line ->
            line.contains("Sandbox host contract functions: " + PolyglotSandboxManager.hostExportCount())));
        assertTrue(report.lines().stream().anyMatch(line -> line.contains("Sandbox host contract digest: ")));
        assertTrue(report.lines().stream().anyMatch(line -> line.contains("Sandbox runtime caches: ")));
        assertTrue(report.lines().stream().anyMatch(line -> line.contains("Network bridge protocol")));
        assertTrue(report.lines().stream().anyMatch(line -> line.contains("Network bridge channels")));
        assertTrue(report.lines().stream().anyMatch(line -> line.contains("Forge SimpleImpl bridge")));
        assertTrue(report.lines().stream().anyMatch(line -> line.contains("NeoForge payload bridge")));
    }

    @Test
    void compatReportAndSbomCommandsWriteArtifacts() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-launcher-reports");
        Path compatReport = Files.createTempFile("intermed-compat-report", ".json");
        Path compatCorpus = Files.createTempFile("intermed-compat-corpus", ".json");
        Path compatSweepMatrix = Files.createTempFile("intermed-compat-sweep-matrix", ".json");
        Path harnessResults = Files.createTempFile("intermed-harness-results", ".json");
        Path launchReadiness = Files.createTempFile("intermed-launch-readiness", ".json");
        Path sbom = Files.createTempFile("intermed-sbom", ".json");
        Path apiGapMatrix = Files.createTempFile("intermed-api-gap-matrix", ".json");
        Path gameDir = Files.createTempDirectory("intermed-launcher-game");
        Path diagnosticsBundle = Files.createTempFile("intermed-diagnostics", ".zip");
        Files.createDirectories(gameDir.resolve("logs"));
        Files.writeString(gameDir.resolve("logs/latest.log"), "launcher diagnostics", StandardCharsets.UTF_8);
        Files.writeString(harnessResults, harnessResultsJson("report_mod"), StandardCharsets.UTF_8);
        createFabricJar(modsDir.resolve("report-mod.jar"), "report_mod");

        assertEquals(0, InterMedLauncher.execute(new String[] {
            "compat-report",
            "--mods-dir", modsDir.toString(),
            "--output", compatReport.toString()
        }));
        assertEquals(0, InterMedLauncher.execute(new String[] {
            "compat-corpus",
            "--mods-dir", modsDir.toString(),
            "--output", compatCorpus.toString()
        }));
        assertEquals(0, InterMedLauncher.execute(new String[] {
            "compat-sweep-matrix",
            "--corpus", compatCorpus.toString(),
            "--results", harnessResults.toString(),
            "--output", compatSweepMatrix.toString()
        }));
        assertEquals(0, InterMedLauncher.execute(new String[] {
            "sbom",
            "--mods-dir", modsDir.toString(),
            "--output", sbom.toString()
        }));
        assertEquals(0, InterMedLauncher.execute(new String[] {
            "api-gap-matrix",
            "--game-dir", gameDir.toString(),
            "--output", apiGapMatrix.toString()
        }));
        assertEquals(0, InterMedLauncher.execute(new String[] {
            "launch-readiness-report",
            "--project-root", Path.of(".").toAbsolutePath().normalize().toString(),
            "--game-dir", gameDir.toString(),
            "--mods-dir", modsDir.toString(),
            "--harness-results", harnessResults.toString(),
            "--output", launchReadiness.toString()
        }));
        assertEquals(0, InterMedLauncher.execute(new String[] {
            "diagnostics-bundle",
            "--game-dir", gameDir.toString(),
            "--mods-dir", modsDir.toString(),
            "--harness-results", harnessResults.toString(),
            "--output", diagnosticsBundle.toString()
        }));

        assertTrue(Files.readString(compatReport).contains("\"report_mod\""));
        assertTrue(Files.readString(compatReport).contains("\"runtime\""));
        assertTrue(Files.readString(compatReport).contains("\"networkBridge\""));
        assertTrue(Files.readString(compatCorpus).contains("\"intermed-compatibility-corpus-v1\""));
        assertTrue(Files.readString(compatCorpus).contains("\"report_mod\""));
        assertTrue(Files.readString(compatSweepMatrix).contains("\"intermed-compatibility-sweep-matrix-v1\""));
        assertTrue(Files.readString(compatSweepMatrix).contains("\"PASS\""));
        assertTrue(Files.readString(launchReadiness).contains("\"intermed-launch-readiness-report-v1\""));
        assertTrue(Files.readString(sbom).contains("\"report_mod\""));
        assertTrue(Files.readString(apiGapMatrix).contains("\"intermed-api-gap-matrix-v1\""));
        try (ZipFile zip = new ZipFile(diagnosticsBundle.toFile())) {
            assertNotNull(zip.getEntry("manifest.json"));
            assertNotNull(zip.getEntry("reports/compatibility-report.json"));
            assertNotNull(zip.getEntry("reports/compatibility-corpus.json"));
            assertNotNull(zip.getEntry("reports/compatibility-sweep-matrix.json"));
            assertNotNull(zip.getEntry("reports/api-gap-matrix.json"));
            assertNotNull(zip.getEntry("reports/security-report.json"));
            assertNotNull(zip.getEntry("reports/launch-readiness-report.json"));
            assertNotNull(zip.getEntry("artifacts/harness/results.json"));
            assertNotNull(zip.getEntry("artifacts/logs/latest.log"));
            String matrix = new String(
                zip.getInputStream(zip.getEntry("reports/compatibility-sweep-matrix.json")).readAllBytes(),
                StandardCharsets.UTF_8
            );
            assertTrue(matrix.contains("\"harness-result-normalization\""));
        }
    }

    @Test
    void launchFailureAutomaticallyWritesDiagnosticsBundle() throws Exception {
        Path root = Files.createTempDirectory("intermed-launcher-failure");
        Path gameDir = root.resolve("game");
        Path modsDir = gameDir.resolve("intermed_mods");
        Path diagnosticsBundle = root.resolve("launch-failure.zip");
        Files.createDirectories(gameDir.resolve("logs"));
        Files.createDirectories(modsDir);
        Files.writeString(gameDir.resolve("logs/latest.log"), "failed launch log", StandardCharsets.UTF_8);
        createFabricJar(modsDir.resolve("failed-mod.jar"), "failed_mod");
        LaunchFailureFixture fixture = createLaunchFailureFixture(root);

        int exitCode = InterMedLauncher.execute(new String[] {
            "launch",
            "--java", javaExecutable().toString(),
            "--agent", fixture.agentJar().toString(),
            "--game-dir", gameDir.toString(),
            "--mods-dir", modsDir.toString(),
            "--diagnostics-output", diagnosticsBundle.toString(),
            "--main-class", "demo.FailingMain",
            "--classpath", fixture.classesDir().toString()
        });

        assertEquals(7, exitCode);
        assertTrue(Files.isRegularFile(diagnosticsBundle));
        try (ZipFile zip = new ZipFile(diagnosticsBundle.toFile())) {
            assertNotNull(zip.getEntry("manifest.json"));
            assertNotNull(zip.getEntry("reports/dependency-plan.json"));
            assertNotNull(zip.getEntry("reports/security-report.json"));
            assertNotNull(zip.getEntry("artifacts/logs/latest.log"));
        }
    }

    @Test
    void doctorBootstrapPathFallsBackFromFabricAgentToGenericBootstrapJar() throws Exception {
        Path fabricAgent = Path.of("/tmp/InterMedCore-8.0-SNAPSHOT-fabric.jar");
        Path bootstrap = invokeBootstrapSupportPath(fabricAgent);

        assertEquals(Path.of("/tmp/InterMedCore-8.0-SNAPSHOT-bootstrap.jar"), bootstrap);
    }

    private static Path invokeBootstrapSupportPath(Path agentPath) throws Exception {
        var method = InterMedLauncher.class.getDeclaredMethod("resolveBootstrapSupportPath", Path.class);
        method.setAccessible(true);
        return (Path) method.invoke(null, agentPath);
    }

    private static LaunchFailureFixture createLaunchFailureFixture(Path root) throws Exception {
        Path sourceDir = root.resolve("launcher-fixture-src");
        Path classesDir = root.resolve("launcher-fixture-classes");
        Files.createDirectories(sourceDir.resolve("demo"));
        Files.createDirectories(classesDir);
        Path agentSource = sourceDir.resolve("demo/NoopAgent.java");
        Path mainSource = sourceDir.resolve("demo/FailingMain.java");
        Files.writeString(agentSource, """
            package demo;
            import java.lang.instrument.Instrumentation;
            public final class NoopAgent {
                public static void premain(String args, Instrumentation instrumentation) {
                }
            }
            """, StandardCharsets.UTF_8);
        Files.writeString(mainSource, """
            package demo;
            public final class FailingMain {
                public static void main(String[] args) {
                    System.err.println("synthetic launch failure");
                    System.exit(7);
                }
            }
            """, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "System Java compiler is required for launcher failure fixture");
        int result = compiler.run(
            null,
            null,
            null,
            "-d",
            classesDir.toString(),
            agentSource.toString(),
            mainSource.toString()
        );
        assertEquals(0, result, "Synthetic launcher fixture must compile");

        Path agentJar = root.resolve("noop-agent.jar");
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(new Attributes.Name("Premain-Class"), "demo.NoopAgent");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(agentJar), manifest)) {
            output.putNextEntry(new JarEntry("demo/NoopAgent.class"));
            output.write(Files.readAllBytes(classesDir.resolve("demo/NoopAgent.class")));
            output.closeEntry();
        }
        return new LaunchFailureFixture(agentJar, classesDir);
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    private static void createFabricJar(Path jarPath, String modId) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write(("""
                {
                  "id": "%s",
                  "version": "1.0.0",
                  "entrypoints": { "main": ["demo.report.EntryPoint"] }
                }
                """.formatted(modId)).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
    }

    private static String harnessResultsJson(String modId) {
        return """
            {
              "generatedAt": "2026-04-19T00:00:00Z",
              "totalCount": 1,
              "results": [
                {
                  "id": "single-%s-fabric",
                  "description": "Single: %s",
                  "loader": "FABRIC",
                  "modCount": 1,
                  "outcome": "PASS",
                  "passed": true,
                  "startupMs": 12000,
                  "exitCode": 0,
                  "executedAt": "2026-04-19T00:00:01Z",
                  "mods": [
                    {
                      "slug": "%s",
                      "name": "%s",
                      "version": "1.0.0",
                      "downloads": 1
                    }
                  ],
                  "issues": []
                }
              ]
            }
            """.formatted(modId, modId, modId, modId);
    }

    private record LaunchFailureFixture(Path agentJar, Path classesDir) {}
}
