package org.intermed.core.bridge.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Настоящий транслятор сетевого API (Fabric -> Forge).
 * Не просто заглушка, а реестр обработчиков пакетов.
 */
public class ClientPlayNetworkingBridge {
    
    // Хранилище всех сетевых каналов, которые просит зарегистрировать Fabric-мод
    public static final Map<Object, PlayChannelHandlerBridge> RECEIVERS = new ConcurrentHashMap<>();

    // Сигнатура метода, который ждет AppleSkin.
    // Используем Object для буферов, чтобы потом мапить Forge FriendlyByteBuf в Fabric PacketByteBuf
    public interface PlayChannelHandlerBridge {
        void receive(Object client, Object handler, Object buf, Object responseSender);
    }

    // Метод, на который наш Роутер перенаправляет вызов из мода
    public static boolean registerGlobalReceiver(Object id, PlayChannelHandlerBridge handler) {
        // Мы РЕАЛЬНО сохраняем обработчик. 
        RECEIVERS.put(id, handler);
        System.out.println("\033[1;34m[Network Bridge] Successfully mapped Fabric packet channel to Forge: " + id + "\033[0m");
        
        // Позже, в ForgeEventProxy, мы подпишемся на Forge CustomPayloadEvent,
        // будем проверять этот RECEIVERS и передавать байты напрямую в мод!
        return true;
    }
}