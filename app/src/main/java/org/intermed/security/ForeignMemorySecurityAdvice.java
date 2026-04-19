package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

public final class ForeignMemorySecurityAdvice {
    private static final String ARENA_TYPE = "java.lang.foreign.Arena";
    private static final String MEMORY_SEGMENT_TYPE = "java.lang.foreign.MemorySegment";

    private ForeignMemorySecurityAdvice() {}

    @Advice.OnMethodEnter
    public static void checkForeignMemoryAccess(@Advice.This(optional = true) Object self,
                                                @Advice.Origin Class<?> ownerType,
                                                @Advice.Origin("#m") String methodName) {
        CapabilityManager.checkForeignMemoryAccess(resolveOwner(self, ownerType) + "." + methodName);
    }

    private static String resolveOwner(Object self, Class<?> ownerType) {
        if (isForeignType(self, ownerType, ARENA_TYPE)) {
            return ARENA_TYPE;
        }
        return MEMORY_SEGMENT_TYPE;
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
