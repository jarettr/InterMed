package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

public final class HttpRequestSecurityAdvice {
    private HttpRequestSecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkRequest(@Advice.Argument(0) Object request) {
        CapabilityManager.checkNetworkTarget(request);
    }
}
