package org.intermed.core.classloading;

/**
 * Controls whether a parent edge in the DAG can be traversed only by the owning
 * loader or also re-exported to callers that reached the loader through another
 * dependency edge.
 */
public enum ParentLinkPolicy {
    /**
     * The parent is visible only while the current loader resolves its own
     * classes and resources. Requesters that reach this loader through a direct
     * dependency edge do not gain transitive access to the parent.
     */
    LOCAL_ONLY,

    /**
     * The parent may be re-exported to external requesters. Reserved for future
     * bridge/API edges that intentionally expose transitive content.
     */
    REEXPORT;

    public boolean allowsTransitiveAccess() {
        return this == REEXPORT;
    }
}
