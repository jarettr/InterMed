package org.intermed.core.classloading;

import java.util.ArrayList;
import java.util.List;

/**
 * O(1) Lowest Common Ancestor via Euler-tour reduction to Range Minimum Query
 * (ТЗ 3.5.4).
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li><b>Euler tour</b>: a DFS traversal of the class hierarchy tree records
 *       every node each time it is <em>entered or returned to</em>, producing
 *       an array {@code euler[]} of length {@code 2N-1} and a companion depth
 *       array {@code depth[]}.</li>
 *   <li><b>First-occurrence index</b>: {@code first[nodeId]} stores the
 *       first position in the Euler-tour array where that node appears.</li>
 *   <li><b>Sparse table</b>: a static RMQ structure built over {@code depth[]}.
 *       {@code sparse[k][i]} stores the index in {@code euler[]} with minimum
 *       depth in the range {@code [i, i + 2^k)}.</li>
 *   <li><b>LCA query</b>: LCA(u, v) = euler[rmq(first[u], first[v])].</li>
 * </ol>
 *
 * <h3>Complexity</h3>
 * <ul>
 *   <li>Build: O(N log N) time and space.</li>
 *   <li>Query: O(1) time.</li>
 * </ul>
 *
 * <h3>Integration</h3>
 * Used by {@link DagAwareClassWriter} to answer
 * {@code getCommonSuperClass(type1, type2)} in O(1) during bytecode generation,
 * replacing the default ASM reflection path that degrades exponentially in
 * large DAGs.
 */
public final class EulerTourRmq {

    private final int nodeCount;
    /** Euler-tour sequence: nodeId at each step (length = 2*nodeCount - 1). */
    private final int[] euler;
    /** Depth at each step in the Euler tour. */
    private final int[] depth;
    /** First occurrence of each nodeId in the Euler tour. */
    private final int[] first;
    /** Sparse table: sparse[k][i] = position in euler[] with min depth in [i, i+2^k). */
    private final int[][] sparse;
    /** log2 table: log2[n] = floor(log2(n)). */
    private final int[] log2;

    private EulerTourRmq(int nodeCount, int[] euler, int[] depth, int[] first) {
        this.nodeCount = nodeCount;
        this.euler = euler;
        this.depth = depth;
        this.first = first;

        int tourLen = euler.length;
        int logLen  = tourLen > 1 ? 32 - Integer.numberOfLeadingZeros(tourLen - 1) + 1 : 1;

        // ── Sparse table ──────────────────────────────────────────────────────
        this.sparse = new int[logLen][tourLen];
        // Base case: window of size 1
        for (int i = 0; i < tourLen; i++) {
            sparse[0][i] = i;
        }
        // Fill higher levels
        for (int k = 1; k < logLen; k++) {
            int half = 1 << (k - 1);
            for (int i = 0; i + (1 << k) <= tourLen; i++) {
                int left  = sparse[k - 1][i];
                int right = sparse[k - 1][i + half];
                sparse[k][i] = depth[left] <= depth[right] ? left : right;
            }
        }

        // ── log2 table ────────────────────────────────────────────────────────
        this.log2 = new int[tourLen + 1];
        for (int i = 2; i <= tourLen; i++) {
            log2[i] = log2[i >> 1] + 1;
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the LCA node ID of {@code u} and {@code v}.
     * Returns -1 if either node is unknown.
     *
     * @param u  node ID (0-based index)
     * @param v  node ID (0-based index)
     * @return LCA node ID, or -1
     */
    public int lca(int u, int v) {
        if (u < 0 || u >= nodeCount || v < 0 || v >= nodeCount) {
            return -1;
        }
        int lu = first[u];
        int lv = first[v];
        if (lu > lv) {
            int tmp = lu; lu = lv; lv = tmp;
        }
        return euler[rmq(lu, lv)];
    }

    /** Returns the depth of {@code nodeId} in the class hierarchy (root = 0). */
    public int depthOf(int nodeId) {
        if (nodeId < 0 || nodeId >= nodeCount) return -1;
        return depth[first[nodeId]];
    }

    public int nodeCount() { return nodeCount; }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Builder that assembles the Euler tour from an adjacency list representation
     * of the class hierarchy.
     *
     * <p>Node IDs are 0-based integer indices.  The hierarchy must be a rooted
     * forest; multiple roots are allowed (the builder uses a virtual root at
     * index -1 to unify them).  Pass isolated nodes (no parent, no children)
     * for leaf classes.
     */
    public static final class Builder {
        private final int nodeCount;
        /** children[i] = list of child node IDs of node i. */
        private final List<List<Integer>> children;

        public Builder(int nodeCount) {
            this.nodeCount = nodeCount;
            this.children = new ArrayList<>(nodeCount);
            for (int i = 0; i < nodeCount; i++) {
                children.add(new ArrayList<>());
            }
        }

        /**
         * Declares {@code child} as a direct subtype of {@code parent} in
         * the hierarchy.
         */
        public Builder addEdge(int parent, int child) {
            if (parent >= 0 && parent < nodeCount && child >= 0 && child < nodeCount) {
                children.get(parent).add(child);
            }
            return this;
        }

        /**
         * Builds the EulerTourRmq structure.
         *
         * @param roots  list of root node IDs (e.g. nodes with no parent)
         */
        public EulerTourRmq build(int[] roots) {
            if (nodeCount == 0 || roots == null || roots.length == 0) {
                return new EulerTourRmq(0, new int[0], new int[0], new int[0]);
            }

            int tourLen = 2 * nodeCount - 1;
            int[] euler = new int[tourLen];
            int[] depth = new int[tourLen];
            int[] first = new int[nodeCount];
            java.util.Arrays.fill(first, -1);

            // Iterative DFS to avoid stack overflow on deep hierarchies
            // Stack entries: (nodeId, depth, childIndex)
            int[] stackNode  = new int[nodeCount + 1];
            int[] stackDepth = new int[nodeCount + 1];
            int[] stackChild = new int[nodeCount + 1];
            int   sp         = 0;
            int   pos        = 0;

            for (int root : roots) {
                if (root < 0 || root >= nodeCount) continue;
                stackNode[sp]  = root;
                stackDepth[sp] = 0;
                stackChild[sp] = 0;
                sp++;

                while (sp > 0 && pos < tourLen) {
                    int node  = stackNode[sp - 1];
                    int d     = stackDepth[sp - 1];
                    int ci    = stackChild[sp - 1];

                    if (ci == 0) {
                        // First visit — record in Euler tour
                        euler[pos] = node;
                        depth[pos] = d;
                        if (first[node] < 0) first[node] = pos;
                        pos++;
                    }

                    List<Integer> kids = children.get(node);
                    if (ci < kids.size()) {
                        // Push child
                        stackChild[sp - 1]++;
                        int child = kids.get(ci);
                        stackNode[sp]  = child;
                        stackDepth[sp] = d + 1;
                        stackChild[sp] = 0;
                        sp++;
                    } else {
                        // Backtrack — record parent in Euler tour (unless we're back at root)
                        sp--;
                        if (sp > 0 && pos < tourLen) {
                            int parent = stackNode[sp - 1];
                            euler[pos] = parent;
                            depth[pos] = stackDepth[sp - 1];
                            pos++;
                        }
                    }
                }
            }

            // Fill any remaining positions with the last recorded node
            for (int i = pos; i < tourLen; i++) {
                euler[i] = euler[Math.max(0, pos - 1)];
                depth[i] = depth[Math.max(0, pos - 1)];
            }

            return new EulerTourRmq(nodeCount, euler, depth, first);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Range minimum query: returns the position in euler[] with minimum depth in [l, r]. */
    private int rmq(int l, int r) {
        if (l == r) return l;
        int k   = log2[r - l + 1];
        int left  = sparse[k][l];
        int right = sparse[k][r - (1 << k) + 1];
        return depth[left] <= depth[right] ? left : right;
    }
}
