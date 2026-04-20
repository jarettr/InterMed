package org.intermed.core.remapping;

import org.intermed.core.classloading.BytecodeTransformer;

/**
 * Bytecode transformer that applies the active {@link MappingDictionary}
 * (Intermediary → Mojang/SRG) to every class that passes through the
 * ClassLoader pipeline.
 *
 * <p>Delegates entirely to {@link InterMedRemapper#transformClassBytes} so
 * that all class and member remapping is driven by the dictionary populated at
 * boot time via {@link org.intermed.core.remapping.DictionaryParser} — no
 * mod-specific hardcoding required.
 *
 * <p>If no mappings have been loaded yet (dictionary is empty) the transformer
 * returns the original bytes unchanged, making it a zero-cost no-op in
 * environments where remapping is disabled.
 */
public class AsmSurgicalRemapper implements BytecodeTransformer {

    @Override
    public byte[] transform(String className, byte[] originalBytes) {
        return InterMedRemapper.transformClassBytes(className, originalBytes);
    }
}