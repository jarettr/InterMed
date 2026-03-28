package org.intermed.core.remapping;

import org.intermed.core.classloading.BytecodeTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.util.Map;
import java.util.Map.Entry;

public class LightweightRemapper extends Remapper implements BytecodeTransformer {
    private final MappingDictionary dict;

    public LightweightRemapper(MappingDictionary dict) {
        this.dict = dict;
    }

    @Override
    public byte[] transform(String className, byte[] originalBytes) {
        try {
            ClassReader reader = new ClassReader(originalBytes);
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            ClassRemapper adapter = new ClassRemapper(writer, this);
            reader.accept(adapter, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch (Exception e) {
            System.err.println("[Remapper] Error transforming " + className + ": " + e.getMessage());
            return originalBytes;
        }
    }

    @Override
    public String map(String internalName) {
        String mapped = dict.map(internalName);
        return mapped != null ? mapped : internalName;
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        // 1. Пытаемся найти точный перевод в словаре
        Map<String, String> methods = dict.methods.get(owner);
        if (methods != null) {
            String mapped = methods.get(name + descriptor);
            if (mapped != null) return mapped;
        }

        // 2. Fallback: если в словаре нет, используем SRG-хак
        if (name.startsWith("method_")) {
            return "m_" + name.substring(7) + "_";
        }
        return name;
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        // 1. Пытаемся найти точный перевод в словаре
        Map<String, String> fields = dict.fields.get(owner);
        if (fields != null) {
            String mapped = fields.get(name + descriptor);
            if (mapped != null) return mapped;
        }

        // 2. Fallback: если в словаре нет, используем SRG-хак
        if (name.startsWith("field_")) {
            return "f_" + name.substring(6) + "_";
        }
        return name;
    }

    @Override
    public Object mapValue(Object value) {
        if (value instanceof String str) {
            // Если строка содержит упоминание майнкрафта или типичные префиксы Fabric
            if (str.contains("class_") || str.contains("method_") || str.contains("field_") || str.contains("net/minecraft")) {
                String result = str;

                // 1. Ремаппинг классов (от длинных к коротким, чтобы не ломать части имен)
                for (Entry<String, String> entry : dict.classes.entrySet()) {
                    if (result.contains(entry.getKey())) {
                        result = result.replace(entry.getKey(), entry.getValue());
                    }
                }

                // 2. Ремаппинг методов и полей через Regex
                // Ищем паттерны: method_12345 или field_12345
                result = result.replaceAll("method_(\\d+)", "m_$1_");
                result = result.replaceAll("field_(\\d+)", "f_$1_");

                return result;
            }
        }
        return super.mapValue(value);
    }
}