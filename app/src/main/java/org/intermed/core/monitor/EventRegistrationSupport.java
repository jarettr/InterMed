package org.intermed.core.monitor;

import org.intermed.core.classloading.TransformationContext;
import org.intermed.core.security.CapabilityManager;

/**
 * Shared registration context for Fabric-compatible event shims.
 */
public final class EventRegistrationSupport {

    private EventRegistrationSupport() {}

    public static String captureRegistrationModId() {
        String modId = CapabilityManager.currentModIdOr(
            TransformationContext.currentModIdOr("unknown")
        );
        return modId == null || modId.isBlank() ? "unknown" : modId;
    }
}
