package org.intermed.core.lifecycle;

import org.intermed.core.classloading.LazyInterMedClassLoader;
import org.intermed.core.classloading.InterMedClassLoader;
import org.intermed.core.classloading.ParentLinkPolicy;
import org.intermed.core.classloading.ShaderClassLoader;
import org.intermed.core.bridge.BridgeRuntime;
import org.intermed.core.registry.VirtualRegistryService;
import org.intermed.core.metadata.RuntimeModIndex;
import org.intermed.core.sandbox.GraalVMSandbox;
import org.intermed.core.sandbox.PolyglotSandboxManager;
import org.intermed.core.sandbox.SandboxExecutionResult;
import org.intermed.core.sandbox.SandboxMode;
import org.intermed.core.sandbox.SandboxPlan;
import org.intermed.core.sandbox.SandboxedEntrypoint;
import org.intermed.core.config.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LifecycleManagerIntegrationTest {

    @AfterEach
    void tearDown() {
        LifecycleManager.resetForTests();
        clearProperties(
            "intermed.modsDir",
            "intermed.mappings.tiny",
            "intermed.smoke.loaded",
            "intermed.fabric.static.method.loaded",
            "intermed.forge.smoke.loaded",
            "intermed.security.probe.file",
            "intermed.security.blocked",
            "intermed.security.allowed",
            "intermed.registry.alpha.lookup",
            "intermed.registry.alpha.optional",
            "intermed.registry.alpha.rawId",
            "intermed.registry.beta.lookup",
            "intermed.registry.beta.optional",
            "intermed.registry.beta.rawId",
            "intermed.remap.bytecode",
            "intermed.remap.class.indy",
            "intermed.remap.class.builder",
            "intermed.alpha.loaded",
            "intermed.beta.loaded",
            "intermed.consumer.api",
            "intermed.peer.provider.loaded",
            "intermed.peer.consumer.api",
            "intermed.fabric.api.peer",
            "intermed.bridge.version.loaded",
            "intermed.server.loaded",
            "intermed.sandbox.loaded",
            "intermed.partial.good.loaded",
            "sandbox.native.fallback.enabled",
            "runtime.env"
        );
        RuntimeConfig.resetForTests();
    }

    @Test
    @Tag("compat-smoke")
    void assemblesDagAndBootsFabricEntrypoint() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-smoke-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());

        createFabricModJar(
            modsDir.resolve("smoke-mod.jar"),
            "smoke_mod",
            "test.mods.SmokeMod",
            """
            package test.mods;
            public class SmokeMod implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.setProperty("intermed.smoke.loaded", "true");
                }
            }
            """
        );

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        assertEquals("true", System.getProperty("intermed.smoke.loaded"));
        Map<String, LazyInterMedClassLoader> loaders = LifecycleManager.getModClassLoaders();
        assertTrue(loaders.containsKey("smoke_mod"));
        assertNotNull(loaders.get("smoke_mod").loadClass("test.mods.SmokeMod"));
    }

    @Test
    void assemblesDagAndBootsFabricStaticMethodEntrypoint() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-static-entrypoint-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());

        createFabricModJarFromClasses(
            modsDir.resolve("static-entrypoint-mod.jar"),
            "static_entrypoint_mod",
            "test.mods.StaticEntrypoint::init",
            Map.of(
                "test/mods/StaticEntrypoint.class",
                createStaticMethodEntrypointClass(
                    "test/mods/StaticEntrypoint",
                    "intermed.fabric.static.method.loaded"
                )
            )
        );

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        assertEquals("true", System.getProperty("intermed.fabric.static.method.loaded"));
        assertTrue(BridgeRuntime.getEntrypoints("main").stream()
            .anyMatch(entry -> entry.modId().equals("static_entrypoint_mod")
                && entry.definition().equals("test.mods.StaticEntrypoint::init")));
    }

    @Test
    @Tag("compat-smoke")
    void assemblesDagAndBootsForgeEntrypointByConstructingAnnotatedMod() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-smoke-forge-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());

        createForgeModJar(modsDir.resolve("forge-smoke.jar"), "forge_smoke", "test.mods.ForgeSmoke");

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        assertEquals("true", System.getProperty("intermed.forge.smoke.loaded"));
        Map<String, LazyInterMedClassLoader> loaders = LifecycleManager.getModClassLoaders();
        assertTrue(loaders.containsKey("forge_smoke"));
        assertNotNull(loaders.get("forge_smoke").loadClass("test.mods.ForgeSmoke"));
    }

    @Test
    void assemblesResolvableSubsetWhenOneModHasHardMissingDependency() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-partial-resolve-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());

        createFabricModJar(
            modsDir.resolve("healthy-mod.jar"),
            "healthy_mod",
            "test.mods.HealthyMod",
            """
            package test.mods;
            public class HealthyMod implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.setProperty("intermed.partial.good.loaded", "true");
                }
            }
            """
        );

        createFabricModJar(
            modsDir.resolve("broken-mod.jar"),
            "broken_mod",
            "test.mods.BrokenMod",
            """
            package test.mods;
            public class BrokenMod implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.setProperty("intermed.partial.good.loaded", "broken-should-not-load");
                }
            }
            """,
            """
            "depends": {
              "missing_api": "*"
            }
            """
        );

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        assertEquals("true", System.getProperty("intermed.partial.good.loaded"));
        assertTrue(LifecycleManager.getModClassLoaders().containsKey("healthy_mod"));
        assertFalse(LifecycleManager.getModClassLoaders().containsKey("broken_mod"));
        assertTrue(RuntimeModIndex.isLoaded("healthy_mod"));
        assertFalse(RuntimeModIndex.isLoaded("broken_mod"));
        assertEquals(
            "Missing dependency 'missing_api' required by broken_mod@1.0.0",
            RuntimeModIndex.loadFailure("broken_mod").orElseThrow()
        );
        assertTrue(RuntimeModIndex.visibleModsForUi().stream()
            .map(metadata -> metadata.id())
            .anyMatch("broken_mod"::equals));
    }

    @Test
    void resolvesFabricBridgeVersionFromDiscoveredFabricApiModule() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-fabric-bridge-version-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());

        createFabricModJarWithVersion(
            modsDir.resolve("fabric-api.jar"),
            "fabric-api",
            "0.92.3+1.20.1",
            "test.mods.FabricApiRoot",
            """
            package test.mods;
            public class FabricApiRoot implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                }
            }
            """
        );

        createFabricModJar(
            modsDir.resolve("fabric-consumer.jar"),
            "fabric_bridge_consumer",
            "test.mods.FabricBridgeConsumer",
            """
            package test.mods;
            public class FabricBridgeConsumer implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.setProperty("intermed.bridge.version.loaded", "true");
                }
            }
            """,
            """
            "depends": {
              "fabric-api": ">=0.92.2"
            }
            """
        );

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        assertEquals("true", System.getProperty("intermed.bridge.version.loaded"));
        assertTrue(RuntimeModIndex.isLoaded("fabric_bridge_consumer"));
        assertTrue(LifecycleManager.getModClassLoaders().containsKey("fabric_bridge_consumer"));
    }

    @Test
    void dagClassBytesForMixinAnalysisUseRuntimeMappings() throws Exception {
        Path jarPath = Files.createTempFile("intermed-mixin-analysis-remap-", ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new JarEntry("demo/mixin/CriteriaAccessorMixin.class"));
            jos.write(createClassLiteralMixin("demo/mixin/CriteriaAccessorMixin", "net/minecraft/class_42"));
            jos.closeEntry();
        }

        LifecycleManager.DICTIONARY.clear();
        LifecycleManager.DICTIONARY.addClass("net/minecraft/class_42", "net/minecraft/advancements/CriteriaTriggers");
        org.intermed.core.remapping.InterMedRemapper.installDictionary(LifecycleManager.DICTIONARY);

        try {
            InterMedClassLoader loader = new InterMedClassLoader(
                "mixin_analysis_remap",
                jarPath.toFile(),
                Map.of(),
                LifecycleManagerIntegrationTest.class.getClassLoader()
            );
            LifecycleManager.registerLoaderForTests("mixin_analysis_remap", loader, null);

            byte[] bytes = LifecycleManager.getClassBytesFromDAG("demo.mixin.CriteriaAccessorMixin");
            assertNotNull(bytes);

            ClassNode node = new ClassNode();
            new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            AnnotationNode mixin = node.invisibleAnnotations.stream()
                .filter(annotation -> "Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc))
                .findFirst()
                .orElseThrow();

            @SuppressWarnings("unchecked")
            List<Type> targets = (List<Type>) mixin.values.get(mixin.values.indexOf("value") + 1);
            assertEquals("net/minecraft/advancements/CriteriaTriggers", targets.get(0).getInternalName());
        } finally {
            LifecycleManager.DICTIONARY.clear();
            org.intermed.core.remapping.InterMedRemapper.installDictionary(LifecycleManager.DICTIONARY);
        }
    }

    @Test
    void bootsFabricModInIsolationAndDeniesRestrictedFileReadWithoutCapability() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-security-mods");
        Path probeFile = Files.createTempFile("intermed-security-probe", ".txt");
        Files.writeString(probeFile, "secret", StandardCharsets.UTF_8);

        System.setProperty("intermed.modsDir", modsDir.toString());
        System.setProperty("intermed.security.probe.file", probeFile.toString());

        createFabricModJar(
            modsDir.resolve("restricted-mod.jar"),
            "restricted_mod",
            "test.mods.RestrictedMod",
            """
            package test.mods;
            public class RestrictedMod implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    try {
                        new java.io.FileInputStream(System.getProperty("intermed.security.probe.file")).close();
                        System.setProperty("intermed.security.blocked", "unexpected-allow");
                    } catch (SecurityException denied) {
                        System.setProperty("intermed.security.blocked", "denied");
                    } catch (Exception other) {
                        System.setProperty("intermed.security.blocked", "error:" + other.getClass().getSimpleName());
                    }
                }
            }
            """
        );

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        assertEquals("denied", System.getProperty("intermed.security.blocked"));
    }

    @Test
    void bootsFabricModInIsolationAndAllowsGrantedFileReadCapability() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-security-allowed-mods");
        Path probeFile = Files.createTempFile("intermed-security-allowed", ".txt");
        Files.writeString(probeFile, "allowed", StandardCharsets.UTF_8);

        System.setProperty("intermed.modsDir", modsDir.toString());
        System.setProperty("intermed.security.probe.file", probeFile.toString());

        createFabricModJar(
            modsDir.resolve("allowed-mod.jar"),
            "allowed_mod",
            "test.mods.AllowedMod",
            """
            package test.mods;
            public class AllowedMod implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    try (java.io.FileInputStream in =
                             new java.io.FileInputStream(System.getProperty("intermed.security.probe.file"))) {
                        if (in.read() >= 0) {
                            System.setProperty("intermed.security.allowed", "allowed");
                        }
                    } catch (SecurityException denied) {
                        System.setProperty("intermed.security.allowed", "denied");
                    } catch (Exception other) {
                        System.setProperty("intermed.security.allowed", "error:" + other.getClass().getSimpleName());
                    }
                }
            }
            """,
            """
              "intermed:security": {
                "capabilities": ["FILE_READ"],
                "fileReadPaths": ["%s"]
              }
            """.formatted(probeFile.toAbsolutePath().normalize().toString().replace("\\", "\\\\"))
        );

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        assertEquals("allowed", System.getProperty("intermed.security.allowed"));
    }

    @Test
    void bootsMultipleFabricModsWithConflictingRegistryKeysThroughVirtualRegistrySharding() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-registry-sharding-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());

        createFabricModJar(
            modsDir.resolve("alpha-registry.jar"),
            "alpha_registry_mod",
            "test.mods.AlphaRegistryMod",
            """
            package test.mods;
            public class AlphaRegistryMod implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    net.minecraft.class_2378 registry = net.minecraft.class_2378.ITEM;
                    String value = "alpha-value";
                    net.minecraft.class_2378.method_10226(registry, "shared:gear", value);
                    System.setProperty("intermed.registry.alpha.lookup", String.valueOf(registry.method_17966("shared:gear")));
                    System.setProperty("intermed.registry.alpha.optional",
                        registry.method_36376("shared:gear").map(String::valueOf).orElse("empty"));
                    System.setProperty("intermed.registry.alpha.rawId", Integer.toString(registry.method_10176(value)));
                }
            }
            """
        );

        createFabricModJar(
            modsDir.resolve("beta-registry.jar"),
            "beta_registry_mod",
            "test.mods.BetaRegistryMod",
            """
            package test.mods;
            public class BetaRegistryMod implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    net.minecraft.class_2378 registry = net.minecraft.class_2378.ITEM;
                    String value = "beta-value";
                    net.minecraft.class_2378.method_10226(registry, "shared:gear", value);
                    System.setProperty("intermed.registry.beta.lookup", String.valueOf(registry.method_17966("shared:gear")));
                    System.setProperty("intermed.registry.beta.optional",
                        registry.method_36376("shared:gear").map(String::valueOf).orElse("empty"));
                    System.setProperty("intermed.registry.beta.rawId", Integer.toString(registry.method_10176(value)));
                }
            }
            """
        );

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        assertEquals("alpha-value", System.getProperty("intermed.registry.alpha.lookup"));
        assertEquals("alpha-value", System.getProperty("intermed.registry.alpha.optional"));
        assertEquals("beta-value", System.getProperty("intermed.registry.beta.lookup"));
        assertEquals("beta-value", System.getProperty("intermed.registry.beta.optional"));

        int alphaRawId = Integer.parseInt(System.getProperty("intermed.registry.alpha.rawId"));
        int betaRawId = Integer.parseInt(System.getProperty("intermed.registry.beta.rawId"));

        assertNotEquals(alphaRawId, betaRawId);
        assertEquals("alpha-value", VirtualRegistryService.lookupValue("alpha_registry_mod", "shared:gear"));
        assertEquals("beta-value", VirtualRegistryService.lookupValue("beta_registry_mod", "shared:gear"));
        assertEquals("alpha-value", VirtualRegistryService.lookupValueByGlobalId(alphaRawId));
        assertEquals("beta-value", VirtualRegistryService.lookupValueByGlobalId(betaRawId));
        assertEquals("shared:gear", VirtualRegistryService.resolveOriginalId("alpha_registry_mod", alphaRawId));
        assertEquals("shared:gear", VirtualRegistryService.resolveOriginalId("beta_registry_mod", betaRawId));
    }

    @Test
    void isolatesPrivateNestedLibrariesPerOwningMod() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-private-lib-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());

        createFabricModJarWithNestedLibrary(
            modsDir.resolve("alpha-mod.jar"),
            "alpha_mod",
            "test.mods.AlphaMod",
            """
            package test.mods;
            public class AlphaMod implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.setProperty("intermed.alpha.loaded", "true");
                }
            }
            """,
            "private-lib.jar",
            "test.libs.SharedPrivateLib",
            """
            package test.libs;
            public class SharedPrivateLib {
                public static final String VALUE = "alpha";
            }
            """,
            "",
            ""
        );

        createFabricModJarWithNestedLibrary(
            modsDir.resolve("beta-mod.jar"),
            "beta_mod",
            "test.mods.BetaMod",
            """
            package test.mods;
            public class BetaMod implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.setProperty("intermed.beta.loaded", "true");
                }
            }
            """,
            "private-lib.jar",
            "test.libs.SharedPrivateLib",
            """
            package test.libs;
            public class SharedPrivateLib {
                public static final String VALUE = "beta";
            }
            """,
            "",
            ""
        );

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        Map<String, LazyInterMedClassLoader> loaders = LifecycleManager.getModClassLoaders();
        LazyInterMedClassLoader alphaLoader = loaders.get("alpha_mod");
        LazyInterMedClassLoader betaLoader = loaders.get("beta_mod");

        assertNotNull(alphaLoader);
        assertNotNull(betaLoader);
        assertEquals("true", System.getProperty("intermed.alpha.loaded"));
        assertEquals("true", System.getProperty("intermed.beta.loaded"));

        Class<?> alphaPrivateLib = alphaLoader.loadClass("test.libs.SharedPrivateLib");
        Class<?> betaPrivateLib = betaLoader.loadClass("test.libs.SharedPrivateLib");

        assertEquals("alpha", alphaPrivateLib.getField("VALUE").get(null));
        assertEquals("beta", betaPrivateLib.getField("VALUE").get(null));
        assertNotSame(alphaPrivateLib, betaPrivateLib);
        assertNotSame(alphaPrivateLib.getClassLoader(), betaPrivateLib.getClassLoader());
        assertEquals(1, alphaLoader.getParents().size(), "Owner mod should only see its scoped shader");
        assertEquals(1, betaLoader.getParents().size(), "Owner mod should only see its scoped shader");
        assertNotEquals(
            alphaLoader.getParents().iterator().next().getNodeId(),
            betaLoader.getParents().iterator().next().getNodeId(),
            "Private nested libraries from different mods must not collapse into one shader"
        );
    }

    @Test
    void allowsModsToReExportPrivateNestedLibrariesToDependants() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-reexport-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());

        Path exportedLibrary = createFabricModJarWithNestedLibrary(
            modsDir.resolve("api-provider.jar"),
            "api_provider",
            "test.mods.ApiProviderMod",
            """
            package test.mods;
            public class ApiProviderMod implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.setProperty("intermed.alpha.loaded", "true");
                }
            }
            """,
            "api-lib.jar",
            "test.libs.SharedPrivateLib",
            """
            package test.libs;
            public class SharedPrivateLib {
                public static final String VALUE = "exported-api";
            }
            """,
            "",
            """
            {
              "intermed:classloader": {
                "reexportPrivateLibraries": true
              }
            }
            """
        );

        createFabricModJar(
            modsDir.resolve("api-consumer.jar"),
            "api_consumer",
            "test.mods.ApiConsumerMod",
            """
            package test.mods;
            public class ApiConsumerMod implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.setProperty("intermed.consumer.api", test.libs.SharedPrivateLib.VALUE);
                }
            }
            """,
            """
              "depends": {
                "api_provider": "*"
              }
            """,
            exportedLibrary.toString()
        );

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        Map<String, LazyInterMedClassLoader> loaders = LifecycleManager.getModClassLoaders();
        LazyInterMedClassLoader providerLoader = loaders.get("api_provider");
        LazyInterMedClassLoader consumerLoader = loaders.get("api_consumer");

        assertNotNull(providerLoader);
        assertNotNull(consumerLoader);
        assertEquals("exported-api", System.getProperty("intermed.consumer.api"));
        assertEquals("exported-api",
            consumerLoader.loadClass("test.libs.SharedPrivateLib").getField("VALUE").get(null));

        LazyInterMedClassLoader exportedShader = providerLoader.getParents().stream()
            .filter(parent -> parent instanceof ShaderClassLoader)
            .findFirst()
            .orElseThrow();
        assertEquals(ParentLinkPolicy.REEXPORT, providerLoader.getParentLinkPolicy(exportedShader));
    }

    @Test
    void wiresDeclaredPeerLinksBeforeEntrypointInitialization() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-peer-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());

        createFabricModJar(
            modsDir.resolve("peer-provider.jar"),
            "peer_provider",
            "net.fabricmc.fabric.api.peer.SharedPeerApi",
            """
            package net.fabricmc.fabric.api.peer;
            public class SharedPeerApi implements net.fabricmc.api.ModInitializer {
                public static final String VALUE = "peer-ok";
                @Override
                public void onInitialize() {
                    System.setProperty("intermed.peer.provider.loaded", VALUE);
                }
            }
            """
        );

        createFabricModJar(
            modsDir.resolve("peer-consumer.jar"),
            "peer_consumer",
            "test.mods.PeerConsumer",
            """
            package test.mods;
            public class PeerConsumer implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.setProperty("intermed.peer.consumer.api",
                        net.fabricmc.fabric.api.peer.SharedPeerApi.VALUE);
                }
            }
            """,
            """
              "intermed:classloader": {
                "peers": ["peer_provider"]
              }
            """,
            modsDir.resolve("peer-provider.jar").toString()
        );

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        Map<String, LazyInterMedClassLoader> loaders = LifecycleManager.getModClassLoaders();
        LazyInterMedClassLoader provider = loaders.get("peer_provider");
        LazyInterMedClassLoader consumer = loaders.get("peer_consumer");

        assertNotNull(provider);
        assertNotNull(consumer);
        assertEquals("peer-ok", System.getProperty("intermed.peer.provider.loaded"));
        assertEquals("peer-ok", System.getProperty("intermed.peer.consumer.api"));
        assertTrue(consumer.getPeers().contains(provider));
    }

    @Test
    void fabricApiSubmodulesCanSeeSiblingFabricApiSubmodulesBeforePlatformFallback() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-fabric-api-sibling-peer-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());

        createFabricModJar(
            modsDir.resolve("fabric-command-api-v2.jar"),
            "fabric-command-api-v2",
            "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback",
            """
            package net.fabricmc.fabric.api.command.v2;
            public class CommandRegistrationCallback implements net.fabricmc.api.ModInitializer {
                public static final String EVENT = "sibling-api-visible";
                @Override
                public void onInitialize() {
                }
            }
            """,
            """
              "depends": {
                "fabricloader": "*"
              }
            """
        );

        createFabricModJar(
            modsDir.resolve("fabric-command-api-v1.jar"),
            "fabric-command-api-v1",
            "net.fabricmc.fabric.impl.command.v1.LegacyHandler",
            """
            package net.fabricmc.fabric.impl.command.v1;
            public class LegacyHandler implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.setProperty("intermed.fabric.api.peer",
                        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT);
                }
            }
            """,
            """
              "depends": {
                "fabricloader": "*"
              }
            """,
            modsDir.resolve("fabric-command-api-v2.jar").toString()
        );

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        Map<String, LazyInterMedClassLoader> loaders = LifecycleManager.getModClassLoaders();
        LazyInterMedClassLoader apiV2 = loaders.get("fabric-command-api-v2");
        LazyInterMedClassLoader apiV1 = loaders.get("fabric-command-api-v1");

        assertNotNull(apiV2);
        assertNotNull(apiV1);
        assertTrue(apiV1.getPeers().contains(apiV2));
        assertEquals("sibling-api-visible", System.getProperty("intermed.fabric.api.peer"));
    }

    @Test
    void fabricVirtualDependencyCanSeeInstalledFabricApiSubmoduleClasses() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-fabric-api-peer-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());

        Path biomeApiJar = modsDir.resolve("fabric-biome-api-v1.jar");
        createFabricModJar(
            biomeApiJar,
            "fabric-biome-api-v1",
            "net.fabricmc.fabric.api.biome.v1.BiomeModifications",
            """
            package net.fabricmc.fabric.api.biome.v1;
            public class BiomeModifications implements net.fabricmc.api.ModInitializer {
                public static final String VALUE = "fabric-api-visible";
                @Override
                public void onInitialize() {
                }
            }
            """
        );

        createFabricModJar(
            modsDir.resolve("fabric-consumer.jar"),
            "fabric_consumer",
            "test.mods.FabricApiConsumer",
            """
            package test.mods;
            public class FabricApiConsumer implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.setProperty("intermed.fabric.api.peer",
                        net.fabricmc.fabric.api.biome.v1.BiomeModifications.VALUE);
                }
            }
            """,
            """
              "depends": {
                "fabric": "*"
              }
            """,
            biomeApiJar.toString()
        );

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        Map<String, LazyInterMedClassLoader> loaders = LifecycleManager.getModClassLoaders();
        LazyInterMedClassLoader provider = loaders.get("fabric-biome-api-v1");
        LazyInterMedClassLoader consumer = loaders.get("fabric_consumer");

        assertNotNull(provider);
        assertNotNull(consumer);
        assertEquals("fabric-api-visible", System.getProperty("intermed.fabric.api.peer"));
        assertTrue(consumer.getPeers().contains(provider));
    }

    @Test
    void bootsFabricModThroughRuntimeClassRemapper() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-bytecode-remap-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());
        System.setProperty("intermed.mappings.tiny", createTestMappings().toString());

        createFabricModJarFromClasses(
            modsDir.resolve("bytecode-remap.jar"),
            "bytecode_remap_mod",
            "test.mods.BytecodeRemapMod",
            Map.of("test/mods/BytecodeRemapMod.class", createBytecodeRemapMod("test/mods/BytecodeRemapMod"))
        );

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        assertEquals("1", System.getProperty("intermed.remap.bytecode"));
    }

    @Test
    void bootsFabricModThroughReflectionStringRemapper() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-reflection-remap-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());
        System.setProperty("intermed.mappings.tiny", createTestMappings().toString());

        createFabricModJar(
            modsDir.resolve("reflection-remap.jar"),
            "reflection_remap_mod",
            "test.mods.ReflectionRemapMod",
            """
            package test.mods;
            public class ReflectionRemapMod implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    try {
                        String indyName = "net.minecraft." + "class_42";
                        String builderName = new StringBuilder().append("net.minecraft.").append("class_42").toString();
                        System.setProperty("intermed.remap.class.indy", Class.forName(indyName).getName());
                        System.setProperty("intermed.remap.class.builder", Class.forName(builderName).getName());
                    } catch (Throwable t) {
                        String failure = "error:" + t.getClass().getSimpleName();
                        System.setProperty("intermed.remap.class.indy", failure);
                        System.setProperty("intermed.remap.class.builder", failure);
                    }
                }
            }
            """
        );

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();

        assertEquals("net.minecraft.server.level.ServerPlayer", System.getProperty("intermed.remap.class.indy"));
        assertEquals("net.minecraft.server.level.ServerPlayer", System.getProperty("intermed.remap.class.builder"));
    }

    @Test
    void registersSandboxPlanDuringLifecycleAssembly() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-sandbox-plan-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());
        System.setProperty("sandbox.native.fallback.enabled", "true");
        RuntimeConfig.reload();

        createFabricModJar(
            modsDir.resolve("sandboxed-mod.jar"),
            "sandboxed_mod",
            "test.mods.SandboxedMod",
            """
            package test.mods;
            public class SandboxedMod implements net.fabricmc.api.ModInitializer {
                @Override
                public void onInitialize() {
                    System.setProperty("intermed.sandbox.loaded", "native");
                    System.out.println("sandboxed-espresso");
                }
            }
            """,
            """
              "intermed:sandbox": { "mode": "espresso", "allowNativeFallback": true }
            """
        );

        LifecycleManager.startPhase0_Preloader();
        GraalVMSandbox.HostStatus hostStatus = GraalVMSandbox.probeAvailability();
        if (!hostStatus.isReady()) {
            LifecycleManager.assembleNow();
            SandboxPlan plan = PolyglotSandboxManager.getPlan("sandboxed_mod").orElseThrow();
            assertEquals(SandboxMode.ESPRESSO, plan.requestedMode());
            assertEquals(SandboxMode.NATIVE, plan.effectiveMode());
            assertTrue(plan.fallbackApplied());
            assertTrue(plan.reason().contains("espresso-host-unavailable"));
            assertEquals("native", System.getProperty("intermed.sandbox.loaded"));
            assertTrue(BridgeRuntime.getEntrypoints("main").stream()
                .anyMatch(entry -> entry.modId().equals("sandboxed_mod")));
            return;
        }

        LifecycleManager.assembleNow();

        BridgeRuntime.LoadedEntrypoint entrypoint = BridgeRuntime.getEntrypoints("main").stream()
            .filter(entry -> entry.modId().equals("sandboxed_mod"))
            .findFirst()
            .orElseThrow();
        assertInstanceOf(SandboxedEntrypoint.class, entrypoint.instance());
        SandboxExecutionResult result = ((SandboxedEntrypoint) entrypoint.instance()).lastSandboxResult();
        assertTrue(result.success(), result.message());
        assertTrue(result.stdout().contains("sandboxed-espresso"));
        assertFalse(result.planReason().isBlank());
        assertTrue(result.runtimeDiagnostics().contains("requested=espresso"), result.runtimeDiagnostics());
        assertNull(System.getProperty("intermed.sandbox.loaded"));
        assertEquals(
            SandboxMode.ESPRESSO,
            PolyglotSandboxManager.getPlan("sandboxed_mod").orElseThrow().effectiveMode()
        );
    }

    @Test
    @Tag("compat-smoke")
    void dispatchesDedicatedServerEntrypointsInServerEnvironment() throws Exception {
        Path modsDir = Files.createTempDirectory("intermed-server-mods");
        System.setProperty("intermed.modsDir", modsDir.toString());
        System.setProperty("runtime.env", "server");
        RuntimeConfig.reload();

        createServerFabricModJar(
            modsDir.resolve("server-smoke.jar"),
            "server_smoke",
            "test.mods.ServerSmokeMod",
            """
            package test.mods;
            public class ServerSmokeMod implements net.fabricmc.api.DedicatedServerModInitializer {
                @Override
                public void onInitializeServer() {
                    System.setProperty("intermed.server.loaded", "true");
                }
            }
            """
        );

        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();
        LifecycleManager.dispatchMainThreadTasks();

        assertEquals("true", System.getProperty("intermed.server.loaded"));
        assertTrue(BridgeRuntime.getEntrypoints("server").stream()
            .anyMatch(entry -> entry.modId().equals("server_smoke")));
    }

    private static void createFabricModJar(Path jarPath, String modId, String entrypoint, String javaSource) throws Exception {
        createFabricModJar(jarPath, modId, entrypoint, javaSource, "");
    }

    private static void createFabricModJarWithVersion(Path jarPath,
                                                      String modId,
                                                      String version,
                                                      String entrypoint,
                                                      String javaSource) throws Exception {
        createFabricModJar(jarPath, modId, version, entrypoint, javaSource, "", null);
    }

    private static void createServerFabricModJar(Path jarPath,
                                                 String modId,
                                                 String entrypoint,
                                                 String javaSource) throws Exception {
        Path root = Files.createTempDirectory("intermed-server-src");
        Path srcRoot = root.resolve("src");
        Path classesRoot = root.resolve("classes");
        compileSingleSource(srcRoot, classesRoot, entrypoint, javaSource, null);

        String modJson = """
            {
              "schemaVersion": 1,
              "id": "%s",
              "version": "1.0.0",
              "entrypoints": {
                "server": ["%s"]
              }
            }
            """.formatted(modId, entrypoint);

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (Path file : Files.walk(classesRoot).filter(Files::isRegularFile).toList()) {
                String entryName = classesRoot.relativize(file).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(entryName));
                jos.write(Files.readAllBytes(file));
                jos.closeEntry();
            }

            jos.putNextEntry(new JarEntry("fabric.mod.json"));
            jos.write(modJson.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
    }

    private static void createFabricModJar(Path jarPath,
                                           String modId,
                                           String entrypoint,
                                           String javaSource,
                                           String extraManifestFields) throws Exception {
        createFabricModJar(jarPath, modId, entrypoint, javaSource, extraManifestFields, null);
    }

    private static void createFabricModJar(Path jarPath,
                                           String modId,
                                           String entrypoint,
                                           String javaSource,
                                           String extraManifestFields,
                                           String extraClasspath) throws Exception {
        createFabricModJar(jarPath, modId, "1.0.0", entrypoint, javaSource, extraManifestFields, extraClasspath);
    }

    private static void createFabricModJar(Path jarPath,
                                           String modId,
                                           String version,
                                           String entrypoint,
                                           String javaSource,
                                           String extraManifestFields,
                                           String extraClasspath) throws Exception {
        Path root = Files.createTempDirectory("intermed-smoke-src");
        Path srcRoot = root.resolve("src");
        Path classesRoot = root.resolve("classes");
        compileSingleSource(srcRoot, classesRoot, entrypoint, javaSource, extraClasspath);

        String normalizedExtraFields = "";
        if (extraManifestFields != null && !extraManifestFields.isBlank()) {
            normalizedExtraFields = extraManifestFields.stripTrailing() + ",";
        }

        String modJson = """
            {
              "schemaVersion": 1,
              "id": "%s",
              "version": "%s",
              %s
              "entrypoints": {
                "main": ["%s"]
              }
            }
            """.formatted(modId, version, normalizedExtraFields, entrypoint);

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (Path file : Files.walk(classesRoot).filter(Files::isRegularFile).toList()) {
                String entryName = classesRoot.relativize(file).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(entryName));
                jos.write(Files.readAllBytes(file));
                jos.closeEntry();
            }

            jos.putNextEntry(new JarEntry("fabric.mod.json"));
            jos.write(modJson.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
    }

    private static Path createFabricModJarWithNestedLibrary(Path jarPath,
                                                            String modId,
                                                            String entrypoint,
                                                            String javaSource,
                                                            String nestedJarName,
                                                            String nestedClass,
                                                            String nestedJavaSource,
                                                            String extraManifestFields,
                                                            String overlayJson) throws Exception {
        Path root = Files.createTempDirectory("intermed-private-lib-src");
        Path nestedJar = root.resolve(nestedJarName);

        compileSingleSource(root.resolve("mod-src"), root.resolve("mod-classes"), entrypoint, javaSource, null);
        compileSingleSource(root.resolve("lib-src"), root.resolve("lib-classes"), nestedClass, nestedJavaSource, null);

        try (JarOutputStream nestedOut = new JarOutputStream(Files.newOutputStream(nestedJar))) {
            for (Path file : Files.walk(root.resolve("lib-classes")).filter(Files::isRegularFile).toList()) {
                String entryName = root.resolve("lib-classes").relativize(file).toString().replace('\\', '/');
                nestedOut.putNextEntry(new JarEntry(entryName));
                nestedOut.write(Files.readAllBytes(file));
                nestedOut.closeEntry();
            }
        }

        String normalizedExtraFields = "";
        if (extraManifestFields != null && !extraManifestFields.isBlank()) {
            normalizedExtraFields = extraManifestFields.stripTrailing() + ",";
        }

        String modJson = """
            {
              "schemaVersion": 1,
              "id": "%s",
              "version": "1.0.0",
              %s
              "entrypoints": {
                "main": ["%s"]
              }
            }
            """.formatted(modId, normalizedExtraFields, entrypoint);

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (Path file : Files.walk(root.resolve("mod-classes")).filter(Files::isRegularFile).toList()) {
                String entryName = root.resolve("mod-classes").relativize(file).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(entryName));
                jos.write(Files.readAllBytes(file));
                jos.closeEntry();
            }

            jos.putNextEntry(new JarEntry("fabric.mod.json"));
            jos.write(modJson.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();

            jos.putNextEntry(new JarEntry("META-INF/jars/" + nestedJarName));
            jos.write(Files.readAllBytes(nestedJar));
            jos.closeEntry();

            if (overlayJson != null && !overlayJson.isBlank()) {
                jos.putNextEntry(new JarEntry("META-INF/intermed.mod.json"));
                jos.write(overlayJson.getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return nestedJar;
    }

    private static void compileSingleSource(Path srcRoot,
                                            Path classesRoot,
                                            String className,
                                            String javaSource,
                                            String extraClasspath) throws Exception {
        Files.createDirectories(srcRoot);
        Files.createDirectories(classesRoot);

        Path javaFile = srcRoot.resolve(className.replace('.', '/') + ".java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, javaSource, StandardCharsets.UTF_8);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        String classpath = System.getProperty("java.class.path");
        if (extraClasspath != null && !extraClasspath.isBlank()) {
            classpath = extraClasspath + java.io.File.pathSeparator + classpath;
        }

        int compileResult = compiler.run(null, null, null,
            "-classpath", classpath,
            "-d", classesRoot.toString(),
            javaFile.toString());
        assertEquals(0, compileResult);
    }

    private static void createFabricModJarFromClasses(Path jarPath,
                                                      String modId,
                                                      String entrypoint,
                                                      Map<String, byte[]> classEntries) throws Exception {
        String modJson = """
            {
              "schemaVersion": 1,
              "id": "%s",
              "version": "1.0.0",
              "entrypoints": {
                "main": ["%s"]
              }
            }
            """.formatted(modId, entrypoint);

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (Map.Entry<String, byte[]> entry : classEntries.entrySet()) {
                jos.putNextEntry(new JarEntry(entry.getKey()));
                jos.write(entry.getValue());
                jos.closeEntry();
            }

            jos.putNextEntry(new JarEntry("fabric.mod.json"));
            jos.write(modJson.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
    }

    private static void createForgeModJar(Path jarPath, String modId, String entrypoint) throws Exception {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jos.putNextEntry(new JarEntry(entrypoint.replace('.', '/') + ".class"));
            jos.write(createForgeSmokeClass(entrypoint.replace('.', '/'), modId));
            jos.closeEntry();

            String modsToml = """
                modLoader="javafml"
                loaderVersion="[47,)"

                [[mods]]
                modId="%s"
                version="1.0.0"

                [[mods.dependencies]]
                modId="forge"
                mandatory=true
                versionRange="[47,)"
                """.formatted(modId);

            jos.putNextEntry(new JarEntry("META-INF/mods.toml"));
            jos.write(modsToml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
    }

    private static byte[] createClassLiteralMixin(String internalName, String targetInternalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
            internalName, null, "java/lang/Object", null);

        AnnotationVisitor mixin = writer.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
        AnnotationVisitor targets = mixin.visitArray("value");
        targets.visit(null, Type.getObjectType(targetInternalName));
        targets.visitEnd();
        mixin.visitEnd();

        MethodVisitor accessor = writer.visitMethod(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
            "getValues",
            "()Ljava/util/Map;",
            null,
            null
        );
        accessor.visitAnnotation("Lorg/spongepowered/asm/mixin/gen/Accessor;", true).visitEnd();
        accessor.visitCode();
        accessor.visitTypeInsn(Opcodes.NEW, "java/lang/AssertionError");
        accessor.visitInsn(Opcodes.DUP);
        accessor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/AssertionError", "<init>", "()V", false);
        accessor.visitInsn(Opcodes.ATHROW);
        accessor.visitMaxs(2, 0);
        accessor.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] createStaticMethodEntrypointClass(String internalName, String propertyName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "init", "()V", null, null);
        init.visitCode();
        init.visitLdcInsn(propertyName);
        init.visitLdcInsn("true");
        init.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            "java/lang/System",
            "setProperty",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            false
        );
        init.visitInsn(Opcodes.POP);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(2, 0);
        init.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] createForgeSmokeClass(String internalName, String modId) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);

        AnnotationVisitor annotation = writer.visitAnnotation("Lnet/minecraftforge/fml/common/Mod;", true);
        annotation.visit("value", modId);
        annotation.visitEnd();

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitLdcInsn("intermed.forge.smoke.loaded");
        constructor.visitLdcInsn("true");
        constructor.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        constructor.visitInsn(Opcodes.POP);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(3, 1);
        constructor.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] createBytecodeRemapMod(String internalName) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object",
            new String[]{"net/fabricmc/api/ModInitializer"});

        MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor onInitialize = writer.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
        onInitialize.visitCode();
        onInitialize.visitTypeInsn(Opcodes.NEW, "net/minecraft/class_42");
        onInitialize.visitInsn(Opcodes.DUP);
        onInitialize.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/class_42", "<init>", "()V", false);
        onInitialize.visitVarInsn(Opcodes.ASTORE, 1);
        onInitialize.visitVarInsn(Opcodes.ALOAD, 1);
        onInitialize.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/class_42", "method_1000", "()V", false);
        onInitialize.visitLdcInsn("intermed.remap.bytecode");
        onInitialize.visitVarInsn(Opcodes.ALOAD, 1);
        onInitialize.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/class_42", "field_500", "I");
        onInitialize.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Integer", "toString", "(I)Ljava/lang/String;", false);
        onInitialize.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "setProperty",
            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", false);
        onInitialize.visitInsn(Opcodes.POP);
        onInitialize.visitInsn(Opcodes.RETURN);
        onInitialize.visitMaxs(3, 2);
        onInitialize.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static Path createTestMappings() throws Exception {
        Path mappingsDir = Files.createTempDirectory("intermed-test-mappings");
        Path clientTxt = mappingsDir.resolve("client.txt");
        Files.writeString(clientTxt, "net.minecraft.server.level.ServerPlayer -> a:\n", StandardCharsets.UTF_8);

        Path tiny = mappingsDir.resolve("mappings.tiny");
        Files.writeString(
            tiny,
            "tiny\t2\t0\tofficial\tintermediary\tnamed\n"
                + "c\ta\tnet/minecraft/class_42\tnet/minecraft/class_42\n"
                + "\tm\t()V\tm_1000_\tmethod_1000\ttick\n"
                + "\tf\tI\tf_500\tfield_500\tcount\n",
            StandardCharsets.UTF_8
        );
        return tiny;
    }

    private static void clearProperties(String... keys) {
        for (String key : keys) {
            System.clearProperty(key);
        }
    }
}
