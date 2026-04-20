package org.intermed.core.registry;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy advice for raw-id lookups such as {@code getRawId}/{@code getId}.
 */
public final class RegistryRawIdAdvice {

    private RegistryRawIdAdvice() {}

    @Advice.OnMethodExit
    public static void onGetExit(
            @Advice.AllArguments Object[] args,
            @Advice.Return(readOnly = false) int returned) {
        if (args == null || args.length == 0) {
            return;
        }
        Object value = args[args.length - 1];
        int virtualId = VirtualRegistry.getRawIdFast(value);
        if (virtualId >= 0) {
            returned = virtualId;
        }
    }
}
