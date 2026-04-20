package org.intermed.core.classloading;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Undirected conflict graph used as input to {@link WelshPowellClusterer}
 * (ТЗ 3.2.1, Requirement 1).
 *
 * <h3>Semantics</h3>
 * <ul>
 *   <li>Each <b>vertex</b> is a {@link LibraryNode} — one library JAR.</li>
 *   <li>An <b>edge</b> between {@code A} and {@code B} means they <em>cannot</em>
 *       share the same {@link ShaderClassLoader} because they conflict.
 *       Typical conflict causes:
 *       <ul>
 *         <li>Same {@code groupId:artifactId}, incompatible versions.</li>
 *         <li>Overlapping Java package namespace from two different artifacts.</li>
 *       </ul>
 *   </li>
 *   <li>Libraries with <b>no edge</b> between them are conflict-free and may be
 *       bundled into a single shared shader loader.</li>
 * </ul>
 *
 * <p>Edges are stored symmetrically: adding conflict(A, B) also records conflict(B, A).
 */
public final class LibraryConflictGraph {

    /** adjacency list: node → set of conflicting nodes */
    private final Map<LibraryNode, Set<LibraryNode>> adjacency = new LinkedHashMap<>();

    // -------------------------------------------------------------------------
    // Mutation
    // -------------------------------------------------------------------------

    /**
     * Registers a library vertex.  Idempotent — safe to call multiple times
     * for the same node.
     */
    public void addLibrary(LibraryNode node) {
        adjacency.putIfAbsent(node, new HashSet<>());
    }

    /**
     * Records a mutual conflict between {@code a} and {@code b}.
     * The edge is undirected: both directions are stored.
     * Implicitly registers both nodes if not already present.
     */
    public void addConflict(LibraryNode a, LibraryNode b) {
        adjacency.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        adjacency.computeIfAbsent(b, k -> new HashSet<>()).add(a);
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    /** All registered library vertices. */
    public Set<LibraryNode> nodes() {
        return Collections.unmodifiableSet(adjacency.keySet());
    }

    /**
     * Returns the set of nodes that conflict with {@code node}
     * (i.e. nodes that cannot share {@code node}'s cluster).
     * Returns an empty set for an unknown node.
     */
    public Set<LibraryNode> neighbors(LibraryNode node) {
        Set<LibraryNode> n = adjacency.get(node);
        return n == null ? Collections.emptySet() : Collections.unmodifiableSet(n);
    }

    /**
     * The conflict-degree of {@code node}: the number of libraries it conflicts with.
     * Used by Welsh-Powell to sort vertices before coloring.
     */
    public int degree(LibraryNode node) {
        Set<LibraryNode> n = adjacency.get(node);
        return n == null ? 0 : n.size();
    }

    /** Total number of library vertices in the graph. */
    public int size() {
        return adjacency.size();
    }

    /** Returns {@code true} if the graph contains no vertices. */
    public boolean isEmpty() {
        return adjacency.isEmpty();
    }

    /** Total number of undirected conflict edges. */
    public int edgeCount() {
        return adjacency.values().stream().mapToInt(Set::size).sum() / 2;
    }
}
