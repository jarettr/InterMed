package org.intermed.core.metadata;

import org.intermed.core.resolver.VirtualDependencyMap;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ModMetadataParserTest {

    @Test
    void parsesFabricManifestIntoNormalizedMetadata() throws Exception {
        Path jar = Files.createTempFile("intermed-fabric-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                {
                  "id": "fabric_smoke",
                  "version": "1.2.3",
                  "depends": { "fabric-api": ">=0.90.0" },
                  "entrypoints": { "main": ["demo.fabric.EntryPoint"] }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        Optional<NormalizedModMetadata> parsed = ModMetadataParser.parse(jar.toFile());
        assertTrue(parsed.isPresent());
        assertEquals(ModPlatform.FABRIC, parsed.get().platform());
        assertEquals("fabric_smoke", parsed.get().id());
        assertEquals("1.2.3", parsed.get().version());
        assertEquals(">=0.90.0", parsed.get().dependencyConstraints().get("fabric-api"));
        assertEquals("demo.fabric.EntryPoint", parsed.get().entrypoints("main").get(0));
    }

    @Test
    void parsesDependencyObjectsWithReexportFlags() throws Exception {
        Path jar = Files.createTempFile("intermed-fabric-export-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                {
                  "id": "fabric_export",
                  "version": "1.2.3",
                  "depends": {
                    "shared-api": {
                      "version": ">=1.0.0",
                      "reexport": true
                    }
                  },
                  "intermed:classloader": {
                    "reexportPrivateLibraries": true
                  },
                  "entrypoints": { "main": ["demo.fabric.EntryPoint"] }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        Optional<NormalizedModMetadata> parsed = ModMetadataParser.parse(jar.toFile());
        assertTrue(parsed.isPresent());
        assertEquals(">=1.0.0", parsed.get().dependencyConstraints().get("shared-api"));
        assertTrue(parsed.get().reexportsDependency("shared-api"));
        assertTrue(parsed.get().reexportsPrivateLibraries());
    }

    @Test
    void parsesDeclaredPeerLinksFromClassloaderSection() throws Exception {
        Path jar = Files.createTempFile("intermed-fabric-peers-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                {
                  "id": "fabric_peers",
                  "version": "1.2.3",
                  "intermed:classloader": {
                    "peers": ["peer_api", "bridge_peer"]
                  },
                  "entrypoints": { "main": ["demo.fabric.EntryPoint"] }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        NormalizedModMetadata parsed = ModMetadataParser.parse(jar.toFile()).orElseThrow();
        assertEquals(java.util.List.of("peer_api", "bridge_peer"), parsed.allowedPeerIds());
    }

    @Test
    void parsesSoftDependenciesAndWeakApiPrefixesFromClassloaderSection() throws Exception {
        Path jar = Files.createTempFile("intermed-fabric-softdeps-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                {
                  "id": "fabric_softdeps",
                  "version": "1.2.3",
                  "suggests": { "suggested_mod": "*" },
                  "intermed:classloader": {
                    "softDepends": ["compat_mod", "api_mod"],
                    "apiPrefixes": ["sample.provider.api", "sample.provider.spi"]
                  },
                  "entrypoints": { "main": ["demo.fabric.EntryPoint"] }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        NormalizedModMetadata parsed = ModMetadataParser.parse(jar.toFile()).orElseThrow();
        assertEquals(java.util.List.of("compat_mod", "api_mod", "suggested_mod"), parsed.softDependencyIds());
        assertEquals(java.util.List.of("sample.provider.api.", "sample.provider.spi."), parsed.weakApiPrefixes());
    }

    @Test
    void parsesForgeManifestAndScansAnnotatedEntrypoint() throws Exception {
        Path jar = Files.createTempFile("intermed-forge-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/mods.toml"));
            output.write("""
                modLoader="javafml"
                loaderVersion="[47,)"

                [[mods]]
                modId="forge_smoke"
                version="2.0.0"
                displayName="Forge Smoke"

                [[mods.dependencies]]
                modId="forge-api"
                mandatory=true
                versionRange="[47,)"
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new JarEntry("demo/forge/ForgeSmoke.class"));
            output.write(createForgeAnnotatedClass("demo/forge/ForgeSmoke", "forge_smoke"));
            output.closeEntry();
        }

        Optional<NormalizedModMetadata> parsed = ModMetadataParser.parse(jar.toFile());
        assertTrue(parsed.isPresent());
        assertEquals(ModPlatform.FORGE, parsed.get().platform());
        assertEquals("forge_smoke", parsed.get().id());
        assertEquals("2.0.0", parsed.get().version());
        assertEquals("[47,)", parsed.get().dependencyConstraints().get("forge-api"));
        assertEquals("demo.forge.ForgeSmoke", parsed.get().entrypoints("main").get(0));
        assertEquals("forge", parsed.get().manifest().get("intermed:platform").getAsString());
    }

    @Test
    void parsesNeoForgeManifestAndScansAnnotatedEntrypoint() throws Exception {
        Path jar = Files.createTempFile("intermed-neoforge-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/neoforge.mods.toml"));
            output.write("""
                modLoader="javafml"
                loaderVersion="[21,)"

                [[mods]]
                modId="neoforge_smoke"
                version="3.1.4"
                displayName="NeoForge Smoke"

                [[mods.dependencies]]
                modId="neoforge"
                mandatory=true
                versionRange="[21,)"
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new JarEntry("demo/neoforge/NeoForgeSmoke.class"));
            output.write(createAnnotatedClass(
                "demo/neoforge/NeoForgeSmoke",
                "neoforge_smoke",
                "Lnet/neoforged/fml/common/Mod;"
            ));
            output.closeEntry();
        }

        Optional<NormalizedModMetadata> parsed = ModMetadataParser.parse(jar.toFile());
        assertTrue(parsed.isPresent());
        assertEquals(ModPlatform.NEOFORGE, parsed.get().platform());
        assertEquals("neoforge_smoke", parsed.get().id());
        assertEquals("3.1.4", parsed.get().version());
        assertEquals("[21,)", parsed.get().dependencyConstraints().get("neoforge"));
        assertEquals("demo.neoforge.NeoForgeSmoke", parsed.get().entrypoints("main").get(0));
        assertEquals("neoforge", parsed.get().manifest().get("intermed:platform").getAsString());
    }

    @Test
    void parsesForgeDependenciesBoundToSpecificOwnerSections() throws Exception {
        Path jar = Files.createTempFile("intermed-forge-owner-deps-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/mods.toml"));
            output.write("""
                modLoader="javafml"

                [[mods]]
                modId="forge_owner"
                version="4.0.0"

                [[dependencies."forge_owner"]]
                modId="forge"
                mandatory=true
                versionRange="[47,)"
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new JarEntry("demo/forge/ForgeOwner.class"));
            output.write(createForgeAnnotatedClass("demo/forge/ForgeOwner", "forge_owner"));
            output.closeEntry();
        }

        NormalizedModMetadata parsed = ModMetadataParser.parse(jar.toFile()).orElseThrow();
        assertEquals("forge_owner", parsed.id());
        assertEquals("[47,)", parsed.dependencyConstraints().get("forge"));
    }

    @Test
    void selectsAnnotatedPrimaryModWhenForgeJarContainsMultipleMods() throws Exception {
        Path jar = Files.createTempFile("intermed-forge-multi-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/mods.toml"));
            output.write("""
                modLoader="javafml"

                [[mods]]
                modId="library_stub"
                version="1.0.0"

                [[mods]]
                modId="real_mod"
                version="2.0.0"

                [[dependencies."real_mod"]]
                modId="forge"
                mandatory=true
                versionRange="[47,)"
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new JarEntry("demo/forge/RealMod.class"));
            output.write(createForgeAnnotatedClass("demo/forge/RealMod", "real_mod"));
            output.closeEntry();
        }

        NormalizedModMetadata parsed = ModMetadataParser.parse(jar.toFile()).orElseThrow();
        assertEquals("real_mod", parsed.id());
        assertEquals("2.0.0", parsed.version());
        assertEquals("[47,)", parsed.dependencyConstraints().get("forge"));
        assertEquals("demo.forge.RealMod", parsed.entrypoints("main").get(0));
    }

    @Test
    void mergesInterMedOverlayForSandboxMetadata() throws Exception {
        Path jar = Files.createTempFile("intermed-overlay-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                {
                  "id": "overlay_mod",
                  "version": "1.0.0",
                  "entrypoints": { "main": ["demo.overlay.EntryPoint"] }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new JarEntry("META-INF/intermed.mod.json"));
            output.write("""
                {
                  "intermed:sandbox": {
                    "mode": "wasm",
                    "modulePath": "META-INF/wasm/demo.wasm",
                    "entrypoint": "init_mod"
                  }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        Optional<NormalizedModMetadata> parsed = ModMetadataParser.parse(jar.toFile());
        assertTrue(parsed.isPresent());
        assertEquals("wasm", parsed.get().requestedSandboxMode());
        assertEquals("META-INF/wasm/demo.wasm", parsed.get().sandboxModulePath());
        assertEquals("init_mod", parsed.get().sandboxEntrypoint());
    }

    @Test
    void mergesInterMedOverlayIntoGeneratedDependencyMetadata() throws Exception {
        Path jar = Files.createTempFile("intermed-overlay-export-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/mods.toml"));
            output.write("""
                modLoader="javafml"
                loaderVersion="[47,)"

                [[mods]]
                modId="forge_overlay"
                version="2.0.0"

                [[mods.dependencies]]
                modId="forge-api"
                mandatory=true
                versionRange="[47,)"
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new JarEntry("demo/forge/ForgeOverlay.class"));
            output.write(createForgeAnnotatedClass("demo/forge/ForgeOverlay", "forge_overlay"));
            output.closeEntry();

            output.putNextEntry(new JarEntry("META-INF/intermed.mod.json"));
            output.write("""
                {
                  "depends": {
                    "forge-api": {
                      "reexport": true
                    }
                  },
                  "intermed:classloader": {
                    "reexportPrivateLibraries": true
                  }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }

        Optional<NormalizedModMetadata> parsed = ModMetadataParser.parse(jar.toFile());
        assertTrue(parsed.isPresent());
        assertEquals("[47,)", parsed.get().dependencyConstraints().get("forge-api"));
        assertTrue(parsed.get().reexportsDependency("forge-api"));
        assertTrue(parsed.get().reexportsPrivateLibraries());
    }

    @Test
    void parsesRealisticFabricCorpusManifest() throws Exception {
        Path jar = Files.createTempFile("intermed-fabric-corpus-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write(resourceBytes("metadata-corpus/fabric/fabric-loader-api.json"));
            output.closeEntry();
        }

        NormalizedModMetadata parsed = ModMetadataParser.parse(jar.toFile()).orElseThrow();
        assertEquals(ModPlatform.FABRIC, parsed.platform());
        assertEquals("fabric_corpus_mod", parsed.id());
        assertEquals("demo.fabric.CorpusEntry", parsed.entrypoints("main").get(0));
        assertEquals(">=0.15.0", parsed.dependencyConstraints().get("fabricloader"));
        assertEquals(">=0.90.7+1.20.1", parsed.dependencyConstraints().get("fabric-api"));
        assertEquals("1.20.1", parsed.dependencyConstraints().get("minecraft"));
        assertEquals(">=21", parsed.dependencyConstraints().get("java"));
        assertVirtualDependencyMappings(parsed, "fabricloader", "fabric-api", "minecraft", "java");
    }

    @Test
    void parsesRealisticForgeCorpusManifest() throws Exception {
        Path jar = Files.createTempFile("intermed-forge-corpus-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/mods.toml"));
            output.write(resourceBytes("metadata-corpus/forge/forge-realistic.toml"));
            output.closeEntry();

            output.putNextEntry(new JarEntry("demo/forge/ForgeCorpus.class"));
            output.write(createForgeAnnotatedClass("demo/forge/ForgeCorpus", "forge_corpus_mod"));
            output.closeEntry();
        }

        NormalizedModMetadata parsed = ModMetadataParser.parse(jar.toFile()).orElseThrow();
        assertEquals(ModPlatform.FORGE, parsed.platform());
        assertEquals("forge_corpus_mod", parsed.id());
        assertEquals("5.4.3", parsed.version());
        assertEquals("demo.forge.ForgeCorpus", parsed.entrypoints("main").get(0));
        assertEquals("[47,)", parsed.dependencyConstraints().get("forge"));
        assertEquals("[1.20.1]", parsed.dependencyConstraints().get("minecraft"));
        assertEquals("[21,)", parsed.dependencyConstraints().get("java"));
        assertVirtualDependencyMappings(parsed, "forge", "minecraft", "java");
    }

    @Test
    void parsesRealisticNeoForgeCorpusManifest() throws Exception {
        Path jar = Files.createTempFile("intermed-neoforge-corpus-", ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry("META-INF/neoforge.mods.toml"));
            output.write(resourceBytes("metadata-corpus/neoforge/neoforge-realistic.toml"));
            output.closeEntry();

            output.putNextEntry(new JarEntry("demo/neoforge/NeoForgeCorpus.class"));
            output.write(createAnnotatedClass(
                "demo/neoforge/NeoForgeCorpus",
                "neoforge_corpus_mod",
                "Lnet/neoforged/fml/common/Mod;"
            ));
            output.closeEntry();
        }

        NormalizedModMetadata parsed = ModMetadataParser.parse(jar.toFile()).orElseThrow();
        assertEquals(ModPlatform.NEOFORGE, parsed.platform());
        assertEquals("neoforge_corpus_mod", parsed.id());
        assertEquals("8.1.0", parsed.version());
        assertEquals("demo.neoforge.NeoForgeCorpus", parsed.entrypoints("main").get(0));
        assertEquals("[21.0,)", parsed.dependencyConstraints().get("neoforge"));
        assertEquals("[1.20.1]", parsed.dependencyConstraints().get("minecraft"));
        assertVirtualDependencyMappings(parsed, "neoforge", "minecraft");
    }

    private static byte[] createForgeAnnotatedClass(String internalName, String modId) {
        return createAnnotatedClass(internalName, modId, "Lnet/minecraftforge/fml/common/Mod;");
    }

    private static byte[] createAnnotatedClass(String internalName, String modId, String annotationDesc) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        AnnotationVisitor annotation = writer.visitAnnotation(annotationDesc, true);
        annotation.visit("value", modId);
        annotation.visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] resourceBytes(String path) throws Exception {
        try (InputStream input = ModMetadataParserTest.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing test resource: " + path);
            }
            return input.readAllBytes();
        }
    }

    private static void assertVirtualDependencyMappings(NormalizedModMetadata metadata, String... dependencyIds) {
        for (String dependencyId : dependencyIds) {
            assertTrue(metadata.dependencyConstraints().containsKey(dependencyId), dependencyId);
            assertTrue(VirtualDependencyMap.isVirtual(dependencyId), dependencyId);
            String bridgeId = VirtualDependencyMap.substitute(dependencyId);
            assertNotEquals(dependencyId, bridgeId);
            assertFalse(VirtualDependencyMap.bridgeCompatibilityVersionForBridge(bridgeId).isBlank(), bridgeId);
        }
    }
}
