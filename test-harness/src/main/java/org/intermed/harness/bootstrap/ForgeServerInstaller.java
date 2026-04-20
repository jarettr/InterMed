package org.intermed.harness.bootstrap;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Downloads and runs the Forge server installer for a given MC + Forge version.
 *
 * <p>The Forge installer is freely downloadable from files.minecraftforge.net.
 * Running it with {@code --installServer <dir>} creates a self-contained server
 * directory with all Forge libraries and a launch script.
 *
 * <p>After installation the base directory contains:
 * <ul>
 *   <li>{@code forge-<mc>-<forge>-server.jar} (or {@code run.sh} / {@code run.bat})</li>
 *   <li>{@code libraries/} with all Forge + MC libraries</li>
 * </ul>
 */
public final class ForgeServerInstaller {

    /** Maven repository root for Forge artefacts. */
    private static final String FORGE_MAVEN =
        "https://maven.minecraftforge.net/net/minecraftforge/forge";

    private final HttpClient http;

    private final HttpDownloader downloader;

    public ForgeServerInstaller() {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();
        this.downloader = new HttpDownloader(http);
    }

    /**
     * Ensures a Forge server base is installed at {@code installDir}.
     *
     * @param mcVersion    Minecraft version, e.g. {@code "1.20.1"}
     * @param forgeVersion Forge build, e.g. {@code "47.3.0"}
     * @param cacheDir     Directory to store the downloaded installer JAR
     * @param installDir   Target server installation directory
     * @param javaExe      Java executable to use for running the installer
     * @return path to the Forge server JAR (entry point for the server)
     */
    public Path install(String mcVersion, String forgeVersion,
                        Path cacheDir, Path installDir, String javaExe)
            throws IOException, InterruptedException {

        String markerName = "forge-" + mcVersion + "-" + forgeVersion + ".installed";
        Path marker = installDir.resolve(markerName);
        if (Files.exists(marker)) {
            System.out.println("[Bootstrap] Forge base already installed at: " + installDir);
            return locateServerJar(installDir, mcVersion, forgeVersion);
        }

        Path installerJar = downloadInstaller(mcVersion, forgeVersion, cacheDir);

        System.out.println("[Bootstrap] Running Forge installer (--installServer)...");
        Files.createDirectories(installDir);

        // Pre-place the vanilla server JAR so the Forge installer does not have
        // to re-download it from Mojang CDN (avoids TLS / timeout issues).
        // Forge expects it at: {installDir}/minecraft_server.{mcVersion}.jar
        Path vanillaCached = cacheDir.resolve("minecraft-server-" + mcVersion + ".jar");
        Path vanillaTarget = installDir.resolve("minecraft_server." + mcVersion + ".jar");
        if (Files.exists(vanillaCached) && !Files.exists(vanillaTarget)) {
            System.out.println("[Bootstrap] Pre-placing vanilla server JAR to skip Forge CDN download...");
            Files.copy(vanillaCached, vanillaTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        ProcessBuilder pb = new ProcessBuilder(
            javaExe, "-jar", installerJar.toString(),
            "--installServer", installDir.toString()
        );
        pb.directory(installDir.toFile());
        pb.redirectErrorStream(true);
        pb.inheritIO(); // stream output to our own stdout for visibility

        Process proc = pb.start();
        boolean done = proc.waitFor(15, TimeUnit.MINUTES);
        if (!done) {
            proc.destroyForcibly();
            throw new IOException("Forge installer timed out after 15 minutes");
        }
        if (proc.exitValue() != 0) {
            throw new IOException("Forge installer exited with code " + proc.exitValue());
        }

        // Write marker so we don't re-run on next invocation
        Files.writeString(marker, "installed");
        System.out.println("[Bootstrap] Forge server installed at: " + installDir);
        return locateServerJar(installDir, mcVersion, forgeVersion);
    }

    private Path downloadInstaller(String mcVersion, String forgeVersion, Path cacheDir)
            throws IOException, InterruptedException {

        String artifact = mcVersion + "-" + forgeVersion;
        String filename = "forge-" + artifact + "-installer.jar";
        Path dest = cacheDir.resolve(filename);
        if (Files.exists(dest)) {
            System.out.println("[Bootstrap] Forge installer already cached: " + dest);
            return dest;
        }

        String url = FORGE_MAVEN + "/" + artifact + "/" + filename;
        System.out.println("[Bootstrap] Downloading Forge installer from: " + url);
        Files.createDirectories(cacheDir);
        downloader.download(url, dest);
        System.out.println("[Bootstrap] Forge installer downloaded: " + dest);
        return dest;
    }

    /**
     * Locates the primary server JAR produced by the Forge installer.
     * Forge 1.20.x produces a {@code run.sh} + universal JAR; fall back to
     * the JAR matching the expected name pattern.
     */
    private Path locateServerJar(Path installDir, String mcVersion, String forgeVersion) {
        Path unixArgs = installDir.resolve("libraries/net/minecraftforge/forge/"
            + mcVersion + "-" + forgeVersion + "/unix_args.txt");
        if (Files.exists(unixArgs)) {
            return unixArgs;
        }
        Path runScript = installDir.resolve("run.sh");
        if (Files.exists(runScript)) {
            return runScript;
        }
        // Forge >= 1.17 uses a run.sh / @libraries arg-file approach;
        // the "server" jar is usually named forge-<mc>-<forge>-server.jar
        String[] candidates = {
            "forge-" + mcVersion + "-" + forgeVersion + "-server.jar",
            "forge-" + mcVersion + "-" + forgeVersion + ".jar",
            "forge-server.jar",
            "server.jar"
        };
        for (String name : candidates) {
            Path candidate = installDir.resolve(name);
            if (Files.exists(candidate)) return candidate;
        }
        // Last resort: find any forge*.jar
        try (var stream = Files.list(installDir)) {
            return stream
                .filter(p -> p.getFileName().toString().startsWith("forge")
                    && p.getFileName().toString().endsWith(".jar"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "Could not locate Forge server JAR in: " + installDir));
        } catch (IOException e) {
            throw new IllegalStateException("Could not list install dir: " + installDir, e);
        }
    }
}
