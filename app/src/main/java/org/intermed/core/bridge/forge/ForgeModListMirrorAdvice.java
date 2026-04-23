package org.intermed.core.bridge.forge;

import net.bytebuddy.asm.Advice;

import java.util.List;

public final class ForgeModListMirrorAdvice {
    private ForgeModListMirrorAdvice() {}

    @Advice.OnMethodExit
    public static void appendInterMedMods(@Advice.Return(readOnly = false) List<?> returned) {
        returned = ForgeModListMirror.augmentModInfos(returned);
    }
}
