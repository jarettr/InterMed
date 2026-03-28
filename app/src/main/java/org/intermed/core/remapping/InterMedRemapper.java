package org.intermed.core.remapping;

import org.intermed.core.util.MappingManager;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Центральный Диспетчер Ремаппинга InterMed.
 * Служит мостом для систем рефлексии и старых загрузчиков.
 */
public class InterMedRemapper {

    /**
     * Статический метод для перевода строк рефлексии (используется ReflectionRemapper)
     */
    public static String translateRuntimeString(String original) {
        if (original == null || !original.contains("minecraft")) return original;
        String internalName = original.replace('.', '/');
        
        String translated = MappingManager.translate(internalName);
        if (translated != null) {
            return translated.replace('/', '.');
        }
        return original;
    }

    /**
     * Метод глубокой трансформации для ClassLoader'ов
     */
    public static byte[] transformClassBytes(String className, byte[] rawBytes) {
        try {
            ClassReader reader = new ClassReader(rawBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);

            // Используем логику MappingManager для базовой трансформации
            Remapper mapper = new Remapper() {
                @Override
                public String map(String internalName) {
                    String t = MappingManager.translate(internalName);
                    return t != null ? t : internalName;
                }
            };

            ClassRemapper remapper = new ClassRemapper(writer, mapper);
            reader.accept(remapper, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch (Exception e) {
            return rawBytes;
        }
    }
}