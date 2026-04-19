package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

import java.net.URLConnection;

public final class UrlConnectionSecurityAdvice {
    private UrlConnectionSecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkConnectionAccess(@Advice.This URLConnection connection) {
        CapabilityManager.checkNetworkTarget(connection);
    }
}
