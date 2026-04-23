package org.intermed.core.remapping;

import org.intermed.core.classloading.BytecodeTransformer;
import org.intermed.core.classloading.DagAwareClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * Runs only the reflection/symbolic instrumentation pass without legacy
 * owner/member remapping.
 */
final class ReflectionOnlyTransformer implements BytecodeTransformer {

    @Override
    public byte[] transform(String className, byte[] originalBytes) {
        return InterMedRemapper.transformSymbolicOnlyClassBytes(className, originalBytes, true);
    }

    static byte[] instrument(String className, byte[] rawBytes) {
        try {
            ClassReader reader = new ClassReader(rawBytes);
            ClassWriter writer = DagAwareClassWriter.create(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            reader.accept(new ReflectionTransformer(writer), ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch (Throwable t) {
            System.err.println("[ReflectionOnly] Failed to instrument " + className + ": " + t.getMessage());
            return rawBytes;
        }
    }
}
