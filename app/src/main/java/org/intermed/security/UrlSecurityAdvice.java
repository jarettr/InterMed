package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

import java.net.URL;

public final class UrlSecurityAdvice {
    private UrlSecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkUrlAccess(@Advice.This URL url) {
        CapabilityManager.checkNetworkTarget(url);
    }
}
