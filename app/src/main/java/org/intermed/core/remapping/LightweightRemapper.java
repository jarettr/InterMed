package org.intermed.core.remapping;

import org.intermed.core.classloading.BytecodeTransformer;
import org.objectweb.asm.commons.Remapper;

/**
 * Legacy-compatible dictionary remapper. The modern pipeline uses
 * {@link TinyRemapperTransformer}, but some older call sites still expect this type.
 */
public final class LightweightRemapper extends Remapper implements BytecodeTransformer {
    private final MappingDictionary dict;

    public LightweightRemapper(MappingDictionary dict) {
        this.dict = dict;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytes) {
        InterMedRemapper.installDictionary(dict);
        return InterMedRemapper.transformClassBytes(className, originalBytes, false);
    }

    @Override
    public String map(String internalName) {
        return dict.mapClassName(internalName);
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        return dict.mapMethodName(owner, name, descriptor);
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        return dict.mapFieldName(owner, name, descriptor);
    }

    @Override
    public Object mapValue(Object value) {
        if (value instanceof String stringValue) {
            return InterMedRemapper.translateRuntimeString(stringValue);
        }
        return super.mapValue(value);
    }
}
