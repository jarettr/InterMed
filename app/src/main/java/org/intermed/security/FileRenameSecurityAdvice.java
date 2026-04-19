package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

import java.io.File;

public final class FileRenameSecurityAdvice {
    private FileRenameSecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkRename(@Advice.This File source, @Advice.Argument(0) File target) {
        CapabilityManager.checkFileTransfer(source, target);
    }
}
