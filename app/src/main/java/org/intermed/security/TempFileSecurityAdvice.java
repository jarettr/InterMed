package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

import java.io.File;
import java.nio.file.Path;

public final class TempFileSecurityAdvice {
    private TempFileSecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkTempCreation(@Advice.AllArguments Object[] args) {
        Object directoryHint = null;
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Path || arg instanceof File) {
                    directoryHint = arg;
                    break;
                }
            }
        }
        CapabilityManager.checkTempFileTarget(directoryHint);
    }
}
