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
import java.time.Duration;

/**
 * Downloads the vanilla Minecraft server JAR from Mojang's CDN.
 *
 * <p>The server JAR is freely distributable and does not require a Minecraft
 * account. It is used by Forge/Fabric installer scripts to set up the server
 * library classpath.
 *
 * <p>Resolution chain:
 * <ol>
 *   <li>Fetch {@code version_manifest_v2.json} from Mojang's launcher meta CDN.</li>
 *   <li>Find the requested MC version entry and follow its package URL.</li>
 *   <li>Extract {@code downloads.server.url} and download the JAR.</li>
 * </ol>
 */
public final class VanillaServerFetcher {

    private static final String MANIFEST_URL =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final HttpClient http;

    private final HttpDownloader downloader;

    public VanillaServerFetcher() {
        this.http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();
        this.downloader = new HttpDownloader(http);
    }

    /**
     * Downloads the server JAR for {@code mcVersion} to {@code destDir/minecraft-server-<version>.jar}.
     *
     * @return path to the downloaded JAR
     */
    public Path fetch(String mcVersion, Path destDir) throws IOException, InterruptedException {
        Path dest = destDir.resolve("minecraft-server-" + mcVersion + ".jar");
        if (Files.exists(dest)) {
            System.out.println("[Bootstrap] Vanilla server JAR already cached: " + dest);
            return dest;
        }

        System.out.println("[Bootstrap] Fetching version manifest from Mojang CDN...");
        String manifestJson = getJson(MANIFEST_URL);
        String packageUrl = findPackageUrl(manifestJson, mcVersion);

        System.out.println("[Bootstrap] Fetching version package for " + mcVersion + "...");
        String packageJson = getJson(packageUrl);
        String serverJarUrl = extractServerJarUrl(packageJson);

        System.out.println("[Bootstrap] Downloading vanilla server JAR (" + mcVersion + ")...");
        Files.createDirectories(destDir);
        downloadFile(serverJarUrl, dest);
        System.out.println("[Bootstrap] Vanilla server JAR saved: " + dest);
        return dest;
    }

    private String findPackageUrl(String manifestJson, String targetVersion) {
        JsonObject manifest = JsonParser.parseString(manifestJson).getAsJsonObject();
        JsonArray versions = manifest.getAsJsonArray("versions");
        for (var elem : versions) {
            JsonObject v = elem.getAsJsonObject();
            if (targetVersion.equals(v.get("id").getAsString())) {
                return v.get("url").getAsString();
            }
        }
        throw new IllegalArgumentException("MC version not found in manifest: " + targetVersion);
    }

    private String extractServerJarUrl(String packageJson) {
        JsonObject pkg = JsonParser.parseString(packageJson).getAsJsonObject();
        return pkg.getAsJsonObject("downloads")
                  .getAsJsonObject("server")
                  .get("url").getAsString();
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

    private void downloadFile(String url, Path dest) throws IOException, InterruptedException {
        downloader.download(url, dest);
    }
}
