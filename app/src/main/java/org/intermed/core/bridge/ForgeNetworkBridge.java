package org.intermed.core.bridge;

import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/**
 * Forge SimpleImpl-style compatibility bridge built on top of the universal
 * InterMed network transport.
 */
public final class ForgeNetworkBridge {

    private static final Map<ResourceLocation, SimpleChannelState> CHANNELS = new ConcurrentHashMap<>();
    private static final AtomicLong ENCODED_MESSAGES = new AtomicLong();
    private static final AtomicLong DISPATCHED_MESSAGES = new AtomicLong();
    private static final AtomicLong DROPPED_MESSAGES = new AtomicLong();

    private ForgeNetworkBridge() {}

    public static SimpleChannelHandle channel(ResourceLocation channelId, String protocolVersion) {
        String normalized = protocolVersion == null || protocolVersion.isBlank() ? "1" : protocolVersion;
        Predicate<String> accepted = version -> Objects.equals(normalized, version);
        return channel(channelId, normalized, accepted, accepted);
    }

    public static SimpleChannelHandle channel(ResourceLocation channelId,
                                              String protocolVersion,
                                              Predicate<String> clientAcceptedVersions,
                                              Predicate<String> serverAcceptedVersions) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(clientAcceptedVersions, "clientAcceptedVersions");
        Objects.requireNonNull(serverAcceptedVersions, "serverAcceptedVersions");
        return new SimpleChannelHandle(CHANNELS.compute(channelId, (ignored, existing) -> {
            if (existing == null) {
                return new SimpleChannelState(channelId, protocolVersion, clientAcceptedVersions, serverAcceptedVersions);
            }
            existing.verifyProtocol(protocolVersion);
            return existing;
        }));
    }

    public static boolean acceptsClientVersion(ResourceLocation channelId, String remoteVersion) {
        SimpleChannelState state = channelId == null ? null : CHANNELS.get(channelId);
        return state != null && state.acceptsClientVersion(remoteVersion);
    }

    public static boolean acceptsServerVersion(ResourceLocation channelId, String remoteVersion) {
        SimpleChannelState state = channelId == null ? null : CHANNELS.get(channelId);
        return state != null && state.acceptsServerVersion(remoteVersion);
    }

    public static List<ForgeChannelDescriptor> snapshotChannels() {
        List<ForgeChannelDescriptor> channels = new ArrayList<>(CHANNELS.size());
        CHANNELS.values().forEach(state -> channels.add(state.channelDescriptor()));
        channels.sort(Comparator.comparing(descriptor -> descriptor.channelId().toString()));
        return List.copyOf(channels);
    }

    public static List<ForgeMessageDescriptor> snapshotMessages() {
        List<ForgeMessageDescriptor> messages = new ArrayList<>();
        CHANNELS.values().forEach(state -> state.collectMessages(messages));
        messages.sort(Comparator
            .comparing((ForgeMessageDescriptor descriptor) -> descriptor.channelId().toString())
            .thenComparingInt(ForgeMessageDescriptor::discriminator)
            .thenComparing(ForgeMessageDescriptor::direction));
        return List.copyOf(messages);
    }

    public static ForgeNetworkDiagnostics diagnostics() {
        return new ForgeNetworkDiagnostics(
            snapshotChannels().size(),
            snapshotMessages().size(),
            ENCODED_MESSAGES.get(),
            DISPATCHED_MESSAGES.get(),
            DROPPED_MESSAGES.get()
        );
    }

    public static void clear() {
        CHANNELS.clear();
        ENCODED_MESSAGES.set(0);
        DISPATCHED_MESSAGES.set(0);
        DROPPED_MESSAGES.set(0);
    }

    public static void resetForTests() {
        clear();
    }

    public interface ClientMessageHandler<T> {
        void handle(Object client, Object handler, T payload, Object responseSender);
    }

    public interface ServerMessageHandler<T> {
        void handle(Object server, Object player, Object handler, T payload, Object responseSender);
    }

    public record ForgeChannelDescriptor(ResourceLocation channelId,
                                         String protocolVersion,
                                         long clientboundChannelId,
                                         long serverboundChannelId,
                                         int clientboundMessages,
                                         int serverboundMessages) {
    }

    public record ForgeMessageDescriptor(ResourceLocation channelId,
                                         int discriminator,
                                         InterMedNetworkBridge.DeliveryDirection direction,
                                         String ownerModId,
                                         String payloadType) {
    }

    public record ForgeNetworkDiagnostics(int registeredChannels,
                                          int registeredMessages,
                                          long encodedMessages,
                                          long dispatchedMessages,
                                          long droppedMessages) {
    }

    public static final class SimpleChannelHandle {
        private final SimpleChannelState state;

        private SimpleChannelHandle(SimpleChannelState state) {
            this.state = state;
        }

        public ResourceLocation channelId() {
            return state.channelId;
        }

        public String protocolVersion() {
            return state.protocolVersion;
        }

        public boolean acceptsClientVersion(String remoteVersion) {
            return state.acceptsClientVersion(remoteVersion);
        }

        public boolean acceptsServerVersion(String remoteVersion) {
            return state.acceptsServerVersion(remoteVersion);
        }

        public <T> void registerClientbound(int discriminator,
                                            String ownerModId,
                                            InterMedNetworkBridge.PayloadCodec<T> codec,
                                            ClientMessageHandler<T> handler) {
            state.registerClientbound(discriminator, ownerModId, codec, handler);
        }

        public <T> void registerServerbound(int discriminator,
                                            String ownerModId,
                                            InterMedNetworkBridge.PayloadCodec<T> codec,
                                            ServerMessageHandler<T> handler) {
            state.registerServerbound(discriminator, ownerModId, codec, handler);
        }

        public void registerClientboundUtf8(int discriminator,
                                            String ownerModId,
                                            ClientMessageHandler<String> handler) {
            registerClientbound(discriminator, ownerModId, InterMedNetworkBridge.PayloadCodec.utf8String(), handler);
        }

        public void registerServerboundUtf8(int discriminator,
                                            String ownerModId,
                                            ServerMessageHandler<String> handler) {
            registerServerbound(discriminator, ownerModId, InterMedNetworkBridge.PayloadCodec.utf8String(), handler);
        }

        public void registerClientboundBinary(int discriminator,
                                              String ownerModId,
                                              ClientMessageHandler<byte[]> handler) {
            registerClientbound(discriminator, ownerModId, InterMedNetworkBridge.PayloadCodec.bytes(), handler);
        }

        public void registerServerboundBinary(int discriminator,
                                              String ownerModId,
                                              ServerMessageHandler<byte[]> handler) {
            registerServerbound(discriminator, ownerModId, InterMedNetworkBridge.PayloadCodec.bytes(), handler);
        }

        public <T> byte[] createClientboundFrame(int discriminator, String sourceModId, T payload) {
            return state.createClientboundFrame(discriminator, sourceModId, payload);
        }

        public <T> byte[] createServerboundFrame(int discriminator, String sourceModId, T payload) {
            return state.createServerboundFrame(discriminator, sourceModId, payload);
        }

        public byte[] createClientboundUtf8Frame(int discriminator, String sourceModId, String payload) {
            return createClientboundFrame(discriminator, sourceModId, payload);
        }

        public byte[] createServerboundUtf8Frame(int discriminator, String sourceModId, String payload) {
            return createServerboundFrame(discriminator, sourceModId, payload);
        }
    }

    private static final class SimpleChannelState {
        private final ResourceLocation channelId;
        private final String protocolVersion;
        private final Predicate<String> clientAcceptedVersions;
        private final Predicate<String> serverAcceptedVersions;
        private final Map<Integer, MessageRegistration> clientbound = new ConcurrentHashMap<>();
        private final Map<Integer, MessageRegistration> serverbound = new ConcurrentHashMap<>();
        private final AtomicBoolean clientDispatcherInstalled = new AtomicBoolean(false);
        private final AtomicBoolean serverDispatcherInstalled = new AtomicBoolean(false);

        private SimpleChannelState(ResourceLocation channelId,
                                   String protocolVersion,
                                   Predicate<String> clientAcceptedVersions,
                                   Predicate<String> serverAcceptedVersions) {
            this.channelId = channelId;
            this.protocolVersion = protocolVersion == null || protocolVersion.isBlank() ? "1" : protocolVersion;
            this.clientAcceptedVersions = clientAcceptedVersions;
            this.serverAcceptedVersions = serverAcceptedVersions;
        }

        private void verifyProtocol(String candidateProtocol) {
            String normalized = candidateProtocol == null || candidateProtocol.isBlank() ? "1" : candidateProtocol;
            if (!Objects.equals(protocolVersion, normalized)) {
                throw new IllegalStateException("Forge channel " + channelId + " already uses protocol " + protocolVersion
                    + ", cannot redefine as " + normalized);
            }
        }

        private boolean acceptsClientVersion(String remoteVersion) {
            return clientAcceptedVersions.test(remoteVersion);
        }

        private boolean acceptsServerVersion(String remoteVersion) {
            return serverAcceptedVersions.test(remoteVersion);
        }

        private <T> void registerClientbound(int discriminator,
                                             String ownerModId,
                                             InterMedNetworkBridge.PayloadCodec<T> codec,
                                             ClientMessageHandler<T> handler) {
            register(discriminator, ownerModId, codec, handler, null, clientbound, InterMedNetworkBridge.DeliveryDirection.CLIENTBOUND);
            ensureClientDispatcher();
        }

        private <T> void registerServerbound(int discriminator,
                                             String ownerModId,
                                             InterMedNetworkBridge.PayloadCodec<T> codec,
                                             ServerMessageHandler<T> handler) {
            register(discriminator, ownerModId, codec, null, handler, serverbound, InterMedNetworkBridge.DeliveryDirection.SERVERBOUND);
            ensureServerDispatcher();
        }

        private <T> void register(int discriminator,
                                  String ownerModId,
                                  InterMedNetworkBridge.PayloadCodec<T> codec,
                                  ClientMessageHandler<T> clientHandler,
                                  ServerMessageHandler<T> serverHandler,
                                  Map<Integer, MessageRegistration> target,
                                  InterMedNetworkBridge.DeliveryDirection direction) {
            Objects.requireNonNull(codec, "codec");
            MessageRegistration created = new MessageRegistration(
                discriminator,
                ownerModId == null || ownerModId.isBlank() ? channelId.getNamespace() : ownerModId,
                payloadType(codec),
                castCodec(codec),
                castClientHandler(clientHandler),
                castServerHandler(serverHandler),
                direction
            );
            MessageRegistration existing = target.putIfAbsent(discriminator, created);
            if (existing != null) {
                throw new IllegalStateException("Forge channel " + channelId + " already has a "
                    + direction + " message with discriminator " + discriminator);
            }
        }

        private void ensureClientDispatcher() {
            if (!clientDispatcherInstalled.compareAndSet(false, true)) {
                return;
            }
            InterMedNetworkBridge.registerTypedClientReceiver(
                channelId,
                channelId.getNamespace(),
                InterMedNetworkBridge.PayloadCodec.bytes(),
                (client, handler, payload, responseSender) -> dispatchClientPayload(payload, client, handler, responseSender)
            );
        }

        private void ensureServerDispatcher() {
            if (!serverDispatcherInstalled.compareAndSet(false, true)) {
                return;
            }
            InterMedNetworkBridge.registerTypedServerReceiver(
                channelId,
                channelId.getNamespace(),
                InterMedNetworkBridge.PayloadCodec.bytes(),
                (server, player, handler, payload, responseSender) -> dispatchServerPayload(payload, server, player, handler, responseSender)
            );
        }

        private <T> byte[] createClientboundFrame(int discriminator, String sourceModId, T payload) {
            MessageRegistration registration = requireMessage(discriminator, clientbound, InterMedNetworkBridge.DeliveryDirection.CLIENTBOUND);
            byte[] encoded = encodeMessage(discriminator, registration.encode(payload));
            ENCODED_MESSAGES.incrementAndGet();
            return InterMedNetworkBridge.encodeClientbound(channelId, sourceModId, encoded, InterMedNetworkBridge.PayloadCodec.bytes());
        }

        private <T> byte[] createServerboundFrame(int discriminator, String sourceModId, T payload) {
            MessageRegistration registration = requireMessage(discriminator, serverbound, InterMedNetworkBridge.DeliveryDirection.SERVERBOUND);
            byte[] encoded = encodeMessage(discriminator, registration.encode(payload));
            ENCODED_MESSAGES.incrementAndGet();
            return InterMedNetworkBridge.encodeServerbound(channelId, sourceModId, encoded, InterMedNetworkBridge.PayloadCodec.bytes());
        }

        private void dispatchClientPayload(byte[] payloadBytes,
                                           Object client,
                                           Object handler,
                                           Object responseSender) {
            DecodedMessage decoded = decodeMessage(payloadBytes);
            MessageRegistration registration = clientbound.get(decoded.discriminator());
            if (registration == null || registration.clientHandler() == null) {
                DROPPED_MESSAGES.incrementAndGet();
                return;
            }
            registration.clientHandler().handle(client, handler, registration.decode(decoded.payload()), responseSender);
            DISPATCHED_MESSAGES.incrementAndGet();
        }

        private void dispatchServerPayload(byte[] payloadBytes,
                                           Object server,
                                           Object player,
                                           Object handler,
                                           Object responseSender) {
            DecodedMessage decoded = decodeMessage(payloadBytes);
            MessageRegistration registration = serverbound.get(decoded.discriminator());
            if (registration == null || registration.serverHandler() == null) {
                DROPPED_MESSAGES.incrementAndGet();
                return;
            }
            registration.serverHandler().handle(server, player, handler, registration.decode(decoded.payload()), responseSender);
            DISPATCHED_MESSAGES.incrementAndGet();
        }

        private MessageRegistration requireMessage(int discriminator,
                                                   Map<Integer, MessageRegistration> source,
                                                   InterMedNetworkBridge.DeliveryDirection direction) {
            MessageRegistration registration = source.get(discriminator);
            if (registration == null) {
                throw new IllegalArgumentException("No " + direction + " Forge message " + discriminator + " registered for " + channelId);
            }
            return registration;
        }

        private ForgeChannelDescriptor channelDescriptor() {
            return new ForgeChannelDescriptor(
                channelId,
                protocolVersion,
                InterMedNetworkBridge.resolveChannelNumericId(channelId, InterMedNetworkBridge.DeliveryDirection.CLIENTBOUND),
                InterMedNetworkBridge.resolveChannelNumericId(channelId, InterMedNetworkBridge.DeliveryDirection.SERVERBOUND),
                clientbound.size(),
                serverbound.size()
            );
        }

        private void collectMessages(List<ForgeMessageDescriptor> target) {
            clientbound.values().forEach(message -> target.add(message.descriptor(channelId)));
            serverbound.values().forEach(message -> target.add(message.descriptor(channelId)));
        }
    }

    private record MessageRegistration(int discriminator,
                                       String ownerModId,
                                       String payloadType,
                                       InterMedNetworkBridge.PayloadCodec<Object> codec,
                                       ClientMessageHandler<Object> clientHandler,
                                       ServerMessageHandler<Object> serverHandler,
                                       InterMedNetworkBridge.DeliveryDirection direction) {

        private byte[] encode(Object payload) {
            return codec.encode(payload);
        }

        private Object decode(byte[] payloadBytes) {
            return codec.decode(payloadBytes);
        }

        private ForgeMessageDescriptor descriptor(ResourceLocation channelId) {
            return new ForgeMessageDescriptor(channelId, discriminator, direction, ownerModId, payloadType);
        }
    }

    private record DecodedMessage(int discriminator, byte[] payload) {
    }

    private static byte[] encodeMessage(int discriminator, byte[] body) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             DataOutputStream data = new DataOutputStream(output)) {
            data.writeInt(discriminator);
            data.write(body);
            data.flush();
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to encode Forge bridge message " + discriminator, e);
        }
    }

    private static DecodedMessage decodeMessage(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded");
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int discriminator = data.readInt();
            return new DecodedMessage(discriminator, data.readAllBytes());
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to decode Forge bridge message", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> InterMedNetworkBridge.PayloadCodec<Object> castCodec(InterMedNetworkBridge.PayloadCodec<T> codec) {
        return (InterMedNetworkBridge.PayloadCodec<Object>) codec;
    }

    @SuppressWarnings("unchecked")
    private static <T> ClientMessageHandler<Object> castClientHandler(ClientMessageHandler<T> handler) {
        return handler == null ? null : (ClientMessageHandler<Object>) handler;
    }

    @SuppressWarnings("unchecked")
    private static <T> ServerMessageHandler<Object> castServerHandler(ServerMessageHandler<T> handler) {
        return handler == null ? null : (ServerMessageHandler<Object>) handler;
    }

    private static String payloadType(InterMedNetworkBridge.PayloadCodec<?> codec) {
        if (codec == InterMedNetworkBridge.PayloadCodec.utf8String()) {
            return "utf8";
        }
        if (codec == InterMedNetworkBridge.PayloadCodec.bytes()) {
            return "binary";
        }
        return codec.getClass().getSimpleName();
    }
}
