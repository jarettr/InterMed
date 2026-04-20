package org.intermed.harness.bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Downloads the Fabric server launch JAR for the given MC version + latest
 * stable Fabric loader, then starts it once to complete library setup.
 *
 * <p>Uses the Fabric meta API to resolve the latest stable loader version and
 * constructs the server-launch JAR download URL:
 * <pre>
 *   https://meta.fabricmc.net/v2/versions/loader/{mcVersion}/{loaderVersion}/{installerVersion}/server/jar
 * </pre>
 */
public final class FabricServerInstaller {

    private static final String FABRIC_META = "https://meta.fabricmc.net/v2";

    private final HttpClient http;

    private final HttpDownloader downloader;

    public FabricServerInstaller() {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();
        this.downloader = new HttpDownloader(http);
    }

    /**
     * Ensures a Fabric server base is installed at {@code installDir}.
     *
     * @return the path to the {@code fabric-server-launch.jar}
     */
    public Path install(String mcVersion, Path cacheDir, Path installDir, String javaExe)
            throws IOException, InterruptedException {

        Path marker = installDir.resolve("fabric-" + mcVersion + ".installed");
        if (Files.exists(marker)) {
            System.out.println("[Bootstrap] Fabric base already installed at: " + installDir);
            return installDir.resolve("fabric-server-launch.jar");
        }

        System.out.println("[Bootstrap] Resolving latest Fabric loader for MC " + mcVersion + "...");
        String loaderVersion   = resolveLatestLoader(mcVersion);
        String installerVersion = resolveLatestInstaller();
        System.out.println("[Bootstrap] Fabric loader=" + loaderVersion + " installer=" + installerVersion);

        Path launchJar = downloadServerLaunchJar(
            mcVersion, loaderVersion, installerVersion, cacheDir);

        // Copy the server-launch jar into the installDir
        Files.createDirectories(installDir);
        Path dest = installDir.resolve("fabric-server-launch.jar");
        Files.copy(launchJar, dest, StandardCopyOption.REPLACE_EXISTING);

        // Write the server.properties defaults and eula=true so the initial run doesn't block
        writeEula(installDir);
        writeServerProperties(installDir);

        // Run once to trigger library downloads
        System.out.println("[Bootstrap] Running Fabric server once to pull libraries...");
        ProcessBuilder pb = new ProcessBuilder(
            javaExe, "-Xmx512m", "-jar", dest.toString(), "nogui", "--installOnly"
        );
        pb.directory(installDir.toFile());
        pb.redirectErrorStream(true);
        pb.inheritIO();
        Process proc = pb.start();
        boolean done = proc.waitFor(10, TimeUnit.MINUTES);
        if (!done) proc.destroyForcibly();
        // Non-zero exit is expected if --installOnly is not recognised; just continue.

        Files.writeString(marker, "installed");
        System.out.println("[Bootstrap] Fabric server installed at: " + installDir);
        return dest;
    }

    private String resolveLatestLoader(String mcVersion) throws IOException, InterruptedException {
        String url = FABRIC_META + "/versions/loader/" + mcVersion;
        JsonArray arr = JsonParser.parseString(getJson(url)).getAsJsonArray();
        for (var elem : arr) {
            JsonObject entry = elem.getAsJsonObject();
            JsonObject loader = entry.getAsJsonObject("loader");
            if (loader.get("stable").getAsBoolean()) {
                return loader.get("version").getAsString();
            }
        }
        // Fall back to first entry if no stable found
        return arr.get(0).getAsJsonObject().getAsJsonObject("loader").get("version").getAsString();
    }

    private String resolveLatestInstaller() throws IOException, InterruptedException {
        String url = FABRIC_META + "/versions/installer";
        JsonArray arr = JsonParser.parseString(getJson(url)).getAsJsonArray();
        for (var elem : arr) {
            JsonObject entry = elem.getAsJsonObject();
            if (entry.get("stable").getAsBoolean()) {
                return entry.get("version").getAsString();
            }
        }
        return arr.get(0).getAsJsonObject().get("version").getAsString();
    }

    private Path downloadServerLaunchJar(String mcVersion, String loaderVersion,
                                          String installerVersion, Path cacheDir)
            throws IOException, InterruptedException {

        String filename = "fabric-server-" + mcVersion + "-" + loaderVersion + ".jar";
        Path dest = cacheDir.resolve(filename);
        if (Files.exists(dest)) {
            System.out.println("[Bootstrap] Fabric server-launch JAR already cached: " + dest);
            return dest;
        }

        String url = FABRIC_META + "/versions/loader/" + mcVersion + "/" + loaderVersion
            + "/" + installerVersion + "/server/jar";
        System.out.println("[Bootstrap] Downloading Fabric server-launch JAR...");
        Files.createDirectories(cacheDir);
        downloader.download(url, dest);
        System.out.println("[Bootstrap] Fabric server-launch JAR downloaded: " + dest);
        return dest;
    }

    private String getJson(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " fetching: " + url);
        }
        return resp.body();
    }

    private void writeEula(Path dir) throws IOException {
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
    }

    private void writeServerProperties(Path dir) throws IOException {
        String props = "online-mode=false\n"
            + "level-type=flat\n"
            + "generate-structures=false\n"
            + "spawn-monsters=false\n"
            + "spawn-npcs=false\n"
            + "spawn-animals=false\n"
            + "view-distance=4\n"
            + "simulation-distance=4\n";
        Files.writeString(dir.resolve("server.properties"), props);
    }
}
