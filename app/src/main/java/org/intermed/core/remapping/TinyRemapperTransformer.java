package org.intermed.core.remapping;

import net.fabricmc.tinyremapper.TinyRemapper;
import org.intermed.core.classloading.BytecodeTransformer;
import org.intermed.core.classloading.DagAwareClassWriter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Runtime remapping transformer used by custom ClassLoaders.
 *
 * <p>If a real TinyRemapper instance is available, its environment remapper is used
 * first, and unresolved names fall back to the InterMed runtime dictionary. When
 * TinyRemapper is unavailable, the transformer delegates to the native InterMed
 * ASM pipeline so remapping still happens on every defineClass.
 */
public final class TinyRemapperTransformer implements BytecodeTransformer {
    private final TinyRemapper tinyRemapper;

    public TinyRemapperTransformer() {
        this(null);
    }

    public TinyRemapperTransformer(TinyRemapper tinyRemapper) {
        this.tinyRemapper = tinyRemapper;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytes) {
        if (SelectiveRemapBypassPolicy.useSymbolicOnly(className)) {
            return InterMedRemapper.transformSymbolicOnlyClassBytes(className, originalBytes, true);
        }

        if (tinyRemapper == null) {
            return InterMedRemapper.transformClassBytes(className, originalBytes);
        }

        try {
            ClassReader reader = new ClassReader(originalBytes);
            ClassWriter writer = DagAwareClassWriter.create(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);

            final Remapper baseRemapper = tinyRemapper.getEnvironment().getRemapper();
            Remapper adapter = new Remapper() {
                @Override
                public String map(String internalName) {
                    String mapped = baseRemapper.map(internalName);
                    if (mapped != null && !mapped.equals(internalName)) {
                        return mapped;
                    }
                    return MappingDictionary.normalizeInternalName(
                        InterMedRemapper.remapBinaryClassName(internalName)
                    );
                }

                @Override
                public String mapMethodName(String owner, String name, String descriptor) {
                    String mapped = baseRemapper.mapMethodName(owner, name, descriptor);
                    return mapped != null && !mapped.equals(name)
                        ? mapped
                        : LifecycleAwareDictionaryHolder.mapMethodName(owner, name, descriptor);
                }

                @Override
                public String mapFieldName(String owner, String name, String descriptor) {
                    String mapped = baseRemapper.mapFieldName(owner, name, descriptor);
                    return mapped != null && !mapped.equals(name)
                        ? mapped
                        : LifecycleAwareDictionaryHolder.mapFieldName(owner, name, descriptor);
                }

                @Override
                public Object mapValue(Object value) {
                    if (value instanceof String stringValue) {
                        return InterMedRemapper.translateRuntimeString(stringValue);
                    }
                    return super.mapValue(value);
                }
            };

            ClassRemapper classRemapper = new ClassRemapper(writer, adapter);
            reader.accept(new ReflectionTransformer(classRemapper), ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch (Throwable t) {
            System.err.println("[TinyRemapper] Failed to transform " + className + ": " + t.getMessage());
            return InterMedRemapper.transformClassBytes(className, originalBytes);
        }
    }

    /**
     * Isolated holder to keep the fallback dictionary access explicit in Tiny mode.
     */
    private static final class LifecycleAwareDictionaryHolder {
        private static String mapMethodName(String owner, String name, String descriptor) {
            return org.intermed.core.lifecycle.LifecycleManager.DICTIONARY.mapMethodName(owner, name, descriptor);
        }

        private static String mapFieldName(String owner, String name, String descriptor) {
            return org.intermed.core.lifecycle.LifecycleManager.DICTIONARY.mapFieldName(owner, name, descriptor);
        }
    }
}
