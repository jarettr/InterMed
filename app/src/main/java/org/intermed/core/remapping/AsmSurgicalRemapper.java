package org.intermed.core.remapping;

import org.intermed.core.classloading.BytecodeTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Хирургический in-memory транслятор байт-кода.
 * Переводит критические имена Fabric (Intermediary/MojMaps) в формат Forge (SRG).
 */
public class AsmSurgicalRemapper implements BytecodeTransformer {

    @Override
    public byte[] transform(String className, byte[] originalBytes) {
        try {
            ClassReader reader = new ClassReader(originalBytes);
            ClassWriter writer = new ClassWriter(0); // 0 для скорости, так как мы меняем только строки

            Remapper dictionary = new Remapper() {
                @Override
                public String map(String internalName) {
                    // Перевод для AppleSkin (Intermediary -> Forge/Mojang)
                    if (internalName.equals("net/minecraft/class_2960")) {
                        return "net/minecraft/resources/ResourceLocation";
                    }
                    if (internalName.equals("net/minecraft/class_1657")) {
                        return "net/minecraft/world/entity/player/Player";
                    }
                    if (internalName.equals("net/minecraft/class_1799")) {
                        return "net/minecraft/world/food/FoodData";
                    }
                    if (internalName.equals("net/minecraft/class_1792")) {
                        return "net/minecraft/world/item/Item";
                    }
                    if (internalName.equals("net/minecraft/class_1799$class_4612")) {
                        return "net/minecraft/world/food/FoodProperties";
                    }
                    
                    // Универсальный проброс
                    return super.map(internalName);
                }
            };

            ClassRemapper classRemapper = new ClassRemapper(writer, dictionary);
            reader.accept(classRemapper, 0);
            
            return writer.toByteArray();
        } catch (Exception e) {
            return originalBytes;
        }
    }
}