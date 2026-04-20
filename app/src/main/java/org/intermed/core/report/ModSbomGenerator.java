package org.intermed.core.report;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.intermed.core.InterMedVersion;
import org.intermed.core.metadata.ModMetadataParser;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.sandbox.PolyglotSandboxManager;
import org.intermed.core.sandbox.SandboxPlan;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Emits a lightweight CycloneDX-style SBOM for discovered mods.
 */
public final class ModSbomGenerator {

    private ModSbomGenerator() {}

    public static void writeSbom(Path output, List<File> jars) throws Exception {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(
            output,
            new GsonBuilder().setPrettyPrinting().create().toJson(generate(jars)),
            StandardCharsets.UTF_8
        );
    }

    public static JsonObject generate(List<File> jars) {
        JsonObject root = new JsonObject();
        root.addProperty("bomFormat", "CycloneDX");
        root.addProperty("specVersion", "1.5");
        root.addProperty("serialNumber", "urn:uuid:intermed-" + Instant.now().toEpochMilli());
        root.addProperty("version", 1);

        JsonObject metadata = new JsonObject();
        metadata.addProperty("timestamp", Instant.now().toString());
        JsonObject component = new JsonObject();
        component.addProperty("type", "application");
        component.addProperty("name", "InterMed");
        component.addProperty("version", InterMedVersion.BUILD_VERSION);
        metadata.add("component", component);
        root.add("metadata", metadata);

        JsonArray components = new JsonArray();
        for (File jar : jars) {
            components.add(componentFor(jar));
        }
        root.add("components", components);
        return root;
    }

    private static JsonObject componentFor(File jar) {
        JsonObject component = new JsonObject();
        component.addProperty("type", "library");
        component.addProperty("name", jar.getName());
        component.addProperty("version", "unknown");
        component.addProperty("purl", "pkg:generic/intermed/" + jar.getName());

        JsonArray hashes = new JsonArray();
        JsonObject hash = new JsonObject();
        hash.addProperty("alg", "SHA-256");
        hash.addProperty("content", sha256(jar.toPath()));
        hashes.add(hash);
        component.add("hashes", hashes);

        JsonArray properties = new JsonArray();
        try {
            Optional<NormalizedModMetadata> parsed = ModMetadataParser.parse(jar);
            if (parsed.isPresent()) {
                NormalizedModMetadata mod = parsed.get();
                SandboxPlan plan = PolyglotSandboxManager.planFor(mod);
                component.addProperty("name", mod.id());
                component.addProperty("version", mod.version());
                component.addProperty(
                    "purl",
                    "pkg:generic/intermed/" + mod.platform().name().toLowerCase() + "/" + mod.id() + "@" + mod.version()
                );
                addProperty(properties, "intermed:platform", mod.platform().name());
                addProperty(properties, "intermed:clientResources", Boolean.toString(mod.hasClientResources()));
                addProperty(properties, "intermed:serverData", Boolean.toString(mod.hasServerData()));
                addProperty(properties, "intermed:sandbox.requested", plan.requestedMode().externalName());
                addProperty(properties, "intermed:sandbox.effective", plan.effectiveMode().externalName());
                addProperty(properties, "intermed:sandbox.reason", plan.reason());
            } else {
                Optional<DataDrivenArchiveDetector.Artifact> dataDriven = DataDrivenArchiveDetector.detect(jar);
                if (dataDriven.isEmpty()) {
                    addProperty(properties, "intermed:status", "unsupported");
                    component.add("properties", properties);
                    return component;
                }
                DataDrivenArchiveDetector.Artifact artifact = dataDriven.get();
                component.addProperty("name", artifact.id());
                component.addProperty("version", artifact.version());
                component.addProperty(
                    "purl",
                    "pkg:generic/intermed/" + artifact.artifactType() + "/" + artifact.id() + "@" + artifact.version()
                );
                addProperty(properties, "intermed:status", "data-driven");
                addProperty(properties, "intermed:platform", artifact.platform());
                addProperty(properties, "intermed:artifactType", artifact.artifactType());
                addProperty(properties, "intermed:clientResources", Boolean.toString(artifact.clientResources()));
                addProperty(properties, "intermed:serverData", Boolean.toString(artifact.serverData()));
                addProperty(properties, "intermed:sandbox.requested", "native");
                addProperty(properties, "intermed:sandbox.effective", "native");
                addProperty(properties, "intermed:sandbox.reason", artifact.artifactType());
            }
        } catch (Exception e) {
            addProperty(properties, "intermed:status", "failed");
            addProperty(properties, "intermed:error", e.getMessage());
        }
        component.add("properties", properties);
        return component;
    }

    private static void addProperty(JsonArray properties, String name, String value) {
        JsonObject property = new JsonObject();
        property.addProperty("name", name);
        property.addProperty("value", value == null ? "" : value);
        properties.add(property);
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
