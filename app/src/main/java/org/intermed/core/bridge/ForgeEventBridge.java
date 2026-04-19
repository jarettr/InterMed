package org.intermed.core.bridge;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.intermed.core.bridge.assets.AssetInjector;
import org.intermed.core.registry.VirtualRegistryService;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Forge mod-bus subscriber that drains {@link RegistryCache} into the
 * appropriate Forge registries when each {@link RegisterEvent} fires.
 *
 * <p>Covers all Forge-side registry types required by ТЗ 3.2.2 so that
 * Fabric mods' registry calls (intercepted by the VirtualRegistry layer)
 * are properly injected into Forge's registry system.
 */
@Mod.EventBusSubscriber(modid = "intermed", bus = Mod.EventBusSubscriber.Bus.MOD)
public class ForgeEventBridge {

    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void onRegister(RegisterEvent event) {
        String regName = event.getRegistryKey().location().toString();

        // ── Core ──────────────────────────────────────────────────────────────
        if (regName.equals("minecraft:block")) {
            injectAll("minecraft:block", "Blocks", RegistryCache.BLOCKS,
                (net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.BLOCKS);
        }
        if (regName.equals("minecraft:item")) {
            injectAll("minecraft:item", "Items", RegistryCache.ITEMS,
                (net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.ITEMS);
        }
        if (regName.equals("minecraft:entity_type")) {
            injectAll("minecraft:entity_type", "Entities", RegistryCache.ENTITIES,
                (net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.ENTITY_TYPES);
        }
        if (regName.equals("minecraft:sound_event")) {
            injectAll("minecraft:sound_event", "Sounds", RegistryCache.SOUNDS,
                (net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.SOUND_EVENTS);
        }

        // ── Extended registry types ────────────────────────────────────────────
        if (regName.equals("minecraft:enchantment")) {
            injectAll("minecraft:enchantment", "Enchantments", RegistryCache.ENCHANTMENTS,
                (net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.ENCHANTMENTS);
        }
        if (regName.equals("minecraft:mob_effect")) {
            injectAll("minecraft:mob_effect", "MobEffects", RegistryCache.MOB_EFFECTS,
                (net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.MOB_EFFECTS);
        }
        if (regName.equals("minecraft:potion")) {
            injectAll("minecraft:potion", "Potions", RegistryCache.POTIONS,
                (net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.POTIONS);
        }
        if (regName.equals("forge:fluid_type")) {
            injectAll("forge:fluid_type", "FluidTypes", RegistryCache.FLUID_TYPES,
                (net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.FLUID_TYPES);
        }
        if (regName.equals("minecraft:creative_mode_tab")) {
            injectAll("minecraft:creative_mode_tab", "CreativeModeTabs", RegistryCache.CREATIVE_MODE_TABS,
                resolveForgeRegistry("CREATIVE_MODE_TABS"));
        }
        if (regName.equals("minecraft:recipe_type")) {
            injectAll("minecraft:recipe_type", "RecipeTypes", RegistryCache.RECIPE_TYPES,
                (net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.RECIPE_TYPES);
        }
        if (regName.equals("minecraft:particle_type")) {
            injectAll("minecraft:particle_type", "ParticleTypes", RegistryCache.PARTICLE_TYPES,
                (net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.PARTICLE_TYPES);
        }
        if (regName.equals("minecraft:block_entity_type")) {
            injectAll("minecraft:block_entity_type", "BlockEntityTypes", RegistryCache.BLOCK_ENTITY_TYPES,
                (net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.BLOCK_ENTITY_TYPES);
        }
        if (regName.equals("minecraft:feature")) {
            injectAll("minecraft:feature", "Features", RegistryCache.FEATURES,
                (net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.FEATURES);
        }
    }

    @SubscribeEvent
    public static void onPackFinder(net.minecraftforge.event.AddPackFindersEvent event) {
        try {
            List<java.io.File> resourceJars = AssetInjector.collectResourceJars();
            for (java.io.File jar : resourceJars) {
                Path path = jar.toPath().toAbsolutePath().normalize();
                try {
                    FileSystems.newFileSystem(path, Map.of());
                } catch (java.nio.file.FileSystemAlreadyExistsException ignored) {
                    // Already mounted for this JVM session.
                } catch (Exception mountError) {
                    System.err.println("[ResourceBridge] Failed to mount "
                        + path.getFileName() + ": " + mountError.getMessage());
                    continue;
                }
                System.out.println("\033[1;32m[ResourceBridge] Resource pack candidate ready: "
                    + path.getFileName() + "\033[0m");
            }
        } catch (Exception e) {
            System.err.println("[ResourceBridge] Pack finder bridge failed: " + e.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void injectAll(String registryScope,
                                  String label,
                                  List<RegistryCache.PendingEntry> pending,
                                  net.minecraftforge.registries.IForgeRegistry registry) {
        if (pending.isEmpty() || registry == null) return;
        System.out.printf("\033[1;35m[ForgeBridge] Injecting %d %s\033[0m%n", pending.size(), label);
        for (RegistryCache.PendingEntry e : pending) {
            try {
                VirtualRegistryService.registerVirtualized(e.modId, registryScope, e.id.toString(), -1, e.entry);
                registry.register(e.id, e.entry);
            } catch (Exception ex) {
                System.err.printf("[ForgeBridge] Failed to register %s (%s): %s%n",
                    e.id, label, ex.getMessage());
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private static net.minecraftforge.registries.IForgeRegistry resolveForgeRegistry(String fieldName) {
        try {
            return (net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.class.getField(fieldName).get(null);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
