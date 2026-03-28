package org.intermed.core.lifecycle;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.intermed.core.async.BackgroundPreparator;
import org.intermed.core.async.PreparatorTask;
import org.intermed.core.classloading.LazyInterMedClassLoader;
import org.intermed.core.remapping.*;
import org.intermed.core.security.*;
import org.intermed.core.mixin.MixinTransformer;
import org.intermed.core.lifecycle.ModDiscovery;

import net.fabricmc.api.ClientModInitializer;
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
    private static boolean phase1Started = false;
    
    // Global services managed by the lifecycle
    public static final MappingDictionary DICTIONARY = new MappingDictionary();
    public static final Queue<Runnable> MAIN_THREAD_TASKS = new ConcurrentLinkedQueue<>();
    private static final Map<String, LazyInterMedClassLoader> modClassLoaders = new ConcurrentHashMap<>();

    // =================================================================================================================
    // Dependency Resolution (Requirement 3.2.1)
    // =================================================================================================================

    /**
     * Represents a mod discovered on the filesystem, acting as a node in the dependency graph.
     */
    static class ModNode {
        final String id;
        final String version;
        final File jar;
        final Set<String> dependencies = new HashSet<>();
        final JsonObject metadata;

        ModNode(String id, String version, File jar, JsonObject metadata) {
            this.id = id;
            this.version = version;
            this.jar = jar;
            this.metadata = metadata;
            
            // Basic dependency parsing from fabric.mod.json
            if (metadata.has("depends")) {
                metadata.getAsJsonObject("depends").entrySet().forEach(entry -> dependencies.add(entry.getKey()));
            }
        }
    }

    /**
     * Scans for mods and resolves the loading order via topological sort.
     * This is a temporary solution until PubGrub is integrated (Requirement 3.2.1).
     */
    static class DependencyResolver {
        public static List<ModNode> resolveOrder() {
            List<File> jars = ModDiscovery.discoverJars();
            Map<String, ModNode> nodes = new HashMap<>();
            Map<String, List<String>> adjList = new HashMap<>();

            // 1. Create nodes and adjacency list representation
            for (File jar : jars) {
                try (JarFile jarFile = new JarFile(jar)) {
                    JarEntry entry = jarFile.getJarEntry("fabric.mod.json");
                    if (entry != null) {
                        JsonObject json = JsonParser.parseReader(new InputStreamReader(jarFile.getInputStream(entry))).getAsJsonObject();
                        String modId = json.get("id").getAsString();
                        String version = json.has("version") ? json.get("version").getAsString() : "unknown";
                        
                        ModNode node = new ModNode(modId, version, jar, json);
                        nodes.put(modId, node);
                        adjList.put(modId, new ArrayList<>());
                    }
                } catch (Exception e) {
                    System.err.println("[Resolver] Failed to parse metadata for " + jar.getName() + ": " + e.getMessage());
                }
            }

            // Populate adjacency list
            for (ModNode node : nodes.values()) {
                for (String depId : node.dependencies) {
                    if (adjList.containsKey(depId)) {
                        adjList.get(depId).add(node.id);
                    }
                }
            }

            // 2. Perform Topological Sort (Kahn's algorithm)
            List<ModNode> sortedList = new ArrayList<>();
            Map<String, Integer> inDegree = new HashMap<>();
            nodes.keySet().forEach(id -> inDegree.put(id, 0));

            for (ModNode node : nodes.values()) {
                for (String depId : node.dependencies) {
                    if (inDegree.containsKey(depId)) {
                        inDegree.compute(node.id, (k, v) -> (v == null ? 0 : v) + 1);
                    }
                }
            }

            Queue<String> queue = new LinkedList<>();
            for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
                if (entry.getValue() == 0) {
                    queue.add(entry.getKey());
                }
            }
            
            while (!queue.isEmpty()) {
                String modId = queue.poll();
                sortedList.add(nodes.get(modId));

                for (String neighborId : adjList.getOrDefault(modId, Collections.emptyList())) {
                    inDegree.compute(neighborId, (k, v) -> v - 1);
                    if (inDegree.get(neighborId) == 0) {
                        queue.add(neighborId);
                    }
                }
            }

            if (sortedList.size() != nodes.size()) {
                 System.err.println("\033[1;31m[Resolver] CRITICAL: Cyclic dependency detected! Some mods may not load.\033[0m");
                 // Also print the remaining nodes to help debug
                 nodes.keySet().stream()
                    .filter(id -> !sortedList.stream().anyMatch(n -> n.id.equals(id)))
                    .forEach(id -> System.err.println("[Resolver]   - Involved in cycle: " + id));
            }
            
            System.out.println("[Resolver] Mod load order resolved: " + sortedList.stream().map(n -> n.id).collect(Collectors.toList()));
            return sortedList;
        }
    }

    // =================================================================================================================
    // LIFECYCLE PHASES
    // =================================================================================================================

    public static void startPhase0_Preloader() {
        System.out.println("\033[1;36m[Lifecycle] Phase 0: Initializing Remapping Dictionary...\033[0m");
        try {
            // TODO: The path to the mappings file needs to be provided dynamically by the environment.
            // This could come from a config file or be discovered relative to the game directory.
            Path tinyPath = null; // Paths.get("path", "to", "mappings.tiny");
            if (tinyPath != null) {
                DictionaryParser.parse(tinyPath, null, DICTIONARY);
            } else {
                 System.out.println("[Lifecycle] Skipping dictionary load: No path provided.");
            }
        } catch (Exception e) { 
            System.err.println("[Lifecycle] Dictionary Failure: " + e.getMessage());
        }
    }

    public static synchronized void startPhase1_BackgroundAssembly() {
        if (phase1Started) return;
        phase1Started = true;

        BackgroundPreparator.getInstance().submitTask(new PreparatorTask() {
            @Override public int getPriority() { return 1; }
            @Override public String getName() { return "DAG Assembly Engine"; }
            @Override public void execute() {
                System.out.println("\033[1;36m[Lifecycle] Phase 1: Building ClassLoader DAG...\033[0m");
                
                // The platform classloader is the one that loaded the kernel; it's the root of our graph.
                ClassLoader platformClassLoader = InterMedKernel.class.getClassLoader();

                // Inject InterMed's own system-level mixins into the platform environment
                injectSystemMixins(platformClassLoader);

                // Resolve dependency order using topological sort
                List<ModNode> sortedMods = DependencyResolver.resolveOrder();

                // Build the DAG of classloaders
                for (ModNode mod : sortedMods) {
                    try {
                        System.out.println("\033[1;35m[DAG] Creating ClassLoader for: " + mod.id + "\033[0m");

                        // 1. Find parent classloaders from already loaded dependencies
                        Set<LazyInterMedClassLoader> parentLoaders = mod.dependencies.stream()
                            .map(modClassLoaders::get)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());

                        // 2. Create the dedicated classloader for this mod using the CORRECT DAG constructor
                        LazyInterMedClassLoader modLoader = new LazyInterMedClassLoader(mod.id, mod.jar, parentLoaders, platformClassLoader);

                        // 3. Register transformers for this mod's classes
                        modLoader.addTransformer(new LightweightRemapper(DICTIONARY));
                        modLoader.addTransformer(new SecurityHookTransformer());
                        modLoader.addTransformer(new MixinTransformer());

                        // 4. Store the classloader for future mods to depend on
                        modClassLoaders.put(mod.id, modLoader);
                        
                        // 5. Awaken the mod by calling its initializers
                        awakenFabricModSafe(modLoader, mod.metadata);

                    } catch (Exception e) {
                        System.err.println("[DAG] Failed to build node for " + mod.id + ": " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                
                System.out.println("\033[1;32m[Lifecycle] ClassLoader DAG constructed. Universal Bridge Active.\033[0m");
            }
        });
    }

    private static void injectSystemMixins(ClassLoader loader) {
         try {
            // Use reflection to call Mixins.addConfiguration, making InterMed compatible with or without Mixin on the classpath at compile time.
            Class<?> mixinsClass = Class.forName("org.spongepowered.asm.mixin.Mixins", true, loader);
            java.lang.reflect.Method addConfigMethod = mixinsClass.getMethod("addConfiguration", String.class);
            addConfigMethod.invoke(null, "intermed.mixins.json");
            System.out.println("\033[1;36m[InterMed] Native System Mixin injected!\033[0m");
        } catch (Exception e) {
            System.err.println("[InterMed] Failed to inject system mixins: " + e.getMessage());
        }
    }

    private static void awakenFabricModSafe(LazyInterMedClassLoader loader, JsonObject metadata) {
        if (!metadata.has("entrypoints")) return;
        
        JsonObject entrypoints = metadata.getAsJsonObject("entrypoints");
        
        // Initialize common entrypoints on the background thread
        initializeEntrypoints(loader, entrypoints, "main", ModInitializer.class, ModInitializer::onInitialize);
        
        // Schedule client/server entrypoints to run on the main game thread
        MAIN_THREAD_TASKS.add(() -> initializeEntrypoints(loader, entrypoints, "client", ClientModInitializer.class, ClientModInitializer::onInitializeClient));
        // MAIN_THREAD_TASKS.add(() -> initializeEntrypoints(loader, entrypoints, "server", ...));
    }
    
    private static <T> void initializeEntrypoints(ClassLoader loader, JsonObject entrypoints, String type, Class<T> interfaceClass, EntrypointRunner<T> runner) {
        if (!entrypoints.has(type)) return;

        JsonArray points = entrypoints.getAsJsonArray(type);
        for (JsonElement point : points) {
            String entrypointString = point.isJsonPrimitive() ? point.getAsString() : point.getAsJsonObject().get("value").getAsString();
            try {
                Class<?> clazz = loader.loadClass(entrypointString);
                if (interfaceClass.isAssignableFrom(clazz)) {
                    T instance = interfaceClass.cast(clazz.getDeclaredConstructor().newInstance());
                    runner.run(instance);
                    System.out.println("\033[1;32m[DAG] " + loader.getNodeId() + " -> " + type.toUpperCase() + " INITIALIZED\033[0m");
                }
            } catch (Throwable t) {
                System.err.println("\033[1;33m[DAG] Soft-fail in " + loader.getNodeId() + " (" + type + "): " + t.toString() + "\033[0m");
            }
        }
    }
    
    @FunctionalInterface
    interface EntrypointRunner<T> {
        void run(T instance) throws Exception;
    }
}