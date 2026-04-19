package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

public final class VarHandleLookupSecurityAdvice {
    private VarHandleLookupSecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkLookupAccess(@Advice.Origin("#m") String operation) {
        CapabilityManager.checkVarHandleLookup(operation);
    }
}
