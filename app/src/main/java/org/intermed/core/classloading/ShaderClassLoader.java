package org.intermed.core.classloading;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * A shared ClassLoader that holds a cluster of conflict-free library JARs,
 * as determined by {@link WelshPowellClusterer} (ТЗ 3.2.1, Requirement 1).
 *
 * <h3>Purpose</h3>
 * The Welsh-Powell algorithm groups libraries that have no version or namespace
 * conflicts into the same colour (cluster).  Each cluster is represented by
 * exactly one {@code ShaderClassLoader}.  Multiple mod {@link InterMedClassLoader}
 * instances share the same shader loader as a DAG parent, so every library class
 * lives in exactly one place in the JVM heap — reducing ClassLoader count and
 * eliminating duplicate class definitions.
 *
 * <h3>Position in the DAG</h3>
 * Shader loaders are root-level nodes.  They are parents of mod loaders but
 * have no mod-owned parents themselves — they delegate directly to the platform
 * ClassLoader for anything they do not contain.
 *
 * <h3>No-transform guarantee</h3>
 * Library JARs inside a shader are loaded as-is.  Security and remapping
 * transformers are intentionally <em>not</em> registered here — those are
 * applied by the mod's own {@link InterMedClassLoader} when it processes
 * mod-owned bytecode.
 */
public final class ShaderClassLoader extends LazyInterMedClassLoader {

    private final int               clusterIndex;
    private final String            visibilityDomain;
    private final List<LibraryNode> libraries;

    /**
     * @param clusterIndex  Welsh-Powell colour index; unique per cluster.
     * @param libraries     Libraries whose JARs will be mounted into this loader.
     * @param parents       Parent DAG nodes (typically empty for shader roots).
     * @param platform      Platform / bootstrap ClassLoader; receives all
     *                      unresolved lookups.
     */
    public ShaderClassLoader(int clusterIndex,
                             String visibilityDomain,
                             List<LibraryNode> libraries,
                             Set<LazyInterMedClassLoader> parents,
                             ClassLoader platform) {
        super("shader-cluster-" + clusterIndex, /* jar= */ null, parents, platform);
        this.clusterIndex = clusterIndex;
        this.visibilityDomain = LibraryDiscovery.normalizeVisibilityDomain(visibilityDomain);
        this.libraries    = Collections.unmodifiableList(libraries);

        // Mount every library JAR belonging to this cluster
        for (LibraryNode lib : libraries) {
            if (lib.jar != null && lib.jar.exists()) {
                addJar(lib.jar);
                System.out.printf("[Shader-%d] Mounted: %s%n", clusterIndex, lib);
            } else if (lib.jar != null) {
                System.err.printf("[Shader-%d] JAR not found, skipped: %s%n",
                    clusterIndex, lib.jar);
            }
        }

        System.out.printf("[Shader-%d] Ready — %d libraries bundled.%n",
            clusterIndex, libraries.size());
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    /** Welsh-Powell cluster index (colour). */
    public int getClusterIndex() {
        return clusterIndex;
    }

    public String getVisibilityDomain() {
        return visibilityDomain;
    }

    /** Immutable list of all libraries housed in this shader. */
    public List<LibraryNode> getLibraries() {
        return libraries;
    }

    @Override
    public String toString() {
        return "ShaderClassLoader[cluster=" + clusterIndex +
               ", scope=" + visibilityDomain +
               ", libs=" + libraries.size() + "]";
    }
}
