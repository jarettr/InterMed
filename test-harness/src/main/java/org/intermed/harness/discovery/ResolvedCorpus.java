package org.intermed.harness.discovery;

import java.util.List;

/**
 * Pairing of the full discovery corpus lock and the subset currently eligible
 * for the active harness lane.
 */
public record ResolvedCorpus(
    CorpusLock lock,
    List<ModCandidate> runnableMods
) {
    public ResolvedCorpus {
        runnableMods = runnableMods == null ? List.of() : List.copyOf(runnableMods);
    }
}
