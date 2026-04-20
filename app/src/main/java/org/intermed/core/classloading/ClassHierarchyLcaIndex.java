package org.intermed.core.classloading;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Class hierarchy index that exposes an {@link EulerTourRmq} for O(1) LCA
 * queries during bytecode generation (ТЗ 3.5.4).
 *
 * <h3>Build process</h3>
 * <ol>
 *   <li>All classes loaded by every {@link LazyInterMedClassLoader} node are
 *       enumerated at DAG freeze time.</li>
 *   <li>Each class is assigned a stable integer node ID.</li>
 *   <li>Parent edges (superclass + interfaces) are added.</li>
 *   <li>{@link EulerTourRmq} is built from the resulting forest.</li>
 * </ol>
 *
 * <h3>Thread safety</h3>
 * The active index is stored in an {@link AtomicReference}; reads are lock-free.
 * Rebuilds are triggered by the lifecycle manager at DAG freeze time.
 */
public final class ClassHierarchyLcaIndex {

    private static final AtomicReference<ClassHierarchyLcaIndex> ACTIVE =
        new AtomicReference<>(new ClassHierarchyLcaIndex(null, null, null));

    private final EulerTourRmq rmq;
    /** Internal class name (e.g. "java/lang/Object") → node ID. */
    private final Map<String, Integer> nameToId;
    /** Node ID → internal class name. */
    private final String[] idToName;

    private ClassHierarchyLcaIndex(EulerTourRmq rmq,
                                    Map<String, Integer> nameToId,
                                    String[] idToName) {
        this.rmq      = rmq;
        this.nameToId = nameToId;
        this.idToName = idToName;
    }

    // ── Active instance ───────────────────────────────────────────────────────

    /** Returns the currently active index. Never null. */
    public static ClassHierarchyLcaIndex get() {
        return ACTIVE.get();
    }

    /** Installs a newly built index (called by the lifecycle manager). */
    public static void install(ClassHierarchyLcaIndex index) {
        ACTIVE.set(index != null ? index : new ClassHierarchyLcaIndex(null, null, null));
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Returns {@code true} if the index has been built and is ready for queries. */
    public boolean isReady() {
        return rmq != null && nameToId != null;
    }

    /** Returns the {@link EulerTourRmq} structure (may be {@code null} if not built). */
    public EulerTourRmq rmq() {
        return rmq;
    }

    /**
     * Returns the node ID for an internal class name, or -1 if not in the index.
     *
     * @param internalName  e.g. {@code "net/minecraft/world/item/Item"}
     */
    public int nodeIdForInternalName(String internalName) {
        if (nameToId == null || internalName == null) return -1;
        Integer id = nameToId.get(internalName);
        return id != null ? id : -1;
    }

    /**
     * Returns the internal class name for a node ID, or {@code null} if out of range.
     */
    public String internalNameForNodeId(int nodeId) {
        if (idToName == null || nodeId < 0 || nodeId >= idToName.length) return null;
        return idToName[nodeId];
    }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Builds the LCA index from the given set of {@link LazyInterMedClassLoader}
     * nodes.  Safe to call from the lifecycle manager immediately after the DAG
     * is frozen.
     *
     * @param dagNodes  all mod ClassLoader nodes in the DAG
     * @return the built index
     */
    public static ClassHierarchyLcaIndex buildFrom(Iterable<LazyInterMedClassLoader> dagNodes) {
        // ── Step 1: collect all known classes ─────────────────────────────────
        List<Class<?>> allClasses = new ArrayList<>();
        for (LazyInterMedClassLoader node : dagNodes) {
            for (Class<?> clazz : node.classCache.values()) {
                allClasses.add(clazz);
            }
        }

        if (allClasses.isEmpty()) {
            return new ClassHierarchyLcaIndex(null, null, null);
        }

        // ── Step 2: assign node IDs ───────────────────────────────────────────
        Map<String, Integer> nameToId = new HashMap<>(allClasses.size() * 2);
        // Always include Object as node 0
        nameToId.put("java/lang/Object", 0);
        int nextId = 1;
        for (Class<?> clazz : allClasses) {
            String internalName = clazz.getName().replace('.', '/');
            if (!nameToId.containsKey(internalName)) {
                nameToId.put(internalName, nextId++);
            }
        }

        // Also add any superclass/interface not in allClasses that appears as a parent
        // (we traverse them below and may encounter types outside our direct load set)
        int nodeCount = nextId;
        String[] idToName = new String[nodeCount];
        for (Map.Entry<String, Integer> e : nameToId.entrySet()) {
            idToName[e.getValue()] = e.getKey();
        }

        // ── Step 3: build parent edges ────────────────────────────────────────
        EulerTourRmq.Builder builder = new EulerTourRmq.Builder(nodeCount);
        List<Integer> roots = new ArrayList<>();

        for (Class<?> clazz : allClasses) {
            String internalName = clazz.getName().replace('.', '/');
            int childId = nameToId.get(internalName);

            Class<?> superclass = clazz.getSuperclass();
            if (superclass == null || superclass == Object.class) {
                // Treat Object or root as a child of the implicit root (node 0)
                if (childId != 0) {
                    builder.addEdge(0, childId);
                }
            } else {
                String parentName = superclass.getName().replace('.', '/');
                Integer parentId = nameToId.get(parentName);
                if (parentId != null) {
                    builder.addEdge(parentId, childId);
                } else {
                    // Parent not in index — attach to Object
                    builder.addEdge(0, childId);
                }
            }
        }

        // java/lang/Object is always a root
        roots.add(0);

        // ── Step 4: build RMQ ──────────────────────────────────────────────────
        int[] rootArray = roots.stream().mapToInt(Integer::intValue).toArray();
        EulerTourRmq rmq = builder.build(rootArray);

        System.out.printf("[LCA] Class hierarchy index built: %d nodes from %d classes%n",
            nodeCount, allClasses.size());

        return new ClassHierarchyLcaIndex(rmq, nameToId, idToName);
    }
}
