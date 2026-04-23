package org.intermed.core.bridge.forge;

import net.bytebuddy.asm.Advice;

public final class ForgePackRepositoryConstructorAdvice {
    private ForgePackRepositoryConstructorAdvice() {}

    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterConstructor(@Advice.This Object repository) {
        ForgePackRepositoryBridge.injectIntoPackRepository(repository);
    }
}
