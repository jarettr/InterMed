package org.intermed.core.classloading;

import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryDiscoveryTest {

    @Test
    void detectsSplitPackageConflictsAsGraphEdges() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-library-discovery");
        Path left = createLibraryJar(
            tempDir.resolve("alpha-lib-1.0.0.jar"),
            Map.of("shared.alpha.LeftType", "package shared.alpha; public class LeftType { }")
        );
        Path right = createLibraryJar(
            tempDir.resolve("beta-lib-2.0.0.jar"),
            Map.of("shared.alpha.RightType", "package shared.alpha; public class RightType { }")
        );

        LibraryConflictGraph graph = LibraryDiscovery.discover(
            java.util.List.of(left.toFile(), right.toFile())
        );

        assertTrue(graph.edgeCount() >= 1, "Split-package jars must produce a conflict edge");
        LibraryNode leftNode = graph.nodes().stream().filter(node -> node.jar.equals(left.toFile())).findFirst().orElseThrow();
        LibraryNode rightNode = graph.nodes().stream().filter(node -> node.jar.equals(right.toFile())).findFirst().orElseThrow();
        assertTrue(graph.neighbors(leftNode).contains(rightNode));
        assertTrue(graph.neighbors(rightNode).contains(leftNode));
    }

    private static Path createLibraryJar(Path jarPath, Map<String, String> sources) throws Exception {
        Path classesDir = Files.createTempDirectory("intermed-library-classes");
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("JDK compiler is required for test");
        }

        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path sourceFile = classesDir.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(sourceFile.getParent());
            Files.writeString(sourceFile, entry.getValue());
            int result = compiler.run(null, null, null, "-d", classesDir.toString(), sourceFile.toString());
            if (result != 0) {
                throw new IllegalStateException("Compilation failed for " + entry.getKey());
            }
        }

        Files.createDirectories(jarPath.getParent());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath));
             var paths = Files.walk(classesDir)) {
            paths
                .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"))
                .forEach(path -> writeEntry(jar, classesDir, path));
        }
        return jarPath;
    }

    private static void writeEntry(JarOutputStream jar, Path root, Path file) {
        String entryName = root.relativize(file).toString().replace('\\', '/');
        try {
            jar.putNextEntry(new JarEntry(entryName));
            jar.write(Files.readAllBytes(file));
            jar.closeEntry();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write test jar entry " + entryName, e);
        }
    }
}
