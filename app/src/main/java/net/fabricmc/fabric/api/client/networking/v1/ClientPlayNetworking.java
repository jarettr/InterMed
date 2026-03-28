package net.fabricmc.fabric.api.client.networking.v1;

import net.minecraft.resources.ResourceLocation;

public class ClientPlayNetworking {
    public interface PlayChannelHandler {
        // Пустой интерфейс для совместимости
    }
    
    public static boolean registerGlobalReceiver(ResourceLocation id, PlayChannelHandler handler) {
        System.out.println("\033[1;32m[InterMed Network] Registered Fabric Global Receiver: " + id + "\033[0m");
        return true;
    }
}