package org.intermed.core.remapping;

import org.intermed.core.classloading.BytecodeTransformer;

/**
 * Compatibility wrapper kept as a dedicated transformer stage for explicit reflection handling.
 */
public final class ReflectionRemapper implements BytecodeTransformer {
    @Override
    public byte[] transform(String className, byte[] bytes) {
        return InterMedRemapper.transformClassBytes(className, bytes, true);
    }

    public static String translate(String original) {
        return InterMedRemapper.translateRuntimeString(original);
    }
}
