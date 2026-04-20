package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

public final class FileWriteSecurityAdvice {
    private FileWriteSecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkWriteAccess(@Advice.AllArguments Object[] args) {
        if (args == null || args.length == 0 || args[0] == null) {
            return;
        }

        if (args.length > 1 && args[1] instanceof String mode) {
            if (mode.contains("w")) {
                CapabilityManager.checkRandomAccessTarget(args[0], mode);
            }
            return;
        }

        CapabilityManager.checkFileWriteTarget(args[0]);
    }
}
