package org.intermed.core.bridge;

import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;

public class RegistryCache {
    
    public static class PendingEntry {
        public final ResourceLocation id;
        public final Object entry;
        public PendingEntry(ResourceLocation id, Object entry) { this.id = id; this.entry = entry; }
    }

    public static final List<PendingEntry> BLOCKS = new ArrayList<>();
    public static final List<PendingEntry> ITEMS = new ArrayList<>();
    public static final List<PendingEntry> ENTITIES = new ArrayList<>();
    public static final List<PendingEntry> SOUNDS = new ArrayList<>();

    public static void harvest(ResourceLocation id, Object entry) {
        String fullId = id.toString();
        
        // Игнорируем ядро игры и Forge, собираем только моды
        if (fullId.startsWith("minecraft:") || fullId.startsWith("forge:") || fullId.startsWith("brigadier:")) {
            return;
        }

        // Молча раскидываем по складам
        if (entry instanceof net.minecraft.world.level.block.Block) {
            BLOCKS.add(new PendingEntry(id, entry));
        } else if (entry instanceof net.minecraft.world.item.Item) {
            ITEMS.add(new PendingEntry(id, entry));
        } else if (entry instanceof net.minecraft.world.entity.EntityType) {
            ENTITIES.add(new PendingEntry(id, entry));
        } else if (entry instanceof net.minecraft.sounds.SoundEvent) {
            SOUNDS.add(new PendingEntry(id, entry));
        }
    }
}