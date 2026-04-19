package org.intermed.core.registry;

import net.bytebuddy.asm.Advice;

import java.util.Optional;

/**
 * ByteBuddy advice for Optional-returning registry lookups.
 */
public final class RegistryOptionalGetAdvice {

    private RegistryOptionalGetAdvice() {}

    @Advice.OnMethodExit
    public static void onGetExit(
            @Advice.This(optional = true) Object self,
            @Advice.AllArguments Object[] args,
            @Advice.Return(readOnly = false) Optional<?> returned) {
        Object virtual = VirtualRegistryService.lookupValueForCurrentMod(self, args);
        if (virtual != null) {
            returned = Optional.of(virtual);
        }
    }
}
