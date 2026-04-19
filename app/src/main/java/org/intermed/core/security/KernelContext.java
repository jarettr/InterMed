package org.intermed.core.security;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;

@SuppressWarnings("removal") // Подавляем предупреждение Java 21 о Deprecation
public class KernelContext {

    private static final ThreadLocal<Integer> PRIVILEGED_DEPTH =
        ThreadLocal.withInitial(() -> 0);

    public static <T> T execute(PrivilegedAction<T> action) {
        enter();
        try {
            return AccessController.doPrivileged(action);
        } finally {
            exit();
        }
    }

    public static <T> T executeWithException(PrivilegedExceptionAction<T> action) throws Exception {
        enter();
        try {
            return AccessController.doPrivileged(action);
        } finally {
            exit();
        }
    }

    public static boolean isActive() {
        return PRIVILEGED_DEPTH.get() > 0;
    }

    private static void enter() {
        PRIVILEGED_DEPTH.set(PRIVILEGED_DEPTH.get() + 1);
    }

    private static void exit() {
        int depth = PRIVILEGED_DEPTH.get() - 1;
        if (depth <= 0) {
            PRIVILEGED_DEPTH.remove();
            return;
        }
        PRIVILEGED_DEPTH.set(depth);
    }
}
