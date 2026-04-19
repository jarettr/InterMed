package net.fabricmc.fabric.api.networking.v1;

import net.minecraft.resources.ResourceLocation;
import org.intermed.core.bridge.InterMedNetworkBridge;

import java.util.List;

public final class ServerPlayNetworking {

    private ServerPlayNetworking() {}

    public interface PlayChannelHandler {
        void receive(Object server, Object player, Object handler, Object buf, Object responseSender);
    }

    public interface Utf8PlayChannelHandler {
        void receive(Object server, Object player, Object handler, String payload, Object responseSender);
    }

    public interface BinaryPlayChannelHandler {
        void receive(Object server, Object player, Object handler, byte[] payload, Object responseSender);
    }

    public static boolean registerGlobalReceiver(ResourceLocation id, PlayChannelHandler handler) {
        if (handler == null) {
            return false;
        }
        return InterMedNetworkBridge.registerServerReceiver(id, handler::receive);
    }

    public static boolean registerUtf8Receiver(ResourceLocation id, Utf8PlayChannelHandler handler) {
        if (handler == null) {
            return false;
        }
        return InterMedNetworkBridge.registerTypedServerReceiver(
            id,
            null,
            InterMedNetworkBridge.PayloadCodec.utf8String(),
            handler::receive
        );
    }

    public static boolean registerBinaryReceiver(ResourceLocation id, BinaryPlayChannelHandler handler) {
        if (handler == null) {
            return false;
        }
        return InterMedNetworkBridge.registerTypedServerReceiver(
            id,
            null,
            InterMedNetworkBridge.PayloadCodec.bytes(),
            handler::receive
        );
    }

    public static ResourceLocation transportChannel() {
        return InterMedNetworkBridge.transportChannel();
    }

    public static long globalChannelId(ResourceLocation id) {
        return InterMedNetworkBridge.resolveChannelNumericId(id, InterMedNetworkBridge.DeliveryDirection.SERVERBOUND);
    }

    public static byte[] createPayloadFrame(ResourceLocation id, byte[] payload) {
        return createPayloadFrame(id, null, payload);
    }

    public static byte[] createPayloadFrame(ResourceLocation id, String sourceModId, byte[] payload) {
        return InterMedNetworkBridge.encodeServerbound(id, sourceModId, payload, InterMedNetworkBridge.PayloadCodec.bytes());
    }

    public static byte[] createStringPayloadFrame(ResourceLocation id, String payload) {
        return createStringPayloadFrame(id, null, payload);
    }

    public static byte[] createStringPayloadFrame(ResourceLocation id, String sourceModId, String payload) {
        return InterMedNetworkBridge.encodeServerbound(id, sourceModId, payload, InterMedNetworkBridge.PayloadCodec.utf8String());
    }

    public static boolean dispatchGlobalFrame(byte[] encodedEnvelope,
                                              Object server,
                                              Object player,
                                              Object handler,
                                              Object responseSender) {
        return InterMedNetworkBridge.dispatchServerEnvelope(encodedEnvelope, server, player, handler, responseSender);
    }

    public static List<InterMedNetworkBridge.ChannelDescriptor> snapshotChannels() {
        return InterMedNetworkBridge.snapshotChannelDescriptors().stream()
            .filter(descriptor -> descriptor.direction() == InterMedNetworkBridge.DeliveryDirection.SERVERBOUND)
            .toList();
    }
}
