package org.intermed.harness.report;

import org.intermed.harness.HarnessConfig;
import org.intermed.harness.HarnessEvidenceLevel;
import org.intermed.harness.discovery.CorpusLock;

/**
 * Extra machine-readable context for a harness results artifact.
 */
public record HarnessRunMetadata(
    HarnessEvidenceLevel evidenceLevel,
    String effectiveExecutionLane,
    HarnessConfig.TestMode mode,
    HarnessConfig.LoaderFilter loaderFilter,
    String mcVersion,
    int shardCount,
    int shardIndex,
    boolean resumeFailed,
    int retryFlaky,
    int totalPlannedCases,
    int shardPlannedCases,
    int selectedCases,
    int carriedForwardCases,
    int previousFailingCases,
    int missingCases,
    String corpusSchema,
    String corpusFingerprint,
    int corpusTotalCandidates,
    int corpusRunnableCandidates,
    String corpusLockFile
) {
    public static HarnessRunMetadata from(HarnessConfig config,
                                          CorpusLock lock,
                                          int totalPlannedCases,
                                          int shardPlannedCases,
                                          int selectedCases,
                                          int carriedForwardCases,
                                          int previousFailingCases,
                                          int missingCases,
                                          String corpusLockFile) {
        CorpusLock.Summary summary = lock == null ? null : lock.summary();
        return new HarnessRunMetadata(
            config.evidenceLevel,
            HarnessEvidenceLevel.BOOTED.name(),
            config.mode,
            config.loaderFilter,
            config.mcVersion,
            config.shardCount,
            config.shardIndex,
            config.resumeFailed,
            config.retryFlaky,
            totalPlannedCases,
            shardPlannedCases,
            selectedCases,
            carriedForwardCases,
            previousFailingCases,
            missingCases,
            lock == null ? "unknown" : lock.schema(),
            lock == null ? "unavailable" : lock.corpusFingerprint(),
            summary == null ? 0 : summary.totalCandidates(),
            summary == null ? 0 : summary.runnableCandidates(),
            corpusLockFile == null ? "" : corpusLockFile
        );
    }
}
