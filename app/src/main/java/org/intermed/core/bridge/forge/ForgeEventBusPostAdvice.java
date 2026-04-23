package org.intermed.core.bridge.forge;

import net.bytebuddy.asm.Advice;

public final class ForgeEventBusPostAdvice {
    private ForgeEventBusPostAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beforePost(@Advice.Argument(0) Object event) {
        ForgePackRepositoryBridge.installIfPackFinderEvent(event);
    }
}
