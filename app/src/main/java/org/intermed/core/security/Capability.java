package org.intermed.core.security;

/**
 * Перечисление возможных разрешений (Capabilities) для политик безопасности песочницы.
 * ТЗ 3.2.5 (Требование 8).
 */
public enum Capability {
    FILE_READ,
    FILE_WRITE,
    NETWORK_CONNECT,
    UNSAFE_ACCESS,
    REFLECTION_ACCESS
}