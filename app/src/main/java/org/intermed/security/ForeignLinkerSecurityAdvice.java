package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

public final class ForeignLinkerSecurityAdvice {
    private static final String LINKER_TYPE = "java.lang.foreign.Linker";
    private static final String SYMBOL_LOOKUP_TYPE = "java.lang.foreign.SymbolLookup";

    private ForeignLinkerSecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkForeignLinkerAccess(@Advice.This(optional = true) Object self,
                                                @Advice.Origin Class<?> ownerType,
                                                @Advice.Origin("#m") String methodName,
                                                @Advice.AllArguments Object[] args) {
        Object target = args != null && args.length > 0 ? args[0] : null;
        CapabilityManager.checkForeignLinkerOperation(resolveOwner(self, ownerType) + "." + methodName, target);
    }

    private static String resolveOwner(Object self, Class<?> ownerType) {
        if (isForeignType(self, ownerType, SYMBOL_LOOKUP_TYPE)) {
            return SYMBOL_LOOKUP_TYPE;
        }
        return LINKER_TYPE;
    }

    private static boolean isForeignType(Object self, Class<?> ownerType, String typeName) {
        try {
            Class<?> foreignType = Class.forName(typeName);
            return (self != null && foreignType.isInstance(self))
                || (ownerType != null && foreignType.isAssignableFrom(ownerType));
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
