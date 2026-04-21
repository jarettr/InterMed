package org.intermed.harness.bootstrap;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Downloads and runs the NeoForge server installer for a given MC + NeoForge version.
 *
 * <p>NeoForge 1.20.1 is published from the official NeoForged Maven under the
 * legacy {@code net/neoforged/forge} coordinate, while newer lines use
 * {@code net/neoforged/neoforge}.  The harness normalizes this detail so the
 * rest of the runtime can treat NeoForge as a first-class loader lane.</p>
 */
public final class NeoForgeServerInstaller {

    private static final String NEOFORGED_MAVEN = "https://maven.neoforged.net/releases/net/neoforged";

    private final HttpClient http;
    private final HttpDownloader downloader;

    public NeoForgeServerInstaller() {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();
        this.downloader = new HttpDownloader(http);
    }

    public Path install(String mcVersion,
                        String neoForgeVersion,
                        Path cacheDir,
                        Path installDir,
                        String javaExe) throws IOException, InterruptedException {

        String effectiveVersion = effectiveVersion(mcVersion, neoForgeVersion);
        String artifactId = artifactId(effectiveVersion);
        Path marker = installDir.resolve(artifactId + "-" + effectiveVersion + ".installed");
        if (Files.exists(marker)) {
            System.out.println("[Bootstrap] NeoForge base already installed at: " + installDir);
            return locateServerEntrypoint(installDir, artifactId, effectiveVersion);
        }

        Path installerJar = downloadInstaller(artifactId, effectiveVersion, cacheDir);

        System.out.println("[Bootstrap] Running NeoForge installer (--installServer)...");
        Files.createDirectories(installDir);

        ProcessBuilder pb = new ProcessBuilder(
            javaExe, "-jar", installerJar.toString(),
            "--installServer", installDir.toString()
        );
        pb.directory(installDir.toFile());
        pb.redirectErrorStream(true);
        pb.inheritIO();

        Process proc = pb.start();
        boolean done = proc.waitFor(15, TimeUnit.MINUTES);
        if (!done) {
            proc.destroyForcibly();
            throw new IOException("NeoForge installer timed out after 15 minutes");
        }
        if (proc.exitValue() != 0) {
            throw new IOException("NeoForge installer exited with code " + proc.exitValue());
        }

        Files.writeString(marker, "installed");
        System.out.println("[Bootstrap] NeoForge server installed at: " + installDir);
        return locateServerEntrypoint(installDir, artifactId, effectiveVersion);
    }

    static String effectiveVersion(String mcVersion, String neoForgeVersion) {
        if (neoForgeVersion == null || neoForgeVersion.isBlank()) {
            return mcVersion == null || mcVersion.isBlank()
                ? "1.20.1-47.1.106"
                : mcVersion + "-47.1.106";
        }
        String trimmed = neoForgeVersion.trim();
        if (trimmed.startsWith(mcVersion + "-")) {
            return trimmed;
        }
        if ("1.20.1".equals(mcVersion) && trimmed.matches("\\d+(?:\\.\\d+){2,}(?:-[A-Za-z0-9.]+)?")) {
            return mcVersion + "-" + trimmed;
        }
        return trimmed;
    }

    static String artifactId(String effectiveVersion) {
        return effectiveVersion.startsWith("1.20.1-") ? "forge" : "neoforge";
    }

    private Path downloadInstaller(String artifactId, String effectiveVersion, Path cacheDir)
            throws IOException, InterruptedException {
        String filename = artifactId + "-" + effectiveVersion + "-installer.jar";
        Path dest = cacheDir.resolve(filename);
        if (Files.exists(dest)) {
            System.out.println("[Bootstrap] NeoForge installer already cached: " + dest);
            return dest;
        }

        String url = NEOFORGED_MAVEN + "/" + artifactId + "/" + effectiveVersion + "/" + filename;
        System.out.println("[Bootstrap] Downloading NeoForge installer from: " + url);
        Files.createDirectories(cacheDir);
        downloader.download(url, dest);
        System.out.println("[Bootstrap] NeoForge installer downloaded: " + dest);
        return dest;
    }

    private Path locateServerEntrypoint(Path installDir, String artifactId, String effectiveVersion) {
        try (var stream = Files.walk(installDir)) {
            Path argsFile = stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals("unix_args.txt"))
                .findFirst()
                .orElse(null);
            if (argsFile != null) {
                return argsFile;
            }
        } catch (IOException ignored) {
            // Fall through to script/jar lookup.
        }

        Path runScript = installDir.resolve("run.sh");
        if (Files.exists(runScript)) {
            return runScript;
        }

        String[] candidates = {
            artifactId + "-" + effectiveVersion + "-server.jar",
            artifactId + "-" + effectiveVersion + ".jar",
            artifactId + "-server.jar",
            "server.jar"
        };
        for (String name : candidates) {
            Path candidate = installDir.resolve(name);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        try (var stream = Files.list(installDir)) {
            return stream
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith(artifactId) && name.endsWith(".jar");
                })
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "Could not locate NeoForge server entrypoint in: " + installDir));
        } catch (IOException e) {
            throw new IllegalStateException("Could not list install dir: " + installDir, e);
        }
    }
}
