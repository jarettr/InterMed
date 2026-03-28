package org.intermed.core.classloading;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.intermed.core.lifecycle.LifecycleManager;

/**
 * A classloader that implements a Directed Acyclic Graph (DAG) structure for mod isolation and dependency resolution.
 * It delays class loading until absolutely necessary and supports bytecode transformation.
 * This implementation fulfills Requirement 3.2.1 of the InterMed technical specification regarding the ClassLoader DAG.
 */
public class LazyInterMedClassLoader extends URLClassLoader {

    private final String nodeId;
    private final Set<LazyInterMedClassLoader> parents; // Represents the edges of the DAG
    private final List<BytecodeTransformer> transformers = new ArrayList<>();
    private final Map<String, Class<?>> classCache = new ConcurrentHashMap<>();

    public LazyInterMedClassLoader(String nodeId, File initialJar, Set<LazyInterMedClassLoader> parents, ClassLoader platformClassLoader) {
        super(new URL[]{}, platformClassLoader); // The ultimate parent is the platform loader
        this.nodeId = nodeId;
        this.parents = parents != null ? parents : Collections.emptySet();
        if (initialJar != null) {
            addJar(initialJar);
        }
    }

    public String getNodeId() {
        return nodeId;
    }

    public void addJar(File file) {
        try {
            if (file.exists()) {
                super.addURL(file.toURI().toURL());
            }
        } catch (Exception e) {
            System.err.println("[" + nodeId + "] Failed to link JAR: " + file.getName());
        }
    }

    public void addTransformer(BytecodeTransformer transformer) {
        this.transformers.add(transformer);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // --- 0. System classes have top priority and are not transformed or cached here ---
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.") || name.startsWith("sun.")) {
            return super.loadClass(name, resolve);
        }

        // --- 1. Check local cache first ---
        Class<?> cachedClass = classCache.get(name);
        if (cachedClass != null) {
            return cachedClass;
        }

        synchronized (getClassLoadingLock(name)) {
            // Double-checked locking
            cachedClass = classCache.get(name);
            if (cachedClass != null) {
                return cachedClass;
            }

            // --- 2. Attempt to load from self first (findClass will trigger transformations) ---
            try {
                Class<?> c = findClass(name);
                classCache.put(name, c);
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            } catch (ClassNotFoundException e) {
                // Not found in self, proceed to search parents.
            }

            // --- 3. Traverse the DAG: search in parent classloaders ---
            for (LazyInterMedClassLoader parentLoader : this.parents) {
                try {
                    Class<?> c = parentLoader.loadClass(name, resolve);
                    // Do NOT cache classes from parents here, let the parent loader manage its own cache.
                    return c;
                } catch (ClassNotFoundException e) {
                    // Not found in this parent, continue to the next.
                }
            }
            
            // --- 4. If not found anywhere in the DAG, delegate to the platform classloader ---
            // This will find game classes, system classes, and anything from the base classpath.
            return super.loadClass(name, resolve);
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // --- 1. Remapping hook ---
        String targetName = remapClassName(name);

        // --- 2. Load and transform bytes ---
        byte[] bytes = loadBytes(targetName);
        if (bytes != null) {
            for (BytecodeTransformer t : transformers) {
                try {
                    bytes = t.transform(targetName, bytes);
                } catch (Exception ex) {
                    System.err.println("[Transformer] Failed on " + targetName + ": " + ex.getMessage());
                }
            }
            return defineClass(targetName, bytes, 0, bytes.length);
        }
        
        // --- 3. Dummy class generation for compatibility ---
        if (name.equals("net.fabricmc.loader.api.FabricLoader")) {
            System.out.println("\033[1;33m[Interceptor] Generating dummy FabricLoader to bypass IncompatibleClassChangeError...\033[0m");
            try {
                byte[] dummyBytes = generateDummyFabricLoader();
                return defineClass(name, dummyBytes, 0, dummyBytes.length);
            } catch (Exception e) {
                System.err.println("[Interceptor] Failed to generate dummy FabricLoader: " + e.getMessage());
            }
        }
        
        // If all attempts fail, throw.
        throw new ClassNotFoundException(name);
    }

    private String remapClassName(String name) {
        if (name.contains("class_") || name.contains("net.minecraft.")) {
            String internal = name.replace('.', '/');
            String mapped = LifecycleManager.DICTIONARY.map(internal);
            if (mapped != null && !mapped.equals(internal)) {
                return mapped.replace('/', '.');
            }
        }
        return name;
    }

    private byte[] loadBytes(String name) {
        String path = name.replace('.', '/') + ".class";
        try (java.io.InputStream is = getResourceAsStream(path)) {
            if (is != null) return is.readAllBytes();
        } catch (Exception ignore) {}
        return null;
    }

    private byte[] generateDummyFabricLoader() {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_SUPER, "net/fabricmc/loader/api/FabricLoader", null, "java/lang/Object", null);
        
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_STATIC, "getInstance", "()Lnet/fabricmc/loader/api/FabricLoader;", null, null);
        mv.visitCode();
        mv.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        mv.visitInsn(org.objectweb.asm.Opcodes.ARETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        
        cw.visitEnd();
        return cw.toByteArray();
    }
}