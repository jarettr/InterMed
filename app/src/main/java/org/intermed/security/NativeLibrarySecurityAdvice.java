package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

public final class NativeLibrarySecurityAdvice {
    private NativeLibrarySecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkNativeLibraryAccess(@Advice.Origin("#t.#m") String origin,
                                                @Advice.AllArguments Object[] args) {
        Object target = args != null && args.length > 0 ? args[0] : null;
        CapabilityManager.checkNativeLibraryOperation(origin, target);
    }
}
