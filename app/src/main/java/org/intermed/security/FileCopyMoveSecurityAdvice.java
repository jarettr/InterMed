package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

public final class FileCopyMoveSecurityAdvice {
    private FileCopyMoveSecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkCopyOrMove(@Advice.AllArguments Object[] args) {
        if (args == null || args.length < 2) {
            return;
        }
        CapabilityManager.checkFileTransfer(args[0], args[1]);
    }
}
