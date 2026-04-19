package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

import java.nio.file.Path;

public final class FileChannelSecurityAdvice {
    private FileChannelSecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkChannelAccess(@Advice.Argument(0) Path path,
                                          @Advice.AllArguments Object[] args) {
        Object options = args != null && args.length > 1 ? args[1] : null;
        CapabilityManager.checkFileChannel(path, options);
    }
}
