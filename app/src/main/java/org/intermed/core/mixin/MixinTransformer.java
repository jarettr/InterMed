
package org.intermed.core.mixin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import org.intermed.core.ast.AstMetadataReclaimer;
import org.intermed.core.classloading.BytecodeTransformer;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.lifecycle.LifecycleManager;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class MixinTransformer implements BytecodeTransformer {

    /**
     * Maps internal class names (slash-separated) to the ordered list of mixins
     * that target them.
     *
     * <p>ConcurrentHashMap is required because {@link #registerModMixins} is called
     * from the boot thread while {@link #transform} may be invoked concurrently from
     * multiple class-loading threads.  The inner lists are wrapped in
     * {@link Collections#synchronizedList} and all multi-step (add + sort)
     * operations on them are performed under the list's own monitor.
     */
    private static final Map<String, List<MixinInfo>> mixinConfigs = new ConcurrentHashMap<>();
    private static final AtomicInteger REGISTRATION_SEQUENCE = new AtomicInteger();

    public static void registerModMixins(File jarFile) {
        registerModMixins(jarFile, null);
    }

    public static void registerModMixins(File jarFile, NormalizedModMetadata metadata) {
        if (jarFile == null || !jarFile.exists()) {
            return;
        }
        String modVersion = (metadata != null && metadata.version() != null)
            ? metadata.version() : "unknown";
        try (JarFile jar = new JarFile(jarFile)) {
            JsonArray mixinsArray = resolveMixinArray(jar, metadata);
            if (mixinsArray == null) return;
            for (JsonElement mixinElement : mixinsArray) {
                String configPath = mixinElement.isJsonPrimitive() ? mixinElement.getAsString() : mixinElement.getAsJsonObject().get("config").getAsString();

                // Read the mixin config file from the JAR
                JarEntry mixinConfigEntry = jar.getJarEntry(configPath);
                if (mixinConfigEntry == null) continue;

                try (InputStream is = jar.getInputStream(mixinConfigEntry)) {
                    JsonObject mixinConfig = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();

                    // "package" is mandatory in every valid mixin config.
                    // A missing key means the config is malformed — skip it and warn.
                    if (!mixinConfig.has("package") || mixinConfig.get("package").isJsonNull()) {
                        System.err.printf("[MixinEngine] Skipping '%s' in %s: missing required 'package' field.%n",
                            configPath, jarFile.getName());
                        continue;
                    }
                    String mixinPackage = mixinConfig.get("package").getAsString().trim();
                    if (mixinPackage.isBlank()) {
                        System.err.printf("[MixinEngine] Skipping '%s' in %s: 'package' field is blank.%n",
                            configPath, jarFile.getName());
                        continue;
                    }

                    int priority = mixinConfig.has("priority") ? mixinConfig.get("priority").getAsInt() : 1000;
                    List<String> mixinClassNames = resolveMixinClassNames(mixinConfig);
                    if (requiresNativeMixinRuntime(jar, mixinPackage, mixinClassNames)) {
                        InterMedPlatformAgent.registerExternalMixinConfig(configPath);
                    }

                    for (String mixinSimpleName : mixinClassNames) {
                        String mixinClassName = mixinPackage + "." + mixinSimpleName;
                        List<String> targets = getMixinTargets(jar, mixinClassName);
                        int registrationOrder = REGISTRATION_SEQUENCE.getAndIncrement();

                        MixinInfo info = new MixinInfo(
                            configPath,
                            mixinClassName,
                            targets,
                            priority,
                            registrationOrder,
                            modVersion
                        );

                        for (String target : targets) {
                            String internalTarget = target.replace('.', '/');
                            // computeIfAbsent is atomic; the returned list is a
                            // synchronizedList so add + sort are guarded by its monitor.
                            List<MixinInfo> infos = mixinConfigs.computeIfAbsent(
                                internalTarget,
                                k -> Collections.synchronizedList(new ArrayList<>())
                            );
                            synchronized (infos) {
                                infos.add(info);
                                infos.sort(Comparator
                                    .comparingInt(MixinInfo::getPriority)
                                    .reversed()
                                    .thenComparingInt(MixinInfo::getRegistrationOrder));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MixinEngine] Failed to parse mixins for " + jarFile.getName() + ": " + e.getMessage());
        }
    }

    public static void resetForTests() {
        // ConcurrentHashMap.clear() is safe without external lock.
        // No need to synchronize individual inner lists — we're replacing the whole map.
        mixinConfigs.clear();
        REGISTRATION_SEQUENCE.set(0);
    }

    static List<MixinInfo> registeredMixinsForTarget(String targetClassName) {
        if (targetClassName == null || targetClassName.isBlank()) {
            return List.of();
        }
        String internalName = targetClassName.replace('.', '/');
        List<MixinInfo> infos = mixinConfigs.get(internalName);
        if (infos == null) {
            return List.of();
        }
        synchronized (infos) {
            return List.copyOf(infos);
        }
    }

    private static JsonArray resolveMixinArray(JarFile jar, NormalizedModMetadata metadata) throws Exception {
        if (metadata != null && !metadata.mixinConfigs().isEmpty()) {
            JsonArray configs = new JsonArray();
            metadata.mixinConfigs().forEach(configs::add);
            return configs;
        }

        JarEntry modJsonEntry = jar.getJarEntry("fabric.mod.json");
        if (modJsonEntry == null) {
            return null;
        }

        JsonObject json = JsonParser.parseReader(new InputStreamReader(jar.getInputStream(modJsonEntry))).getAsJsonObject();
        if (!json.has("mixins")) {
            return null;
        }
        return json.getAsJsonArray("mixins");
    }

    private static List<String> resolveMixinClassNames(JsonObject mixinConfig) {
        LinkedHashSet<String> classNames = new LinkedHashSet<>();
        addMixinClassNames(mixinConfig, "mixins", classNames);

        EnvType environment = RuntimeConfig.get().getEnvironmentType();
        if (environment == EnvType.CLIENT) {
            addMixinClassNames(mixinConfig, "client", classNames);
        } else {
            addMixinClassNames(mixinConfig, "server", classNames);
        }
        return List.copyOf(classNames);
    }

    private static void addMixinClassNames(JsonObject mixinConfig,
                                           String section,
                                           LinkedHashSet<String> sink) {
        if (!mixinConfig.has(section) || !mixinConfig.get(section).isJsonArray()) {
            return;
        }
        for (JsonElement element : mixinConfig.getAsJsonArray(section)) {
            if (element != null && element.isJsonPrimitive()) {
                String mixinName = element.getAsString().trim();
                if (!mixinName.isEmpty()) {
                    sink.add(mixinName);
                }
            }
        }
    }

    private static List<String> getMixinTargets(JarFile jar, String mixinClassName) {
        String classPath = mixinClassName.replace('.', '/') + ".class";
        JarEntry classEntry = jar.getJarEntry(classPath);
        if (classEntry == null) {
            return Collections.emptyList();
        }

        try (InputStream is = jar.getInputStream(classEntry)) {
            ClassReader cr = new ClassReader(is);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            LinkedHashSet<String> targetNames = new LinkedHashSet<>();
            collectMixinTargets(classNode.visibleAnnotations, targetNames);
            collectMixinTargets(classNode.invisibleAnnotations, targetNames);
            if (RuntimeConfig.get().isMixinAstReclaimEnabled()) {
                AstMetadataReclaimer.reclaim(classNode);
            }
            return List.copyOf(targetNames);
        } catch (Exception e) {
            System.err.println("[MixinEngine] Failed to read targets for mixin " + mixinClassName + ": " + e.getMessage());
        }
        return Collections.emptyList();
    }

    private static boolean requiresNativeMixinRuntime(JarFile jar,
                                                      String mixinPackage,
                                                      List<String> mixinSimpleNames) {
        for (String mixinSimpleName : mixinSimpleNames) {
            String mixinClassName = mixinPackage + "." + mixinSimpleName;
            for (String target : getMixinTargets(jar, mixinClassName)) {
                if (isPlatformTarget(jar, target)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isPlatformTarget(JarFile jar, String targetClassName) {
        if (targetClassName == null || targetClassName.isBlank()) {
            return false;
        }
        String classPath = targetClassName.replace('.', '/') + ".class";
        if (jar.getJarEntry(classPath) != null) {
            return false;
        }
        return LifecycleManager.getClassBytesFromDAG(targetClassName) == null;
    }

    @SuppressWarnings("unchecked")
    private static void collectMixinTargets(List<AnnotationNode> annotations,
                                            LinkedHashSet<String> sink) {
        if (annotations == null) {
            return;
        }
        for (AnnotationNode annotation : annotations) {
            if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc)
                || annotation.values == null) {
                continue;
            }
            for (int i = 0; i < annotation.values.size(); i += 2) {
                Object name = annotation.values.get(i);
                Object value = annotation.values.get(i + 1);
                if ("value".equals(name) && value instanceof List<?> values) {
                    for (Object candidate : values) {
                        if (candidate instanceof Type type) {
                            sink.add(type.getClassName());
                        }
                    }
                } else if ("targets".equals(name) && value instanceof List<?> values) {
                    for (Object candidate : values) {
                        if (candidate instanceof String target && !target.isBlank()) {
                            sink.add(target.replace('/', '.'));
                        }
                    }
                }
            }
        }
    }

    @Override
    public byte[] transform(String className, byte[] bytes) {
        String internalClassName = className.replace('.', '/');
        List<MixinInfo> infos = mixinConfigs.get(internalClassName);
        if (infos == null || infos.isEmpty()) {
            return bytes;
        }
        // Take an immutable snapshot under the list's monitor so the AST analyzer
        // works on a stable view without holding the lock during bytecode processing.
        List<MixinInfo> snapshot;
        synchronized (infos) {
            snapshot = List.copyOf(infos);
        }
        if (snapshot.isEmpty()) {
            return bytes;
        }
        System.out.println("\033[1;33m[AST-Engine] Class " + className
            + " is a mixin target. Applying " + snapshot.size() + " mixin(s)...\033[0m");
        return MixinASTAnalyzer.analyzeAndResolve(className, bytes, snapshot);
    }
}
