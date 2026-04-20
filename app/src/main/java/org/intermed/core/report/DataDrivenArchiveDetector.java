package org.intermed.core.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class DataDrivenArchiveDetector {

    private static final Pattern VERSION_TOKEN = Pattern.compile("(?i)(?:^|[-_+\\s])(?:mc)?\\d");
    private static final Pattern EXPLICIT_VERSION_TOKEN =
        Pattern.compile("(?i)(?:^|[-_+\\s])v(\\d+(?:\\.\\d+)+(?:[-+][a-z0-9._-]+)?)");
    private static final Pattern SEMANTIC_VERSION_TOKEN =
        Pattern.compile("(?i)(?:^|[-_+\\s])(?:mc)?(\\d+(?:\\.\\d+)+(?:[-+][a-z0-9._-]+)?)");

    private DataDrivenArchiveDetector() {}

    static Optional<Artifact> detect(File archive) {
        try (ZipFile zip = new ZipFile(archive)) {
            boolean packMetadata = zip.getEntry("pack.mcmeta") != null;
            boolean serverData = false;
            boolean clientResources = false;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("data/")) {
                    serverData = true;
                }
                if (name.startsWith("assets/")) {
                    clientResources = true;
                }
            }
            if (!packMetadata && !serverData && !clientResources) {
                return Optional.empty();
            }

            String id = artifactId(archive.getName());
            return Optional.of(new Artifact(
                id,
                displayName(id),
                artifactVersion(archive.getName()),
                platform(serverData, clientResources),
                artifactType(serverData, clientResources),
                clientResources,
                serverData
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    static JsonObject emptyEntrypoints() {
        JsonObject entrypoints = new JsonObject();
        entrypoints.add("main", new JsonArray());
        entrypoints.add("client", new JsonArray());
        entrypoints.add("server", new JsonArray());
        return entrypoints;
    }

    static JsonObject sandbox(Artifact artifact) {
        JsonObject sandbox = new JsonObject();
        sandbox.addProperty("modId", artifact.id());
        sandbox.addProperty("requested", "native");
        sandbox.addProperty("effective", "native");
        sandbox.addProperty("risky", false);
        sandbox.addProperty("hotPath", true);
        sandbox.addProperty("fallbackApplied", false);
        sandbox.addProperty("reason", artifact.artifactType());
        sandbox.addProperty("entrypoint", "data_driven_content");
        return sandbox;
    }

    private static String artifactType(boolean serverData, boolean clientResources) {
        if (serverData && clientResources) {
            return "data-resource-pack";
        }
        return serverData ? "data-pack" : "resource-pack";
    }

    private static String platform(boolean serverData, boolean clientResources) {
        if (serverData && clientResources) {
            return "DATA_RESOURCE_PACK";
        }
        return serverData ? "DATA_PACK" : "RESOURCE_PACK";
    }

    private static String artifactId(String fileName) {
        String stem = fileStem(fileName);
        Matcher matcher = VERSION_TOKEN.matcher(stem);
        String prefix = matcher.find() ? stem.substring(0, matcher.start()) : stem;
        String normalized = prefix.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? stem.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_") : normalized;
    }

    private static String artifactVersion(String fileName) {
        String stem = fileStem(fileName);
        String version = "unknown";
        Matcher matcher = EXPLICIT_VERSION_TOKEN.matcher(stem);
        while (matcher.find()) {
            version = matcher.group(1).replace('_', '.');
        }
        if (!"unknown".equals(version)) {
            return version;
        }
        matcher = SEMANTIC_VERSION_TOKEN.matcher(stem);
        while (matcher.find()) {
            version = matcher.group(1).replace('_', '.');
        }
        return version;
    }

    private static String displayName(String id) {
        String[] words = id.split("[_\\-]+");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                builder.append(word.substring(1));
            }
        }
        return builder.isEmpty() ? id : builder.toString();
    }

    private static String fileStem(String fileName) {
        String name = fileName == null ? "" : fileName;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jar") || lower.endsWith(".zip")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    record Artifact(String id,
                    String name,
                    String version,
                    String platform,
                    String artifactType,
                    boolean clientResources,
                    boolean serverData) {
    }
}
