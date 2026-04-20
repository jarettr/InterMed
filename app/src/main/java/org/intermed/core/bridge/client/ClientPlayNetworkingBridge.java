package org.intermed.core.bridge.client;

import net.minecraft.resources.ResourceLocation;
import org.intermed.core.bridge.InterMedNetworkBridge;

import java.util.List;
import java.util.Map;

/**
 * Настоящий транслятор сетевого API (Fabric -> Forge).
 * Не просто заглушка, а реестр обработчиков пакетов.
 */
public class ClientPlayNetworkingBridge {

    // Сигнатура метода, который ждет AppleSkin.
    // Используем Object для буферов, чтобы потом мапить Forge FriendlyByteBuf в Fabric PacketByteBuf
    public interface PlayChannelHandlerBridge {
        void receive(Object client, Object handler, Object buf, Object responseSender);
    }

    public interface Utf8PlayChannelHandlerBridge {
        void receive(Object client, Object handler, String payload, Object responseSender);
    }

    public interface BinaryPlayChannelHandlerBridge {
        void receive(Object client, Object handler, byte[] payload, Object responseSender);
    }

    // Метод, на который наш Роутер перенаправляет вызов из мода
    public static boolean registerGlobalReceiver(ResourceLocation id, PlayChannelHandlerBridge handler) {
        if (handler == null) {
            return false;
        }
        return InterMedNetworkBridge.registerClientReceiver(id, handler::receive);
    }

    public static boolean registerUtf8Receiver(ResourceLocation id, Utf8PlayChannelHandlerBridge handler) {
        if (handler == null) {
            return false;
        }
        return InterMedNetworkBridge.registerTypedClientReceiver(
            id,
            null,
            InterMedNetworkBridge.PayloadCodec.utf8String(),
            handler::receive
        );
    }

    public static boolean registerBinaryReceiver(ResourceLocation id, BinaryPlayChannelHandlerBridge handler) {
        if (handler == null) {
            return false;
        }
        return InterMedNetworkBridge.registerTypedClientReceiver(
            id,
            null,
            InterMedNetworkBridge.PayloadCodec.bytes(),
            handler::receive
        );
    }

    public static void dispatch(ResourceLocation id, Object client, Object handler, Object buf, Object responseSender) {
        InterMedNetworkBridge.dispatchClientReceiver(id, client, handler, buf, responseSender);
    }

    public static boolean dispatchEnvelope(byte[] encodedEnvelope, Object client, Object handler, Object responseSender) {
        return InterMedNetworkBridge.dispatchClientEnvelope(encodedEnvelope, client, handler, responseSender);
    }

    public static ResourceLocation transportChannel() {
        return InterMedNetworkBridge.transportChannel();
    }

    public static long globalChannelId(ResourceLocation id) {
        return InterMedNetworkBridge.resolveChannelNumericId(id, InterMedNetworkBridge.DeliveryDirection.CLIENTBOUND);
    }

    public static byte[] createPayloadFrame(ResourceLocation id, String sourceModId, byte[] payload) {
        return InterMedNetworkBridge.encodeClientbound(id, sourceModId, payload, InterMedNetworkBridge.PayloadCodec.bytes());
    }

    public static byte[] createUtf8PayloadFrame(ResourceLocation id, String sourceModId, String payload) {
        return InterMedNetworkBridge.encodeClientbound(id, sourceModId, payload, InterMedNetworkBridge.PayloadCodec.utf8String());
    }

    public static Map<ResourceLocation, InterMedNetworkBridge.PacketReceiver> snapshotReceivers() {
        return InterMedNetworkBridge.snapshotClientReceivers();
    }

    public static List<InterMedNetworkBridge.ChannelDescriptor> snapshotChannels() {
        return InterMedNetworkBridge.snapshotChannelDescriptors().stream()
            .filter(descriptor -> descriptor.direction() == InterMedNetworkBridge.DeliveryDirection.CLIENTBOUND)
            .toList();
    }
}
