package org.intermed.core.bridge;

import net.minecraft.resources.ResourceLocation;
import org.intermed.core.security.CapabilityManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Staging area for registry objects collected from Fabric mods before
 * Forge/NeoForge registration events fire.
 *
 * <p>Each mod's calls to {@code Registry.register()} are intercepted by
 * {@code RegistryRegisterAdvice} / {@code VirtualRegistryService} and routed
 * here.  {@link ForgeEventBridge} / {@link NeoForgeEventBridge} drain these
 * lists during the appropriate {@code RegisterEvent}.
 */
public class RegistryCache {

    public static class PendingEntry {
        public final String modId;
        public final ResourceLocation id;
        public final Object entry;

        public PendingEntry(ResourceLocation id, Object entry) {
            this(CapabilityManager.currentModIdOr("unknown"), id, entry);
        }

        public PendingEntry(String modId, ResourceLocation id, Object entry) {
            this.modId = (modId == null || modId.isBlank()) ? "unknown" : modId;
            this.id = id;
            this.entry = entry;
        }
    }

    // ── Core game objects ─────────────────────────────────────────────────────
    public static final List<PendingEntry> BLOCKS   = new ArrayList<>();
    public static final List<PendingEntry> ITEMS    = new ArrayList<>();
    public static final List<PendingEntry> ENTITIES = new ArrayList<>();
    public static final List<PendingEntry> SOUNDS   = new ArrayList<>();

    // ── Extended registry types (ТЗ 3.2.2) ───────────────────────────────────
    public static final List<PendingEntry> ENCHANTMENTS       = new ArrayList<>();
    public static final List<PendingEntry> MOB_EFFECTS        = new ArrayList<>();
    public static final List<PendingEntry> POTIONS            = new ArrayList<>();
    public static final List<PendingEntry> FLUID_TYPES        = new ArrayList<>();
    public static final List<PendingEntry> CREATIVE_MODE_TABS = new ArrayList<>();
    public static final List<PendingEntry> RECIPE_TYPES       = new ArrayList<>();
    public static final List<PendingEntry> BIOMES             = new ArrayList<>();
    public static final List<PendingEntry> FEATURES           = new ArrayList<>();
    public static final List<PendingEntry> PARTICLE_TYPES     = new ArrayList<>();
    public static final List<PendingEntry> BLOCK_ENTITY_TYPES = new ArrayList<>();

    /**
     * Classifies {@code entry} and appends it to the appropriate pending list.
     *
     * <p>Vanilla and Forge system namespaces are skipped — they have already
     * been registered by the base game.
     */
    public static void harvest(ResourceLocation id, Object entry) {
        String fullId = id.toString();
        if (fullId.startsWith("minecraft:") || fullId.startsWith("forge:")
                || fullId.startsWith("neoforge:") || fullId.startsWith("brigadier:")) {
            return;
        }

        if (entry instanceof net.minecraft.world.level.block.Block) {
            BLOCKS.add(new PendingEntry(id, entry));
        } else if (entry instanceof net.minecraft.world.item.Item) {
            ITEMS.add(new PendingEntry(id, entry));
        } else if (entry instanceof net.minecraft.world.entity.EntityType) {
            ENTITIES.add(new PendingEntry(id, entry));
        } else if (entry instanceof net.minecraft.sounds.SoundEvent) {
            SOUNDS.add(new PendingEntry(id, entry));
        } else if (entry instanceof net.minecraft.world.item.enchantment.Enchantment) {
            ENCHANTMENTS.add(new PendingEntry(id, entry));
        } else if (entry instanceof net.minecraft.world.effect.MobEffect) {
            MOB_EFFECTS.add(new PendingEntry(id, entry));
        } else if (entry instanceof net.minecraft.world.item.alchemy.Potion) {
            POTIONS.add(new PendingEntry(id, entry));
        } else if (entry instanceof net.minecraft.world.level.material.Fluid) {
            FLUID_TYPES.add(new PendingEntry(id, entry));
        } else if (entry instanceof net.minecraft.world.item.CreativeModeTab) {
            CREATIVE_MODE_TABS.add(new PendingEntry(id, entry));
        } else if (entry instanceof net.minecraft.world.item.crafting.RecipeType) {
            RECIPE_TYPES.add(new PendingEntry(id, entry));
        } else if (entry instanceof net.minecraft.world.level.levelgen.feature.Feature) {
            FEATURES.add(new PendingEntry(id, entry));
        } else if (entry instanceof net.minecraft.core.particles.ParticleType) {
            PARTICLE_TYPES.add(new PendingEntry(id, entry));
        } else if (entry instanceof net.minecraft.world.level.block.entity.BlockEntityType) {
            BLOCK_ENTITY_TYPES.add(new PendingEntry(id, entry));
        }
        // Note: biomes are data-driven in 1.20+ and registered via DataPack,
        // not through ForgeRegistries at init time. BIOMES list is populated
        // only for legacy mods that still use the static registry path.
    }

    /** Clears all pending lists — used after successful injection or in tests. */
    public static void clear() {
        BLOCKS.clear(); ITEMS.clear(); ENTITIES.clear(); SOUNDS.clear();
        ENCHANTMENTS.clear(); MOB_EFFECTS.clear(); POTIONS.clear();
        FLUID_TYPES.clear(); CREATIVE_MODE_TABS.clear(); RECIPE_TYPES.clear();
        BIOMES.clear(); FEATURES.clear(); PARTICLE_TYPES.clear(); BLOCK_ENTITY_TYPES.clear();
    }
}
