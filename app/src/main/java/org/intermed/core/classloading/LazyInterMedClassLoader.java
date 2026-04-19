package org.intermed.core.classloading;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.Manifest;
import org.intermed.core.lifecycle.LifecycleManager;
import org.intermed.core.remapping.InterMedRemapper;
import org.intermed.core.vfs.VirtualFileSystemRouter;

/**
 * A classloader that implements a Directed Acyclic Graph (DAG) structure for mod isolation
 * and dependency resolution (ТЗ 3.2.1, Requirement 1).
 *
 * <h3>Delegation order (per spec)</h3>
 * <ol>
 *   <li>JVM system packages ({@code java.*}, {@code javax.*}, etc.) — JVM bootstrap.</li>
 *   <li>Local class cache — fast path.</li>
 *   <li>Self ({@code findClass}) — triggers the bytecode transformer pipeline.</li>
 *   <li>Explicit-dependency parents — direct DAG edges (declared dependencies).</li>
 *   <li>Peer loaders — <b>restricted</b> to {@link #PLATFORM_API_PREFIXES} only.
 *       Loading arbitrary classes from peers without an explicit edge is forbidden.</li>
 *   <li>Platform ClassLoader — game classes and InterMed core.</li>
 * </ol>
 */
public class LazyInterMedClassLoader extends URLClassLoader {

    /**
     * Package prefixes whose classes are allowed to cross peer boundaries without
     * an explicit DAG edge.  These are the interfaces that must be class-identity-
     * consistent across all mod ClassLoaders so that instanceof checks and casts work.
     *
     * Additional prefixes can be registered at boot time via {@link #addPlatformApiPrefix}.
     */
    static final Set<String> PLATFORM_API_PREFIXES = ConcurrentHashMap.newKeySet();

    static {
        PLATFORM_API_PREFIXES.add("org.intermed.");                 // InterMed bridge interfaces
        PLATFORM_API_PREFIXES.add("net.fabricmc.api.");             // Fabric lifecycle interfaces
        PLATFORM_API_PREFIXES.add("net.fabricmc.loader.api.");      // Fabric Loader API
        PLATFORM_API_PREFIXES.add("net.fabricmc.fabric.api.");      // Fabric API shims
        PLATFORM_API_PREFIXES.add("net.minecraftforge.");           // Forge API
        PLATFORM_API_PREFIXES.add("net.neoforged.");               // NeoForge API
        PLATFORM_API_PREFIXES.add("org.spongepowered.asm.mixin."); // Mixin system
    }

    /** Allows the platform bootstrap to register additional API packages at startup. */
    public static void addPlatformApiPrefix(String prefix) {
        PLATFORM_API_PREFIXES.add(prefix);
    }

    private final String nodeId;
    private final Set<LazyInterMedClassLoader> parents; // Represents explicit dependency edges
    private final Map<LazyInterMedClassLoader, ParentLinkPolicy> parentPolicies;
    private final Set<LazyInterMedClassLoader> children = ConcurrentHashMap.newKeySet();
    private final Set<LazyInterMedClassLoader> peers    = ConcurrentHashMap.newKeySet();
    private final Set<LazyInterMedClassLoader> incomingPeers = ConcurrentHashMap.newKeySet();
    private final Map<LazyInterMedClassLoader, WeakPeerPolicy> weakPeers = new ConcurrentHashMap<>();
    private final Set<LazyInterMedClassLoader> incomingWeakPeers = ConcurrentHashMap.newKeySet();
    private final List<BytecodeTransformer> transformers = new ArrayList<>();
    final Map<String, Class<?>> classCache = new ConcurrentHashMap<>();

    public LazyInterMedClassLoader(String nodeId, File initialJar, Set<LazyInterMedClassLoader> parents, ClassLoader platformClassLoader) {
        this(nodeId, initialJar, defaultParentPolicies(parents), platformClassLoader);
    }

    public LazyInterMedClassLoader(String nodeId,
                                   File initialJar,
                                   Map<LazyInterMedClassLoader, ParentLinkPolicy> parentLinks,
                                   ClassLoader platformClassLoader) {
        super(new URL[]{}, platformClassLoader); // The ultimate parent is the platform loader
        this.nodeId = nodeId;
        LinkedHashMap<LazyInterMedClassLoader, ParentLinkPolicy> orderedParents = new LinkedHashMap<>();
        if (parentLinks != null) {
            parentLinks.forEach((parent, policy) -> {
                if (parent != null) {
                    orderedParents.put(parent, policy == null ? ParentLinkPolicy.LOCAL_ONLY : policy);
                }
            });
        }
        this.parents = Collections.unmodifiableSet(new LinkedHashSet<>(orderedParents.keySet()));
        this.parentPolicies = Map.copyOf(orderedParents);
        for (LazyInterMedClassLoader parent : this.parents) {
            parent.children.add(this);
        }
        if (initialJar != null) {
            addJar(initialJar);
        }
    }

    public String getNodeId() {
        return nodeId;
    }

    public Set<LazyInterMedClassLoader> getParents() {
        return parents;
    }

    public Set<LazyInterMedClassLoader> getChildren() {
        return Collections.unmodifiableSet(children);
    }

    public Set<LazyInterMedClassLoader> getPeers() {
        return Collections.unmodifiableSet(peers);
    }

    public Set<LazyInterMedClassLoader> getWeakPeers() {
        return Collections.unmodifiableSet(weakPeers.keySet());
    }

    public ParentLinkPolicy getParentLinkPolicy(LazyInterMedClassLoader parent) {
        return parentPolicies.getOrDefault(parent, ParentLinkPolicy.LOCAL_ONLY);
    }

    public WeakPeerPolicy getWeakPeerPolicy(LazyInterMedClassLoader peer) {
        return weakPeers.get(peer);
    }

    public boolean hasLoadedClass(String name) {
        return classCache.containsKey(name);
    }

    public void addPeer(LazyInterMedClassLoader peer) {
        if (peer == null) {
            throw new IllegalArgumentException("Peer loader must not be null for " + nodeId);
        }
        if (peer == this) {
            throw new IllegalArgumentException("Loader '" + nodeId + "' cannot peer with itself");
        }
        if (parents.contains(peer)) {
            throw new IllegalArgumentException(
                "Loader '" + nodeId + "' cannot declare dependency parent '" + peer.nodeId + "' as a peer"
            );
        }
        if (children.contains(peer)) {
            throw new IllegalArgumentException(
                "Loader '" + nodeId + "' cannot declare child '" + peer.nodeId + "' as a peer"
            );
        }
        // Transitive cycle detection: the peer graph is traversed during class loading.
        // If 'peer' is a transitive ancestor of 'this' (or vice-versa), installing a
        // horizontal peer edge would create a cycle in the combined delegation graph,
        // producing confusing ClassNotFoundExceptions at load time.
        if (hasTransitiveParent(this, peer)) {
            throw new IllegalArgumentException(
                "Adding peer '" + peer.nodeId + "' to '" + nodeId
                + "' would create a DAG cycle: '" + peer.nodeId
                + "' is a transitive ancestor of '" + nodeId + "'");
        }
        if (hasTransitiveParent(peer, this)) {
            throw new IllegalArgumentException(
                "Adding peer '" + peer.nodeId + "' to '" + nodeId
                + "' would create a DAG cycle: '" + nodeId
                + "' is a transitive ancestor of '" + peer.nodeId + "'");
        }
        if (peers.add(peer)) {
            peer.incomingPeers.add(this);
        }
    }

    public void addWeakPeer(LazyInterMedClassLoader peer, WeakPeerPolicy policy) {
        if (peer == null) {
            throw new IllegalArgumentException("Weak peer loader must not be null for " + nodeId);
        }
        if (policy == null) {
            throw new IllegalArgumentException("Weak peer policy must not be null for " + nodeId);
        }
        if (peer == this) {
            throw new IllegalArgumentException("Loader '" + nodeId + "' cannot weak-peer with itself");
        }
        if (parents.contains(peer)) {
            throw new IllegalArgumentException(
                "Loader '" + nodeId + "' cannot declare dependency parent '" + peer.nodeId + "' as a weak peer"
            );
        }
        if (children.contains(peer)) {
            throw new IllegalArgumentException(
                "Loader '" + nodeId + "' cannot declare child '" + peer.nodeId + "' as a weak peer"
            );
        }
        // Transitive cycle detection (same rationale as addPeer).
        if (hasTransitiveParent(this, peer)) {
            throw new IllegalArgumentException(
                "Adding weak peer '" + peer.nodeId + "' to '" + nodeId
                + "' would create a DAG cycle: '" + peer.nodeId
                + "' is a transitive ancestor of '" + nodeId + "'");
        }
        if (hasTransitiveParent(peer, this)) {
            throw new IllegalArgumentException(
                "Adding weak peer '" + peer.nodeId + "' to '" + nodeId
                + "' would create a DAG cycle: '" + nodeId
                + "' is a transitive ancestor of '" + peer.nodeId + "'");
        }
        weakPeers.merge(peer, policy, WeakPeerPolicy::merge);
        peer.incomingWeakPeers.add(this);
    }

    /**
     * Returns {@code true} if {@code target} is a transitive parent (ancestor)
     * of {@code node} in the parent/child DAG.  Uses BFS over the {@code parents}
     * sets to avoid recursion depth issues on large graphs.
     *
     * <p>Called from {@link #addPeer} and {@link #addWeakPeer} to prevent cycles
     * in the combined delegation graph before they are installed (ТЗ 3.2.1).
     */
    static boolean hasTransitiveParent(LazyInterMedClassLoader node, LazyInterMedClassLoader target) {
        Set<LazyInterMedClassLoader> visited = new java.util.HashSet<>();
        java.util.Deque<LazyInterMedClassLoader> queue = new java.util.ArrayDeque<>(node.parents);
        while (!queue.isEmpty()) {
            LazyInterMedClassLoader current = queue.poll();
            if (current == target) return true;
            if (visited.add(current)) {
                queue.addAll(current.parents);
            }
        }
        return false;
    }

    public boolean hasWeakPeer(LazyInterMedClassLoader peer) {
        return weakPeers.containsKey(peer);
    }

    public void invalidateCaches() {
        classCache.clear();
    }

    public void validateTopology() {
        if (parents.contains(this)) {
            throw new IllegalStateException("Loader '" + nodeId + "' lists itself as a parent");
        }
        if (children.contains(this)) {
            throw new IllegalStateException("Loader '" + nodeId + "' lists itself as a child");
        }
        if (peers.contains(this) || incomingPeers.contains(this)) {
            throw new IllegalStateException("Loader '" + nodeId + "' lists itself as a peer");
        }
        if (weakPeers.containsKey(this) || incomingWeakPeers.contains(this)) {
            throw new IllegalStateException("Loader '" + nodeId + "' lists itself as a weak peer");
        }
        for (LazyInterMedClassLoader parent : parents) {
            if (parent == null || !parent.children.contains(this)) {
                throw new IllegalStateException(
                    "Loader '" + nodeId + "' has a broken parent/child invariant for parent "
                        + (parent == null ? "<null>" : parent.nodeId)
                );
            }
            if (peers.contains(parent)) {
                throw new IllegalStateException(
                    "Loader '" + nodeId + "' exposes parent '" + parent.nodeId + "' through peer visibility"
                );
            }
        }
        for (LazyInterMedClassLoader child : children) {
            if (child == null || !child.parents.contains(this)) {
                throw new IllegalStateException(
                    "Loader '" + nodeId + "' has a broken child/parent invariant for child "
                        + (child == null ? "<null>" : child.nodeId)
                );
            }
            if (peers.contains(child)) {
                throw new IllegalStateException(
                    "Loader '" + nodeId + "' exposes child '" + child.nodeId + "' through peer visibility"
                );
            }
        }
        for (LazyInterMedClassLoader peer : peers) {
            if (peer == null || !peer.incomingPeers.contains(this)) {
                throw new IllegalStateException(
                    "Loader '" + nodeId + "' has a broken peer backlink for "
                        + (peer == null ? "<null>" : peer.nodeId)
                );
            }
        }
        for (LazyInterMedClassLoader requester : incomingPeers) {
            if (requester == null || !requester.peers.contains(this)) {
                throw new IllegalStateException(
                    "Loader '" + nodeId + "' has a broken incoming peer backlink from "
                        + (requester == null ? "<null>" : requester.nodeId)
                );
            }
        }
        weakPeers.forEach((peer, policy) -> {
            if (peer == null || policy == null || !peer.incomingWeakPeers.contains(this)) {
                throw new IllegalStateException(
                    "Loader '" + nodeId + "' has a broken weak-peer backlink for "
                        + (peer == null ? "<null>" : peer.nodeId)
                );
            }
        });
        for (LazyInterMedClassLoader requester : incomingWeakPeers) {
            if (requester == null || !requester.weakPeers.containsKey(this)) {
                throw new IllegalStateException(
                    "Loader '" + nodeId + "' has a broken incoming weak-peer backlink from "
                        + (requester == null ? "<null>" : requester.nodeId)
                );
            }
        }
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
        if (transformer == null) {
            return;
        }
        boolean duplicateType = transformers.stream()
            .anyMatch(existing -> existing.getClass().equals(transformer.getClass()));
        if (!duplicateType) {
            this.transformers.add(transformer);
        }
    }

    /**
     * Returns the class bytes only if the bytecode is physically hosted by this loader.
     * Parent/platform delegation is intentionally bypassed so callers can reason
     * about class ownership inside the DAG.
     */
    public byte[] readLocalClassBytes(String binaryClassName) {
        return loadBytes(binaryClassName);
    }

    /**
     * Returns a resource only if it is hosted by this node.
     */
    public URL findLocalResource(String name) {
        return super.findResource(name);
    }

    @Override
    public void close() throws java.io.IOException {
        invalidateCaches();
        transformers.clear();
        for (LazyInterMedClassLoader parent : parents) {
            parent.children.remove(this);
        }
        for (LazyInterMedClassLoader peer : peers) {
            peer.incomingPeers.remove(this);
        }
        for (LazyInterMedClassLoader requester : incomingPeers) {
            requester.peers.remove(this);
        }
        for (LazyInterMedClassLoader peer : weakPeers.keySet()) {
            peer.incomingWeakPeers.remove(this);
        }
        for (LazyInterMedClassLoader requester : incomingWeakPeers) {
            requester.weakPeers.remove(this);
        }
        peers.clear();
        incomingPeers.clear();
        weakPeers.clear();
        incomingWeakPeers.clear();
        children.clear();
        super.close();
    }

    @Override
    public URL getResource(String name) {
        URL managed = VirtualFileSystemRouter.resolveManagedResource(name);
        if (managed != null) {
            return managed;
        }

        URL local = findLocalResource(name);
        if (local != null) {
            return local;
        }

        URL fromParents = findResourceFrom(parents, name, ConcurrentHashMap.newKeySet(), this);
        if (fromParents != null) {
            return fromParents;
        }

        if (isPlatformSharedResource(name)) {
            URL fromPeers = findResourceFrom(peers, name, ConcurrentHashMap.newKeySet(), this);
            if (fromPeers != null) {
                return fromPeers;
            }
        }

        ClassLoader platform = getParent();
        return platform != null ? platform.getResource(name) : null;
    }

    @Override
    public Enumeration<URL> getResources(String name) throws java.io.IOException {
        if (VirtualFileSystemRouter.isManagedResourcePath(name)) {
            List<URL> managed = VirtualFileSystemRouter.resolveManagedResources(name);
            if (!managed.isEmpty()) {
                return Collections.enumeration(managed);
            }
        }

        LinkedHashSet<URL> results = new LinkedHashSet<>();

        URL local = findLocalResource(name);
        if (local != null) {
            results.add(local);
        }

        collectResourcesFrom(parents, name, ConcurrentHashMap.newKeySet(), results, this);

        if (isPlatformSharedResource(name)) {
            collectResourcesFrom(peers, name, ConcurrentHashMap.newKeySet(), results, this);
        }

        ClassLoader platform = getParent();
        if (platform != null) {
            Enumeration<URL> platformResources = platform.getResources(name);
            while (platformResources.hasMoreElements()) {
                results.add(platformResources.nextElement());
            }
        }

        return Collections.enumeration(results);
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        URL managed = VirtualFileSystemRouter.resolveManagedResource(name);
        if (managed != null) {
            try {
                return managed.openStream();
            } catch (Exception e) {
                return null;
            }
        }
        URL resource = getResource(name);
        if (resource == null) {
            return null;
        }
        try {
            return resource.openStream();
        } catch (Exception e) {
            return null;
        }
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

            // --- 3. Traverse the DAG: search in explicit dependency loaders first ---
            Class<?> fromParents = tryLoadFrom(parents, name, resolve, ConcurrentHashMap.newKeySet(), this);
            if (fromParents != null) {
                return fromParents;
            }

            Class<?> fromWeakPeers = tryLoadFromWeakPeers(name, resolve, ConcurrentHashMap.newKeySet(), this);
            if (fromWeakPeers != null) {
                return fromWeakPeers;
            }

            if (LifecycleManager.tryInstallWeakEdge(this, name)) {
                fromWeakPeers = tryLoadFromWeakPeers(name, resolve, ConcurrentHashMap.newKeySet(), this);
                if (fromWeakPeers != null) {
                    return fromWeakPeers;
                }
            }

            // --- 4. Peer loaders — RESTRICTED to PLATFORM_API_PREFIXES (ТЗ 3.2.1) ---
            // "Загрузка от соседей без явного ребра запрещена, исключение —
            //  интерфейсы и классы API платформы, которые доступны всем."
            if (isPlatformApiClass(name)) {
                Class<?> fromPeers = tryLoadFrom(peers, name, resolve, ConcurrentHashMap.newKeySet(), this);
                if (fromPeers != null) {
                    return fromPeers;
                }
            }

            // --- 5. If not found anywhere in the DAG, delegate to the platform classloader ---
            // This will find game classes, system classes, and anything from the base classpath.
            return super.loadClass(name, resolve);
        }
    }

    private Class<?> tryLoadFrom(Set<LazyInterMedClassLoader> loaders, String name, boolean resolve,
                                 Set<LazyInterMedClassLoader> visited,
                                 LazyInterMedClassLoader requester) throws ClassNotFoundException {
        for (LazyInterMedClassLoader candidate : loaders) {
            if (candidate == null || visited.contains(candidate)) {
                continue;
            }

            try {
                Class<?> c = candidate.loadClassInternal(name, resolve, visited, requester);
                if (c != null) {
                    return c;
                }
            } catch (ClassNotFoundException ignored) {
                // Continue searching other allowed loaders.
            }
        }
        return null;
    }

    private Class<?> tryLoadFromWeakPeers(String name,
                                          boolean resolve,
                                          Set<LazyInterMedClassLoader> visited,
                                          LazyInterMedClassLoader requester) throws ClassNotFoundException {
        for (Map.Entry<LazyInterMedClassLoader, WeakPeerPolicy> entry : weakPeers.entrySet()) {
            LazyInterMedClassLoader candidate = entry.getKey();
            WeakPeerPolicy policy = entry.getValue();
            if (candidate == null || policy == null || visited.contains(candidate) || !policy.allows(name, candidate)) {
                continue;
            }
            try {
                Class<?> c = candidate.loadClassInternal(name, resolve, visited, requester);
                if (c != null) {
                    return c;
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
    }

    private URL findResourceFrom(Set<LazyInterMedClassLoader> loaders, String name,
                                 Set<LazyInterMedClassLoader> visited,
                                 LazyInterMedClassLoader requester) {
        for (LazyInterMedClassLoader candidate : loaders) {
            if (candidate == null || visited.contains(candidate)) {
                continue;
            }
            URL found = candidate.findResourceInternal(name, visited, requester);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private void collectResourcesFrom(Set<LazyInterMedClassLoader> loaders, String name,
                                      Set<LazyInterMedClassLoader> visited,
                                      LinkedHashSet<URL> sink,
                                      LazyInterMedClassLoader requester) {
        for (LazyInterMedClassLoader candidate : loaders) {
            if (candidate == null || visited.contains(candidate)) {
                continue;
            }
            candidate.collectResourcesInternal(name, visited, sink, requester);
        }
    }

    private Class<?> loadClassInternal(String name, boolean resolve,
                                       Set<LazyInterMedClassLoader> visited,
                                       LazyInterMedClassLoader requester)
            throws ClassNotFoundException {
        if (!visited.add(this)) {
            throw new ClassNotFoundException(name);
        }

        Class<?> cachedClass = classCache.get(name);
        if (cachedClass != null) {
            return cachedClass;
        }

        synchronized (getClassLoadingLock(name)) {
            cachedClass = classCache.get(name);
            if (cachedClass != null) {
                return cachedClass;
            }

            try {
                Class<?> c = findClass(name);
                classCache.put(name, c);
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            } catch (ClassNotFoundException ignored) {
                // Continue through allowed edges.
            }

            for (LazyInterMedClassLoader parentLoader : this.parents) {
                if (!canTraverseParent(parentLoader, requester)) {
                    continue;
                }
                try {
                    Class<?> c = parentLoader.loadClassInternal(name, resolve, visited, requester);
                    return c;
                } catch (ClassNotFoundException ignored) {}
            }

            // Peer search inside recursive traversal — same restriction as top-level
            if (isPlatformApiClass(name)) {
                for (LazyInterMedClassLoader peerLoader : this.peers) {
                    try {
                        Class<?> c = peerLoader.loadClassInternal(name, resolve, visited, requester);
                        return c;
                    } catch (ClassNotFoundException ignored) {}
                }
            }
        }

        throw new ClassNotFoundException(name);
    }

    private URL findResourceInternal(String name, Set<LazyInterMedClassLoader> visited,
                                     LazyInterMedClassLoader requester) {
        if (!visited.add(this)) {
            return null;
        }

        URL local = findLocalResource(name);
        if (local != null) {
            return local;
        }

        for (LazyInterMedClassLoader parentLoader : this.parents) {
            if (!canTraverseParent(parentLoader, requester) || visited.contains(parentLoader)) {
                continue;
            }
            URL fromParent = parentLoader.findResourceInternal(name, visited, requester);
            if (fromParent != null) {
                return fromParent;
            }
        }

        if (isPlatformSharedResource(name)) {
            URL fromPeers = findResourceFrom(this.peers, name, visited, requester);
            if (fromPeers != null) {
                return fromPeers;
            }
        }

        return null;
    }

    private void collectResourcesInternal(String name, Set<LazyInterMedClassLoader> visited,
                                          LinkedHashSet<URL> sink,
                                          LazyInterMedClassLoader requester) {
        if (!visited.add(this)) {
            return;
        }

        URL local = findLocalResource(name);
        if (local != null) {
            sink.add(local);
        }

        for (LazyInterMedClassLoader parentLoader : this.parents) {
            if (!canTraverseParent(parentLoader, requester) || visited.contains(parentLoader)) {
                continue;
            }
            parentLoader.collectResourcesInternal(name, visited, sink, requester);
        }

        if (isPlatformSharedResource(name)) {
            collectResourcesFrom(this.peers, name, visited, sink, requester);
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        // --- 1. Remapping hook ---
        String targetName = remapClassName(name);

        // --- 2. Load and transform bytes ---
        byte[] bytes = loadBytes(targetName);
        if (bytes != null) {
            try (TransformationContext.Scope ignored = TransformationContext.enter(nodeId, targetName)) {
                for (BytecodeTransformer t : transformers) {
                    try {
                        bytes = t.transform(targetName, bytes);
                    } catch (Exception ex) {
                        System.err.println("[Transformer] Failed on " + targetName + ": " + ex.getMessage());
                    }
                }
            }
            definePackageIfNecessary(targetName);
            return defineClass(targetName, bytes, 0, bytes.length);
        }
        // If all attempts fail, throw.
        throw new ClassNotFoundException(name);
    }

    /**
     * Returns {@code true} if {@code className} belongs to a platform API package
     * that is permitted to cross peer boundaries (ТЗ 3.2.1).
     */
    static boolean isPlatformApiClass(String className) {
        for (String prefix : PLATFORM_API_PREFIXES) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }

    static boolean isPlatformSharedResource(String resourceName) {
        if (resourceName == null || resourceName.isBlank()) {
            return false;
        }
        if (resourceName.startsWith("META-INF/services/")) {
            return true;
        }
        if (resourceName.endsWith(".class")) {
            String binaryName = resourceName.substring(0, resourceName.length() - 6).replace('/', '.');
            return isPlatformApiClass(binaryName);
        }
        return false;
    }

    private String remapClassName(String name) {
        return InterMedRemapper.remapBinaryClassName(name);
    }

    private byte[] loadBytes(String name) {
        String path = name.replace('.', '/') + ".class";
        URL localResource = findLocalResource(path);
        if (localResource == null) {
            return null;
        }
        try (InputStream is = localResource.openStream()) {
            if (is != null) return is.readAllBytes();
        } catch (Exception ignore) {}
        return null;
    }

    private void definePackageIfNecessary(String className) {
        int lastDot = className.lastIndexOf('.');
        if (lastDot <= 0) {
            return;
        }

        String packageName = className.substring(0, lastDot);
        if (getDefinedPackage(packageName) != null) {
            return;
        }

        Manifest manifest = null;
        URL manifestUrl = findResource("META-INF/MANIFEST.MF");
        if (manifestUrl != null) {
            try (InputStream is = manifestUrl.openStream()) {
                manifest = new Manifest(is);
            } catch (Exception ignored) {
                manifest = null;
            }
        }

        try {
            if (manifest != null) {
                definePackage(packageName, manifest, null);
            } else {
                definePackage(packageName, null, null, null, null, null, null, null);
            }
        } catch (IllegalArgumentException ignored) {
            // Another racing thread may have already defined the package.
        }
    }

    private boolean canTraverseParent(LazyInterMedClassLoader parentLoader, LazyInterMedClassLoader requester) {
        if (parentLoader == null) {
            return false;
        }
        if (requester == this) {
            return true;
        }
        return getParentLinkPolicy(parentLoader).allowsTransitiveAccess();
    }

    private static Map<LazyInterMedClassLoader, ParentLinkPolicy> defaultParentPolicies(
        Set<LazyInterMedClassLoader> parents) {
        if (parents == null || parents.isEmpty()) {
            return Collections.emptyMap();
        }
        LinkedHashMap<LazyInterMedClassLoader, ParentLinkPolicy> links = new LinkedHashMap<>();
        for (LazyInterMedClassLoader parent : parents) {
            if (parent != null) {
                links.put(parent, ParentLinkPolicy.LOCAL_ONLY);
            }
        }
        return links;
    }

    /**
     * Records native library resolution through the singleton {@link NativeLinkerNode}
     * so JNI/JNA libraries have one canonical owner/diagnostic path regardless of
     * how many DAG nodes attempt to resolve the same library (ТЗ 3.5.1).
     *
     * <p>This method intentionally does not call {@link System#load(String)} or
     * {@link System#loadLibrary(String)}.  The JVM may call {@code findLibrary()}
     * while it is already performing a native load for the requesting classloader;
     * pre-loading here would create a second load attempt. Direct mod bytecode
     * calls are redirected to {@link NativeLinkerNode} by {@code SecurityHookTransformer}.
     */
    @Override
    protected String findLibrary(String libName) {
        String defaultPath = resolveNativeLibraryPath(libName);
        if (defaultPath != null) {
            return NativeLinkerNode.getInstance().claimResolvedLibrary(defaultPath, nodeId);
        }
        return null;
    }

    protected String resolveNativeLibraryPath(String libName) {
        return super.findLibrary(libName);
    }
}
