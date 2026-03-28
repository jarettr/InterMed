/**
package org.intermed.core.bridge;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;


 * Сетевой мост (Раздел 3.2.7 ТЗ).
 * Транслирует пакеты Fabric в каналы Forge SimpleChannel.

public class InterMedNetworkBridge {

    private static final String PROTOCOL_VERSION = "1";
    private static final Map<ResourceLocation, SimpleChannel> CHANNELS = new ConcurrentHashMap<>();

    public static void registerFabricPacketReceiver(ResourceLocation channelId, Object fabricReceiver) {
        System.out.println("\033[1;34m[InterMed Network] Регистрация канала: " + channelId + "\033[0m");

        SimpleChannel forgeChannel = NetworkRegistry.newSimpleChannel(
                channelId,
                () -> PROTOCOL_VERSION,
                PROTOCOL_VERSION::equals,
                PROTOCOL_VERSION::equals
        );

        forgeChannel.registerMessage(
                0,
                FabricPacketWrapper.class,
                FabricPacketWrapper::encode,
                FabricPacketWrapper::decode,
                (packet, contextSupplier) -> handlePacket(packet, contextSupplier, fabricReceiver)
        );

        CHANNELS.put(channelId, forgeChannel);
    }

    private static void handlePacket(FabricPacketWrapper packet, Supplier<NetworkEvent.Context> contextSupplier, Object fabricReceiver) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // Внедрение данных в коллбэк Fabric-мода
            System.out.println("[InterMed] Пакет передан в Fabric-мод");
        });
        context.setPacketHandled(true);
    }

    public static class FabricPacketWrapper {
        private final byte[] payload;

        public FabricPacketWrapper(byte[] payload) {
            this.payload = payload;
        }

public static void encode(FabricPacketWrapper msg, net.minecraft.network.FriendlyByteBuf buf) {
            // Пишем длину массива, а потом сам массив. Это стандартный способ.
            buf.writeInt(msg.payload.length);
            buf.writeBytes(msg.payload); 
        }

        public static FabricPacketWrapper decode(net.minecraft.network.FriendlyByteBuf buf) {
            // Читаем длину, создаем массив нужного размера и заполняем его.
            int length = buf.readInt();
            byte[] payload = new byte[length];
            buf.readBytes(payload);
            return new FabricPacketWrapper(payload);
        }
    }
}

*/