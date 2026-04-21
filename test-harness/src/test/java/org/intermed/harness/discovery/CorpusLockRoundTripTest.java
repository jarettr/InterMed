package org.intermed.harness.discovery;

import org.intermed.harness.HarnessConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorpusLockRoundTripTest {

    @Test
    void buildsAndRoundTripsCorpusLockWithJarFeatures() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-corpus-lock");
        Path jar = tempDir.resolve("sample-mod.jar");
        createSampleJar(jar);

        ModCandidate candidate = new ModCandidate(
            "proj-1",
            "sample-mod",
            "Sample Mod",
            12345L,
            List.of("fabric"),
            "ver-1",
            "1.2.3",
            "https://example.invalid/sample-mod.jar",
            "sample-mod.jar",
            true,
            List.of("fabric", "library"),
            "modrinth",
            "https://modrinth.com/mod/sample-mod",
            "optional",
            "required"
        );

        HarnessConfig config = HarnessConfig.builder()
            .outputDir(tempDir)
            .topN(25)
            .build();

        CorpusLock lock = CorpusLockBuilder.build(config, List.of(candidate), ignored -> jar);
        Path output = tempDir.resolve("corpus-lock.json");
        new CorpusLockIO().write(lock, output);

        CorpusLock restored = new CorpusLockIO().read(output);

        assertEquals(CorpusLock.SCHEMA, restored.schema());
        assertEquals(1, restored.summary().totalCandidates());
        assertEquals(1, restored.summary().runnableCandidates());
        assertEquals(1, restored.summary().withMixins());
        assertEquals(1, restored.summary().withNativeLibraries());
        assertEquals(1, restored.summary().withDataPacks());
        assertEquals(1, restored.summary().withResourcePacks());
        assertEquals(1, restored.summary().withFrameworkDependencies());

        CorpusLock.Entry entry = restored.entries().get(0);
        assertEquals("sample-mod", entry.slug());
        assertEquals("head", entry.popularityTier());
        assertTrue(entry.serverSideCompatible());
        assertTrue(entry.hasNativeLibraries());
        assertTrue(entry.hasDataPack());
        assertTrue(entry.hasResourcePack());
        assertTrue(entry.hasPackMetadata());
        assertTrue(entry.frameworkDependencies().contains("fabric-api"));
        assertTrue(entry.mixinConfigs().contains("sample.mixins.json"));
        assertFalse(entry.sha256().isBlank());
        assertEquals("sample-mod", restored.runnableMods().get(0).slug());
    }

    private static void createSampleJar(Path jarPath) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("MixinConfigs", "sample.mixins.json");

        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath), manifest)) {
            writeEntry(jar, "fabric.mod.json", """
                {
                  "schemaVersion": 1,
                  "id": "sample-mod",
                  "version": "1.2.3",
                  "name": "Sample Mod",
                  "depends": {
                    "fabric-api": "*"
                  },
                  "mixins": [
                    "sample.mixins.json"
                  ]
                }
                """);
            writeEntry(jar, "sample.mixins.json", "{\"required\":true}");
            writeEntry(jar, "assets/sample/lang/en_us.json", "{\"key\":\"value\"}");
            writeEntry(jar, "data/sample/tags/items/test.json", "{\"values\":[]}");
            writeEntry(jar, "pack.mcmeta", "{\"pack\":{\"pack_format\":15,\"description\":\"test\"}}");
            writeEntry(jar, "META-INF/natives/libsample.so", "native");
        }
    }

    private static void writeEntry(JarOutputStream jar, String name, String content) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }
}
