package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

import java.io.File;

public final class FileDirectoryReadSecurityAdvice {
    private FileDirectoryReadSecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkDirectoryRead(@Advice.This File file) {
        CapabilityManager.checkFileReadTarget(file);
    }
}
