package org.intermed.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.intermed.core.bridge.ForgeNetworkBridge;
import org.intermed.core.bridge.InterMedNetworkBridge;
import org.intermed.core.bridge.NeoForgeNetworkBridge;
import org.intermed.core.metadata.RuntimeModIndex;
import org.intermed.core.monitor.ObservabilityMonitor;
import org.intermed.core.monitor.PerformanceMonitor;
import org.intermed.core.registry.VirtualRegistry;
import org.intermed.core.remapping.InterMedRemapper;
import org.intermed.core.sandbox.SandboxExposed;
import org.intermed.core.sandbox.PolyglotSandboxManager;
import org.intermed.core.sandbox.SandboxSharedExecutionContext;
import org.intermed.core.sandbox.WitContractCatalog;
import org.intermed.core.security.Capability;
import org.intermed.core.security.CapabilityManager;
import org.intermed.core.security.SecurityPolicy;

import java.util.Collection;
import java.util.Base64;
import java.util.List;

/**
 * Public InterMed API exposed to mods and sandboxed guests.
 *
 * <p>Methods annotated with {@link SandboxExposed} are available to Espresso
 * guests. WIT-compatible signatures are additionally surfaced as Wasm host
 * functions via {@link org.intermed.core.sandbox.WitContractCatalog}. Methods
 * without the annotation are available to native mods only.
 *
 * <h3>Design contract</h3>
 * <ul>
 *   <li>All methods are {@code static} — no instance state.</li>
 *   <li>No checked exceptions — guests cannot handle Java exception types.</li>
 *   <li>Primitive / String / boolean return types preferred for sandbox compatibility.</li>
 * </ul>
 */
@SandboxExposed
public final class InterMedAPI {

    private InterMedAPI() {}

    // ── Remapping ─────────────────────────────────────────────────────────────

    /**
     * Remaps a binary class name from Intermediary to the active mapping (Mojang/SRG).
     *
     * @param originalName binary class name, e.g. {@code "net.minecraft.class_2960"}
     * @return mapped name, or {@code originalName} if no mapping is registered
     */
    @SandboxExposed
    public static String remapClassname(String originalName) {
        return InterMedRemapper.remapBinaryClassName(originalName);
    }

    /**
     * Remaps a runtime string that may contain Intermediary class or member names.
     * Used by mods that build reflection strings dynamically.
     *
     * @param originalValue string potentially containing unmapped names
     * @return translated string, or the original if no substitution applied
     */
    @SandboxExposed
    public static String remapRuntimeString(String originalValue) {
        return InterMedRemapper.translateRuntimeString(originalValue);
    }

    // ── Registry ──────────────────────────────────────────────────────────────

    /**
     * Looks up a registry entry by its namespace key (e.g. {@code "minecraft:stone"}).
     *
     * @param key namespace key in {@code "namespace:path"} form
     * @return the registered object, or {@code null} if not found
     */
    @SandboxExposed
    public static Object registryGet(String key) {
        return VirtualRegistry.getFast(key);
    }

    /**
     * Looks up a registry entry by its raw/global integer id.
     *
     * @param rawId raw id as assigned during registration
     * @return the registered object, or {@code null} if not found
     */
    @SandboxExposed
    public static Object registryGetById(int rawId) {
        return VirtualRegistry.getFastByRawId(rawId);
    }

    /**
     * Returns the raw id assigned to {@code value}, or {@code -1} if unknown.
     */
    @SandboxExposed
    public static int registryRawId(Object value) {
        return VirtualRegistry.getRawIdFast(value);
    }

    /**
     * Returns {@code true} if the registry has been frozen (i.e. the flat-array
     * fast path is active).  Before freeze all lookups go through
     * {@link java.util.concurrent.ConcurrentHashMap}.
     */
    @SandboxExposed
    public static boolean isRegistryFrozen() {
        return VirtualRegistry.isFrozen();
    }

    // ── Networking ────────────────────────────────────────────────────────────

    @SandboxExposed
    public static int networkProtocolVersion() {
        return InterMedNetworkBridge.protocolVersion();
    }

    @SandboxExposed
    public static String networkTransportChannel() {
        return InterMedNetworkBridge.transportChannel().toString();
    }

    @SandboxExposed
    public static int networkRegisteredChannelCount() {
        return InterMedNetworkBridge.diagnostics().registeredChannels();
    }

    @SandboxExposed
    public static long networkClientboundChannelId(String channelKey) {
        return InterMedNetworkBridge.resolveChannelNumericId(
            parseResourceLocation(channelKey),
            InterMedNetworkBridge.DeliveryDirection.CLIENTBOUND
        );
    }

    @SandboxExposed
    public static long networkServerboundChannelId(String channelKey) {
        return InterMedNetworkBridge.resolveChannelNumericId(
            parseResourceLocation(channelKey),
            InterMedNetworkBridge.DeliveryDirection.SERVERBOUND
        );
    }

    @SandboxExposed
    public static String networkDiagnosticsJson() {
        InterMedNetworkBridge.NetworkBridgeDiagnostics diagnostics = InterMedNetworkBridge.diagnostics();
        JsonObject json = new JsonObject();
        json.addProperty("protocolVersion", diagnostics.protocolVersion());
        json.addProperty("transportChannel", diagnostics.transportChannel().toString());
        json.addProperty("registeredChannels", diagnostics.registeredChannels());
        json.addProperty("encodedEnvelopes", diagnostics.encodedEnvelopes());
        json.addProperty("decodedEnvelopes", diagnostics.decodedEnvelopes());
        json.addProperty("dispatchedEnvelopes", diagnostics.dispatchedEnvelopes());
        json.addProperty("droppedEnvelopes", diagnostics.droppedEnvelopes());
        json.addProperty("fallbackChannelMatches", diagnostics.fallbackChannelMatches());

        JsonArray channels = new JsonArray();
        for (InterMedNetworkBridge.ChannelDescriptor descriptor : InterMedNetworkBridge.snapshotChannelDescriptors()) {
            JsonObject channel = new JsonObject();
            channel.addProperty("id", descriptor.channelId().toString());
            channel.addProperty("direction", descriptor.direction().name());
            channel.addProperty("numericId", Long.toUnsignedString(descriptor.numericId()));
            channel.addProperty("ownerModId", descriptor.ownerModId());
            channel.addProperty("typedPayload", descriptor.typedPayload());
            channel.addProperty("receiverRegistered", descriptor.receiverRegistered());
            channels.add(channel);
        }
        json.add("channels", channels);

        ForgeNetworkBridge.ForgeNetworkDiagnostics forgeDiagnostics = ForgeNetworkBridge.diagnostics();
        JsonObject forge = new JsonObject();
        forge.addProperty("registeredChannels", forgeDiagnostics.registeredChannels());
        forge.addProperty("registeredMessages", forgeDiagnostics.registeredMessages());
        forge.addProperty("encodedMessages", forgeDiagnostics.encodedMessages());
        forge.addProperty("dispatchedMessages", forgeDiagnostics.dispatchedMessages());
        forge.addProperty("droppedMessages", forgeDiagnostics.droppedMessages());
        JsonArray forgeChannels = new JsonArray();
        for (ForgeNetworkBridge.ForgeChannelDescriptor descriptor : ForgeNetworkBridge.snapshotChannels()) {
            JsonObject channel = new JsonObject();
            channel.addProperty("id", descriptor.channelId().toString());
            channel.addProperty("protocolVersion", descriptor.protocolVersion());
            channel.addProperty("clientboundChannelId", Long.toUnsignedString(descriptor.clientboundChannelId()));
            channel.addProperty("serverboundChannelId", Long.toUnsignedString(descriptor.serverboundChannelId()));
            channel.addProperty("clientboundMessages", descriptor.clientboundMessages());
            channel.addProperty("serverboundMessages", descriptor.serverboundMessages());
            forgeChannels.add(channel);
        }
        forge.add("channels", forgeChannels);
        JsonArray forgeMessages = new JsonArray();
        for (ForgeNetworkBridge.ForgeMessageDescriptor descriptor : ForgeNetworkBridge.snapshotMessages()) {
            JsonObject message = new JsonObject();
            message.addProperty("channelId", descriptor.channelId().toString());
            message.addProperty("discriminator", descriptor.discriminator());
            message.addProperty("direction", descriptor.direction().name());
            message.addProperty("ownerModId", descriptor.ownerModId());
            message.addProperty("payloadType", descriptor.payloadType());
            forgeMessages.add(message);
        }
        forge.add("messages", forgeMessages);
        json.add("forgeSimpleImpl", forge);

        NeoForgeNetworkBridge.NeoForgeNetworkDiagnostics neoForgeDiagnostics = NeoForgeNetworkBridge.diagnostics();
        JsonObject neoForge = new JsonObject();
        neoForge.addProperty("listenersRegistered", neoForgeDiagnostics.listenersRegistered());
        neoForge.addProperty("observedEventClass", neoForgeDiagnostics.observedEventClass());
        neoForge.addProperty("observedRegistrarStyle", neoForgeDiagnostics.observedRegistrarStyle());
        neoForge.addProperty("observedRegistrationEvents", neoForgeDiagnostics.observedRegistrationEvents());
        neoForge.addProperty("registeredPayloads", neoForgeDiagnostics.registeredPayloads());
        neoForge.addProperty("encodedEnvelopes", neoForgeDiagnostics.encodedEnvelopes());
        JsonArray neoForgePayloads = new JsonArray();
        for (NeoForgeNetworkBridge.PayloadDescriptor descriptor : NeoForgeNetworkBridge.snapshotPayloads()) {
            JsonObject payload = new JsonObject();
            payload.addProperty("id", descriptor.payloadId().toString());
            payload.addProperty("protocolVersion", descriptor.protocolVersion());
            payload.addProperty("ownerModId", descriptor.ownerModId());
            payload.addProperty("clientbound", descriptor.clientbound());
            payload.addProperty("serverbound", descriptor.serverbound());
            payload.addProperty("payloadType", descriptor.payloadType());
            neoForgePayloads.add(payload);
        }
        neoForge.add("payloads", neoForgePayloads);
        json.add("neoForgePayloadBridge", neoForge);
        return json.toString();
    }

    @SandboxExposed
    public static String networkCreateClientboundUtf8Envelope(String channelKey, String payload) {
        return Base64.getEncoder().encodeToString(InterMedNetworkBridge.encodeClientbound(
            parseResourceLocation(channelKey),
            normalizeNetworkSourceModId(),
            payload,
            InterMedNetworkBridge.PayloadCodec.utf8String()
        ));
    }

    @SandboxExposed
    public static String networkCreateServerboundUtf8Envelope(String channelKey, String payload) {
        return Base64.getEncoder().encodeToString(InterMedNetworkBridge.encodeServerbound(
            parseResourceLocation(channelKey),
            normalizeNetworkSourceModId(),
            payload,
            InterMedNetworkBridge.PayloadCodec.utf8String()
        ));
    }

    @SandboxExposed
    public static String networkDecodeEnvelopeSummary(String base64Envelope) {
        JsonObject json = new JsonObject();
        try {
            byte[] bytes = Base64.getDecoder().decode(base64Envelope == null ? "" : base64Envelope);
            InterMedNetworkBridge.InterMedPacketEnvelope envelope = InterMedNetworkBridge.deserializeEnvelope(bytes);
            json.addProperty("status", "ok");
            json.addProperty("protocolVersion", envelope.protocolVersion());
            json.addProperty("direction", envelope.direction().name());
            json.addProperty("channelNumericId", Long.toUnsignedString(envelope.channelNumericId()));
            json.addProperty("sourceModId", envelope.sourceModId());
            json.addProperty("logicalChannel", envelope.originalChannelId().toString());
            json.addProperty("payloadBytes", envelope.payloadBytes().length);
        } catch (RuntimeException e) {
            json.addProperty("status", "invalid");
            json.addProperty("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        return json.toString();
    }

    // ── Mod discovery ─────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if a mod with the given id has been loaded by InterMed.
     *
     * @param modId mod id as declared in {@code fabric.mod.json} / {@code mods.toml}
     */
    @SandboxExposed
    public static boolean isModLoaded(String modId) {
        return RuntimeModIndex.isLoaded(modId);
    }

    /**
     * Returns the version string of the loaded mod, or {@code "unknown"} if the
     * mod is not loaded or its manifest omits a version field.
     */
    @SandboxExposed
    public static String modVersion(String modId) {
        return RuntimeModIndex.get(modId)
            .map(m -> m.version() != null ? m.version() : "unknown")
            .orElse("unknown");
    }

    /**
     * Returns an unmodifiable list of all currently loaded mod ids.
     */
    public static Collection<String> loadedModIds() {
        return RuntimeModIndex.allMods().stream()
            .map(m -> m.id())
            .toList();
    }

    // ── Security ──────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if the calling mod has been granted the specified
     * capability.  The caller is resolved from the active {@link SecurityPolicy}
     * by the supplied {@code modId}.
     *
     * <p>In non-strict mode this always returns {@code true}.
     *
     * @param modId      mod id to query
     * @param capability capability name matching {@link Capability} enum constant
     *                   (e.g. {@code "FILE_READ"}, {@code "NETWORK_CONNECT"})
     * @return {@code true} if the capability is granted
     */
    @SandboxExposed
    public static boolean hasCapability(String modId, String capability) {
        if (modId == null || capability == null) {
            return false;
        }
        try {
            Capability cap = Capability.valueOf(capability.toUpperCase(java.util.Locale.ROOT));
            return SecurityPolicy.hasCapabilityGrant(modId, cap);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * Returns the currently active mod id as resolved from the capability
     * security context. This is especially useful inside sandboxes where the
     * guest should not need to hardcode its own mod id.
     */
    @SandboxExposed
    public static String currentModId() {
        return CapabilityManager.currentModIdOr("unknown");
    }

    /**
     * Convenience wrapper around {@link #hasCapability(String, String)} that
     * resolves the caller from the active security context.
     */
    @SandboxExposed
    public static boolean hasCurrentCapability(String capability) {
        String modId = CapabilityManager.currentModId();
        return modId != null && hasCapability(modId, capability);
    }

    /**
     * Returns the effective sandbox mode for the currently executing mod, or
     * {@code "native"} when the caller is not inside a registered sandbox plan.
     */
    @SandboxExposed
    public static String currentSandboxMode() {
        int sharedModeId = SandboxSharedExecutionContext.currentEffectiveModeId();
        if (sharedModeId != 0 || SandboxSharedExecutionContext.currentSharedStateBytes() > 0) {
            return switch (sharedModeId) {
                case 1 -> "espresso";
                case 2 -> "wasm";
                default -> "native";
            };
        }
        String modId = CapabilityManager.currentModId();
        if (modId == null) {
            return "native";
        }
        return PolyglotSandboxManager.getPlan(modId)
            .map(plan -> plan.effectiveMode().externalName())
            .orElse("native");
    }

    /**
     * Returns a stable numeric identifier for the effective sandbox mode.
     * This is a Wasm-friendly alternative to {@link #currentSandboxMode()}.
     *
     * <p>Mapping:
     * <ul>
     *   <li>{@code 0} = native</li>
     *   <li>{@code 1} = espresso</li>
     *   <li>{@code 2} = wasm</li>
     * </ul>
     */
    @SandboxExposed
    public static int currentSandboxModeId() {
        int sharedModeId = SandboxSharedExecutionContext.currentEffectiveModeId();
        if (sharedModeId != 0 || SandboxSharedExecutionContext.currentSharedStateBytes() > 0) {
            return sharedModeId;
        }
        return switch (currentSandboxMode()) {
            case "espresso" -> 1;
            case "wasm" -> 2;
            default -> 0;
        };
    }

    @SandboxExposed
    public static int currentSandboxRequestedModeId() {
        int sharedModeId = SandboxSharedExecutionContext.currentRequestedModeId();
        if (sharedModeId != 0 || SandboxSharedExecutionContext.currentSharedStateBytes() > 0) {
            return sharedModeId;
        }
        String modId = CapabilityManager.currentModId();
        if (modId == null) {
            return 0;
        }
        return PolyglotSandboxManager.getPlan(modId)
            .map(plan -> switch (plan.requestedMode()) {
                case ESPRESSO -> 1;
                case WASM -> 2;
                default -> 0;
            })
            .orElse(0);
    }

    @SandboxExposed
    public static String currentSandboxInvocationKey() {
        return SandboxSharedExecutionContext.currentInvocationKey();
    }

    @SandboxExposed
    public static int currentSandboxInvocationKeyLength() {
        return SandboxSharedExecutionContext.currentInvocationKeyLength();
    }

    @SandboxExposed
    public static String currentSandboxTarget() {
        return SandboxSharedExecutionContext.currentTarget();
    }

    @SandboxExposed
    public static int currentSandboxTargetLength() {
        return SandboxSharedExecutionContext.currentTargetLength();
    }

    @SandboxExposed
    public static String currentSandboxPlanReason() {
        return SandboxSharedExecutionContext.currentPlanReason();
    }

    @SandboxExposed
    public static int currentSandboxSharedStateBytes() {
        return SandboxSharedExecutionContext.currentSharedStateBytes();
    }

    @SandboxExposed
    public static String currentSandboxSharedTransport() {
        return SandboxSharedExecutionContext.currentTransportKind();
    }

    @SandboxExposed
    public static int currentSandboxSharedGraphNodes() {
        return SandboxSharedExecutionContext.currentSharedGraphNodeCount();
    }

    @SandboxExposed
    public static int currentSandboxSharedGraphProperties() {
        return SandboxSharedExecutionContext.currentSharedGraphPropertyCount();
    }

    @SandboxExposed
    public static String currentSandboxSharedGraphRootType() {
        return SandboxSharedExecutionContext.currentSharedGraphRootType();
    }

    @SandboxExposed
    public static String currentSandboxSharedGraphRootName() {
        return SandboxSharedExecutionContext.currentSharedGraphRootName();
    }

    @SandboxExposed
    public static String currentSandboxSharedGraphNodeType(int nodeIndex) {
        return SandboxSharedExecutionContext.currentSharedGraphNodeType(nodeIndex);
    }

    @SandboxExposed
    public static String currentSandboxSharedGraphNodeName(int nodeIndex) {
        return SandboxSharedExecutionContext.currentSharedGraphNodeName(nodeIndex);
    }

    @SandboxExposed
    public static int currentSandboxSharedGraphNodeParentIndex(int nodeIndex) {
        return SandboxSharedExecutionContext.currentSharedGraphNodeParentIndex(nodeIndex);
    }

    @SandboxExposed
    public static int currentSandboxSharedGraphNodeFirstChildIndex(int nodeIndex) {
        return SandboxSharedExecutionContext.currentSharedGraphNodeFirstChildIndex(nodeIndex);
    }

    @SandboxExposed
    public static int currentSandboxSharedGraphNodeChildCount(int nodeIndex) {
        return SandboxSharedExecutionContext.currentSharedGraphNodeChildCount(nodeIndex);
    }

    @SandboxExposed
    public static int currentSandboxSharedGraphNodeFlags(int nodeIndex) {
        return SandboxSharedExecutionContext.currentSharedGraphNodeFlags(nodeIndex);
    }

    @SandboxExposed
    public static double currentSandboxSharedGraphNodeX(int nodeIndex) {
        return SandboxSharedExecutionContext.currentSharedGraphNodeX(nodeIndex);
    }

    @SandboxExposed
    public static double currentSandboxSharedGraphNodeY(int nodeIndex) {
        return SandboxSharedExecutionContext.currentSharedGraphNodeY(nodeIndex);
    }

    @SandboxExposed
    public static double currentSandboxSharedGraphNodeZ(int nodeIndex) {
        return SandboxSharedExecutionContext.currentSharedGraphNodeZ(nodeIndex);
    }

    @SandboxExposed
    public static String currentSandboxSharedGraphNodeProperty(int nodeIndex, String key) {
        return SandboxSharedExecutionContext.currentSharedGraphNodeProperty(nodeIndex, key);
    }

    @SandboxExposed
    public static String sandboxHostContractDigest() {
        return PolyglotSandboxManager.hostContractDigest();
    }

    @SandboxExposed
    public static String sandboxHostContractJson() {
        return WitContractCatalog.toJson().toString();
    }

    @SandboxExposed
    public static String sandboxDiagnosticsJson() {
        return PolyglotSandboxManager.diagnosticsJson();
    }

    @SandboxExposed
    public static boolean isCurrentSandboxHotPath() {
        return SandboxSharedExecutionContext.isCurrentHotPath();
    }

    // ── Performance / observability ───────────────────────────────────────────

    /**
     * Returns the current EWMA-smoothed server TPS (target: 20.0).
     * Values significantly below 20 indicate server lag.
     */
    @SandboxExposed
    public static double currentTps() {
        return PerformanceMonitor.getTps();
    }

    /**
     * Returns the current EWMA tick duration in milliseconds as measured by
     * {@link ObservabilityMonitor} (target: 50 ms ≈ 20 TPS).
     */
    @SandboxExposed
    public static double currentTickMs() {
        return ObservabilityMonitor.getEwmaTickTime();
    }

    /**
     * Returns {@code true} if the given mod is currently inside its throttle
     * penalty window (i.e. it has been identified as a lag source and its event
     * callbacks are being skipped for the next few seconds).
     */
    @SandboxExposed
    public static boolean isModThrottled(String modId) {
        return ObservabilityMonitor.isModThrottled(modId);
    }

    private static String normalizeNetworkSourceModId() {
        String modId = currentModId();
        return modId == null || modId.isBlank() || "unknown".equals(modId) ? null : modId;
    }

    private static ResourceLocation parseResourceLocation(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Resource location key cannot be null");
        }
        String normalized = key.trim();
        int separator = normalized.indexOf(':');
        if (separator <= 0 || separator == normalized.length() - 1) {
            throw new IllegalArgumentException("Invalid resource location: " + key);
        }
        return new ResourceLocation(normalized.substring(0, separator), normalized.substring(separator + 1));
    }
}
