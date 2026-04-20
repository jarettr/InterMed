package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

public final class VarHandleSecurityAdvice {
    private VarHandleSecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkVarHandleAccess(@Advice.Origin("#m") String memberName) {
        CapabilityManager.checkVarHandleOperation(memberName);
    }
}
