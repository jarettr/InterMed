package org.intermed.core.lifecycle;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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

    private static String readNestedMarker(Path nestedJar) throws Exception {
        try (JarFile jar = new JarFile(nestedJar.toFile())) {
            JarEntry entry = jar.getJarEntry("marker.txt");
            return new String(jar.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
