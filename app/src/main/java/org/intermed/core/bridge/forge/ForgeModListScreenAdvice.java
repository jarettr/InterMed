package org.intermed.core.bridge.forge;

import net.bytebuddy.asm.Advice;

public final class ForgeModListScreenAdvice {
    private ForgeModListScreenAdvice() {}

    @Advice.OnMethodExit
    public static void syncInterMedMods(@Advice.This Object screen) {
        ForgeModListMirror.syncScreenLists(screen);
    }
}
