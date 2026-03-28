package org.intermed.core.security;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.PrivilegedExceptionAction;

@SuppressWarnings("removal") // Подавляем предупреждение Java 21 о Deprecation
public class KernelContext {

    public static <T> T execute(PrivilegedAction<T> action) {
        return AccessController.doPrivileged(action);
    }

    public static <T> T executeWithException(PrivilegedExceptionAction<T> action) throws Exception {
        return AccessController.doPrivileged(action);
    }
}