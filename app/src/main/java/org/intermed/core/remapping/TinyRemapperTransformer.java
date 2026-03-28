package org.intermed.core.remapping;

import net.fabricmc.tinyremapper.TinyRemapper;
import org.intermed.core.classloading.BytecodeTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

public class TinyRemapperTransformer implements BytecodeTransformer {
    private final TinyRemapper tinyRemapper;

    public TinyRemapperTransformer(TinyRemapper tinyRemapper) {
        this.tinyRemapper = tinyRemapper;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytes) {
        if (tinyRemapper == null) return originalBytes;

        try {
            ClassReader reader = new ClassReader(originalBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);

            final Remapper baseRemapper = tinyRemapper.getEnvironment().getRemapper();

            Remapper interMedAdapter = new Remapper() {
                @Override
                public String map(String internalName) {
                    String mapped = baseRemapper.map(internalName);
                    if (mapped != null && !mapped.equals(internalName)) return mapped;
                    // Если Tiny не нашел, идем в диспетчер
                    return InterMedRemapper.translateRuntimeString(internalName.replace('/', '.')).replace('.', '/');
                }

                @Override
                public Object mapValue(Object value) {
                    if (value instanceof String str) {
                        return InterMedRemapper.translateRuntimeString(str);
                    }
                    return super.mapValue(value);
                }
            };

            ClassRemapper classRemapper = new ClassRemapper(writer, interMedAdapter);
            reader.accept(classRemapper, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();

        } catch (Throwable t) {
            return originalBytes;
        }
    }
}