package org.intermed.core.bridge;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import org.intermed.core.registry.RegistryTranslationMatrix;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runtime registry for Fabric-style packet receivers bridged onto the host networking layer.
 *
 * <p>In addition to raw receiver registration, this bridge now exposes a platform
 * envelope format that assigns a deterministic universal numeric channel id to
 * every logical packet channel.  This allows isolated mods and compatibility
 * bridges to exchange packets over the single transport channel declared by
 * {@link #transportChannel()} while keeping the original logical channel visible
 * for diagnostics and fallback routing.
 */
public final class InterMedNetworkBridge {

    public static final int NETWORK_PROTOCOL_VERSION = 1;
    private static final int ENVELOPE_MAGIC = 0x494D4E42; // IMNB
    private static final String TRANSPORT_CHANNEL_NAMESPACE = "intermed";
    private static final String TRANSPORT_CHANNEL_PATH = "network_bridge";
    private static final String REGISTRY_SYNC_CHANNEL_NAMESPACE = "intermed";
    private static final String REGISTRY_SYNC_CHANNEL_PATH = "registry_sync";
    private static final byte[] EMPTY_PAYLOAD = new byte[0];
    private static final Object REGISTRATION_LOCK = new Object();

    private static final Map<ResourceLocation, ClientChannelBinding> CLIENT_CHANNELS = new ConcurrentHashMap<>();
    private static final Map<Long, ClientChannelBinding> CLIENT_CHANNELS_BY_NUMERIC_ID = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, ServerChannelBinding> SERVER_CHANNELS = new ConcurrentHashMap<>();
    private static final Map<Long, ServerChannelBinding> SERVER_CHANNELS_BY_NUMERIC_ID = new ConcurrentHashMap<>();

    private static final AtomicLong ENCODED_ENVELOPES = new AtomicLong();
    private static final AtomicLong DECODED_ENVELOPES = new AtomicLong();
    private static final AtomicLong DISPATCHED_ENVELOPES = new AtomicLong();
    private static final AtomicLong DROPPED_ENVELOPES = new AtomicLong();
    private static final AtomicLong FALLBACK_CHANNEL_MATCHES = new AtomicLong();
    private static final AtomicBoolean REGISTRY_SYNC_CHANNEL_REGISTERED = new AtomicBoolean();
    private static final AtomicBoolean REGISTRY_SYNC_LIFECYCLE_HOOKS_REGISTERED = new AtomicBoolean();

    private InterMedNetworkBridge() {}

    public static boolean registerClientReceiver(ResourceLocation channelId, PacketReceiver receiver) {
        if (channelId == null || receiver == null) {
            return false;
        }
        ClientChannelBinding binding = ensureClientBinding(channelId, null);
        binding.registerLegacyReceiver(receiver);
        System.out.println("\033[1;34m[InterMed Network] Registered bridged client receiver: " + channelId
            + " -> " + Long.toUnsignedString(binding.numericId()) + "\033[0m");
        return true;
    }

    public static boolean hasClientReceiver(ResourceLocation channelId) {
        return channelId != null && Optional.ofNullable(CLIENT_CHANNELS.get(channelId))
            .map(ClientChannelBinding::hasReceiver)
            .orElse(false);
    }

    public static boolean registerServerReceiver(ResourceLocation channelId, ServerPacketReceiver receiver) {
        if (channelId == null || receiver == null) {
            return false;
        }
        ServerChannelBinding binding = ensureServerBinding(channelId, null);
        binding.registerLegacyReceiver(receiver);
        System.out.println("\033[1;34m[InterMed Network] Registered bridged server receiver: " + channelId
            + " -> " + Long.toUnsignedString(binding.numericId()) + "\033[0m");
        return true;
    }

    public static boolean hasServerReceiver(ResourceLocation channelId) {
        return channelId != null && Optional.ofNullable(SERVER_CHANNELS.get(channelId))
            .map(ServerChannelBinding::hasReceiver)
            .orElse(false);
    }

    public static <T> boolean registerTypedClientReceiver(ResourceLocation channelId,
                                                          String ownerModId,
                                                          PayloadCodec<T> codec,
                                                          TypedPacketReceiver<T> receiver) {
        if (channelId == null || receiver == null || codec == null) {
            return false;
        }
        ClientChannelBinding binding = ensureClientBinding(channelId, ownerModId);
        binding.registerTypedReceiver(codec, receiver);
        System.out.println("\033[1;34m[InterMed Network] Registered typed client receiver: " + channelId
            + " -> " + Long.toUnsignedString(binding.numericId()) + "\033[0m");
        return true;
    }

    public static <T> boolean registerTypedServerReceiver(ResourceLocation channelId,
                                                          String ownerModId,
                                                          PayloadCodec<T> codec,
                                                          TypedServerPacketReceiver<T> receiver) {
        if (channelId == null || receiver == null || codec == null) {
            return false;
        }
        ServerChannelBinding binding = ensureServerBinding(channelId, ownerModId);
        binding.registerTypedReceiver(codec, receiver);
        System.out.println("\033[1;34m[InterMed Network] Registered typed server receiver: " + channelId
            + " -> " + Long.toUnsignedString(binding.numericId()) + "\033[0m");
        return true;
    }

    public static long resolveChannelNumericId(ResourceLocation channelId, DeliveryDirection direction) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(direction, "direction");
        return switch (direction) {
            case CLIENTBOUND -> ensureClientBinding(channelId, null).numericId();
            case SERVERBOUND -> ensureServerBinding(channelId, null).numericId();
        };
    }

    public static Optional<ChannelDescriptor> findChannel(DeliveryDirection direction, long numericId) {
        Objects.requireNonNull(direction, "direction");
        return switch (direction) {
            case CLIENTBOUND -> Optional.ofNullable(CLIENT_CHANNELS_BY_NUMERIC_ID.get(numericId)).map(ClientChannelBinding::descriptor);
            case SERVERBOUND -> Optional.ofNullable(SERVER_CHANNELS_BY_NUMERIC_ID.get(numericId)).map(ServerChannelBinding::descriptor);
        };
    }

    public static List<ChannelDescriptor> snapshotChannelDescriptors() {
        List<ChannelDescriptor> descriptors = new ArrayList<>(CLIENT_CHANNELS.size() + SERVER_CHANNELS.size());
        CLIENT_CHANNELS.values().forEach(binding -> descriptors.add(binding.descriptor()));
        SERVER_CHANNELS.values().forEach(binding -> descriptors.add(binding.descriptor()));
        descriptors.sort(Comparator
            .comparing(ChannelDescriptor::direction)
            .thenComparing(descriptor -> descriptor.channelId().toString()));
        return List.copyOf(descriptors);
    }

    public static NetworkBridgeDiagnostics diagnostics() {
        return new NetworkBridgeDiagnostics(
            NETWORK_PROTOCOL_VERSION,
            transportChannel(),
            snapshotChannelDescriptors().size(),
            ENCODED_ENVELOPES.get(),
            DECODED_ENVELOPES.get(),
            DISPATCHED_ENVELOPES.get(),
            DROPPED_ENVELOPES.get(),
            FALLBACK_CHANNEL_MATCHES.get()
        );
    }

    public static int protocolVersion() {
        return NETWORK_PROTOCOL_VERSION;
    }

    public static boolean canLinkMinecraftNetworkTypes() {
        try {
            Class.forName("net.minecraft.resources.ResourceLocation", false, InterMedNetworkBridge.class.getClassLoader());
            Class.forName("net.minecraft.network.FriendlyByteBuf", false, InterMedNetworkBridge.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    public static ResourceLocation transportChannel() {
        return new ResourceLocation(TRANSPORT_CHANNEL_NAMESPACE, TRANSPORT_CHANNEL_PATH);
    }

    public static ResourceLocation registrySyncChannel() {
        return new ResourceLocation(REGISTRY_SYNC_CHANNEL_NAMESPACE, REGISTRY_SYNC_CHANNEL_PATH);
    }

    public static <T> InterMedPacketEnvelope createClientboundEnvelope(ResourceLocation channelId,
                                                                       String sourceModId,
                                                                       T payload,
                                                                       PayloadCodec<T> codec) {
        return createEnvelope(channelId, DeliveryDirection.CLIENTBOUND, sourceModId, payload, codec);
    }

    public static <T> InterMedPacketEnvelope createServerboundEnvelope(ResourceLocation channelId,
                                                                       String sourceModId,
                                                                       T payload,
                                                                       PayloadCodec<T> codec) {
        return createEnvelope(channelId, DeliveryDirection.SERVERBOUND, sourceModId, payload, codec);
    }

    public static <T> byte[] encodeClientbound(ResourceLocation channelId,
                                               String sourceModId,
                                               T payload,
                                               PayloadCodec<T> codec) {
        return serializeEnvelope(createClientboundEnvelope(channelId, sourceModId, payload, codec));
    }

    public static <T> byte[] encodeServerbound(ResourceLocation channelId,
                                               String sourceModId,
                                               T payload,
                                               PayloadCodec<T> codec) {
        return serializeEnvelope(createServerboundEnvelope(channelId, sourceModId, payload, codec));
    }

    public static byte[] serializeEnvelope(InterMedPacketEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             DataOutputStream data = new DataOutputStream(output)) {
            data.writeInt(ENVELOPE_MAGIC);
            data.writeShort(envelope.protocolVersion());
            data.writeByte(envelope.direction().wireId());
            data.writeLong(envelope.channelNumericId());
            writeString(data, envelope.sourceModId());
            writeString(data, envelope.originalChannelId().toString());
            byte[] payload = envelope.payloadBytes();
            data.writeInt(payload.length);
            data.write(payload);
            data.flush();
            return output.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to serialize InterMed network envelope", e);
        }
    }

    public static InterMedPacketEnvelope deserializeEnvelope(byte[] encodedEnvelope) {
        Objects.requireNonNull(encodedEnvelope, "encodedEnvelope");
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(encodedEnvelope))) {
            int magic = data.readInt();
            if (magic != ENVELOPE_MAGIC) {
                throw new IllegalArgumentException("Invalid InterMed network envelope magic: " + Integer.toHexString(magic));
            }
            int protocolVersion = Short.toUnsignedInt(data.readShort());
            DeliveryDirection direction = DeliveryDirection.fromWireId(Byte.toUnsignedInt(data.readByte()));
            long numericId = data.readLong();
            String sourceModId = readString(data);
            ResourceLocation originalChannelId = parseResourceLocation(readString(data));
            int payloadLength = data.readInt();
            if (payloadLength < 0) {
                throw new IllegalArgumentException("Negative payload size in InterMed network envelope");
            }
            byte[] payload = data.readNBytes(payloadLength);
            if (payload.length != payloadLength) {
                throw new IllegalArgumentException("Truncated InterMed network envelope payload");
            }
            DECODED_ENVELOPES.incrementAndGet();
            return new InterMedPacketEnvelope(protocolVersion, direction, numericId, sourceModId, originalChannelId, payload);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to decode InterMed network envelope", e);
        }
    }

    public static void dispatchClientReceiver(ResourceLocation channelId,
                                              Object client,
                                              Object handler,
                                              Object buf,
                                              Object responseSender) {
        ClientChannelBinding binding = CLIENT_CHANNELS.get(channelId);
        if (binding == null) {
            return;
        }
        binding.dispatchDirect(client, handler, buf, responseSender);
    }

    public static void dispatchServerReceiver(ResourceLocation channelId,
                                              Object server,
                                              Object player,
                                              Object handler,
                                              Object buf,
                                              Object responseSender) {
        ServerChannelBinding binding = SERVER_CHANNELS.get(channelId);
        if (binding == null) {
            return;
        }
        binding.dispatchDirect(server, player, handler, buf, responseSender);
    }

    public static boolean dispatchClientEnvelope(byte[] encodedEnvelope,
                                                 Object client,
                                                 Object handler,
                                                 Object responseSender) {
        return dispatchClientEnvelope(deserializeEnvelope(encodedEnvelope), client, handler, responseSender);
    }

    public static boolean dispatchClientEnvelope(InterMedPacketEnvelope envelope,
                                                 Object client,
                                                 Object handler,
                                                 Object responseSender) {
        if (envelope == null || envelope.direction() != DeliveryDirection.CLIENTBOUND) {
            DROPPED_ENVELOPES.incrementAndGet();
            return false;
        }
        ClientChannelBinding binding = resolveClientBinding(envelope);
        if (binding == null || !binding.hasReceiver()) {
            DROPPED_ENVELOPES.incrementAndGet();
            return false;
        }
        binding.dispatchEnvelope(client, handler, envelope.payloadBytes(), responseSender);
        DISPATCHED_ENVELOPES.incrementAndGet();
        return true;
    }

    public static boolean dispatchServerEnvelope(byte[] encodedEnvelope,
                                                 Object server,
                                                 Object player,
                                                 Object handler,
                                                 Object responseSender) {
        return dispatchServerEnvelope(deserializeEnvelope(encodedEnvelope), server, player, handler, responseSender);
    }

    public static boolean dispatchServerEnvelope(InterMedPacketEnvelope envelope,
                                                 Object server,
                                                 Object player,
                                                 Object handler,
                                                 Object responseSender) {
        if (envelope == null || envelope.direction() != DeliveryDirection.SERVERBOUND) {
            DROPPED_ENVELOPES.incrementAndGet();
            return false;
        }
        ServerChannelBinding binding = resolveServerBinding(envelope);
        if (binding == null || !binding.hasReceiver()) {
            DROPPED_ENVELOPES.incrementAndGet();
            return false;
        }
        binding.dispatchEnvelope(server, player, handler, envelope.payloadBytes(), responseSender);
        DISPATCHED_ENVELOPES.incrementAndGet();
        return true;
    }

    public static Map<ResourceLocation, PacketReceiver> snapshotClientReceivers() {
        Map<ResourceLocation, PacketReceiver> snapshot = new ConcurrentHashMap<>();
        CLIENT_CHANNELS.forEach((channelId, binding) -> {
            if (binding.legacyReceiver() != null) {
                snapshot.put(channelId, binding.legacyReceiver());
            }
        });
        return Map.copyOf(snapshot);
    }

    public static Map<ResourceLocation, ServerPacketReceiver> snapshotServerReceivers() {
        Map<ResourceLocation, ServerPacketReceiver> snapshot = new ConcurrentHashMap<>();
        SERVER_CHANNELS.forEach((channelId, binding) -> {
            if (binding.legacyReceiver() != null) {
                snapshot.put(channelId, binding.legacyReceiver());
            }
        });
        return Map.copyOf(snapshot);
    }

    public static void clear() {
        CLIENT_CHANNELS.clear();
        CLIENT_CHANNELS_BY_NUMERIC_ID.clear();
        SERVER_CHANNELS.clear();
        SERVER_CHANNELS_BY_NUMERIC_ID.clear();
        REGISTRY_SYNC_SERVER_SNAPSHOTS.clear();
        registrySyncLastServerSnapshot = EMPTY_PAYLOAD;
        ENCODED_ENVELOPES.set(0);
        DECODED_ENVELOPES.set(0);
        DISPATCHED_ENVELOPES.set(0);
        DROPPED_ENVELOPES.set(0);
        FALLBACK_CHANNEL_MATCHES.set(0);
    }

    public static void resetForTests() {
        clear();
        REGISTRY_SYNC_CHANNEL_REGISTERED.set(false);
        REGISTRY_SYNC_LIFECYCLE_HOOKS_REGISTERED.set(false);
        registrySyncServerSnapshotSupplier = RegistryTranslationMatrix::buildLocalSnapshot;
        registrySyncClientSnapshotSupplier = RegistryTranslationMatrix::buildLocalSnapshot;
        RegistryTranslationMatrix.reset();
    }

    // ── Registry translation matrix handshake (ТЗ 3.5.3) ─────────────────────

    private static final Map<Object, byte[]> REGISTRY_SYNC_SERVER_SNAPSHOTS = new ConcurrentHashMap<>();
    private static volatile Supplier<byte[]> registrySyncServerSnapshotSupplier =
        RegistryTranslationMatrix::buildLocalSnapshot;
    private static volatile Supplier<byte[]> registrySyncClientSnapshotSupplier =
        RegistryTranslationMatrix::buildLocalSnapshot;
    private static volatile byte[] registrySyncLastServerSnapshot = EMPTY_PAYLOAD;

    public static void registerRegistrySyncChannel() {
        registerRegistrySyncChannel(RegistryTranslationMatrix::buildLocalSnapshot);
    }

    public static void registerRegistrySyncChannel(Supplier<byte[]> localSnapshotSupplier) {
        registerRegistrySyncChannel(localSnapshotSupplier, localSnapshotSupplier);
    }

    /**
     * Registers the serverbound and clientbound registry-sync channels used during
     * the multiplayer handshake to exchange MPHF slot snapshots and build the
     * bijective {@link org.intermed.core.registry.RegistryTranslationMatrix}
     * (ТЗ 3.5.3).
     *
     * <p>Call this once during server startup, after the local registry is frozen.
     * The {@code localSnapshot} is produced by
     * {@link org.intermed.core.registry.RegistryTranslationMatrix#buildLocalSnapshot()}
     * which is the public API that encapsulates the package-private
     * {@code FrozenStringIntHashIndex}.
     *
     * @param localSnapshot  serialised binary snapshot of the server's registry
     *                       (use {@link org.intermed.core.registry.RegistryTranslationMatrix#buildLocalSnapshot()})
     */
    public static void registerRegistrySyncChannel(byte[] localSnapshot) {
        byte[] snapshot = sanitizePayloadBytes(localSnapshot);
        registerRegistrySyncChannel(() -> snapshot, () -> snapshot);
    }

    public static void registerRegistrySyncChannel(Supplier<byte[]> serverSnapshotSupplier,
                                                   Supplier<byte[]> clientSnapshotSupplier) {
        registrySyncServerSnapshotSupplier = serverSnapshotSupplier != null
            ? serverSnapshotSupplier
            : RegistryTranslationMatrix::buildLocalSnapshot;
        registrySyncClientSnapshotSupplier = clientSnapshotSupplier != null
            ? clientSnapshotSupplier
            : RegistryTranslationMatrix::buildLocalSnapshot;

        refreshRegistrySyncServerSnapshot();

        if (!REGISTRY_SYNC_CHANNEL_REGISTERED.compareAndSet(false, true)) {
            return;
        }

        ResourceLocation registrySyncChannel = registrySyncChannel();

        // Serverbound: client → server — client sends its own snapshot during handshake.
        // Server builds the translation matrix from the two snapshots.
        registerServerReceiver(registrySyncChannel, (server, player, handler, buf, responseSender) -> {
            if (!(buf instanceof byte[])) return;
            byte[] clientSnapshotBytes = sanitizePayloadBytes((byte[]) buf);
            try {
                byte[] serverSnapshotBytes = resolveServerSnapshot(player, handler);
                RegistryTranslationMatrix matrix = buildTranslationMatrix(serverSnapshotBytes, clientSnapshotBytes);
                RegistryTranslationMatrix.install(matrix);
                System.out.println("[InterMed Network] RegistryTranslationMatrix installed ("
                    + RegistryTranslationMatrix.deserialiseSnapshot(clientSnapshotBytes).size() + " client entries)");
            } catch (Exception e) {
                System.err.println("[InterMed Network] Failed to build RegistryTranslationMatrix: " + e.getMessage());
            }
        });

        // Clientbound: server → client — broadcast server snapshot on player join
        // so the client can also build its local translation view.
        registerClientReceiver(registrySyncChannel, (client, handler, buf, responseSender) -> {
            if (!(buf instanceof byte[])) return;
            byte[] serverSnapshotBytes = sanitizePayloadBytes((byte[]) buf);
            try {
                byte[] clientSnapshotBytes = buildSnapshot(registrySyncClientSnapshotSupplier);
                RegistryTranslationMatrix matrix = buildTranslationMatrix(serverSnapshotBytes, clientSnapshotBytes);
                RegistryTranslationMatrix.install(matrix);
                byte[] replyFrame = encodeServerbound(
                    registrySyncChannel,
                    "intermed",
                    clientSnapshotBytes,
                    PayloadCodec.bytes()
                );
                if (!sendTransportFrame(responseSender, DeliveryDirection.SERVERBOUND, replyFrame)) {
                    System.err.println("[InterMed Network] Registry-sync client reply could not be delivered.");
                }
                List<RegistryTranslationMatrix.SlotEntry> serverEntries =
                    RegistryTranslationMatrix.deserialiseSnapshot(serverSnapshotBytes);
                System.out.println("[InterMed Network] Client received server registry snapshot ("
                    + serverEntries.size() + " entries)");
            } catch (Exception e) {
                System.err.println("[InterMed Network] Failed to parse server registry snapshot: " + e.getMessage());
            }
        });

        registerRegistrySyncLifecycleHooks();
    }

    private static ClientChannelBinding ensureClientBinding(ResourceLocation channelId, String ownerModId) {
        ClientChannelBinding binding = CLIENT_CHANNELS.get(channelId);
        if (binding != null) {
            binding.touchOwner(ownerModId);
            return binding;
        }
        synchronized (REGISTRATION_LOCK) {
            binding = CLIENT_CHANNELS.get(channelId);
            if (binding != null) {
                binding.touchOwner(ownerModId);
                return binding;
            }
            long numericId = deterministicNumericId(DeliveryDirection.CLIENTBOUND, channelId);
            ClientChannelBinding existing = CLIENT_CHANNELS_BY_NUMERIC_ID.get(numericId);
            if (existing != null && !existing.channelId().equals(channelId)) {
                throw new IllegalStateException("Clientbound network id collision between "
                    + existing.channelId() + " and " + channelId + " (" + Long.toUnsignedString(numericId) + ")");
            }
            ClientChannelBinding created = new ClientChannelBinding(channelId, numericId, normalizeOwner(ownerModId, channelId));
            CLIENT_CHANNELS.put(channelId, created);
            CLIENT_CHANNELS_BY_NUMERIC_ID.put(numericId, created);
            return created;
        }
    }

    private static ServerChannelBinding ensureServerBinding(ResourceLocation channelId, String ownerModId) {
        ServerChannelBinding binding = SERVER_CHANNELS.get(channelId);
        if (binding != null) {
            binding.touchOwner(ownerModId);
            return binding;
        }
        synchronized (REGISTRATION_LOCK) {
            binding = SERVER_CHANNELS.get(channelId);
            if (binding != null) {
                binding.touchOwner(ownerModId);
                return binding;
            }
            long numericId = deterministicNumericId(DeliveryDirection.SERVERBOUND, channelId);
            ServerChannelBinding existing = SERVER_CHANNELS_BY_NUMERIC_ID.get(numericId);
            if (existing != null && !existing.channelId().equals(channelId)) {
                throw new IllegalStateException("Serverbound network id collision between "
                    + existing.channelId() + " and " + channelId + " (" + Long.toUnsignedString(numericId) + ")");
            }
            ServerChannelBinding created = new ServerChannelBinding(channelId, numericId, normalizeOwner(ownerModId, channelId));
            SERVER_CHANNELS.put(channelId, created);
            SERVER_CHANNELS_BY_NUMERIC_ID.put(numericId, created);
            return created;
        }
    }

    private static ClientChannelBinding resolveClientBinding(InterMedPacketEnvelope envelope) {
        ClientChannelBinding binding = CLIENT_CHANNELS_BY_NUMERIC_ID.get(envelope.channelNumericId());
        if (binding != null) {
            return binding;
        }
        binding = CLIENT_CHANNELS.get(envelope.originalChannelId());
        if (binding != null) {
            FALLBACK_CHANNEL_MATCHES.incrementAndGet();
        }
        return binding;
    }

    private static ServerChannelBinding resolveServerBinding(InterMedPacketEnvelope envelope) {
        ServerChannelBinding binding = SERVER_CHANNELS_BY_NUMERIC_ID.get(envelope.channelNumericId());
        if (binding != null) {
            return binding;
        }
        binding = SERVER_CHANNELS.get(envelope.originalChannelId());
        if (binding != null) {
            FALLBACK_CHANNEL_MATCHES.incrementAndGet();
        }
        return binding;
    }

    private static <T> InterMedPacketEnvelope createEnvelope(ResourceLocation channelId,
                                                             DeliveryDirection direction,
                                                             String sourceModId,
                                                             T payload,
                                                             PayloadCodec<T> codec) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(direction, "direction");
        PayloadCodec<T> resolvedCodec = resolveCodec(payload, codec);
        byte[] payloadBytes = sanitizePayloadBytes(resolvedCodec.encode(payload));
        long numericId = resolveChannelNumericId(channelId, direction);
        ENCODED_ENVELOPES.incrementAndGet();
        return new InterMedPacketEnvelope(
            NETWORK_PROTOCOL_VERSION,
            direction,
            numericId,
            normalizeOwner(sourceModId, channelId),
            channelId,
            payloadBytes
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> PayloadCodec<T> resolveCodec(T payload, PayloadCodec<T> codec) {
        if (codec != null) {
            return codec;
        }
        if (payload instanceof byte[]) {
            return (PayloadCodec<T>) PayloadCodec.bytes();
        }
        throw new IllegalArgumentException("Payload codec is required for non-byte[] payloads");
    }

    private static byte[] sanitizePayloadBytes(byte[] payloadBytes) {
        if (payloadBytes == null || payloadBytes.length == 0) {
            return EMPTY_PAYLOAD;
        }
        return Arrays.copyOf(payloadBytes, payloadBytes.length);
    }

    private static long deterministicNumericId(DeliveryDirection direction, ResourceLocation channelId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((direction.name() + "|" + channelId).getBytes(StandardCharsets.UTF_8));
            long value = ByteBuffer.wrap(hash, 0, Long.BYTES).getLong() & Long.MAX_VALUE;
            return value == 0 ? 1L : value;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to derive deterministic network id for " + channelId, e);
        }
    }

    private static String normalizeOwner(String ownerModId, ResourceLocation channelId) {
        if (ownerModId != null && !ownerModId.isBlank()) {
            return ownerModId;
        }
        String value = channelId.toString();
        int separator = value.indexOf(':');
        if (separator > 0) {
            return value.substring(0, separator);
        }
        return "unknown";
    }

    private static void writeString(DataOutputStream data, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        data.writeInt(bytes.length);
        data.write(bytes);
    }

    private static String readString(DataInputStream data) throws IOException {
        int length = data.readInt();
        if (length < 0) {
            throw new IllegalArgumentException("Negative string length in InterMed network envelope");
        }
        byte[] bytes = data.readNBytes(length);
        if (bytes.length != length) {
            throw new IllegalArgumentException("Truncated string field in InterMed network envelope");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static ResourceLocation parseResourceLocation(String value) {
        String normalized = value == null ? "" : value.trim();
        int separator = normalized.indexOf(':');
        if (separator <= 0 || separator == normalized.length() - 1) {
            throw new IllegalArgumentException("Invalid logical channel id in InterMed network envelope: " + normalized);
        }
        return new ResourceLocation(normalized.substring(0, separator), normalized.substring(separator + 1));
    }

    private static void registerRegistrySyncLifecycleHooks() {
        if (!REGISTRY_SYNC_LIFECYCLE_HOOKS_REGISTERED.compareAndSet(false, true)) {
            return;
        }

        ServerLifecycleEvents.SERVER_STARTED.register(server -> refreshRegistrySyncServerSnapshot());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ResourceLocation registrySyncChannel = registrySyncChannel();
            byte[] serverSnapshot = refreshRegistrySyncServerSnapshot();
            if (serverSnapshot.length == 0) {
                return;
            }
            if (handler != null) {
                REGISTRY_SYNC_SERVER_SNAPSHOTS.put(handler, serverSnapshot);
            }
            byte[] frame = encodeClientbound(
                registrySyncChannel,
                "intermed",
                serverSnapshot,
                PayloadCodec.bytes()
            );
            if (!sendTransportFrame(sender, DeliveryDirection.CLIENTBOUND, frame)) {
                System.err.println("[InterMed Network] Registry-sync join sender unavailable; handshake deferred.");
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (handler != null) {
                REGISTRY_SYNC_SERVER_SNAPSHOTS.remove(handler);
            }
            if (REGISTRY_SYNC_SERVER_SNAPSHOTS.isEmpty()) {
                RegistryTranslationMatrix.reset();
            }
        });
    }

    private static RegistryTranslationMatrix buildTranslationMatrix(byte[] serverSnapshotBytes,
                                                                   byte[] clientSnapshotBytes) {
        List<RegistryTranslationMatrix.SlotEntry> serverSnapshot =
            RegistryTranslationMatrix.deserialiseSnapshot(serverSnapshotBytes);
        List<RegistryTranslationMatrix.SlotEntry> clientSnapshot =
            RegistryTranslationMatrix.deserialiseSnapshot(clientSnapshotBytes);
        return RegistryTranslationMatrix.buildFromSnapshots(serverSnapshot, clientSnapshot);
    }

    private static byte[] refreshRegistrySyncServerSnapshot() {
        byte[] snapshot = buildSnapshot(registrySyncServerSnapshotSupplier);
        registrySyncLastServerSnapshot = snapshot;
        return snapshot;
    }

    private static byte[] resolveServerSnapshot(Object player, Object handler) {
        byte[] snapshot = snapshotForPeer(player);
        if (snapshot.length == 0) {
            snapshot = snapshotForPeer(handler);
        }
        if (snapshot.length == 0) {
            snapshot = registrySyncLastServerSnapshot;
        }
        if (snapshot.length == 0) {
            snapshot = refreshRegistrySyncServerSnapshot();
        }
        return snapshot;
    }

    private static byte[] snapshotForPeer(Object peer) {
        if (peer == null) {
            return EMPTY_PAYLOAD;
        }
        return sanitizePayloadBytes(REGISTRY_SYNC_SERVER_SNAPSHOTS.get(peer));
    }

    private static byte[] buildSnapshot(Supplier<byte[]> snapshotSupplier) {
        if (snapshotSupplier == null) {
            return EMPTY_PAYLOAD;
        }
        try {
            return sanitizePayloadBytes(snapshotSupplier.get());
        } catch (Exception e) {
            System.err.println("[InterMed Network] Failed to build registry snapshot: " + e.getMessage());
            return EMPTY_PAYLOAD;
        }
    }

    public static boolean sendClientboundFrame(Object sender, byte[] encodedEnvelope) {
        return sendTransportFrame(sender, DeliveryDirection.CLIENTBOUND, encodedEnvelope);
    }

    public static boolean sendServerboundFrame(Object sender, byte[] encodedEnvelope) {
        return sendTransportFrame(sender, DeliveryDirection.SERVERBOUND, encodedEnvelope);
    }

    @SuppressWarnings("unchecked")
    private static boolean sendTransportFrame(Object sender,
                                              DeliveryDirection direction,
                                              byte[] encodedEnvelope) {
        byte[] frame = sanitizePayloadBytes(encodedEnvelope);
        if (sender == null || frame.length == 0) {
            return false;
        }

        if (sender instanceof FrameSender frameSender) {
            frameSender.sendFrame(frame);
            return true;
        }
        ResourceLocation transportChannel = transportChannel();
        if (sender instanceof ChannelFrameSender frameSender) {
            frameSender.send(transportChannel, frame);
            return true;
        }
        if (sender instanceof Consumer<?> consumer) {
            ((Consumer<byte[]>) consumer).accept(frame);
            return true;
        }
        if (invokeMatchingMethod(sender, new String[]{"sendFrame", "send", "accept"}, frame)) {
            return true;
        }
        if (invokeMatchingMethod(sender, new String[]{"send", "sendPacket"}, transportChannel, frame)) {
            return true;
        }
        FriendlyByteBuf rawBuffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(Arrays.copyOf(frame, frame.length)));
        if (invokeMatchingMethod(sender, new String[]{"send", "sendPacket"}, transportChannel, rawBuffer)) {
            return true;
        }

        Object packet = createMinecraftTransportPacket(direction, frame);
        if (packet == null) {
            return false;
        }
        if (invokeMatchingMethod(sender, new String[]{"send", "sendPacket"}, packet)) {
            return true;
        }
        Object nestedConnection = nestedConnection(sender);
        return nestedConnection != null
            && invokeMatchingMethod(nestedConnection, new String[]{"send", "sendPacket"}, packet);
    }

    private static Object createMinecraftTransportPacket(DeliveryDirection direction, byte[] encodedEnvelope) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(Arrays.copyOf(encodedEnvelope, encodedEnvelope.length)));
        ResourceLocation transportChannel = transportChannel();
        return switch (direction) {
            case CLIENTBOUND -> new ClientboundCustomPayloadPacket(transportChannel, buffer);
            case SERVERBOUND -> new ServerboundCustomPayloadPacket(transportChannel, buffer);
        };
    }

    private static boolean invokeMatchingMethod(Object target, String[] methodNames, Object... args) {
        if (target == null || methodNames == null) {
            return false;
        }
        for (Method method : target.getClass().getMethods()) {
            if (!matchesMethodName(method, methodNames) || method.getParameterCount() != args.length) {
                continue;
            }
            Class<?>[] parameterTypes = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                Object arg = args[i];
                if (arg != null && !parameterTypes[i].isInstance(arg)) {
                    compatible = false;
                    break;
                }
            }
            if (!compatible) {
                continue;
            }
            try {
                method.invoke(target, args);
                return true;
            } catch (ReflectiveOperationException ignored) {
                continue;
            }
        }
        return false;
    }

    private static boolean matchesMethodName(Method method, String[] names) {
        for (String name : names) {
            if (method.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static Object nestedConnection(Object sender) {
        Object connection = invokeNoArg(sender, "getConnection");
        if (connection != null) {
            return connection;
        }
        connection = getFieldValue(sender, "connection");
        if (connection != null) {
            return connection;
        }
        connection = invokeNoArg(sender, "connection");
        if (connection != null) {
            return connection;
        }
        return getFieldValue(sender, "listener");
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null) {
            return null;
        }
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Object getFieldValue(Object target, String fieldName) {
        if (target == null || fieldName == null) {
            return null;
        }
        try {
            Field field = target.getClass().getField(fieldName);
            return field.get(target);
        } catch (ReflectiveOperationException ignored) {
            try {
                Field field = target.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(target);
            } catch (ReflectiveOperationException ignoredAgain) {
                return null;
            }
        }
    }

    @FunctionalInterface
    public interface PacketReceiver {
        void receive(Object client, Object handler, Object buf, Object responseSender);
    }

    @FunctionalInterface
    public interface ServerPacketReceiver {
        void receive(Object server, Object player, Object handler, Object buf, Object responseSender);
    }

    @FunctionalInterface
    public interface TypedPacketReceiver<T> {
        void receive(Object client, Object handler, T payload, Object responseSender);
    }

    @FunctionalInterface
    public interface TypedServerPacketReceiver<T> {
        void receive(Object server, Object player, Object handler, T payload, Object responseSender);
    }

    @FunctionalInterface
    public interface FrameSender {
        void sendFrame(byte[] encodedEnvelope);
    }

    @FunctionalInterface
    public interface ChannelFrameSender {
        void send(ResourceLocation transportChannel, byte[] encodedEnvelope);
    }

    public enum DeliveryDirection {
        CLIENTBOUND(0),
        SERVERBOUND(1);

        private final int wireId;

        DeliveryDirection(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static DeliveryDirection fromWireId(int wireId) {
            for (DeliveryDirection value : values()) {
                if (value.wireId == wireId) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown InterMed network direction id: " + wireId);
        }
    }

    public interface PayloadCodec<T> {
        byte[] encode(T payload);

        T decode(byte[] payloadBytes);

        static PayloadCodec<byte[]> bytes() {
            return BytesPayloadCodec.INSTANCE;
        }

        static PayloadCodec<String> utf8String() {
            return Utf8StringPayloadCodec.INSTANCE;
        }
    }

    public record InterMedPacketEnvelope(int protocolVersion,
                                         DeliveryDirection direction,
                                         long channelNumericId,
                                         String sourceModId,
                                         ResourceLocation originalChannelId,
                                         byte[] payloadBytes) {
        public InterMedPacketEnvelope {
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(originalChannelId, "originalChannelId");
            sourceModId = sourceModId == null || sourceModId.isBlank() ? "unknown" : sourceModId;
            payloadBytes = payloadBytes == null || payloadBytes.length == 0
                ? EMPTY_PAYLOAD
                : Arrays.copyOf(payloadBytes, payloadBytes.length);
        }

        @Override
        public byte[] payloadBytes() {
            return Arrays.copyOf(payloadBytes, payloadBytes.length);
        }
    }

    public record ChannelDescriptor(ResourceLocation channelId,
                                    long numericId,
                                    DeliveryDirection direction,
                                    String ownerModId,
                                    boolean typedPayload,
                                    boolean receiverRegistered) {
    }

    public record NetworkBridgeDiagnostics(int protocolVersion,
                                           ResourceLocation transportChannel,
                                           int registeredChannels,
                                           long encodedEnvelopes,
                                           long decodedEnvelopes,
                                           long dispatchedEnvelopes,
                                           long droppedEnvelopes,
                                           long fallbackChannelMatches) {
    }

    private enum BytesPayloadCodec implements PayloadCodec<byte[]> {
        INSTANCE;

        @Override
        public byte[] encode(byte[] payload) {
            return sanitizePayloadBytes(payload);
        }

        @Override
        public byte[] decode(byte[] payloadBytes) {
            return sanitizePayloadBytes(payloadBytes);
        }
    }

    private enum Utf8StringPayloadCodec implements PayloadCodec<String> {
        INSTANCE;

        @Override
        public byte[] encode(String payload) {
            return payload == null ? EMPTY_PAYLOAD : payload.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String decode(byte[] payloadBytes) {
            return new String(payloadBytes == null ? EMPTY_PAYLOAD : payloadBytes, StandardCharsets.UTF_8);
        }
    }

    private static final class ClientChannelBinding {
        private final ResourceLocation channelId;
        private final long numericId;
        private volatile String ownerModId;
        private volatile PacketReceiver legacyReceiver;
        private volatile PayloadCodec<Object> typedCodec;
        private volatile TypedPacketReceiver<Object> typedReceiver;

        private ClientChannelBinding(ResourceLocation channelId, long numericId, String ownerModId) {
            this.channelId = channelId;
            this.numericId = numericId;
            this.ownerModId = ownerModId;
        }

        private ResourceLocation channelId() {
            return channelId;
        }

        private long numericId() {
            return numericId;
        }

        private PacketReceiver legacyReceiver() {
            return legacyReceiver;
        }

        private void touchOwner(String ownerModId) {
            if (ownerModId != null && !ownerModId.isBlank()) {
                this.ownerModId = ownerModId;
            }
        }

        private boolean hasReceiver() {
            return legacyReceiver != null || typedReceiver != null;
        }

        private void registerLegacyReceiver(PacketReceiver receiver) {
            this.legacyReceiver = receiver;
            this.typedReceiver = null;
            this.typedCodec = null;
        }

        @SuppressWarnings("unchecked")
        private <T> void registerTypedReceiver(PayloadCodec<T> codec, TypedPacketReceiver<T> receiver) {
            this.legacyReceiver = null;
            this.typedCodec = (PayloadCodec<Object>) codec;
            this.typedReceiver = (TypedPacketReceiver<Object>) receiver;
        }

        private void dispatchDirect(Object client, Object handler, Object payload, Object responseSender) {
            if (typedReceiver != null) {
                typedReceiver.receive(client, handler, adaptDirectPayload(payload), responseSender);
                return;
            }
            if (legacyReceiver != null) {
                legacyReceiver.receive(client, handler, payload, responseSender);
            }
        }

        private void dispatchEnvelope(Object client, Object handler, byte[] payloadBytes, Object responseSender) {
            if (typedReceiver != null && typedCodec != null) {
                typedReceiver.receive(client, handler, typedCodec.decode(payloadBytes), responseSender);
                return;
            }
            if (legacyReceiver != null) {
                legacyReceiver.receive(client, handler, sanitizePayloadBytes(payloadBytes), responseSender);
            }
        }

        private Object adaptDirectPayload(Object payload) {
            if (payload instanceof byte[] bytes && typedCodec != null) {
                return typedCodec.decode(bytes);
            }
            return payload;
        }

        private ChannelDescriptor descriptor() {
            return new ChannelDescriptor(channelId, numericId, DeliveryDirection.CLIENTBOUND, ownerModId, typedReceiver != null, hasReceiver());
        }
    }

    private static final class ServerChannelBinding {
        private final ResourceLocation channelId;
        private final long numericId;
        private volatile String ownerModId;
        private volatile ServerPacketReceiver legacyReceiver;
        private volatile PayloadCodec<Object> typedCodec;
        private volatile TypedServerPacketReceiver<Object> typedReceiver;

        private ServerChannelBinding(ResourceLocation channelId, long numericId, String ownerModId) {
            this.channelId = channelId;
            this.numericId = numericId;
            this.ownerModId = ownerModId;
        }

        private ResourceLocation channelId() {
            return channelId;
        }

        private long numericId() {
            return numericId;
        }

        private ServerPacketReceiver legacyReceiver() {
            return legacyReceiver;
        }

        private void touchOwner(String ownerModId) {
            if (ownerModId != null && !ownerModId.isBlank()) {
                this.ownerModId = ownerModId;
            }
        }

        private boolean hasReceiver() {
            return legacyReceiver != null || typedReceiver != null;
        }

        private void registerLegacyReceiver(ServerPacketReceiver receiver) {
            this.legacyReceiver = receiver;
            this.typedReceiver = null;
            this.typedCodec = null;
        }

        @SuppressWarnings("unchecked")
        private <T> void registerTypedReceiver(PayloadCodec<T> codec, TypedServerPacketReceiver<T> receiver) {
            this.legacyReceiver = null;
            this.typedCodec = (PayloadCodec<Object>) codec;
            this.typedReceiver = (TypedServerPacketReceiver<Object>) receiver;
        }

        private void dispatchDirect(Object server, Object player, Object handler, Object payload, Object responseSender) {
            if (typedReceiver != null) {
                typedReceiver.receive(server, player, handler, adaptDirectPayload(payload), responseSender);
                return;
            }
            if (legacyReceiver != null) {
                legacyReceiver.receive(server, player, handler, payload, responseSender);
            }
        }

        private void dispatchEnvelope(Object server, Object player, Object handler, byte[] payloadBytes, Object responseSender) {
            if (typedReceiver != null && typedCodec != null) {
                typedReceiver.receive(server, player, handler, typedCodec.decode(payloadBytes), responseSender);
                return;
            }
            if (legacyReceiver != null) {
                legacyReceiver.receive(server, player, handler, sanitizePayloadBytes(payloadBytes), responseSender);
            }
        }

        private Object adaptDirectPayload(Object payload) {
            if (payload instanceof byte[] bytes && typedCodec != null) {
                return typedCodec.decode(bytes);
            }
            return payload;
        }

        private ChannelDescriptor descriptor() {
            return new ChannelDescriptor(channelId, numericId, DeliveryDirection.SERVERBOUND, ownerModId, typedReceiver != null, hasReceiver());
        }
    }
}
