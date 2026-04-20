package org.intermed.core.classloading;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Welsh-Powell graph-colouring algorithm adapted for library clustering
 * (ТЗ 3.2.1, Requirement 1).
 *
 * <h3>Problem mapping</h3>
 * <pre>
 *   Graph vertex  →  library JAR ({@link LibraryNode})
 *   Graph edge    →  version/namespace conflict — cannot share a ClassLoader
 *   Graph colour  →  cluster index (one shared {@link ShaderClassLoader} per colour)
 * </pre>
 *
 * <h3>Correctness invariant</h3>
 * No two libraries in the same cluster share a conflict edge, so the entire
 * cluster can be loaded by a single ClassLoader without class-loading
 * ambiguities or split-package issues.
 *
 * <h3>Algorithm steps</h3>
 * <ol>
 *   <li>Sort vertices by <em>degree descending</em> — most-conflicting nodes
 *       are coloured first, which produces the fewest distinct colours.</li>
 *   <li>Iterate through the sorted list.  For each uncoloured node, assign
 *       the lowest colour index not already held by any of its neighbours.</li>
 *   <li>Group nodes by colour index to produce the final clusters.</li>
 * </ol>
 *
 * <p>Time complexity: O(V² + E) — acceptable for the library sets we expect
 * (typically tens to a few hundred JARs per installation).
 */
public final class WelshPowellClusterer {

    private WelshPowellClusterer() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Runs the Welsh-Powell algorithm on the supplied conflict graph and returns
     * a mapping from <em>cluster index</em> to the list of libraries in that cluster.
     *
     * <p>Libraries in the same cluster are guaranteed to have no conflict edges
     * between them and may therefore share a single {@link ShaderClassLoader}.
     *
     * @param graph The conflict graph produced by {@link LibraryDiscovery}.
     * @return An ordered map (by ascending cluster index) of clusters.
     *         Returns an empty map if the graph has no nodes.
     */
    public static Map<Integer, List<LibraryNode>> cluster(LibraryConflictGraph graph) {
        if (graph.isEmpty()) {
            System.out.println("[Welsh-Powell] No library nodes — clustering skipped.");
            return Collections.emptyMap();
        }

        // ── Step 1: sort vertices by degree descending ────────────────────────
        List<LibraryNode> sorted = new ArrayList<>(graph.nodes());
        sorted.sort((a, b) -> graph.degree(b) - graph.degree(a));

        // ── Step 2: greedy colour assignment ──────────────────────────────────
        // colorAssignment maps each node to its assigned colour (cluster index).
        Map<LibraryNode, Integer> colorAssignment = new HashMap<>(sorted.size());

        for (LibraryNode node : sorted) {
            // Collect all colours already used by conflict-neighbours
            Set<Integer> neighborColors = new HashSet<>();
            for (LibraryNode neighbor : graph.neighbors(node)) {
                Integer c = colorAssignment.get(neighbor);
                if (c != null) neighborColors.add(c);
            }

            // Assign the smallest non-conflicting colour (= cluster index)
            int color = 0;
            while (neighborColors.contains(color)) {
                color++;
            }
            colorAssignment.put(node, color);
        }

        // ── Step 3: group nodes by colour → clusters ──────────────────────────
        Map<Integer, List<LibraryNode>> clusters = new LinkedHashMap<>();
        for (Map.Entry<LibraryNode, Integer> entry : colorAssignment.entrySet()) {
            clusters.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
                    .add(entry.getKey());
        }

        // ── Diagnostics ───────────────────────────────────────────────────────
        int libs        = sorted.size();
        int clusterCnt  = clusters.size();
        double reduction = libs == 0 ? 0.0 : (1.0 - (double) clusterCnt / libs) * 100.0;

        System.out.printf(
            "[Welsh-Powell] %d libraries → %d shader clusters  " +
            "(ClassLoader reduction: %.0f%%, conflict edges: %d)\n",
            libs, clusterCnt, reduction, graph.edgeCount()
        );
        clusters.forEach((idx, libs2) ->
            System.out.printf("  [Cluster %d] %s%n", idx, libs2)
        );

        return clusters;
    }
}
