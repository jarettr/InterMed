package org.intermed.core.classloading;

import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * Named concrete classloader per ТЗ 3.2.1 Requirement 1.
 * Each mod, library, and ecosystem bridge receives its own InterMedClassLoader instance,
 * forming a node in the ClassLoader DAG.
 *
 * Extends LazyInterMedClassLoader to inherit all DAG traversal, bytecode transformation,
 * and lazy-loading behaviour.
 */
public class InterMedClassLoader extends LazyInterMedClassLoader {

    public InterMedClassLoader(String nodeId, File jar,
                               Set<LazyInterMedClassLoader> parents,
                               ClassLoader platformClassLoader) {
        super(nodeId, jar, parents, platformClassLoader);
    }

    public InterMedClassLoader(String nodeId, File jar,
                               Map<LazyInterMedClassLoader, ParentLinkPolicy> parentLinks,
                               ClassLoader platformClassLoader) {
        super(nodeId, jar, parentLinks, platformClassLoader);
    }

    /** Convenience constructor for nodes with no initial JAR (e.g. virtual bridge nodes). */
    public InterMedClassLoader(String nodeId,
                               Set<LazyInterMedClassLoader> parents,
                               ClassLoader platformClassLoader) {
        super(nodeId, null, parents, platformClassLoader);
    }

    public InterMedClassLoader(String nodeId,
                               Map<LazyInterMedClassLoader, ParentLinkPolicy> parentLinks,
                               ClassLoader platformClassLoader) {
        super(nodeId, null, parentLinks, platformClassLoader);
    }

    /**
     * Full constructor with explicit peer registration (ТЗ 3.2.1).
     *
     * Peers are neighbours in the DAG without a direct dependency edge.
     * They may only supply classes from {@link LazyInterMedClassLoader#PLATFORM_API_PREFIXES}
     * — all other class lookups across peer boundaries are forbidden.
     *
     * @param peers Loaders allowed to contribute platform-API classes to this node.
     *              {@code null} is treated as an empty set.
     */
    public InterMedClassLoader(String nodeId,
                               File jar,
                               Set<LazyInterMedClassLoader> parents,
                               Set<LazyInterMedClassLoader> peers,
                               ClassLoader platformClassLoader) {
        super(nodeId, jar, parents, platformClassLoader);
        if (peers != null) peers.forEach(this::addPeer);
    }

    public InterMedClassLoader(String nodeId,
                               File jar,
                               Map<LazyInterMedClassLoader, ParentLinkPolicy> parentLinks,
                               Set<LazyInterMedClassLoader> peers,
                               ClassLoader platformClassLoader) {
        super(nodeId, jar, parentLinks, platformClassLoader);
        if (peers != null) peers.forEach(this::addPeer);
    }
}
