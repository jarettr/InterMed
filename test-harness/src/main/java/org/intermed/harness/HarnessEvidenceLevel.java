package org.intermed.harness;

import java.util.Locale;

/**
 * Evidence lanes tracked by the harness/report pipeline.
 *
 * <p>The current harness executor primarily implements the boot lane; higher
 * lanes already receive first-class artifact namespaces so they can be added
 * without mixing outputs later.</p>
 */
public enum HarnessEvidenceLevel {
    PARSED,
    BOOTED,
    SESSION_OK,
    SOAK_OK,
    STRICT_OK,
    BASELINE_OK;

    public String fileToken() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
