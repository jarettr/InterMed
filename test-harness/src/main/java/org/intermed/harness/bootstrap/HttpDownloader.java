package org.intermed.harness.bootstrap;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;

/**
 * Robust file downloader that tries curl → wget → JDK HttpClient in order.
 * curl/wget handle TLS session tickets and CDN edge cases better than the
 * JDK HTTP client for large binary files.
 */
public final class HttpDownloader {

    private final HttpClient http;

    public HttpDownloader(HttpClient http) {
        this.http = http;
    }

    /**
     * Downloads {@code url} to {@code dest}, trying curl, wget, then JDK
     * HttpClient in order. Throws IOException if all three fail.
     */
    public void download(String url, Path dest) throws IOException, InterruptedException {
        if (tryCurl(url, dest)) return;
        if (tryWget(url, dest)) return;
        downloadJdk(url, dest);
    }

    // ── backends ───────────────────────────────────────────────────────────────

    private boolean tryCurl(String url, Path dest) throws IOException, InterruptedException {
        if (!commandExists("curl")) return false;
        int exit = new ProcessBuilder(
                "curl", "-fsSL", "--retry", "3", "--retry-delay", "2",
                "-o", dest.toAbsolutePath().toString(), url)
            .inheritIO()
            .start()
            .waitFor();
        if (exit != 0) { Files.deleteIfExists(dest); return false; }
        return Files.exists(dest) && Files.size(dest) > 0;
    }

    private boolean tryWget(String url, Path dest) throws IOException, InterruptedException {
        if (!commandExists("wget")) return false;
        int exit = new ProcessBuilder(
                "wget", "-q", "--tries=3", "-O", dest.toAbsolutePath().toString(), url)
            .inheritIO()
            .start()
            .waitFor();
        if (exit != 0) { Files.deleteIfExists(dest); return false; }
        return Files.exists(dest) && Files.size(dest) > 0;
    }

    private void downloadJdk(String url, Path dest) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(10))
            .GET()
            .build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(
            dest, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE));
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(dest);
            throw new IOException("HTTP " + resp.statusCode() + " downloading: " + url);
        }
    }

    private boolean commandExists(String cmd) {
        try {
            return new ProcessBuilder("which", cmd).start().waitFor() == 0;
        } catch (Exception e) { return false; }
    }
}
