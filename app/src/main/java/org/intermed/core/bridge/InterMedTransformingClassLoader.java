package org.intermed.core.bridge;

import org.intermed.core.classloading.LazyInterMedClassLoader;
import org.intermed.core.classloading.ParentLinkPolicy;

import java.io.File;
import java.util.Map;
import java.util.Set;

/**
 * Bridge-layer classloader used by ClassLoaderManager and ecosystem bridges.
 * Exposes the same DAG + transformer infrastructure as LazyInterMedClassLoader
 * under the name expected by the bridge package.
 */
public class InterMedTransformingClassLoader extends LazyInterMedClassLoader {

    public InterMedTransformingClassLoader(String nodeId, File jar,
                                           Set<LazyInterMedClassLoader> parents,
                                           ClassLoader platformClassLoader) {
        super(nodeId, jar, parents, platformClassLoader);
    }

    public InterMedTransformingClassLoader(String nodeId, File jar,
                                           Map<LazyInterMedClassLoader, ParentLinkPolicy> parentLinks,
                                           ClassLoader platformClassLoader) {
        super(nodeId, jar, parentLinks, platformClassLoader);
    }

    /** Convenience constructor for bridge nodes with no initial JAR. */
    public InterMedTransformingClassLoader(String nodeId,
                                           Set<LazyInterMedClassLoader> parents,
                                           ClassLoader platformClassLoader) {
        super(nodeId, null, parents, platformClassLoader);
    }

    public InterMedTransformingClassLoader(String nodeId,
                                           Map<LazyInterMedClassLoader, ParentLinkPolicy> parentLinks,
                                           ClassLoader platformClassLoader) {
        super(nodeId, null, parentLinks, platformClassLoader);
    }
}
