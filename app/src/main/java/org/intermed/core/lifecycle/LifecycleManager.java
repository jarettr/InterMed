package org.intermed.core.lifecycle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.intermed.mixin.IClassBytesSource;
import org.intermed.mixin.InterMedMixinBootstrap;
import org.intermed.core.InterMedKernel;
import org.intermed.core.async.BackgroundPreparator;
import org.intermed.core.async.PreparatorTask;
import org.intermed.core.classloading.ClassHierarchyLcaIndex;
import org.intermed.core.classloading.InterMedClassLoader;
import org.intermed.core.registry.VirtualRegistryService;
import org.intermed.core.resolver.CrossLoaderTypeChecker;
import org.intermed.core.resolver.PubGrubResolver;
import org.intermed.core.resolver.ResolvedPlan;
import org.intermed.core.resolver.RuntimeModuleKind;
import org.intermed.core.resolver.VirtualDependencyMap;
import org.intermed.core.classloading.LazyInterMedClassLoader;
import org.intermed.core.classloading.LibraryConflictGraph;
import org.intermed.core.classloading.LibraryDiscovery;
import org.intermed.core.classloading.LibraryNode;
import org.intermed.core.classloading.ParentLinkPolicy;
import org.intermed.core.classloading.ShaderClassLoader;
import org.intermed.core.classloading.WeakPeerPolicy;
import org.intermed.core.classloading.WelshPowellClusterer;
import org.intermed.core.bridge.BridgeRuntime;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.metadata.ModMetadataParser;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.metadata.RuntimeModIndex;
import org.intermed.core.monitor.RiskyModRegistry;
import org.intermed.core.mixin.InterMedPlatformAgent;
import org.intermed.core.remapping.*;
import org.intermed.core.security.*;
import org.intermed.core.mixin.MixinTransformer;
import org.intermed.core.mixin.MixinTransmogrifier;
import org.intermed.core.registry.RegistryHookTransformer;
import org.intermed.core.lifecycle.ModDiscovery;
import org.intermed.core.monitor.MetricsRegistry;
import org.intermed.core.monitor.OtelJsonExporter;
import org.intermed.core.monitor.PrometheusExporter;
import org.intermed.core.sandbox.PolyglotSandboxManager;
import org.intermed.core.sandbox.SandboxExecutionResult;
import org.intermed.core.sandbox.SandboxMode;
import org.intermed.core.sandbox.SandboxPlan;
import org.intermed.core.sandbox.SandboxedClientModInitializer;
import org.intermed.core.sandbox.SandboxedModInitializer;
import org.intermed.core.sandbox.SandboxedServerModInitializer;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

/**
 * Manages the startup and shutdown lifecycle of the InterMed platform.
 * This includes mod discovery, dependency resolution, ClassLoader DAG construction, and mod initialization.
 */
public class LifecycleManager {
    private static boolean phase0Started = false;
    private static boolean phase1Started = false;

    // Global services managed by the lifecycle
    public static final MappingDictionary DICTIONARY = new MappingDictionary();
    public static final Queue<Runnable> MAIN_THREAD_TASKS = new ConcurrentLinkedQueue<>();
    private static final Map<String, LazyInterMedClassLoader> modClassLoaders = new ConcurrentHashMap<>();
    private static final Map<String, LazyInterMedClassLoader> bridgeClassLoaders = new ConcurrentHashMap<>();
    private static final List<ShaderClassLoader> shaderLoaders = Collections.synchronizedList(new ArrayList<>());

    /** Hot-reload watcher; {@code null} when the feature is disabled. */
    private static volatile HotReloadManager hotReloadManager;

    /** Prometheus metrics exporter; {@code null} when disabled. */
    private static volatile PrometheusExporter prometheusExporter;

    /** OTLP JSON metrics exporter; {@code null} when disabled. */
    private static volatile OtelJsonExporter otelExporter;

    /** Reverse index: source JAR → mod ID.  Updated alongside {@link #modClassLoaders}. */
    private static final Map<File, String> jarModIdIndex = new ConcurrentHashMap<>();

    // =================================================================================================================
    // Dependency Resolution (Requirement 3.2.1)
    // =================================================================================================================

    /**
     * Represents a mod discovered on the filesystem, acting as a node in the dependency graph.
     */
    static class ModNode {
        final String id;
        final String version;
        final RuntimeModuleKind kind;
        final File jar;
        final Set<String> dependencies = new LinkedHashSet<>();
        final Map<String, ParentLinkPolicy> dependencyLinkPolicies = new LinkedHashMap<>();
        final JsonObject metadata;
        final NormalizedModMetadata descriptor;

        ModNode(String id, String version, File jar, JsonObject metadata, NormalizedModMetadata descriptor) {
            this(id, version, RuntimeModuleKind.forModuleId(id), jar, metadata, descriptor);
        }

        ModNode(String id,
                String version,
                RuntimeModuleKind kind,
                File jar,
                JsonObject metadata,
                NormalizedModMetadata descriptor) {
            this.id = id;
            this.version = version;
            this.kind = kind == null ? RuntimeModuleKind.forModuleId(id) : kind;
            this.jar = jar;
            this.metadata = metadata == null ? new JsonObject() : metadata;
            this.descriptor = descriptor;
            
            // Parse dependencies; apply VirtualDependencyMap substitution so that
            // ecosystem IDs (fabric-api, forge, …) become bridge IDs at construction time.
            if (this.metadata.has("depends")) {
                this.metadata.getAsJsonObject("depends").entrySet().forEach(entry -> {
                    String substituted = VirtualDependencyMap.substituteChecked(entry.getKey());
                    dependencies.add(substituted);
                    ParentLinkPolicy policy = NormalizedModMetadata.dependencyReexport(entry.getValue())
                        ? ParentLinkPolicy.REEXPORT
                        : ParentLinkPolicy.LOCAL_ONLY;
                    dependencyLinkPolicies.merge(
                        substituted,
                        policy,
                        (left, right) -> left.allowsTransitiveAccess() || right.allowsTransitiveAccess()
                            ? ParentLinkPolicy.REEXPORT
                            : ParentLinkPolicy.LOCAL_ONLY
                    );
                });
            }
        }
    }

    /**
     * Scans for mods and resolves the loading order via PubGrub.
     * Topological sorting remains available only as an explicit degraded fallback.
     */
    static class DependencyResolver {

        /** The most recent plan produced by {@link #resolveOrder}. May be null if PubGrub fell back. */
        private static volatile ResolvedPlan lastPlan = null;

        /** Returns the most recently resolved plan, or {@code null} if unavailable. */
        public static ResolvedPlan lastResolvedPlan() {
            return lastPlan;
        }

        /** Convenience overload — discovers JARs internally. */
        public static List<ModNode> resolveOrder() {
            return resolveOrder(ModDiscovery.discoverJars());
        }

        /**
         * Resolves mod load order from a pre-discovered JAR list (ТЗ 3.2.1, Requirement 2).
         *
         * <p>New flow:
         * <ol>
         *   <li>Parse {@code fabric.mod.json} for each JAR → {@link ModNode}.</li>
         *   <li>Inject synthetic bridge stubs for every virtual dependency target.</li>
         *   <li>Build {@link PubGrubResolver.ModManifest} list with full version
         *       constraints (from the mod metadata) and virtual substitution.</li>
         *   <li>Run {@link PubGrubResolver#resolvePlan} → {@link ResolvedPlan}.</li>
         *   <li>Update each {@link ModNode#dependencies} set with the post-substitution
         *       edges from the plan — these become ClassLoader parent assignments.</li>
         *   <li>Return mods in {@link ResolvedPlan#loadOrder()} order so that every
         *       mod's parent ClassLoaders are constructed before the mod itself.</li>
         * </ol>
         *
         * <p>Falls back to Kahn's topological sort only when
         * {@code resolver.allow.fallback=true} is explicitly enabled.
         */
        public static List<ModNode> resolveOrder(List<File> jars) {
            Map<String, ModNode> nodes = new LinkedHashMap<>();

            // ── Step 1: parse Fabric / Forge manifests into one normalized model ─
            for (File jar : jars) {
                if (jar == null) continue;
                try {
                    Optional<NormalizedModMetadata> descriptor = ModMetadataParser.parse(jar);
                    if (descriptor.isPresent()) {
                        NormalizedModMetadata metadata = descriptor.get();
                        JsonObject json = metadata.manifest();
                        String modId = metadata.id();
                        String version = metadata.version();
                        ModNode node = new ModNode(modId, version, jar, json, metadata);
                        ModNode previous = nodes.putIfAbsent(modId, node);
                        if (previous != null) {
                            throw new IllegalStateException(
                                "Duplicate mod id '" + modId + "' discovered in " + previous.jar + " and " + jar
                            );
                        }
                        SecurityPolicy.registerModCapabilities(modId, json);
                    }
                } catch (UnsupportedOperationException unsupported) {
                    throw unsupported;
                } catch (Exception e) {
                    System.err.println("[Resolver] Failed to parse metadata for "
                        + jar.getName() + ": " + e.getMessage());
                }
            }

            // ── Step 2: inject synthetic bridge stubs ─────────────────────────
            // Every virtual dependency target that any mod uses must exist as a
            // node (with version "1.0.0") so PubGrub can satisfy it.
            injectBridgeStubs(nodes);

            // ── Step 3: build PubGrub manifest list ───────────────────────────
            // Version constraints come directly from the mod's "depends" block in
            // fabric.mod.json; IDs are substituted via VirtualDependencyMap.
            List<PubGrubResolver.ModManifest> manifests = buildManifests(nodes);

            // ── Step 4: run PubGrub resolution ────────────────────────────────
            // Also build the constraint map used by cross-loader type safety check.
            Map<String, String> depConstraints = buildDependencyConstraints(nodes);

            try {
                ResolvedPlan plan = new PubGrubResolver().resolvePlan(manifests);
                lastPlan = plan;

                System.out.println("\033[1;32m[PubGrub] Resolution successful.\033[0m");
                if (!plan.softMissingDependencies().isEmpty()) {
                    System.out.println("\033[1;33m[PubGrub] Soft-missing dependencies (gracefully degraded): "
                        + plan.softMissingDependencies() + "\033[0m");
                }
                System.out.println("[PubGrub] Load order: " + plan.loadOrder());

                // ── Cross-loader type safety ──────────────────────────────────
                // Verify that each resolved dependency version actually satisfies
                // the SemVer constraint declared by the dependant.
                CrossLoaderTypeChecker.validateAndThrow(plan, depConstraints);

                // ── Step 5: update ModNode.dependencies from the resolved plan ─
                // The plan's edges are post-substitution, so ClassLoader parents
                // will point at bridges instead of ecosystem-specific packages.
                for (ModNode node : nodes.values()) {
                    Set<String> resolved = plan.depsOf(node.id);
                    if (!resolved.isEmpty()) {
                        node.dependencies.clear();
                        node.dependencies.addAll(resolved);
                    }
                }

                // ── Step 6: return in PubGrub load order ──────────────────────
                List<ModNode> sortedList = new ArrayList<>();
                for (String id : plan.loadOrder()) {
                    ModNode n = nodes.get(id);
                    if (n != null) sortedList.add(n);
                }
                System.out.println("[Resolver] Mod load order: "
                    + sortedList.stream().map(n -> n.id).collect(Collectors.toList()));
                return sortedList;

            } catch (Exception pubGrubEx) {
                lastPlan = null;
                if (!RuntimeConfig.get().isResolverFallbackEnabled()) {
                    throw new IllegalStateException("PubGrub resolution failed in production mode", pubGrubEx);
                }
                System.err.println("\033[1;31m[PubGrub] Resolution failed ("
                    + pubGrubEx.getMessage()
                    + ") — falling back to Kahn's topological sort because resolver.allow.fallback=true.\033[0m");
            }

            // ── Fallback: Kahn's topological sort (used if PubGrub throws) ────
            return fallbackKahnsSort(nodes);
        }

        // ── Helpers ──────────────────────────────────────────────────────────

        /**
         * Injects a synthetic {@link ModNode} stub for every VIRTUAL bridge ID
         * (i.e. ids from {@link VirtualDependencyMap}) that is not yet present as
         * an actual node.  Real missing mods (e.g. a transitive dependency absent
         * from the user's intermed_mods folder) are intentionally NOT stubbed: a
         * stub with version "1.0.0" would never satisfy a constraint like [7.3.28,)
         * and would cause PubGrub to fail even when the fallback would handle the
         * situation correctly.
         */
        private static void injectBridgeStubs(Map<String, ModNode> nodes) {
            Set<String> bridgeIds = VirtualDependencyMap.allBridgeIds();
            for (String bridgeId : bridgeIds) {
                nodes.computeIfAbsent(bridgeId,
                    id -> new ModNode(
                        id,
                        VirtualDependencyMap.bridgeCompatibilityVersionForBridge(id),
                        RuntimeModuleKind.forModuleId(id),
                        null,
                        new JsonObject(),
                        null
                    ));
            }
        }

        /**
         * Builds the {@link PubGrubResolver.ModManifest} list from the current node map.
         * Version constraints are read from the mod's original metadata; the dependency
         * IDs use the already-substituted values from {@link ModNode#dependencies}.
         */
        private static List<PubGrubResolver.ModManifest> buildManifests(Map<String, ModNode> nodes) {
            List<PubGrubResolver.ModManifest> manifests = new ArrayList<>();
            for (ModNode node : nodes.values().stream().sorted(Comparator.comparing(mod -> mod.id)).toList()) {
                List<PubGrubResolver.ModManifest.DepSpec> deps = new ArrayList<>();

                if (node.metadata.has("depends")) {
                    // Use original metadata to get version constraints. The resolver
                    // performs virtual dependency substitution centrally so it can
                    // also normalize ecosystem-specific bridge constraints.
                    node.metadata.getAsJsonObject("depends").entrySet().forEach(entry -> {
                        String constraint = NormalizedModMetadata.dependencyConstraint(entry.getValue());
                        deps.add(new PubGrubResolver.ModManifest.DepSpec(entry.getKey(), constraint));
                    });
                } else {
                    // Synthetic bridge stub — use already-substituted dep IDs
                    for (String depId : node.dependencies) {
                        deps.add(new PubGrubResolver.ModManifest.DepSpec(depId, "*"));
                    }
                }
                deps.sort(Comparator
                    .comparing(PubGrubResolver.ModManifest.DepSpec::id)
                    .thenComparing(dep -> dep.versionConstraint() == null ? "*" : dep.versionConstraint()));
                manifests.add(new PubGrubResolver.ModManifest(node.id, node.version, deps));
            }
            return manifests;
        }

        /**
         * Builds a {@code "dependantId:dependencyId" → effectiveConstraint} map
         * for every dependency edge declared by every mod.
         *
         * <p>Uses {@link NormalizedModMetadata#dependencyConstraints()} rather than
         * the raw JSON {@code "depends"} key so that Forge manifests (which use a
         * different structure) are covered identically to Fabric manifests.
         *
         * <p>Each raw dependency ID is stored under <em>two</em> keys:
         * <ol>
         *   <li>The <b>substituted</b> ID (e.g. {@code "modA:intermed-fabric-bridge"}) —
         *       matches the post-substitution edges that PubGrub records in
         *       {@link ResolvedPlan#dependencyEdges()}.</li>
         *   <li>The <b>raw</b> ID (e.g. {@code "modA:fabric-api"}) — fallback for deep
         *       transitive chains where an intermediate node preserves the un-substituted
         *       edge after backtracking.</li>
         * </ol>
         */
        private static Map<String, String> buildDependencyConstraints(Map<String, ModNode> nodes) {
            Map<String, String> constraints = new LinkedHashMap<>();
            for (ModNode node : nodes.values().stream().sorted(Comparator.comparing(mod -> mod.id)).toList()) {
                if (node.descriptor == null) continue;
                node.descriptor.dependencyConstraints().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                    String rawDepId = entry.getKey();
                    String declaredConstraint = entry.getValue();
                    String substituted = VirtualDependencyMap.substituteChecked(rawDepId);
                    String effectiveConstraint = VirtualDependencyMap.effectiveConstraint(rawDepId, declaredConstraint);
                    // Primary key: post-substitution — aligns with plan.dependencyEdges()
                    constraints.put(node.id + ":" + substituted, effectiveConstraint);
                    // Fallback key: raw ID — handles cases where an intermediate node in a
                    // transitive chain retains the pre-substitution dep ID after backtracking
                    if (!substituted.equals(rawDepId)) {
                        constraints.putIfAbsent(node.id + ":" + rawDepId, effectiveConstraint);
                    }
                });
            }
            return constraints;
        }

        /**
         * Kahn's topological sort fallback used when PubGrub resolution fails.
         * Preserves the original behavior so broken manifests can still partially load.
         */
        private static List<ModNode> fallbackKahnsSort(Map<String, ModNode> nodes) {
            Map<String, List<String>> adjList  = new HashMap<>();
            Map<String, Integer>      inDegree = new HashMap<>();
            nodes.keySet().forEach(id -> { adjList.put(id, new ArrayList<>()); inDegree.put(id, 0); });

            for (ModNode node : nodes.values()) {
                for (String depId : node.dependencies) {
                    if (adjList.containsKey(depId)) {
                        adjList.get(depId).add(node.id);
                        inDegree.compute(node.id, (k, v) -> v + 1);
                    }
                }
            }

            Queue<String> queue = new PriorityQueue<>();
            inDegree.entrySet().stream().filter(e -> e.getValue() == 0)
                .map(Map.Entry::getKey).forEach(queue::add);

            List<ModNode> sorted = new ArrayList<>();
            while (!queue.isEmpty()) {
                String id = queue.poll();
                ModNode n = nodes.get(id);
                if (n != null) sorted.add(n);
                for (String neighbor : adjList.getOrDefault(id, Collections.emptyList())) {
                    if (inDegree.compute(neighbor, (k, v) -> v - 1) == 0) queue.add(neighbor);
                }
            }

            if (sorted.size() != nodes.size()) {
                System.err.println("\033[1;31m[Resolver] Cyclic dependency detected!\033[0m");
                nodes.keySet().stream()
                    .filter(id -> sorted.stream().noneMatch(n -> n.id.equals(id)))
                    .forEach(id -> System.err.println("[Resolver]  cycle: " + id));
            }
            System.out.println("[Resolver] Fallback load order: "
                + sorted.stream().map(n -> n.id).collect(Collectors.toList()));
            return sorted;
        }
    }

    // =================================================================================================================
    // LIFECYCLE PHASES
    // =================================================================================================================

    public static synchronized void startPhase0_Preloader() {
        if (phase0Started) return;
        phase0Started = true;
        System.out.println("\033[1;36m[Lifecycle] Phase 0: Initializing Remapping Dictionary...\033[0m");
        try {
            RuntimeConfig.reload();
            if (shouldSkipEmbeddedMixinBootstrap()) {
                System.out.println("[Lifecycle] Embedded mixin bootstrap skipped for Fabric agent path.");
            } else {
                prepareMixinClassSource();
                MixinTransmogrifier.bootstrapTransmogrification();
                InterMedMixinBootstrap.init();
            }
            DICTIONARY.clear();
            InterMedRemapper.installDictionary(DICTIONARY);

            // Initialise AOT cache directory early so Phase 1 mixin pipeline can use it
            org.intermed.core.cache.AOTCacheManager.initialize();

            // Connect to local SQLite for capability persistence and AOT index
            org.intermed.core.db.DatabaseManager.initialize();

            // ── Mapping resolution (priority order) ──────────────────────────
            // 1. Explicit system-property override (launcher / test environments)
            String override = System.getProperty("intermed.mappings.tiny");
            Path tinyPath = (override != null) ? Paths.get(override) : null;

            // 2. mappings/ directory next to the InterMed JAR file
            if (tinyPath == null) {
                try {
                    Path jarDir = Paths.get(
                        LifecycleManager.class.getProtectionDomain().getCodeSource().getLocation().toURI()
                    ).getParent();
                    Path candidate = jarDir.resolve("mappings/mappings.tiny");
                    if (java.nio.file.Files.exists(candidate)) tinyPath = candidate;
                } catch (Exception ignored) {}
            }

            // 3. Project-root relative path (dev / IDE environment)
            if (tinyPath == null) {
                Path devPath = Paths.get("mappings/mappings.tiny");
                if (java.nio.file.Files.exists(devPath)) tinyPath = devPath;
            }

            // 4. ~/.intermed/mappings/<mcVersion>/mappings.tiny — user-supplied Fabric
            //    intermediary mappings cache (works for Forge + Fabric mods setups).
            if (tinyPath == null) {
                String mcVer = parseLaunchArg("--fml.mcVersion");
                if (mcVer == null) mcVer = parseLaunchArg("--version");
                if (mcVer != null) {
                    Path candidate = Paths.get(System.getProperty("user.home"),
                        ".intermed", "mappings", mcVer, "mappings.tiny");
                    if (java.nio.file.Files.exists(candidate)) tinyPath = candidate;
                }
            }

            // 5. Auto-detect the Mojang mapping file from the Minecraft library
            //    directory. Forge (1.17+) ships client-<mcVersion>-<mcpVersion>-mappings.txt
            //    which covers obfuscated → Mojang class names.  No tiny file is required for
            //    this path; the dictionary will still serve Forge class-name normalisation.
            Path autoSrgPath = null;
            {
                String mcVer  = parseLaunchArg("--fml.mcVersion");
                String mcpVer = parseLaunchArg("--fml.mcpVersion");
                if (mcVer != null && mcpVer != null) {
                    autoSrgPath = locateMojangMappings(mcVer, mcpVer);
                }
                // Fallback: scan java.class.path for client-*-srg.jar and derive path
                if (autoSrgPath == null) {
                    autoSrgPath = scanClasspathForMojangMappings();
                }
            }

            // ── Load what we found ────────────────────────────────────────────
            if (tinyPath != null) {
                // Prefer companion client.txt in same dir; fall back to auto-detected Mojang map
                Path srgCandidate = tinyPath.getParent().resolve("client.txt");
                Path srgPath = java.nio.file.Files.exists(srgCandidate) ? srgCandidate
                             : (autoSrgPath != null ? autoSrgPath : null);
                DictionaryParser.parse(tinyPath, srgPath, DICTIONARY);
                InterMedRemapper.installDictionary(DICTIONARY);
                System.out.println("\033[1;32m[Lifecycle] Dictionary loaded: "
                    + DICTIONARY.getClassCount() + " classes.\033[0m");
            } else if (autoSrgPath != null) {
                // No Fabric tiny file, but we have the Mojang mapping.
                // Load SRG-only dictionary (obfuscated → Mojang).  Fabric mods that
                // use Intermediary names won't be remapped until a tiny file is placed
                // at ~/.intermed/mappings/<mcVersion>/mappings.tiny.
                DictionaryParser.parseSrgOnly(autoSrgPath, DICTIONARY);
                InterMedRemapper.installDictionary(DICTIONARY);
                System.out.println("\033[1;32m[Lifecycle] Dictionary loaded (SRG→Mojang only, "
                    + DICTIONARY.getClassCount() + " classes). Place Fabric Intermediary "
                    + "mappings at ~/.intermed/mappings/<mcVersion>/mappings.tiny for full support.\033[0m");
            } else {
                System.out.println("\033[1;33m[Lifecycle] WARNING: No mappings file found. "
                    + "Remapping disabled. Set -Dintermed.mappings.tiny=<path> or place "
                    + "mappings at ~/.intermed/mappings/<mcVersion>/mappings.tiny.\033[0m");
            }
        } catch (Exception e) {
            System.err.println("[Lifecycle] Dictionary Failure: " + e.getMessage());
        }
    }

    // ── Mapping auto-detection helpers ────────────────────────────────────────

    /**
     * Parses a named argument from the JVM's launch command
     * (available via {@code sun.java.command}), e.g. {@code --fml.mcVersion}.
     * Returns {@code null} if the argument is not present.
     */
    private static String parseLaunchArg(String argName) {
        String cmd = System.getProperty("sun.java.command", "");
        String[] tokens = cmd.split("\\s+");
        for (int i = 0; i < tokens.length - 1; i++) {
            if (argName.equals(tokens[i])) return tokens[i + 1];
        }
        // Also check Java system properties set by Forge/FML
        String propKey = argName.startsWith("--") ? argName.substring(2) : argName;
        String sysProp = System.getProperty(propKey);
        if (sysProp != null) return sysProp;
        return null;
    }

    /**
     * Locates {@code client-{mcVersion}-{mcpVersion}-mappings.txt} in the
     * standard Minecraft library directory.  Checks the user-home
     * {@code .minecraft} folder and any explicit {@code --gameDir} argument.
     */
    private static Path locateMojangMappings(String mcVersion, String mcpVersion) {
        String fileName = "client-" + mcVersion + "-" + mcpVersion + "-mappings.txt";
        String subPath  = "libraries/net/minecraft/client/" + mcVersion + "-" + mcpVersion;

        List<Path> roots = new ArrayList<>();

        // Standard ~/.minecraft
        roots.add(Paths.get(System.getProperty("user.home"), ".minecraft"));

        // --gameDir (TLauncher / MultiMC may use non-standard locations)
        String gameDir = parseLaunchArg("--gameDir");
        if (gameDir != null) roots.add(Paths.get(gameDir));

        for (Path root : roots) {
            Path candidate = root.resolve(subPath).resolve(fileName);
            if (java.nio.file.Files.exists(candidate)) {
                System.out.println("[Lifecycle] Auto-detected Mojang mappings: " + candidate);
                return candidate;
            }
        }
        return null;
    }

    /**
     * Scans {@code java.class.path} for a JAR whose name matches
     * {@code client-*-srg.jar} and returns the sibling
     * {@code client-*-mappings.txt} file if it exists.
     */
    private static Path scanClasspathForMojangMappings() {
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(java.io.File.pathSeparator)) {
            if (entry.contains("client-") && entry.endsWith("-srg.jar")) {
                Path srgJar = Paths.get(entry);
                String mappingsName = srgJar.getFileName().toString()
                    .replace("-srg.jar", "-mappings.txt");
                Path candidate = srgJar.getParent().resolve(mappingsName);
                if (java.nio.file.Files.exists(candidate)) {
                    System.out.println("[Lifecycle] Auto-detected Mojang mappings (classpath scan): "
                        + candidate);
                    return candidate;
                }
            }
        }
        return null;
    }

    public static synchronized void startPhase1_BackgroundAssembly() {
        if (phase1Started) return;
        phase1Started = true;

        BackgroundPreparator.getInstance().submitTask(new PreparatorTask() {
            @Override public int getPriority() { return 1; }
            @Override public String getName() { return "DAG Assembly Engine"; }
            @Override public void execute() {
                assembleNow();
            }
        });
    }

    public static synchronized void assembleNow() {
        System.out.println("\033[1;36m[Lifecycle] Phase 1: Building ClassLoader DAG...\033[0m");

        tearDownDag();

        ClassLoader platformClassLoader = InterMedKernel.class.getClassLoader();
        injectSystemMixins(platformClassLoader);

        // ── Step 1: Discover all JARs once (mods + libraries) ────────────────
        ModDiscovery.DiscoveryLayout discoveryLayout = ModDiscovery.discoverLayout();
        List<File> allJars = discoveryLayout.jars();

        // ── Step 2: Resolve mod load order using the pre-discovered JAR list ──
        List<ModNode> sortedMods = DependencyResolver.resolveOrder(allJars);
        ResolvedPlan resolvedPlan = DependencyResolver.lastResolvedPlan();
        validateDagPlan(sortedMods, resolvedPlan);
        RuntimeModIndex.registerAll(sortedMods.stream()
            .map(mod -> mod.descriptor)
            .filter(Objects::nonNull)
            .toList());
        loadPersistedCapabilities();
        SecurityPolicy.loadExternalProfiles(RuntimeConfig.get().getConfigDir());
        RuntimeModIndex.allMods().forEach(PolyglotSandboxManager::registerPlan);

        // ── Step 3: Welsh-Powell library clustering (ТЗ 3.2.1, Requirement 1) ─
        // Library JARs inherit the visibility scope of the mod that owns the
        // enclosing JiJ archive. This keeps private implementation libraries out
        // of unrelated mod graphs while still allowing global loose JARs.
        Map<Path, String> ownerIdsByJar = buildJarOwnerIndex(sortedMods);
        LibraryConflictGraph conflictGraph = LibraryDiscovery.discover(
            allJars,
            jar -> resolveLibraryVisibilityDomain(jar, discoveryLayout, ownerIdsByJar)
        );
        Map<Integer, List<LibraryNode>> clusters = WelshPowellClusterer.cluster(conflictGraph);

        List<ShaderClassLoader> shaders = new ArrayList<>();
        for (Map.Entry<Integer, List<LibraryNode>> entry : clusters.entrySet()) {
            String visibilityDomain = entry.getValue().isEmpty()
                ? LibraryDiscovery.GLOBAL_VISIBILITY_DOMAIN
                : entry.getValue().get(0).visibilityDomain;
            ShaderClassLoader shader = new ShaderClassLoader(
                entry.getKey(),
                visibilityDomain,
                entry.getValue(),
                Collections.emptySet(),   // shader loaders are root DAG nodes
                platformClassLoader
            );
            shaders.add(shader);
        }
        shaderLoaders.addAll(shaders);
        System.out.printf("[DAG] %d shader loader(s) created from %d library JAR(s).%n",
            shaders.size(), conflictGraph.size());

        // ── Step 4: Build dedicated bridge nodes first ────────────────────────
        List<ModNode> bridgeNodes = sortedMods.stream()
            .filter(mod -> isBridgeNode(mod.id))
            .toList();
        for (ModNode bridge : bridgeNodes) {
            try {
                Map<LazyInterMedClassLoader, ParentLinkPolicy> parentLoaders = resolveParentLinks(bridge);
                parentLoaders.putAll(selectShaderParentLinks(bridge, shaders));

                LazyInterMedClassLoader bridgeLoader = createBridgeLoader(
                    bridge.id, bridge.kind, parentLoaders, platformClassLoader
                );
                bridgeClassLoaders.put(bridge.id, bridgeLoader);
                System.out.println("[DAG] Bridge node ready: " + bridge.id);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to build bridge node " + bridge.id, e);
            }
        }

        // ── Step 5: Build one InterMedClassLoader per concrete mod ────────────
        for (ModNode mod : sortedMods) {
            try {
                if (isBridgeNode(mod.id)) {
                    continue;
                }
                System.out.println("\033[1;35m[DAG] Creating ClassLoader for: " + mod.id + "\033[0m");

                // a) Direct-dependency parents (other mods this mod declared)
                Map<LazyInterMedClassLoader, ParentLinkPolicy> parentLoaders = resolveParentLinks(mod);

                // b) Only globally shared shaders plus shaders owned by this mod
                //    become direct parents. Unrelated mod-private libraries stay isolated.
                parentLoaders.putAll(selectShaderParentLinks(mod, shaders));

                // c) Create the mod's dedicated ClassLoader node
                InterMedClassLoader modLoader = new InterMedClassLoader(
                    mod.id, mod.jar, parentLoaders, platformClassLoader
                );

                // d) Register bytecode transformer pipeline
                MixinTransformer.registerModMixins(mod.jar, mod.descriptor);
                installProductionBytecodePipeline(modLoader, true);

                // e) Register in the DAG and awaken the mod
                modClassLoaders.put(mod.id, modLoader);
                if (mod.jar != null) jarModIdIndex.put(mod.jar, mod.id);

            } catch (Exception e) {
                throw new IllegalStateException("Failed to build node for " + mod.id, e);
            }
        }

        wireDeclaredPeers(sortedMods);
        validateConstructedDag(sortedMods, resolvedPlan);
        sortedMods.stream()
            .filter(mod -> !isBridgeNode(mod.id))
            .forEach(mod -> {
                LazyInterMedClassLoader loader = modClassLoaders.get(mod.id);
                if (loader != null) {
                    awakenModSafe(loader, mod.metadata);
                }
            });

        logDagSummary();
        System.out.println("\033[1;32m[Lifecycle] ClassLoader DAG constructed. Universal Bridge Active.\033[0m");

        // Freeze the virtual registry: snapshots ConcurrentHashMap → flat array,
        // and patches all pending invokedynamic GET call sites to direct array reads.
        VirtualRegistryService.freeze();
        rebuildClassHierarchyIndex();
        org.intermed.core.bridge.InterMedNetworkBridge.registerRegistrySyncChannel();

        // ── Hot-reload watcher (optional, disabled by default) ────────────────
        // Set the system property "intermed.hotreload=true" to activate.
        if ("true".equalsIgnoreCase(System.getProperty("intermed.hotreload"))) {
            try {
                Path modsPath = RuntimeConfig.get().getModsDir();
                hotReloadManager = HotReloadManager.start(modsPath, LifecycleManager::applyHotReloadDeltas);
            } catch (Exception e) {
                System.err.println("[HotReload] Failed to start watcher: " + e.getMessage());
            }
        }

        // ── Metrics exporters ─────────────────────────────────────────────────
        RuntimeConfig cfg = RuntimeConfig.get();
        if (cfg.isPrometheusEnabled()) {
            try {
                prometheusExporter = PrometheusExporter.start(cfg.getPrometheusPort());
            } catch (Exception e) {
                System.err.println("[Metrics] Failed to start Prometheus exporter: " + e.getMessage());
            }
        }
        if (cfg.isOtelEnabled()) {
            try {
                otelExporter = OtelJsonExporter.start(cfg.getOtelOutputPath());
            } catch (Exception e) {
                System.err.println("[Metrics] Failed to start OTLP exporter: " + e.getMessage());
            }
        }

        // ── Forge → Fabric event bridges ─────────────────────────────────────
        // InterMedEventBridge covers tick + lifecycle + world + player events,
        // and releases BOOT_BARRIER on ServerStarted. ForgeEventProxy covers
        // render-thread HUD callbacks and deferred main-thread task draining.
        // FabricLifecycleBridge is NOT registered here — InterMedEventBridge
        // is a strict superset that avoids double-firing lifecycle/world events.
        org.intermed.core.bridge.InterMedEventBridge.initialize();
        org.intermed.core.bridge.events.ForgeEventProxy.hookIntoForge();
    }

    /**
     * Processes a batch of hot-reload deltas produced by {@link HotReloadManager}.
     *
     * <p>For {@link HotReloadManager.DagDelta.Kind#ADDED_OR_MODIFIED} entries the
     * mod's JAR is re-parsed and a new or replacement ClassLoader node is inserted
     * into the active DAG.  The mod's {@code ModInitializer} entrypoints are
     * re-invoked only for genuinely <em>new</em> mod IDs (first-time loads).
     *
     * <p>For {@link HotReloadManager.DagDelta.Kind#REMOVED} entries the ClassLoader
     * is evicted and subsequent class-load requests will fail with
     * {@link ClassNotFoundException}.  Already-loaded instances remain alive until GC.
     *
     * <p>This method is intentionally {@code synchronized} to prevent concurrent DAG
     * mutations from racing with each other or with normal class-load traffic.
     */
    public static synchronized void applyHotReloadDeltas(List<HotReloadManager.DagDelta> deltas) {
        if (deltas == null || deltas.isEmpty()) return;
        System.out.printf("[HotReload] Applying %d delta(s)%n", deltas.size());

        ClassLoader platformClassLoader = InterMedKernel.class.getClassLoader();

        for (HotReloadManager.DagDelta delta : deltas) {
            File file = delta.file();

            if (delta.kind() == HotReloadManager.DagDelta.Kind.REMOVED) {
                // Evict via the JAR→modId reverse index populated when loaders are created.
                String evictedId = jarModIdIndex.remove(file);
                if (evictedId != null) {
                    modClassLoaders.remove(evictedId);
                    System.out.printf("[HotReload] Evicted ClassLoader for '%s'%n", evictedId);
                } else {
                    System.err.printf("[HotReload] Removed JAR '%s' had no registered mod ID — skipping%n",
                        file.getName());
                }
                continue;
            }

            // ADDED_OR_MODIFIED: re-parse the JAR and rebuild the ClassLoader node.
            try {
                java.util.Optional<org.intermed.core.metadata.NormalizedModMetadata> metaOpt =
                    org.intermed.core.metadata.ModMetadataParser.parse(file);
                if (metaOpt.isEmpty()) {
                    System.err.printf("[HotReload] No manifest in '%s' — skipping%n", file.getName());
                    continue;
                }
                org.intermed.core.metadata.NormalizedModMetadata meta = metaOpt.get();
                String modId = meta.id();
                boolean isNew = !modClassLoaders.containsKey(modId);

                // Minimal parent set: all bridge loaders, each with LOCAL_ONLY visibility.
                Map<LazyInterMedClassLoader, ParentLinkPolicy> parentLinks = new java.util.LinkedHashMap<>();
                bridgeClassLoaders.forEach((id, loader) ->
                    parentLinks.put(loader, ParentLinkPolicy.LOCAL_ONLY));

                InterMedClassLoader modLoader = new InterMedClassLoader(
                    modId, file, parentLinks, platformClassLoader
                );
                MixinTransformer.registerModMixins(file, meta);
                installProductionBytecodePipeline(modLoader, true);
                modClassLoaders.put(modId, modLoader);
                jarModIdIndex.put(file, modId);
                RuntimeModIndex.register(meta);
                wireDeclaredPeers(java.util.List.of(
                    new ModNode(modId, meta.version(), file, meta.manifest(), meta)
                ));

                if (isNew) {
                    System.out.printf("[HotReload] New mod '%s@%s' loaded%n", modId, meta.version());
                    awakenModSafe(modLoader, meta.manifest());
                } else {
                    System.out.printf("[HotReload] Mod '%s@%s' ClassLoader replaced (existing static state unchanged — full restart needed for that)%n",
                        modId, meta.version());
                }
            } catch (Exception e) {
                System.err.printf("[HotReload] Failed to reload '%s': %s%n", file.getName(), e.getMessage());
                e.printStackTrace();
            }
        }
        System.out.println("[HotReload] Delta application complete.");
        rebuildClassHierarchyIndex();
    }

    public static synchronized void prepareMixinClassSource() {
        registerMixinClassSource();
    }

    public static synchronized void resetForTests() {
        phase0Started = false;
        phase1Started = false;
        DependencyResolver.lastPlan = null;
        HotReloadManager mgr = hotReloadManager;
        hotReloadManager = null;
        if (mgr != null) mgr.stop();

        PrometheusExporter prom = prometheusExporter;
        prometheusExporter = null;
        if (prom != null) prom.close();

        OtelJsonExporter otel = otelExporter;
        otelExporter = null;
        if (otel != null) otel.close();

        MetricsRegistry.resetForTests();
        jarModIdIndex.clear();
        tearDownDag();
        MAIN_THREAD_TASKS.clear();
        RuntimeModIndex.clear();
        BridgeRuntime.reset();
        InterMedMixinBootstrap.resetForTests();
        org.intermed.core.mixin.InterMedPlatformAgent.resetForTests();
        org.intermed.core.mixin.MixinTransformer.resetForTests();
        org.intermed.core.mixin.MixinTransmogrifier.resetForTests();
        org.intermed.mixin.service.InterMedGlobalPropertyService.resetForTests();
        org.intermed.core.bridge.InterMedNetworkBridge.resetForTests();
        org.intermed.core.bridge.ForgeNetworkBridge.resetForTests();
        org.intermed.core.bridge.NeoForgeNetworkBridge.resetForTests();
        org.intermed.core.bridge.InterMedEventBridge.resetForTests();
        org.intermed.core.security.SecurityPolicy.resetForTests();
        org.intermed.core.security.CapabilityManager.resetForTests();
        org.intermed.core.registry.VirtualRegistryService.resetForTests();
        RiskyModRegistry.resetForTests();
        org.intermed.core.monitor.ObservabilityMonitor.resetForTests();
        PolyglotSandboxManager.resetForTests();
    }

    public static Map<String, LazyInterMedClassLoader> getModClassLoaders() {
        return Collections.unmodifiableMap(modClassLoaders);
    }

    public static synchronized boolean tryInstallWeakEdge(LazyInterMedClassLoader requester, String binaryClassName) {
        if (requester == null
            || binaryClassName == null
            || binaryClassName.isBlank()
            || !RuntimeConfig.get().isClassloaderDynamicWeakEdgesEnabled()) {
            return false;
        }

        Optional<NormalizedModMetadata> requesterMetadata = RuntimeModIndex.get(requester.getNodeId());
        if (requesterMetadata.isEmpty()) {
            return false;
        }

        for (String dependencyId : requesterMetadata.get().softDependencyIds()) {
            LazyInterMedClassLoader provider = resolveNodeLoader(dependencyId);
            if (provider == null
                || provider == requester
                || requester.getParents().contains(provider)
                || requester.getPeers().contains(provider)) {
                continue;
            }

            WeakPeerPolicy policy = weakPeerPolicyFor(dependencyId);
            if (policy == null || !policy.matchesPrefix(binaryClassName)) {
                continue;
            }

            if (!policy.allows(binaryClassName, provider)) {
                continue;
            }

            requester.addWeakPeer(provider, policy);
            return true;
        }
        return false;
    }

    public static synchronized void registerLoaderForTests(String nodeId,
                                                           LazyInterMedClassLoader loader,
                                                           NormalizedModMetadata metadata) {
        if (nodeId == null || loader == null) {
            throw new IllegalArgumentException("nodeId/loader");
        }
        if (isBridgeNode(nodeId)) {
            bridgeClassLoaders.put(nodeId, loader);
        } else {
            modClassLoaders.put(nodeId, loader);
        }
        if (metadata != null) {
            RuntimeModIndex.register(metadata);
        }
        rebuildClassHierarchyIndex();
    }

    public static int dispatchMainThreadTasks() {
        Runnable task;
        int count = 0;
        while ((task = MAIN_THREAD_TASKS.poll()) != null) {
            task.run();
            count++;
        }
        return count;
    }

    private static void injectSystemMixins(ClassLoader loader) {
         if (shouldSkipEmbeddedMixinBootstrap()) {
            System.out.println("[InterMed] Native system mixin injection skipped for Fabric agent path.");
            return;
         }
         try {
            InterMedPlatformAgent.prepareFromLifecycle();
            InterMedMixinBootstrap.init();
            System.out.println("\033[1;36m[InterMed] Native System Mixin injected!\033[0m");
        } catch (Throwable e) {
            System.err.println("[InterMed] Failed to inject system mixins: " + e.getMessage());
        }
    }

    private static boolean shouldSkipEmbeddedMixinBootstrap() {
        return Boolean.getBoolean("intermed.deferDeepBootstrap");
    }

    private static void awakenModSafe(LazyInterMedClassLoader loader, JsonObject metadata) {
        String modId = loader.getNodeId();

        if (!metadata.has("entrypoints")) {
            // WASM-only mods declare no Java entrypoints — route them directly
            // through the sandbox if a WASM plan was registered during DAG build.
            Optional<NormalizedModMetadata> normalizedMeta = RuntimeModIndex.get(modId);
            if (normalizedMeta.isPresent()) {
                SandboxPlan plan = PolyglotSandboxManager.getPlan(modId)
                    .orElseGet(() -> PolyglotSandboxManager.registerPlan(normalizedMeta.get()));
                if (plan.effectiveMode() == SandboxMode.WASM && plan.isSandboxed()) {
                    awakenWasmMod(modId, plan, normalizedMeta.get());
                }
            }
            return;
        }

        JsonObject entrypoints = metadata.getAsJsonObject("entrypoints");
        
        // Initialize common entrypoints on the background thread
        initializeEntrypoints(loader, entrypoints, "main", ModInitializer.class, ModInitializer::onInitialize);
        
        // Schedule environment-specific entrypoints to run on the game thread.
        if (RuntimeConfig.get().getEnvironmentType() == net.fabricmc.api.EnvType.CLIENT) {
            MAIN_THREAD_TASKS.add(() -> initializeEntrypoints(
                loader,
                entrypoints,
                "client",
                ClientModInitializer.class,
                ClientModInitializer::onInitializeClient
            ));
        } else {
            MAIN_THREAD_TASKS.add(() -> initializeEntrypoints(
                loader,
                entrypoints,
                "server",
                DedicatedServerModInitializer.class,
                DedicatedServerModInitializer::onInitializeServer
            ));
        }
    }
    
    private static void awakenWasmMod(String modId, SandboxPlan plan, NormalizedModMetadata meta) {
        System.out.printf("[DAG] %s -> WASM entrypoint '%s' (module: %s)%n",
            modId, plan.entrypoint(), plan.modulePath());
        SandboxExecutionResult result = PolyglotSandboxManager.executeSandboxedEntrypoint(
            meta, "main", plan.entrypoint(), "invoke");
        if (result.success()) {
            BridgeRuntime.registerEntrypoint(modId, "main", result.target(),
                new SandboxedModInitializer(result));
            System.out.println("\033[1;32m[DAG] " + modId
                + " -> WASM INITIALIZED (" + result.target() + ")\033[0m");
        } else {
            throw new IllegalStateException("WASM entrypoint failed for " + modId + ": " + result.message());
        }
    }

    private static <T> void initializeEntrypoints(LazyInterMedClassLoader loader, JsonObject entrypoints, String type, Class<T> interfaceClass, EntrypointRunner<T> runner) {
        if (!entrypoints.has(type)) return;

        JsonArray points = entrypoints.getAsJsonArray(type);
        for (JsonElement point : points) {
            String entrypointString;
            String mode = "invoke";
            if (point.isJsonPrimitive()) {
                entrypointString = point.getAsString();
            } else {
                JsonObject pointObject = point.getAsJsonObject();
                entrypointString = pointObject.get("value").getAsString();
                if (pointObject.has("mode")) {
                    mode = pointObject.get("mode").getAsString();
                }
            }
            try {
                if (tryInitializeSandboxedEntrypoint(loader.getNodeId(), type, entrypointString, mode)) {
                    continue;
                }
                Class<?> clazz = loader.loadClass(entrypointString);
                Object instance = clazz.getDeclaredConstructor().newInstance();

                boolean initialized = "construct".equalsIgnoreCase(mode);
                if (!initialized && interfaceClass.isAssignableFrom(clazz)) {
                    runner.run(interfaceClass.cast(instance));
                    initialized = true;
                } else if (!initialized) {
                    String fallbackMethod = resolveEntrypointMethodName(type);
                    try {
                        clazz.getMethod(fallbackMethod).invoke(instance);
                        initialized = true;
                    } catch (NoSuchMethodException e) {
                        throw new IllegalStateException("Entrypoint does not implement required method " + fallbackMethod, e);
                    }
                }

                if (initialized) {
                    BridgeRuntime.registerEntrypoint(loader.getNodeId(), type, entrypointString, instance);
                    System.out.println("\033[1;32m[DAG] " + loader.getNodeId() + " -> " + type.toUpperCase()
                        + " INITIALIZED (" + mode + ")\033[0m");
                }
            } catch (Throwable t) {
                throw new IllegalStateException(
                    "Failed to initialize " + type + " entrypoint '" + entrypointString
                        + "' for mod '" + loader.getNodeId() + "'",
                    t
                );
            }
        }
    }

    private static boolean tryInitializeSandboxedEntrypoint(String modId,
                                                            String type,
                                                            String entrypointString,
                                                            String mode) {
        Optional<NormalizedModMetadata> metadata = RuntimeModIndex.get(modId);
        if (metadata.isEmpty()) {
            return false;
        }

        SandboxPlan plan = PolyglotSandboxManager.getPlan(modId)
            .orElseGet(() -> PolyglotSandboxManager.registerPlan(metadata.get()));
        if (!plan.isSandboxed()) {
            return false;
        }

        SandboxExecutionResult result = PolyglotSandboxManager.executeSandboxedEntrypoint(
            metadata.get(),
            type,
            entrypointString,
            mode
        );
        if (!result.success()) {
            if (result.nativeFallbackRecommended()) {
                System.out.println("\033[1;33m[DAG] " + modId + " -> " + type.toUpperCase()
                    + " SANDBOX FALLBACK (" + describeSandboxFailure(result) + ")\033[0m");
                return false;
            }
            throw new IllegalStateException(
                "Sandboxed " + type + " entrypoint failed for mod '" + modId + "': "
                    + describeSandboxFailure(result)
            );
        }

        Object handle = switch (type) {
            case "main" -> new SandboxedModInitializer(result);
            case "client" -> new SandboxedClientModInitializer(result);
            case "server" -> new SandboxedServerModInitializer(result);
            default -> result;
        };
        BridgeRuntime.registerEntrypoint(modId, type, result.target(), handle);
        System.out.println("\033[1;32m[DAG] " + modId + " -> " + type.toUpperCase()
            + " SANDBOX INITIALIZED (" + plan.effectiveMode().externalName() + ")\033[0m");
        return true;
    }

    private static String describeSandboxFailure(SandboxExecutionResult result) {
        StringBuilder builder = new StringBuilder(result.message());
        if (!result.planReason().isBlank()) {
            builder.append(" [plan=").append(result.planReason()).append(']');
        }
        if (!result.runtimeDiagnostics().isBlank()) {
            builder.append(" [diagnostics=").append(result.runtimeDiagnostics()).append(']');
        }
        return builder.toString();
    }

    private static String resolveEntrypointMethodName(String type) {
        return switch (type) {
            case "main" -> "onInitialize";
            case "client" -> "onInitializeClient";
            case "server" -> "onInitializeServer";
            default -> throw new IllegalArgumentException("Unsupported entrypoint type: " + type);
        };
    }
    
    @FunctionalInterface
    interface EntrypointRunner<T> {
        void run(T instance) throws Exception;
    }

    // =================================================================================================================
    // DAG Query API (called by InterMedMixinService)
    // =================================================================================================================

    public static boolean isClassLoadedInDAG(String name) {
        return allDagLoaders().stream()
            .anyMatch(loader -> loader.hasLoadedClass(name));
    }

    public static Class<?> findClassInDAG(String name) throws ClassNotFoundException {
        for (LazyInterMedClassLoader loader : allDagLoaders()) {
            try { return loader.loadClass(name); } catch (ClassNotFoundException ignored) {}
        }
        throw new ClassNotFoundException(name);
    }

    public static byte[] getClassBytesFromDAG(String name) {
        for (LazyInterMedClassLoader loader : allDagLoaders()) {
            byte[] localBytes = loader.readLocalClassBytes(name);
            if (localBytes != null) {
                return localBytes;
            }
        }
        return null;
    }

    private static void validateDagPlan(List<ModNode> sortedMods, ResolvedPlan plan) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> allIds = sortedMods.stream()
            .map(node -> node.id)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, Integer> orderIndex = new LinkedHashMap<>();
        for (int i = 0; i < sortedMods.size(); i++) {
            orderIndex.put(sortedMods.get(i).id, i);
        }

        Set<String> softMissing = (plan != null)
            ? plan.softMissingDependencies()
            : Collections.emptySet();

        for (ModNode mod : sortedMods) {
            if (!seen.add(mod.id)) {
                throw new IllegalStateException("Duplicate DAG node id: " + mod.id);
            }
            if (mod.dependencies.contains(mod.id)) {
                throw new IllegalStateException("Mod depends on itself: " + mod.id);
            }
            RuntimeModuleKind expectedKind = RuntimeModuleKind.forModuleId(mod.id);
            if (mod.kind != expectedKind) {
                throw new IllegalStateException("Node '" + mod.id + "' declared kind " + mod.kind
                    + " but canonical kind is " + expectedKind);
            }
            if (mod.kind.isBuiltInRuntime() && mod.jar != null) {
                throw new IllegalStateException("Built-in runtime node must not point at an external jar: " + mod.id);
            }
            for (String dep : mod.dependencies) {
                if (softMissing.contains(dep)) {
                    // Soft-missing: declared but no provider found — warn and skip
                    System.err.printf(
                        "\033[1;33m[DAG] WARNING: mod '%s' declared dependency '%s' which is unavailable. "
                        + "Loading will proceed without it.\033[0m%n", mod.id, dep);
                } else if (!allIds.contains(dep)) {
                    if (plan == null && RuntimeConfig.get().isResolverFallbackEnabled()) {
                        System.err.printf(
                            "\033[1;33m[DAG] WARNING: fallback plan for '%s' references missing dependency '%s'.\033[0m%n",
                            mod.id, dep
                        );
                        continue;
                    }
                    throw new IllegalStateException(
                        "Resolved DAG references missing dependency '" + dep + "' for mod '" + mod.id + "'"
                    );
                } else if (orderIndex.get(dep) >= orderIndex.get(mod.id)) {
                    throw new IllegalStateException(
                        "Non-deterministic load order: dependency '" + dep + "' must precede '" + mod.id + "'"
                    );
                }
            }
        }

        if (plan == null) {
            return;
        }

        if (!plan.loadOrder().equals(sortedMods.stream().map(node -> node.id).toList())) {
            throw new IllegalStateException(
                "DAG nodes differ from the resolved load order. plan=" + plan.loadOrder()
                    + ", dag=" + sortedMods.stream().map(node -> node.id).toList()
            );
        }

        for (String nodeId : allIds) {
            RuntimeModuleKind planKind = plan.moduleKindOf(nodeId);
            RuntimeModuleKind actualKind = RuntimeModuleKind.forModuleId(nodeId);
            if (planKind != actualKind) {
                throw new IllegalStateException(
                    "Resolved plan kind mismatch for '" + nodeId + "': plan=" + planKind + ", actual=" + actualKind
                );
            }
            if (!plan.resolvedVersions().containsKey(nodeId)) {
                throw new IllegalStateException("Resolved plan omitted DAG node version for " + nodeId);
            }
        }
    }

    private static void validateConstructedDag(List<ModNode> sortedMods, ResolvedPlan plan) {
        Map<String, ModNode> nodesById = sortedMods.stream()
            .collect(Collectors.toMap(node -> node.id, node -> node, (left, right) -> left, LinkedHashMap::new));

        for (LazyInterMedClassLoader loader : allDagLoaders()) {
            loader.validateTopology();
        }

        for (ModNode node : sortedMods) {
            LazyInterMedClassLoader loader = resolveNodeLoader(node.id);
            if (loader == null) {
                throw new IllegalStateException("No ClassLoader exists for DAG node " + node.id);
            }

            Set<String> parentIds = loader.getParents().stream()
                .map(LazyInterMedClassLoader::getNodeId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> expectedParents = node.dependencies.stream()
                .filter(nodesById::containsKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            if (!parentIds.containsAll(expectedParents)) {
                throw new IllegalStateException(
                    "ClassLoader parents for '" + node.id + "' are incomplete. expected="
                        + expectedParents + ", actual=" + parentIds
                );
            }
        }

        if (plan == null) {
            return;
        }

        for (Map.Entry<String, Set<String>> entry : plan.dependencyEdges().entrySet()) {
            LazyInterMedClassLoader dependant = resolveNodeLoader(entry.getKey());
            if (dependant == null) {
                throw new IllegalStateException("Missing loader for dependant " + entry.getKey());
            }
            for (String depId : entry.getValue()) {
                LazyInterMedClassLoader dependency = resolveNodeLoader(depId);
                if (dependency == null) {
                    throw new IllegalStateException("Missing loader for dependency " + depId);
                }
                if (!dependant.getParents().contains(dependency)) {
                    throw new IllegalStateException(
                        "Resolved dependency edge '" + entry.getKey() + "' -> '" + depId
                            + "' is absent from the constructed ClassLoader DAG"
                    );
                }
            }
        }
    }

    /**
     * Restores CLI-granted/revoked capabilities that were persisted to SQLite on a
     * previous run.  Called during Phase 1 assembly after manifest registration and
     * before {@link SecurityPolicy#loadExternalProfiles(Path)} so that:
     * 1. manifest defaults are established first,
     * 2. persisted CLI grants/revocations are replayed second,
     * 3. administrator-managed external profiles can still override both.
     */
    private static void loadPersistedCapabilities() {
        if (!org.intermed.core.db.DatabaseManager.isAvailable()) {
            return;
        }
        List<org.intermed.core.db.DatabaseManager.CapabilityRow> rows =
            org.intermed.core.db.DatabaseManager.loadAllCapabilities();
        if (rows.isEmpty()) return;

        int granted = 0, revoked = 0;
        for (org.intermed.core.db.DatabaseManager.CapabilityRow row : rows) {
            try {
                org.intermed.core.security.Capability cap =
                    org.intermed.core.security.Capability.valueOf(row.capability());
                if (row.allowed()) {
                    SecurityPolicy.grantCapability(row.modId(), cap);
                    granted++;
                } else {
                    SecurityPolicy.revokeCapability(row.modId(), cap);
                    revoked++;
                }
            } catch (IllegalArgumentException e) {
                System.err.printf("[Lifecycle] Unknown persisted capability '%s' for mod '%s' — skipping.%n",
                    row.capability(), row.modId());
            }
        }
        System.out.printf("[Lifecycle] Restored %d granted / %d revoked capability row(s) from DB.%n",
            granted, revoked);
    }

    private static void tearDownDag() {
        Set<String> modIds = new HashSet<>(modClassLoaders.keySet());
        List<AutoCloseable> closeables = new ArrayList<>();
        closeables.addAll(modClassLoaders.values());
        closeables.addAll(bridgeClassLoaders.values());
        closeables.addAll(shaderLoaders);

        BridgeRuntime.reset();
        org.intermed.core.bridge.InterMedNetworkBridge.clear();
        org.intermed.core.bridge.ForgeNetworkBridge.clear();
        org.intermed.core.bridge.NeoForgeNetworkBridge.clear();
        jarModIdIndex.clear();
        modClassLoaders.clear();
        bridgeClassLoaders.clear();
        shaderLoaders.clear();
        ClassHierarchyLcaIndex.install(null);

        for (AutoCloseable closeable : closeables) {
            try {
                closeable.close();
            } catch (Exception e) {
                System.err.println("[DAG] Failed to close loader: " + e.getMessage());
            }
        }

        for (String modId : modIds) {
            org.intermed.core.security.SecurityPolicy.unregisterMod(modId);
        }

    }

    private static void registerMixinClassSource() {
        InterMedMixinBootstrap.registerClassSource(new IClassBytesSource() {
            @Override
            public byte[] getClassBytes(String className) {
                return LifecycleManager.getClassBytesFromDAG(className);
            }

            @Override
            public boolean isClassLoaded(String className) {
                return LifecycleManager.isClassLoadedInDAG(className);
            }

            @Override
            public Class<?> findClass(String className) throws ClassNotFoundException {
                return LifecycleManager.findClassInDAG(className);
            }

            @Override
            public java.net.URL[] getClassPath() {
                return allDagLoaders().stream()
                    .filter(loader -> loader instanceof java.net.URLClassLoader)
                    .flatMap(loader -> Arrays.stream(((java.net.URLClassLoader) loader).getURLs()))
                    .distinct()
                    .toArray(java.net.URL[]::new);
            }
        });
    }

    private static boolean isBridgeNode(String nodeId) {
        return RuntimeModuleKind.forModuleId(nodeId).isBuiltInRuntime();
    }

    private static LazyInterMedClassLoader createBridgeLoader(String nodeId,
                                                              RuntimeModuleKind kind,
                                                              Map<LazyInterMedClassLoader, ParentLinkPolicy> parentLinks,
                                                              ClassLoader platformClassLoader) {
        if (kind == null || !kind.isBuiltInRuntime()) {
            throw new IllegalArgumentException("Bridge loader requested for non-runtime node: " + nodeId);
        }
        InterMedClassLoader loader = new InterMedClassLoader(nodeId, null, parentLinks, platformClassLoader);
        installProductionBytecodePipeline(loader, false);
        return loader;
    }

    private static void installProductionBytecodePipeline(LazyInterMedClassLoader loader,
                                                          boolean includeMixinResolution) {
        // Canonical production order for mod-owned bytecode:
        //   1. TinyRemapperTransformer   — intermediary → named mappings
        //   2. ForgeAnnotationRemapper   — remap class/method refs in @Mixin/@At annotations
        //   3. RegistryHookTransformer   — INVOKEDYNAMIC registry virtualisation
        //   4. SecurityHookTransformer   — capability / file / network sandboxing
        //   5. MixinTransformer          — conflict resolution + bridge generation
        loader.addTransformer(new TinyRemapperTransformer());
        loader.addTransformer(new ForgeAnnotationRemapper());
        loader.addTransformer(new RegistryHookTransformer());
        loader.addTransformer(new SecurityHookTransformer());
        if (includeMixinResolution) {
            loader.addTransformer(new MixinTransformer());
        }
    }

    private static LazyInterMedClassLoader resolveNodeLoader(String nodeId) {
        LazyInterMedClassLoader modLoader = modClassLoaders.get(nodeId);
        if (modLoader != null) {
            return modLoader;
        }
        return bridgeClassLoaders.get(nodeId);
    }

    private static Map<LazyInterMedClassLoader, ParentLinkPolicy> resolveParentLinks(ModNode node) {
        LinkedHashMap<LazyInterMedClassLoader, ParentLinkPolicy> links = new LinkedHashMap<>();
        for (String dependencyId : node.dependencies.stream().sorted().toList()) {
            LazyInterMedClassLoader parent = resolveNodeLoader(dependencyId);
            if (parent == null) {
                if (RuntimeConfig.get().isResolverFallbackEnabled()) {
                    System.err.printf(
                        "[DAG] Skipping unavailable dependency loader '%s' while building '%s' because resolver fallback is enabled.%n",
                        dependencyId, node.id
                    );
                    continue;
                }
                throw new IllegalStateException(
                    "Dependency loader for '" + dependencyId + "' is unavailable while building '" + node.id + "'"
                );
            }
            ParentLinkPolicy policy = node.dependencyLinkPolicies.getOrDefault(
                dependencyId,
                node.kind.isBuiltInRuntime() ? ParentLinkPolicy.REEXPORT : ParentLinkPolicy.LOCAL_ONLY
            );
            links.merge(
                parent,
                policy,
                (left, right) -> left.allowsTransitiveAccess() || right.allowsTransitiveAccess()
                    ? ParentLinkPolicy.REEXPORT
                    : ParentLinkPolicy.LOCAL_ONLY
            );
        }
        return links;
    }

    private static Map<Path, String> buildJarOwnerIndex(List<ModNode> nodes) {
        Map<Path, String> owners = new LinkedHashMap<>();
        for (ModNode node : nodes) {
            if (node.jar != null) {
                owners.put(node.jar.toPath().toAbsolutePath().normalize(), node.id);
            }
        }
        return owners;
    }

    private static String resolveLibraryVisibilityDomain(File libraryJar,
                                                         ModDiscovery.DiscoveryLayout discoveryLayout,
                                                         Map<Path, String> ownerIdsByJar) {
        Path ownerJar = discoveryLayout.ownerOf(libraryJar);
        if (ownerJar == null) {
            return LibraryDiscovery.GLOBAL_VISIBILITY_DOMAIN;
        }
        String ownerId = ownerIdsByJar.get(ownerJar);
        if (ownerId == null || ownerId.isBlank()) {
            return LibraryDiscovery.GLOBAL_VISIBILITY_DOMAIN;
        }
        return LibraryDiscovery.ownerVisibilityDomain(ownerId);
    }

    private static Map<LazyInterMedClassLoader, ParentLinkPolicy> selectShaderParentLinks(ModNode node,
                                                                                           List<ShaderClassLoader> shaders) {
        LinkedHashMap<LazyInterMedClassLoader, ParentLinkPolicy> parents = new LinkedHashMap<>();
        String ownerDomain = LibraryDiscovery.ownerVisibilityDomain(node.id);
        boolean reexportPrivateLibraries = node.descriptor != null && node.descriptor.reexportsPrivateLibraries();
        for (ShaderClassLoader shader : shaders) {
            if (LibraryDiscovery.GLOBAL_VISIBILITY_DOMAIN.equals(shader.getVisibilityDomain())
                || ownerDomain.equals(shader.getVisibilityDomain())) {
                ParentLinkPolicy policy = ownerDomain.equals(shader.getVisibilityDomain()) && reexportPrivateLibraries
                    ? ParentLinkPolicy.REEXPORT
                    : ParentLinkPolicy.LOCAL_ONLY;
                parents.put(shader, policy);
            }
        }
        return parents;
    }

    private static void wireDeclaredPeers(List<ModNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return;
        }
        for (ModNode node : nodes) {
            if (node == null || node.descriptor == null) {
                continue;
            }
            LazyInterMedClassLoader loader = resolveNodeLoader(node.id);
            if (loader == null) {
                continue;
            }
            for (String peerId : node.descriptor.allowedPeerIds()) {
                LazyInterMedClassLoader peer = resolveNodeLoader(peerId);
                if (peer == null) {
                    throw new IllegalStateException(
                        "Declared peer '" + peerId + "' for '" + node.id + "' is unavailable."
                    );
                }
                loader.addPeer(peer);
            }
        }
    }

    private static WeakPeerPolicy weakPeerPolicyFor(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return null;
        }
        return RuntimeModIndex.get(providerId)
            .map(metadata -> new WeakPeerPolicy(providerId, metadata.weakApiPrefixes(), true))
            .filter(policy -> !policy.apiPrefixes().isEmpty())
            .orElse(null);
    }

    private static List<LazyInterMedClassLoader> allDagLoaders() {
        List<LazyInterMedClassLoader> loaders = new ArrayList<>();
        loaders.addAll(bridgeClassLoaders.values());
        loaders.addAll(shaderLoaders);
        loaders.addAll(modClassLoaders.values());
        return loaders;
    }

    private static void rebuildClassHierarchyIndex() {
        ClassHierarchyLcaIndex.install(ClassHierarchyLcaIndex.buildFrom(allDagLoaders()));
    }

    private static void logDagSummary() {
        System.out.printf(
            "[DAG] Summary: %d bridge node(s), %d shader loader(s), %d mod node(s).%n",
            bridgeClassLoaders.size(), shaderLoaders.size(), modClassLoaders.size()
        );

        bridgeClassLoaders.forEach((id, loader) ->
            System.out.printf("[DAG] Bridge %-28s parents=%s%n",
                id,
                loader.getParents().stream()
                    .map(parent -> parent.getNodeId() + "(" + loader.getParentLinkPolicy(parent) + ")")
                    .toList())
        );

        shaderLoaders.forEach(shader ->
            System.out.printf("[DAG] Shader %-28s scope=%s libs=%d%n",
                shader.getNodeId(), shader.getVisibilityDomain(), shader.getLibraries().size())
        );

        modClassLoaders.forEach((id, loader) ->
            System.out.printf("[DAG] Mod    %-28s parents=%s peers=%d weakPeers=%d%n",
                id,
                loader.getParents().stream()
                    .map(parent -> parent.getNodeId() + "(" + loader.getParentLinkPolicy(parent) + ")")
                    .toList(),
                loader.getPeers().size(),
                loader.getWeakPeers().size())
        );
    }
}
