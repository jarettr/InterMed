package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

public final class DynamicClassDefinitionSecurityAdvice {
    private DynamicClassDefinitionSecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkDefinitionAccess(@Advice.Origin("#m") String operation) {
        CapabilityManager.checkDynamicClassDefinition(operation);
    }
}
