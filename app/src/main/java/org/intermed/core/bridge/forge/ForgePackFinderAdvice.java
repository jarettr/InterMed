package org.intermed.core.bridge.forge;

import net.bytebuddy.asm.Advice;

public final class ForgePackFinderAdvice {
    private ForgePackFinderAdvice() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterConstructor(@Advice.This Object event) {
        ForgePackRepositoryBridge.install(event);
    }
}
