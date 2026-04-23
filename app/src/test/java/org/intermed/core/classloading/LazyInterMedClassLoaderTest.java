package org.intermed.core.classloading;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.lifecycle.LifecycleManager;
import org.intermed.core.metadata.ModPlatform;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.metadata.RuntimeModIndex;
import org.intermed.core.vfs.VirtualFileSystemRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.objectweb.asm.tree.ClassNode;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class LazyInterMedClassLoaderTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("runtime.game.dir");
        System.clearProperty("vfs.enabled");
        System.clearProperty("vfs.conflict.policy");
        System.clearProperty("vfs.priority.mods");
        System.clearProperty("vfs.cache.dir");
        RuntimeModIndex.clear();
        RuntimeConfig.resetForTests();
        VirtualFileSystemRouter.invalidateCache();
        NativeLinkerNode.resetForTests();
        LazyInterMedClassLoader.resetRuntimeClassLoadersForTests();
        LifecycleManager.DICTIONARY.clear();
        org.intermed.core.remapping.InterMedRemapper.clearCaches();
        clearActiveMixinTransformer();
    }

    @Test
    void loadsOwnClassesAndDeclaredDependenciesOnly() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-test");
        FileBundle dependency = createJar(tempDir, "dep",
            Map.of("sample.dep.DependencyType", "package sample.dep; public class DependencyType { public static final String VALUE = \"dep\"; }"));
        FileBundle isolated = createJar(tempDir, "isolated",
            Map.of("sample.isolated.NeighborType", "package sample.isolated; public class NeighborType { }"));
        FileBundle main = createJar(tempDir, "main",
            Map.of("sample.main.MainType", "package sample.main; public class MainType { public static final String VALUE = \"main\"; }"));

        LazyInterMedClassLoader dependencyLoader = new LazyInterMedClassLoader(
            "dependency", dependency.jar.toFile(), Set.of(), getClass().getClassLoader());
        LazyInterMedClassLoader isolatedLoader = new LazyInterMedClassLoader(
            "isolated", isolated.jar.toFile(), Set.of(), getClass().getClassLoader());
        LazyInterMedClassLoader mainLoader = new LazyInterMedClassLoader(
            "main", main.jar.toFile(), Set.of(dependencyLoader), getClass().getClassLoader());

        assertEquals("main", mainLoader.loadClass("sample.main.MainType").getField("VALUE").get(null));
        assertEquals("dep", mainLoader.loadClass("sample.dep.DependencyType").getField("VALUE").get(null));
        assertThrows(ClassNotFoundException.class, () -> mainLoader.loadClass("sample.isolated.NeighborType"));

        mainLoader.addPeer(isolatedLoader);
        assertThrows(ClassNotFoundException.class, () -> mainLoader.loadClass("sample.isolated.NeighborType"),
            "Peer access must stay restricted for non-platform packages");
    }

    @Test
    void rejectsInvalidPeerRelationshipsAndValidatesTopology() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-peers");
        FileBundle dependency = createJar(tempDir, "dep-peer",
            Map.of("sample.dep.DependencyType", "package sample.dep; public class DependencyType { }"));
        FileBundle main = createJar(tempDir, "main-peer",
            Map.of("sample.main.MainType", "package sample.main; public class MainType { }"));
        FileBundle side = createJar(tempDir, "side-peer",
            Map.of("sample.side.SideType", "package sample.side; public class SideType { }"));

        LazyInterMedClassLoader dependencyLoader = new LazyInterMedClassLoader(
            "dependency", dependency.jar.toFile(), Set.of(), getClass().getClassLoader());
        LazyInterMedClassLoader mainLoader = new LazyInterMedClassLoader(
            "main", main.jar.toFile(), Set.of(dependencyLoader), getClass().getClassLoader());
        LazyInterMedClassLoader sideLoader = new LazyInterMedClassLoader(
            "side", side.jar.toFile(), Set.of(), getClass().getClassLoader());

        assertThrows(IllegalArgumentException.class, () -> mainLoader.addPeer(mainLoader));
        assertThrows(IllegalArgumentException.class, () -> mainLoader.addPeer(dependencyLoader));

        mainLoader.addPeer(sideLoader);

        mainLoader.validateTopology();
        dependencyLoader.validateTopology();
        sideLoader.validateTopology();
    }

    @Test
    void missingClassIsDelegatedToDependencyLoaderWithoutBeingDefinedLocally() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-delegation");
        FileBundle dependency = createJar(tempDir, "dep-owner",
            Map.of("sample.shared.SharedType", "package sample.shared; public class SharedType { }"));
        FileBundle main = createJar(tempDir, "main-owner",
            Map.of("sample.main.OwnType", "package sample.main; public class OwnType { }"));

        LazyInterMedClassLoader dependencyLoader = new LazyInterMedClassLoader(
            "dependency", dependency.jar.toFile(), Set.of(), getClass().getClassLoader());
        LazyInterMedClassLoader mainLoader = new LazyInterMedClassLoader(
            "main", main.jar.toFile(), Set.of(dependencyLoader), getClass().getClassLoader());

        Class<?> shared = mainLoader.loadClass("sample.shared.SharedType");

        assertSame(dependencyLoader, shared.getClassLoader(),
            "Classes absent from the current node must be owned by the declared dependency loader");
        assertFalse(mainLoader.hasLoadedClass("sample.shared.SharedType"),
            "The requesting loader must not define a dependency-owned class locally");
    }

    @Test
    void readLocalClassBytesRespectsOwnershipBoundaries() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-local-bytes");
        FileBundle dependency = createJar(tempDir, "dep-bytes",
            Map.of("sample.shared.SharedType", "package sample.shared; public class SharedType { }"));
        FileBundle main = createJar(tempDir, "main-bytes",
            Map.of("sample.main.OwnType", "package sample.main; public class OwnType { }"));

        LazyInterMedClassLoader dependencyLoader = new LazyInterMedClassLoader(
            "dependency", dependency.jar.toFile(), Set.of(), getClass().getClassLoader());
        LazyInterMedClassLoader mainLoader = new LazyInterMedClassLoader(
            "main", main.jar.toFile(), Set.of(dependencyLoader), getClass().getClassLoader());

        byte[] ownBytes = mainLoader.readLocalClassBytes("sample.main.OwnType");
        byte[] foreignBytes = mainLoader.readLocalClassBytes("sample.shared.SharedType");
        byte[] dependencyBytes = dependencyLoader.readLocalClassBytes("sample.shared.SharedType");

        assertNotNull(ownBytes);
        assertNull(foreignBytes, "A loader must not expose parent-owned bytecode as its own");
        assertNotNull(dependencyBytes);
        assertFalse(Arrays.equals(ownBytes, dependencyBytes));
    }

    @Test
    void delegatesRemappedMinecraftClassNameToRuntimeParent() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-remapped-parent");
        FileBundle platform = createJar(tempDir, "platform-runtime",
            Map.of("sample.platform.RuntimeType", "package sample.platform; public class RuntimeType { }"));
        FileBundle main = createJar(tempDir, "main-remapped-parent",
            Map.of("sample.main.OwnType", "package sample.main; public class OwnType { }"));

        LifecycleManager.DICTIONARY.addClass("net/minecraft/class_7151", "sample/platform/RuntimeType");
        org.intermed.core.remapping.InterMedRemapper.installDictionary(LifecycleManager.DICTIONARY);

        try (URLClassLoader parent = new URLClassLoader(new URL[] {platform.jar.toUri().toURL()}, getClass().getClassLoader())) {
            LazyInterMedClassLoader mainLoader = new LazyInterMedClassLoader(
                "main", main.jar.toFile(), Set.of(), parent);

            Class<?> remapped = mainLoader.loadClass("net.minecraft.class_7151");

            assertEquals("sample.platform.RuntimeType", remapped.getName());
            assertSame(parent, remapped.getClassLoader());
        }
    }

    @Test
    void internalDagTraversalDelegatesRuntimeClassesToPlatformParent() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-internal-platform");
        FileBundle platform = createJar(tempDir, "platform-runtime-internal",
            Map.of("sample.platform.RuntimeType", "package sample.platform; public class RuntimeType { }"));
        FileBundle dependency = createJar(tempDir, "dependency-internal",
            Map.of("sample.dependency.DependencyType", "package sample.dependency; public class DependencyType { }"));
        FileBundle main = createJar(tempDir, "main-internal-platform",
            Map.of("sample.main.OwnType", "package sample.main; public class OwnType { }"));

        try (URLClassLoader parent = new URLClassLoader(new URL[] {platform.jar.toUri().toURL()}, getClass().getClassLoader())) {
            LazyInterMedClassLoader dependencyLoader = new LazyInterMedClassLoader(
                "dependency", dependency.jar.toFile(), Set.of(), parent);
            LazyInterMedClassLoader mainLoader = new LazyInterMedClassLoader(
                "main", main.jar.toFile(), Set.of(dependencyLoader), parent);

            Class<?> runtimeType = mainLoader.loadClass("sample.platform.RuntimeType");

            assertEquals("sample.platform.RuntimeType", runtimeType.getName());
            assertSame(parent, runtimeType.getClassLoader());
        }
    }

    @Test
    void delegatesRuntimeClassesToRegisteredGameClassLoader() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-game-runtime");
        FileBundle gameRuntime = createJar(tempDir, "game-runtime",
            Map.of("net.minecraft.world.level.levelgen.structure.StructureType",
                "package net.minecraft.world.level.levelgen.structure; public class StructureType { }"));
        FileBundle main = createJar(tempDir, "main-game-runtime",
            Map.of("sample.main.OwnType", "package sample.main; public class OwnType { }"));

        try (URLClassLoader gameLoader = new URLClassLoader(new URL[] {gameRuntime.jar.toUri().toURL()}, null)) {
            LazyInterMedClassLoader.registerRuntimeClassLoader(gameLoader);
            LazyInterMedClassLoader mainLoader = new LazyInterMedClassLoader(
                "main", main.jar.toFile(), Set.of(), ClassLoader.getSystemClassLoader());

            Class<?> structureType = mainLoader.loadClass("net.minecraft.world.level.levelgen.structure.StructureType");

            assertEquals("net.minecraft.world.level.levelgen.structure.StructureType", structureType.getName());
            assertSame(gameLoader, structureType.getClassLoader());
        }
    }

    @Test
    void intermedRuntimeClassesPreferAgentParentOverRegisteredGameClassLoader() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-agent-runtime");
        FileBundle fakeRuntime = createJar(tempDir, "fake-intermed-runtime",
            Map.of("org.intermed.core.config.RuntimeConfig",
                "package org.intermed.core.config; public final class RuntimeConfig { public static String marker() { return \"fake\"; } }"));
        FileBundle main = createJar(tempDir, "main-agent-runtime",
            Map.of("sample.main.OwnType", "package sample.main; public class OwnType { }"));

        try (URLClassLoader gameLoader = new URLClassLoader(new URL[] {fakeRuntime.jar.toUri().toURL()}, null)) {
            LazyInterMedClassLoader.registerRuntimeClassLoader(gameLoader);
            LazyInterMedClassLoader mainLoader = new LazyInterMedClassLoader(
                "main", main.jar.toFile(), Set.of(), getClass().getClassLoader());

            Class<?> runtimeConfig = mainLoader.loadClass("org.intermed.core.config.RuntimeConfig");

            assertSame(RuntimeConfig.class, runtimeConfig);
            assertSame(RuntimeConfig.class.getClassLoader(), runtimeConfig.getClassLoader());
        }
    }

    @Test
    void bridgeLoaderHostsCoreFabricApiShimsWithoutShadowingIntermedRuntime() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-bridge-support");
        FileBundle gameRuntime = createJar(tempDir, "game-runtime-shims",
            Map.of(
                "net.minecraft.server.packs.resources.PreparableReloadListener",
                "package net.minecraft.server.packs.resources; public interface PreparableReloadListener { }",
                "net.minecraft.resources.ResourceLocation",
                "package net.minecraft.resources; public class ResourceLocation { }"
            ));

        try (URLClassLoader gameLoader = new URLClassLoader(new URL[] {gameRuntime.jar.toUri().toURL()}, null)) {
            LazyInterMedClassLoader.registerRuntimeClassLoader(gameLoader);

            LazyInterMedClassLoader bridgeLoader = new LazyInterMedClassLoader(
                "intermed-fabric-bridge", null, Set.of(), getClass().getClassLoader());
            bridgeLoader.addOwnedClassPrefix("net.fabricmc.api.");
            bridgeLoader.addOwnedClassPrefix("net.fabricmc.loader.api.");
            bridgeLoader.addOwnedClassPrefix("net.fabricmc.fabric.api.");
            bridgeLoader.addJar(codeSourceLocation());

            LazyInterMedClassLoader consumerLoader = new LazyInterMedClassLoader(
                "consumer", null, Set.of(bridgeLoader), getClass().getClassLoader());

            Class<?> fabricListener = consumerLoader.loadClass("net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener");

            assertSame(bridgeLoader, fabricListener.getClassLoader());
            assertEquals(1, fabricListener.getInterfaces().length);
            assertEquals("net.minecraft.server.packs.resources.PreparableReloadListener",
                fabricListener.getInterfaces()[0].getName());
            assertSame(gameLoader, fabricListener.getInterfaces()[0].getClassLoader());

            Class<?> runtimeConfig = bridgeLoader.loadClass("org.intermed.core.config.RuntimeConfig");
            assertSame(RuntimeConfig.class, runtimeConfig);
            assertSame(RuntimeConfig.class.getClassLoader(), runtimeConfig.getClassLoader());
        }
    }

    @Test
    void platformApiPeersPreferRealProviderModulesOverBuiltInBridgeFallback() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-platform-provider");
        FileBundle gameRuntime = createJar(tempDir, "game-runtime-intermediary",
            Map.of(
                "net.minecraft.class_3302",
                "package net.minecraft; public interface class_3302 { }",
                "net.minecraft.class_2960",
                "package net.minecraft; public class class_2960 { }"
            ));
        FileBundle fabricResourceProvider = createJar(
            tempDir,
            "fabric-resource-provider",
            Map.of(
                "net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener",
                """
                    package net.fabricmc.fabric.api.resource;

                    import java.util.Collection;
                    import java.util.Collections;
                    import net.minecraft.class_2960;
                    import net.minecraft.class_3302;

                    public interface IdentifiableResourceReloadListener extends class_3302 {
                        class_2960 getFabricId();

                        default Collection<class_2960> getFabricDependencies() {
                            return Collections.emptyList();
                        }
                    }
                    """
            ),
            Map.of(),
            List.of(gameRuntime.jar)
        );

        try (URLClassLoader gameLoader = new URLClassLoader(new URL[] {gameRuntime.jar.toUri().toURL()}, null)) {
            LazyInterMedClassLoader.registerRuntimeClassLoader(gameLoader);

            LazyInterMedClassLoader bridgeLoader = new LazyInterMedClassLoader(
                "intermed-fabric-bridge", null, Set.of(), getClass().getClassLoader());
            bridgeLoader.addOwnedClassPrefix("net.fabricmc.api.");
            bridgeLoader.addOwnedClassPrefix("net.fabricmc.loader.api.");
            bridgeLoader.addOwnedClassPrefix("net.fabricmc.fabric.api.");
            bridgeLoader.addJar(codeSourceLocation());

            LazyInterMedClassLoader fabricApiLoader = new LazyInterMedClassLoader(
                "fabric-resource-loader-v0", fabricResourceProvider.jar.toFile(), Set.of(), getClass().getClassLoader());

            LazyInterMedClassLoader consumerLoader = new LazyInterMedClassLoader(
                "consumer", null, Set.of(), getClass().getClassLoader());
            consumerLoader.addPeer(bridgeLoader);
            consumerLoader.addPeer(fabricApiLoader);

            Class<?> fabricListener = consumerLoader.loadClass("net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener");

            assertSame(fabricApiLoader, fabricListener.getClassLoader(),
                "Real Fabric API modules must win over built-in bridge fallbacks for shared API classes");
            assertEquals("net.minecraft.class_3302", fabricListener.getInterfaces()[0].getName());
            assertSame(gameLoader, fabricListener.getInterfaces()[0].getClassLoader());
        }
    }

    @Test
    void findLocalResourceDoesNotLeakDependencyResources() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-local-resource");
        FileBundle dependency = createJar(
            tempDir,
            "dep-local-resource",
            Map.of("sample.dep.DependencyType", "package sample.dep; public class DependencyType { }"),
            Map.of("dep-only.txt", "dependency")
        );
        FileBundle main = createJar(
            tempDir,
            "main-local-resource",
            Map.of("sample.main.MainType", "package sample.main; public class MainType { }")
        );

        LazyInterMedClassLoader dependencyLoader = new LazyInterMedClassLoader(
            "dependency", dependency.jar.toFile(), Set.of(), getClass().getClassLoader());
        LazyInterMedClassLoader mainLoader = new LazyInterMedClassLoader(
            "main", main.jar.toFile(), Set.of(dependencyLoader), getClass().getClassLoader());

        assertNull(mainLoader.findLocalResource("dep-only.txt"),
            "Local resource lookup must never expose dependency-owned resources");
        assertNotNull(mainLoader.getResource("dep-only.txt"),
            "Regular resource lookup may still delegate across declared dependency edges");
    }

    @Test
    void resourceLookupPrefersOwnThenDependenciesAndRejectsPeerResources() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-resources");
        FileBundle dependency = createJar(
            tempDir,
            "dep-resources",
            Map.of("sample.dep.DependencyType", "package sample.dep; public class DependencyType { }"),
            Map.of(
                "shared/config.txt", "dependency",
                "dep-only.txt", "dep-only"
            )
        );
        FileBundle isolated = createJar(
            tempDir,
            "isolated-resources",
            Map.of("sample.isolated.NeighborType", "package sample.isolated; public class NeighborType { }"),
            Map.of("peer-only.txt", "peer")
        );
        FileBundle main = createJar(
            tempDir,
            "main-resources",
            Map.of("sample.main.MainType", "package sample.main; public class MainType { }"),
            Map.of("shared/config.txt", "main")
        );

        LazyInterMedClassLoader dependencyLoader = new LazyInterMedClassLoader(
            "dependency", dependency.jar.toFile(), Set.of(), getClass().getClassLoader());
        LazyInterMedClassLoader isolatedLoader = new LazyInterMedClassLoader(
            "isolated", isolated.jar.toFile(), Set.of(), getClass().getClassLoader());
        LazyInterMedClassLoader mainLoader = new LazyInterMedClassLoader(
            "main", main.jar.toFile(), Set.of(dependencyLoader), getClass().getClassLoader());
        mainLoader.addPeer(isolatedLoader);

        assertEquals("main", readString(mainLoader.getResourceAsStream("shared/config.txt")));
        assertEquals("dep-only", readString(mainLoader.getResourceAsStream("dep-only.txt")));
        assertNull(mainLoader.getResource("peer-only.txt"),
            "Peer resources must stay inaccessible outside platform-shared resources");

        Enumeration<java.net.URL> resources = mainLoader.getResources("shared/config.txt");
        assertTrue(resources.hasMoreElements());
        assertNotNull(resources.nextElement());
        assertTrue(resources.hasMoreElements(),
            "Resource enumeration should include both local and dependency-owned resources");
    }

    @Test
    void directDependenciesDoNotReExportTheirPrivateParentsTransitively() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-transitive");
        FileBundle library = createJar(
            tempDir,
            "private-lib",
            Map.of("sample.lib.HiddenType", "package sample.lib; public class HiddenType { public static final String VALUE = \"hidden\"; }"),
            Map.of("private-only.txt", "secret")
        );
        FileBundle dependency = createJar(
            tempDir,
            "dep-mod",
            Map.of("sample.dep.DependencyType", "package sample.dep; public class DependencyType { public static final String VALUE = \"dep\"; }")
        );
        FileBundle main = createJar(
            tempDir,
            "main-mod",
            Map.of("sample.main.MainType", "package sample.main; public class MainType { }")
        );

        LazyInterMedClassLoader libraryLoader = new LazyInterMedClassLoader(
            "private-lib", library.jar.toFile(), Set.of(), getClass().getClassLoader());
        LazyInterMedClassLoader dependencyLoader = new LazyInterMedClassLoader(
            "dependency", dependency.jar.toFile(), Set.of(libraryLoader), getClass().getClassLoader());
        LazyInterMedClassLoader mainLoader = new LazyInterMedClassLoader(
            "main", main.jar.toFile(), Set.of(dependencyLoader), getClass().getClassLoader());

        assertEquals("dep", mainLoader.loadClass("sample.dep.DependencyType").getField("VALUE").get(null));
        assertEquals("hidden", dependencyLoader.loadClass("sample.lib.HiddenType").getField("VALUE").get(null));
        assertThrows(ClassNotFoundException.class, () -> mainLoader.loadClass("sample.lib.HiddenType"),
            "Direct dependency edges must not leak transitive private libraries");
        assertNull(mainLoader.getResource("private-only.txt"),
            "Resources from a dependency's private parents must remain hidden");
    }

    @Test
    void directDependenciesMayExplicitlyReExportPrivateParents() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-reexport");
        FileBundle library = createJar(
            tempDir,
            "exported-lib",
            Map.of("sample.lib.ExportedType", "package sample.lib; public class ExportedType { public static final String VALUE = \"exported\"; }"),
            Map.of("exported-only.txt", "shared")
        );
        FileBundle dependency = createJar(
            tempDir,
            "api-mod",
            Map.of("sample.dep.ApiType", "package sample.dep; public class ApiType { }")
        );
        FileBundle main = createJar(
            tempDir,
            "consumer-mod",
            Map.of("sample.main.ConsumerType", "package sample.main; public class ConsumerType { }")
        );

        LazyInterMedClassLoader libraryLoader = new LazyInterMedClassLoader(
            "exported-lib", library.jar.toFile(), Set.of(), getClass().getClassLoader());
        LazyInterMedClassLoader dependencyLoader = new LazyInterMedClassLoader(
            "dependency",
            dependency.jar.toFile(),
            Map.of(libraryLoader, ParentLinkPolicy.REEXPORT),
            getClass().getClassLoader()
        );
        LazyInterMedClassLoader mainLoader = new LazyInterMedClassLoader(
            "main", main.jar.toFile(), Set.of(dependencyLoader), getClass().getClassLoader());

        assertEquals("exported", mainLoader.loadClass("sample.lib.ExportedType").getField("VALUE").get(null));
        assertEquals("shared", readString(mainLoader.getResourceAsStream("exported-only.txt")));
    }

    @Test
    void findLibraryClaimsResolvedPathWithoutPreloading() throws Exception {
        class ExposedLazyInterMedClassLoader extends LazyInterMedClassLoader {
            private final String resolvedPath;

            private ExposedLazyInterMedClassLoader(String resolvedPath) {
                super("native-test", null, Set.of(), LazyInterMedClassLoaderTest.class.getClassLoader());
                this.resolvedPath = resolvedPath;
            }

            String exposeFindLibrary(String libName) {
                return super.findLibrary(libName);
            }

            @Override
            protected String resolveNativeLibraryPath(String libName) {
                return resolvedPath;
            }
        }

        Path tempDir = Files.createTempDirectory("intermed-native-find-library");
        Path libraryPath = tempDir.resolve(System.mapLibraryName("intermed_demo"));
        FakeNativeLoadOperations nativeLoads = new FakeNativeLoadOperations();
        NativeLinkerNode.setLoadOperationsForTests(nativeLoads);

        ExposedLazyInterMedClassLoader loader = new ExposedLazyInterMedClassLoader(libraryPath.toString());
        NativeLinkerNode nativeLinker = NativeLinkerNode.getInstance();

        String resolved = loader.exposeFindLibrary("intermed_demo");

        assertEquals(libraryPath.toFile().getCanonicalPath(), resolved);
        assertEquals(0, nativeLinker.loadCount());
        assertEquals(0, nativeLoads.loadCalls);
        assertEquals(0, nativeLoads.loadLibraryCalls);
        assertTrue(nativeLinker.diagnostics().stream().anyMatch(line -> line.contains("requester=native-test")));
    }

    @Test
    void nativeLinkerDeduplicatesSameCanonicalPath() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-native-dedup");
        Path libraryPath = tempDir.resolve(System.mapLibraryName("intermed_demo"));
        FakeNativeLoadOperations nativeLoads = new FakeNativeLoadOperations();
        NativeLinkerNode.setLoadOperationsForTests(nativeLoads);

        NativeLinkerNode nativeLinker = NativeLinkerNode.getInstance();

        assertTrue(nativeLinker.tryLoad(libraryPath.toString(), "mod_a"));
        assertFalse(nativeLinker.tryLoad(libraryPath.toString(), "mod_b"));
        assertEquals(1, nativeLoads.loadCalls);
        assertEquals(1, nativeLinker.loadCount());
        assertEquals(1, nativeLinker.dedupCount());
    }

    @Test
    void nativeLinkerRejectsSameLogicalNameFromDifferentPaths() throws Exception {
        Path firstDir = Files.createTempDirectory("intermed-native-first");
        Path secondDir = Files.createTempDirectory("intermed-native-second");
        String mappedName = System.mapLibraryName("intermed_demo");
        Path firstLibrary = firstDir.resolve(mappedName);
        Path secondLibrary = secondDir.resolve(mappedName);
        FakeNativeLoadOperations nativeLoads = new FakeNativeLoadOperations();
        NativeLinkerNode.setLoadOperationsForTests(nativeLoads);

        NativeLinkerNode nativeLinker = NativeLinkerNode.getInstance();

        assertTrue(nativeLinker.tryLoad(firstLibrary.toString(), "mod_a"));
        UnsatisfiedLinkError conflict = assertThrows(UnsatisfiedLinkError.class,
            () -> nativeLinker.tryLoad(secondLibrary.toString(), "mod_b"));

        assertTrue(conflict.getMessage().contains("logical name conflict"));
        assertTrue(conflict.getMessage().contains("mod_a"));
        assertTrue(conflict.getMessage().contains("mod_b"));
        assertEquals(1, nativeLoads.loadCalls);
        assertEquals(1, nativeLinker.conflictCount());
    }

    @Test
    void nativeLinkerReleasesClaimAfterLoadFailure() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-native-failure");
        Path libraryPath = tempDir.resolve(System.mapLibraryName("intermed_demo"));
        FakeNativeLoadOperations nativeLoads = new FakeNativeLoadOperations();
        nativeLoads.failNextLoad = true;
        NativeLinkerNode.setLoadOperationsForTests(nativeLoads);

        NativeLinkerNode nativeLinker = NativeLinkerNode.getInstance();

        assertThrows(UnsatisfiedLinkError.class,
            () -> nativeLinker.tryLoad(libraryPath.toString(), "mod_a"));
        assertTrue(nativeLinker.tryLoad(libraryPath.toString(), "mod_a"));

        assertEquals(2, nativeLoads.loadCalls);
        assertEquals(1, nativeLinker.failureCount());
        assertEquals(1, nativeLinker.loadCount());
    }

    @Test
    void managedDataResourcesResolveThroughVfsOverlayBeforeLocalDagLookup() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-vfs");
        System.setProperty("runtime.game.dir", tempDir.toString());
        System.setProperty("vfs.priority.mods", "priority_mod,base_mod");
        RuntimeConfig.reload();

        FileBundle base = createJar(
            tempDir,
            "base-vfs",
            Map.of("sample.base.BaseType", "package sample.base; public class BaseType { }"),
            Map.of("data/minecraft/tags/items/intermed_tools.json", """
                {"replace":false,"values":["minecraft:stick"]}
                """)
        );
        FileBundle priority = createJar(
            tempDir,
            "priority-vfs",
            Map.of("sample.priority.PriorityType", "package sample.priority; public class PriorityType { }"),
            Map.of("data/minecraft/tags/items/intermed_tools.json", """
                {"replace":false,"values":["minecraft:planks","minecraft:stick"]}
                """)
        );

        RuntimeModIndex.registerAll(List.of(
            metadata("base_mod", base.jar.toFile(), false, true),
            metadata("priority_mod", priority.jar.toFile(), false, true)
        ));
        VirtualFileSystemRouter.invalidateCache();

        LazyInterMedClassLoader baseLoader = new LazyInterMedClassLoader(
            "base_mod", base.jar.toFile(), Set.of(), getClass().getClassLoader());
        LazyInterMedClassLoader priorityLoader = new LazyInterMedClassLoader(
            "priority_mod", priority.jar.toFile(), Set.of(baseLoader), getClass().getClassLoader());

        String resourcePath = "data/minecraft/tags/items/intermed_tools.json";
        String json = readString(priorityLoader.getResourceAsStream(resourcePath));
        JsonObject merged = JsonParser.parseString(json).getAsJsonObject();
        JsonArray values = merged.getAsJsonArray("values");
        assertEquals(2, values.size());
        assertEquals("minecraft:stick", values.get(0).getAsString());
        assertEquals("minecraft:planks", values.get(1).getAsString());

        URL resolved = priorityLoader.getResource(resourcePath);
        assertNotNull(resolved);
        assertTrue(resolved.toExternalForm().contains("intermed-vfs-overlay"));

        Enumeration<URL> resources = priorityLoader.getResources(resourcePath);
        assertTrue(resources.hasMoreElements());
        URL first = resources.nextElement();
        assertTrue(first.toExternalForm().contains("intermed-vfs-overlay"));
    }

    @Test
    void closeDetachesBackReferencesAndInvalidatesCaches() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-close");
        FileBundle dependency = createJar(tempDir, "dep-close",
            Map.of("sample.dep.DependencyType", "package sample.dep; public class DependencyType { }"));
        FileBundle side = createJar(tempDir, "side-close",
            Map.of("sample.side.SideType", "package sample.side; public class SideType { }"));
        FileBundle main = createJar(tempDir, "main-close",
            Map.of("sample.main.MainType", "package sample.main; public class MainType { }"));

        LazyInterMedClassLoader dependencyLoader = new LazyInterMedClassLoader(
            "dependency", dependency.jar.toFile(), Set.of(), getClass().getClassLoader());
        LazyInterMedClassLoader sideLoader = new LazyInterMedClassLoader(
            "side", side.jar.toFile(), Set.of(), getClass().getClassLoader());
        LazyInterMedClassLoader mainLoader = new LazyInterMedClassLoader(
            "main", main.jar.toFile(), Set.of(dependencyLoader), getClass().getClassLoader());
        mainLoader.addPeer(sideLoader);

        assertNotNull(mainLoader.loadClass("sample.main.MainType"));
        assertTrue(mainLoader.hasLoadedClass("sample.main.MainType"));
        assertTrue(dependencyLoader.getChildren().contains(mainLoader));

        mainLoader.close();

        assertFalse(mainLoader.hasLoadedClass("sample.main.MainType"));
        assertFalse(dependencyLoader.getChildren().contains(mainLoader));
        sideLoader.validateTopology();
        dependencyLoader.validateTopology();
    }

    @Test
    void dynamicallyInstallsWeakPeerEdgesOnlyForExportedSoftDependencyInterfaces() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-weak-edge");
        FileBundle provider = createJar(
            tempDir,
            "provider-mod",
            Map.of(
                "sample.provider.api.SharedApi",
                "package sample.provider.api; public interface SharedApi { String NAME = \"shared\"; }",
                "sample.provider.impl.HiddenImpl",
                "package sample.provider.impl; public class HiddenImpl implements sample.provider.api.SharedApi { }"
            )
        );
        FileBundle consumer = createJar(
            tempDir,
            "consumer-mod",
            Map.of("sample.consumer.ConsumerType", "package sample.consumer; public class ConsumerType { }")
        );

        JsonObject providerManifest = new JsonObject();
        JsonObject providerClassloader = new JsonObject();
        providerClassloader.add("apiPrefixes", JsonParser.parseString("[\"sample.provider.api\"]"));
        providerManifest.add("intermed:classloader", providerClassloader);

        JsonObject consumerManifest = new JsonObject();
        JsonObject consumerClassloader = new JsonObject();
        consumerClassloader.add("softDepends", JsonParser.parseString("[\"provider_mod\"]"));
        consumerManifest.add("intermed:classloader", consumerClassloader);

        NormalizedModMetadata providerMeta = new NormalizedModMetadata(
            "provider_mod", "1.0.0", provider.jar.toFile(), ModPlatform.FABRIC, providerManifest, Map.of());
        NormalizedModMetadata consumerMeta = new NormalizedModMetadata(
            "consumer_mod", "1.0.0", consumer.jar.toFile(), ModPlatform.FABRIC, consumerManifest, Map.of());

        LazyInterMedClassLoader providerLoader = new LazyInterMedClassLoader(
            "provider_mod", provider.jar.toFile(), Set.of(), getClass().getClassLoader());
        LazyInterMedClassLoader consumerLoader = new LazyInterMedClassLoader(
            "consumer_mod", consumer.jar.toFile(), Set.of(), getClass().getClassLoader());

        LifecycleManager.registerLoaderForTests("provider_mod", providerLoader, providerMeta);
        LifecycleManager.registerLoaderForTests("consumer_mod", consumerLoader, consumerMeta);

        Class<?> apiType = consumerLoader.loadClass("sample.provider.api.SharedApi");
        assertSame(providerLoader, apiType.getClassLoader());
        assertTrue(consumerLoader.getWeakPeers().contains(providerLoader));
        assertEquals(List.of("sample.provider.api."),
            consumerLoader.getWeakPeerPolicy(providerLoader).apiPrefixes());

        assertThrows(ClassNotFoundException.class,
            () -> consumerLoader.loadClass("sample.provider.impl.HiddenImpl"));
    }

    @Test
    void appliesActiveNativeMixinTransformerBeforeDefiningClass() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-loader-native-mixin");
        FileBundle original = createJar(
            tempDir,
            "native-mixin-original",
            Map.of("sample.mixin.NativeTarget",
                "package sample.mixin; public class NativeTarget { public static final String VALUE = \"original\"; }")
        );
        FileBundle transformed = createJar(
            tempDir,
            "native-mixin-transformed",
            Map.of("sample.mixin.NativeTarget",
                "package sample.mixin; public class NativeTarget { public static final String VALUE = \"mixed\"; }")
        );

        byte[] transformedBytes = readJarEntryBytes(transformed.jar, "sample/mixin/NativeTarget.class");
        installActiveMixinTransformer((environment, name, classBytes) ->
            "sample.mixin.NativeTarget".equals(name) ? transformedBytes : classBytes);

        LazyInterMedClassLoader loader = new LazyInterMedClassLoader(
            "native-mixin", original.jar.toFile(), Set.of(), getClass().getClassLoader());

        Class<?> targetClass = loader.loadClass("sample.mixin.NativeTarget");

        assertEquals("mixed", targetClass.getField("VALUE").get(null));
        assertSame(loader, targetClass.getClassLoader());
    }

    private static FileBundle createJar(Path root, String name, Map<String, String> sources) throws Exception {
        return createJar(root, name, sources, Map.of());
    }

    private static File codeSourceLocation() throws Exception {
        return new File(RuntimeConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    }

    private static FileBundle createJar(Path root, String name, Map<String, String> sources,
                                        Map<String, String> resources) throws Exception {
        return createJar(root, name, sources, resources, List.of());
    }

    private static FileBundle createJar(Path root, String name, Map<String, String> sources,
                                        Map<String, String> resources,
                                        List<Path> classpathEntries) throws Exception {
        Path sourceRoot = root.resolve(name + "-src");
        Path classesRoot = root.resolve(name + "-classes");
        Files.createDirectories(sourceRoot);
        Files.createDirectories(classesRoot);

        for (Map.Entry<String, String> entry : sources.entrySet()) {
            Path javaFile = sourceRoot.resolve(entry.getKey().replace('.', '/') + ".java");
            Files.createDirectories(javaFile.getParent());
            Files.writeString(javaFile, entry.getValue(), StandardCharsets.UTF_8);
        }

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "System Java compiler is required for loader tests");
        ArrayList<String> args = new ArrayList<>();
        args.add("-d");
        args.add(classesRoot.toString());
        if (classpathEntries != null && !classpathEntries.isEmpty()) {
            args.add("-classpath");
            args.add(classpathEntries.stream()
                .map(Path::toString)
                .reduce((left, right) -> left + File.pathSeparator + right)
                .orElse(""));
        }
        args.addAll(Files.walk(sourceRoot)
            .filter(p -> p.toString().endsWith(".java"))
            .map(Path::toString)
            .toList());
        int result = compiler.run(null, null, null, args.toArray(String[]::new));
        assertEquals(0, result, "Test classes must compile");

        Path jar = root.resolve(name + ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Path file : Files.walk(classesRoot).filter(Files::isRegularFile).toList()) {
                String entryName = classesRoot.relativize(file).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(entryName));
                jos.write(Files.readAllBytes(file));
                jos.closeEntry();
            }
            for (Map.Entry<String, String> resource : new LinkedHashMap<>(resources).entrySet()) {
                jos.putNextEntry(new JarEntry(resource.getKey()));
                jos.write(resource.getValue().getBytes(StandardCharsets.UTF_8));
                jos.closeEntry();
            }
        }
        return new FileBundle(jar);
    }

    private static String readString(InputStream stream) throws IOException {
        assertNotNull(stream);
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readJarEntryBytes(Path jar, String entryName) throws IOException {
        try (java.util.jar.JarFile jarFile = new java.util.jar.JarFile(jar.toFile())) {
            JarEntry entry = jarFile.getJarEntry(entryName);
            assertNotNull(entry, "Missing test entry " + entryName);
            try (InputStream stream = jarFile.getInputStream(entry)) {
                return stream.readAllBytes();
            }
        }
    }

    private static void installActiveMixinTransformer(NativeMixinTransform callback) {
        MixinBootstrap.init();
        IMixinTransformer transformer = (IMixinTransformer) Proxy.newProxyInstance(
            LazyInterMedClassLoaderTest.class.getClassLoader(),
            new Class<?>[] { IMixinTransformer.class },
            (proxy, method, args) -> {
                String methodName = method.getName();
                if ("transformClass".equals(methodName)
                    && method.getParameterCount() == 3
                    && method.getParameterTypes()[2] == byte[].class) {
                    return callback.apply(
                        (MixinEnvironment) args[0],
                        (String) args[1],
                        (byte[]) args[2]
                    );
                }
                if ("transformClassBytes".equals(methodName)) {
                    return callback.apply(
                        MixinEnvironment.getCurrentEnvironment(),
                        (String) args[1],
                        (byte[]) args[2]
                    );
                }
                if ("computeFramesForClass".equals(methodName)) {
                    return false;
                }
                if ("transformClass".equals(methodName) || "generateClass".equals(methodName)) {
                    return false;
                }
                if ("reload".equals(methodName)) {
                    return List.of();
                }
                if ("getExtensions".equals(methodName) || "audit".equals(methodName)) {
                    return null;
                }
                throw new UnsupportedOperationException(methodName);
            }
        );
        MixinEnvironment.getCurrentEnvironment().setActiveTransformer(transformer);
    }

    private static void clearActiveMixinTransformer() {
        try {
            Field field = MixinEnvironment.class.getDeclaredField("transformer");
            field.setAccessible(true);
            field.set(null, null);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    private static NormalizedModMetadata metadata(String modId, File jar, boolean clientResources, boolean serverData) {
        JsonObject manifest = new JsonObject();
        if (clientResources) {
            manifest.addProperty("intermed:has_client_resources", true);
        }
        if (serverData) {
            manifest.addProperty("intermed:has_server_data", true);
        }
        return new NormalizedModMetadata(modId, "1.0.0", jar, ModPlatform.FABRIC, manifest, Map.of());
    }

    private static final class FakeNativeLoadOperations implements NativeLinkerNode.NativeLoadOperations {
        private int loadCalls;
        private int loadLibraryCalls;
        private boolean failNextLoad;

        @Override
        public void load(String absolutePath) {
            loadCalls++;
            if (failNextLoad) {
                failNextLoad = false;
                throw new UnsatisfiedLinkError("synthetic native load failure: " + absolutePath);
            }
        }

        @Override
        public void loadLibrary(String libName) {
            loadLibraryCalls++;
        }
    }

    @FunctionalInterface
    private interface NativeMixinTransform {
        byte[] apply(MixinEnvironment environment, String name, byte[] classBytes);
    }

    private record FileBundle(Path jar) {}
}
