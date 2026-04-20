package net.fabricmc.fabric.api.client.networking.v1;

import net.minecraft.resources.ResourceLocation;
import org.intermed.core.bridge.InterMedNetworkBridge;
import org.intermed.core.bridge.client.ClientPlayNetworkingBridge;

import java.util.List;

public class ClientPlayNetworking {
    public interface PlayChannelHandler {
        void receive(Object client, Object handler, Object buf, Object responseSender);
    }

    public interface Utf8PlayChannelHandler {
        void receive(Object client, Object handler, String payload, Object responseSender);
    }

    public interface BinaryPlayChannelHandler {
        void receive(Object client, Object handler, byte[] payload, Object responseSender);
    }
    
    public static boolean registerGlobalReceiver(ResourceLocation id, PlayChannelHandler handler) {
        if (handler == null) {
            return false;
        }
        return ClientPlayNetworkingBridge.registerGlobalReceiver(id, handler::receive);
    }

    public static boolean registerUtf8Receiver(ResourceLocation id, Utf8PlayChannelHandler handler) {
        if (handler == null) {
            return false;
        }
        return ClientPlayNetworkingBridge.registerUtf8Receiver(id, handler::receive);
    }

    public static boolean registerBinaryReceiver(ResourceLocation id, BinaryPlayChannelHandler handler) {
        if (handler == null) {
            return false;
        }
        return ClientPlayNetworkingBridge.registerBinaryReceiver(id, handler::receive);
    }

    public static ResourceLocation transportChannel() {
        return ClientPlayNetworkingBridge.transportChannel();
    }

    public static long globalChannelId(ResourceLocation id) {
        return ClientPlayNetworkingBridge.globalChannelId(id);
    }

    public static byte[] createPayloadFrame(ResourceLocation id, byte[] payload) {
        return ClientPlayNetworkingBridge.createPayloadFrame(id, null, payload);
    }

    public static byte[] createPayloadFrame(ResourceLocation id, String sourceModId, byte[] payload) {
        return ClientPlayNetworkingBridge.createPayloadFrame(id, sourceModId, payload);
    }

    public static byte[] createStringPayloadFrame(ResourceLocation id, String payload) {
        return ClientPlayNetworkingBridge.createUtf8PayloadFrame(id, null, payload);
    }

    public static byte[] createStringPayloadFrame(ResourceLocation id, String sourceModId, String payload) {
        return ClientPlayNetworkingBridge.createUtf8PayloadFrame(id, sourceModId, payload);
    }

    public static boolean dispatchGlobalFrame(byte[] encodedEnvelope, Object client, Object handler, Object responseSender) {
        return ClientPlayNetworkingBridge.dispatchEnvelope(encodedEnvelope, client, handler, responseSender);
    }

    public static List<InterMedNetworkBridge.ChannelDescriptor> snapshotChannels() {
        return ClientPlayNetworkingBridge.snapshotChannels();
    }
}
