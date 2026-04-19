package org.intermed.core.security;

/**
 * Перечисление возможных разрешений (Capabilities) для политик безопасности песочницы.
 * ТЗ 3.2.5 (Требование 8).
 */
public enum Capability {
    FILE_READ,
    FILE_WRITE,
    NETWORK_CONNECT,
    /** Low-level memory access via modern JVM APIs such as VarHandle and FFM. */
    MEMORY_ACCESS,
    /**
     * Legacy alias for low-level memory access retained for backwards
     * compatibility with existing manifests and policy overrides.
     */
    UNSAFE_ACCESS,
    REFLECTION_ACCESS,
    /** Spawning OS child processes via {@code ProcessBuilder} or {@code Runtime.exec()}. */
    PROCESS_SPAWN,
    /** Loading native libraries via {@code System.loadLibrary} / {@code System.load}. */
    NATIVE_LIBRARY
}
