package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

import java.io.File;

public final class FileMutationSecurityAdvice {
    private FileMutationSecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkMutation(@Advice.This File file) {
        CapabilityManager.checkFileWriteTarget(file);
    }
}
