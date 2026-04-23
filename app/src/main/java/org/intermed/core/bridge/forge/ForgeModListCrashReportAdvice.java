package org.intermed.core.bridge.forge;

import net.bytebuddy.asm.Advice;

public final class ForgeModListCrashReportAdvice {
    private ForgeModListCrashReportAdvice() {}

    @Advice.OnMethodExit
    public static void appendInterMedMods(@Advice.Return(readOnly = false) String returned) {
        returned = ForgeModListMirror.augmentCrashReport(returned);
    }
}
