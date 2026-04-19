package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

import java.net.SocketAddress;

public final class SocketSecurityAdvice {
    private SocketSecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkSocketAccess(@Advice.Argument(0) SocketAddress address) {
        CapabilityManager.checkNetworkTarget(address);
    }
}
