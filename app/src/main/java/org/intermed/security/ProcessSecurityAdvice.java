package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.Capability;
import org.intermed.core.security.CapabilityManager;

public final class ProcessSecurityAdvice {
    private ProcessSecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkProcessSpawn() {
        CapabilityManager.checkPermission(Capability.PROCESS_SPAWN);
    }
}
