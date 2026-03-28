package org.intermed.core.classloading;

/**
 * Контракт для всех трансформаций байт-кода (Ремаппинг, Миксины, Инъекции).
 */
public interface BytecodeTransformer {
    byte[] transform(String className, byte[] originalBytes);
}