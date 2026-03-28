package org.intermed.core.bridge;

import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.core.Registry;

@Mod.EventBusSubscriber(modid = "intermed", bus = Mod.EventBusSubscriber.Bus.MOD)
public class ForgeEventBridge {

    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void onRegister(RegisterEvent event) {
        String regName = event.getRegistryKey().toString();

        if (regName.contains("minecraft:block") && !RegistryCache.BLOCKS.isEmpty()) {
            System.out.println("\033[1;35m[ForgeBridge] Injecting " + RegistryCache.BLOCKS.size() + " Blocks!\033[0m");
            for (RegistryCache.PendingEntry e : RegistryCache.BLOCKS) {
                // Прямой обход типизации через ForgeRegistries
                ((net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.BLOCKS).register(e.id, e.entry);
            }
        }
        
        if (regName.contains("minecraft:item") && !RegistryCache.ITEMS.isEmpty()) {
            System.out.println("\033[1;35m[ForgeBridge] Injecting " + RegistryCache.ITEMS.size() + " Items!\033[0m");
            for (RegistryCache.PendingEntry e : RegistryCache.ITEMS) {
                ((net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.ITEMS).register(e.id, e.entry);
            }
        }

        if (regName.contains("minecraft:entity_type") && !RegistryCache.ENTITIES.isEmpty()) {
            System.out.println("\033[1;35m[ForgeBridge] Injecting " + RegistryCache.ENTITIES.size() + " Entities!\033[0m");
            for (RegistryCache.PendingEntry e : RegistryCache.ENTITIES) {
                ((net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.ENTITY_TYPES).register(e.id, e.entry);
            }
        }
        
        if (regName.contains("minecraft:sound_event") && !RegistryCache.SOUNDS.isEmpty()) {
            for (RegistryCache.PendingEntry e : RegistryCache.SOUNDS) {
                ((net.minecraftforge.registries.IForgeRegistry) ForgeRegistries.SOUND_EVENTS).register(e.id, e.entry);
            }
        }
    }

    @SubscribeEvent
    public static void onPackFinder(net.minecraftforge.event.AddPackFindersEvent event) {
        try {
            java.nio.file.Path modsDir = java.nio.file.Paths.get(System.getenv("APPDATA"), ".minecraft/intermed_mods");
            if (!java.nio.file.Files.exists(modsDir)) return;

            java.nio.file.Files.walk(modsDir, 1)
                .filter(path -> path.toString().endsWith(".jar"))
                .forEach(path -> {
                    try {
                        Class<?> packTypeClass = net.minecraft.server.packs.PackType.class;
                        Object clientType = packTypeClass.getEnumConstants()[0]; 
                        Object serverType = packTypeClass.getEnumConstants()[1]; 
                        
                        if (event.getPackType() == clientType || event.getPackType() == serverType) {
                            System.out.println("\033[1;32m[ResourceBridge] Mounting textures for: " + path.getFileName() + "\033[0m");
                            net.minecraftforge.fml.loading.FMLPaths.MODSDIR.get().getFileSystem().provider().newFileSystem(path, java.util.Collections.emptyMap());
                        }
                    } catch (Exception ignore) {}
                });
        } catch (Exception e) {}
    }
}