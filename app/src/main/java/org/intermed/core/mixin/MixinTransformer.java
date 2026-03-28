
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class MixinTransformer implements BytecodeTransformer {

    // A map of target class names to a list of mixins that target them.
    private static final Map<String, List<MixinInfo>> mixinConfigs = new HashMap<>();

    public static void registerModMixins(File jarFile) {
        try (JarFile jar = new JarFile(jarFile)) {
            // Find the fabric.mod.json file
            JarEntry modJsonEntry = jar.getJarEntry("fabric.mod.json");
            if (modJsonEntry == null) return;

            JsonObject json = JsonParser.parseReader(new InputStreamReader(jar.getInputStream(modJsonEntry))).getAsJsonObject();
            if (!json.has("mixins")) return;

            JsonArray mixinsArray = json.getAsJsonArray("mixins");
            for (JsonElement mixinElement : mixinsArray) {
                String configPath = mixinElement.isJsonPrimitive() ? mixinElement.getAsString() : mixinElement.getAsJsonObject().get("config").getAsString();
                
                // Read the mixin config file from the JAR
                JarEntry mixinConfigEntry = jar.getJarEntry(configPath);
                if (mixinConfigEntry == null) continue;

                try (InputStream is = jar.getInputStream(mixinConfigEntry)) {
                    JsonObject mixinConfig = JsonParser.parseReader(new InputStreamReader(is)).getAsJsonObject();
                    String mixinPackage = mixinConfig.get("package").getAsString();
                    int priority = mixinConfig.has("priority") ? mixinConfig.get("priority").getAsInt() : 1000;
                    
                    if (mixinConfig.has("mixins")) {
                        JsonArray mixinClasses = mixinConfig.getAsJsonArray("mixins");
                        for (JsonElement mixinClassNameEl : mixinClasses) {
                            String mixinClassName = mixinPackage + "." + mixinClassNameEl.getAsString();
                            List<String> targets = getMixinTargets(jar, mixinClassName);

                            MixinInfo info = new MixinInfo(configPath, mixinClassName, targets, priority);
                            
                            for (String target : targets) {
                                mixinConfigs.computeIfAbsent(target.replace('.', '/'), k -> new ArrayList<>()).add(info);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MixinEngine] Failed to parse mixins for " + jarFile.getName() + ": " + e.getMessage());
        }
    }

    private static List<String> getMixinTargets(JarFile jar, String mixinClassName) {
        String classPath = mixinClassName.replace('.', '/') + ".class";
        try (InputStream is = jar.getInputStream(jar.getJarEntry(classPath))) {
            ClassReader cr = new ClassReader(is);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            if (classNode.visibleAnnotations != null) {
                for (AnnotationNode annotation : classNode.visibleAnnotations) {
                    if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
                        if (annotation.values != null) {
                            for (int i = 0; i < annotation.values.size(); i += 2) {
                                String name = (String) annotation.values.get(i);
                                if (name.equals("value")) {
                                    List<Type> types = (List<Type>) annotation.values.get(i + 1);
                                    List<String> targetNames = new ArrayList<>();
                                    for (Type type : types) {
                                        targetNames.add(type.getClassName());
                                    }
                                    return targetNames;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MixinEngine] Failed to read targets for mixin " + mixinClassName + ": " + e.getMessage());
        }
        return Collections.emptyList();
    }

        @Override

        public byte[] transform(String className, byte[] bytes) {

            String internalClassName = className.replace('.', '/');

            if (mixinConfigs.containsKey(internalClassName)) {

                List<MixinInfo> infos = mixinConfigs.get(internalClassName);

                System.out.println("\033[1;33m[AST-Engine] Class " + className + " is a mixin target. Applying " + infos.size() + " mixin(s)...\033[0m");

                return MixinASTAnalyzer.analyzeAndResolve(className, bytes, infos);

            }

            return bytes; 

        }

    }

    