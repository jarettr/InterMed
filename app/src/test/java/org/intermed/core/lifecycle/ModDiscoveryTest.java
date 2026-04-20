package org.intermed.core.lifecycle;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModDiscoveryTest {

    @Test
    void refreshesNestedJarCacheWhenParentJarContentChanges() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-discovery-cache");
        Path parentJar = modsDir.resolve("parent-mod.jar");

        writeParentJar(parentJar, "v1");
        ModDiscovery.DiscoveryLayout firstLayout = ModDiscovery.discoverLayout(modsDir.toFile());
        java.io.File firstNested = findNestedJar(firstLayout);

        writeParentJar(parentJar, "v2");
        ModDiscovery.DiscoveryLayout secondLayout = ModDiscovery.discoverLayout(modsDir.toFile());
        java.io.File secondNested = findNestedJar(secondLayout);

        assertNotNull(firstNested);
        assertNotNull(secondNested);
        assertEquals("v1", readNestedMarker(firstNested.toPath()));
        assertEquals("v2", readNestedMarker(secondNested.toPath()));
        assertNotEquals(firstNested.getName(), secondNested.getName(),
            "Cache fingerprint must change when nested jar content changes");
    }

    @Test
    void candidateArchivesIncludeZipDataPacksWithoutAddingThemToRuntimeJars() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-discovery-archives");
        writeParentJar(modsDir.resolve("parent-mod.jar"), "v1");
        writeDataPackZip(modsDir.resolve("Terralith_1.20_v2.5.4.zip"));

        List<java.io.File> runtimeJars = ModDiscovery.discoverJars(modsDir.toFile());
        List<java.io.File> candidateArchives = ModDiscovery.discoverCandidateArchives(modsDir.toFile());

        assertTrue(runtimeJars.stream().noneMatch(file -> file.getName().endsWith(".zip")));
        assertTrue(candidateArchives.stream().anyMatch(file -> file.getName().equals("Terralith_1.20_v2.5.4.zip")));
    }

    private static java.io.File findNestedJar(ModDiscovery.DiscoveryLayout layout) {
        return layout.jars().stream()
            .filter(jar -> layout.ownerOf(jar) != null)
            .findFirst()
            .orElseThrow();
    }

    private static void writeParentJar(Path parentJar, String marker) throws Exception {
        byte[] nestedJar = createNestedJar(marker);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(parentJar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                {
                  "id": "cache_probe",
                  "version": "1.0.0",
                  "entrypoints": { "main": [] }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new JarEntry("META-INF/jars/private-lib.jar"));
            output.write(nestedJar);
            output.closeEntry();
        }
    }

    private static byte[] createNestedJar(String marker) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(buffer)) {
            output.putNextEntry(new JarEntry("marker.txt"));
            output.write(marker.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return buffer.toByteArray();
    }

    private static void writeDataPackZip(Path zipPath) throws Exception {
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
    }

    private static String readNestedMarker(Path nestedJar) throws Exception {
        try (JarFile jar = new JarFile(nestedJar.toFile())) {
            JarEntry entry = jar.getJarEntry("marker.txt");
            return new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
