package org.intermed.core.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModSbomGeneratorTest {

    @Test
    void includesDataDrivenZipArchivesAsFirstClassArtifacts() throws Exception {
        Path root = Files.createTempDirectory("intermed-sbom-data-driven");
        Path dataPack = createDataPackZip(root.resolve("Terralith_1.20_v2.5.4.zip"));

        JsonObject sbom = ModSbomGenerator.generate(List.of(dataPack.toFile()));
        JsonObject component = sbom.getAsJsonArray("components").get(0).getAsJsonObject();

        assertEquals("terralith", component.get("name").getAsString());
        assertEquals("2.5.4", component.get("version").getAsString());
        assertTrue(hasProperty(component, "intermed:status", "data-driven"));
        assertTrue(hasProperty(component, "intermed:platform", "DATA_PACK"));
        assertTrue(hasProperty(component, "intermed:artifactType", "data-pack"));
    }

    private static boolean hasProperty(JsonObject component, String name, String value) {
        JsonArray properties = component.getAsJsonArray("properties");
        for (var element : properties) {
            JsonObject property = element.getAsJsonObject();
            if (name.equals(property.get("name").getAsString())
                    && value.equals(property.get("value").getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static Path createDataPackZip(Path zipPath) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(zipPath))) {
            output.putNextEntry(new JarEntry("pack.mcmeta"));
            output.write("""
                {
                  "pack": {
                    "pack_format": 15,
                    "description": "Terralith"
                  }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new JarEntry("data/terralith/worldgen/biome/test.json"));
            output.write("{}".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return zipPath;
    }
}
