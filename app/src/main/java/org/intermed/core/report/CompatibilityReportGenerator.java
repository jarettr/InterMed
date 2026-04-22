package org.intermed.core.report;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.intermed.core.InterMedVersion;
import org.intermed.core.bridge.ForgeNetworkBridge;
import org.intermed.core.bridge.InterMedNetworkBridge;
import org.intermed.core.bridge.NeoForgeNetworkBridge;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.metadata.ModMetadataParser;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.monitor.RiskyModRegistry;
import org.intermed.core.sandbox.GraalVMSandbox;
import org.intermed.core.sandbox.PolyglotSandboxManager;
import org.intermed.core.sandbox.SandboxPlan;
import org.intermed.core.sandbox.WasmSandbox;
import org.intermed.core.sandbox.WitContractCatalog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Generates a crowdsourcing-friendly compatibility report for discovered mods.
 */
public final class CompatibilityReportGenerator {

    private CompatibilityReportGenerator() {}

    public static void writeReport(Path output, List<File> jars) throws Exception {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(
            output,
            new GsonBuilder().setPrettyPrinting().create().toJson(generate(jars)),
            StandardCharsets.UTF_8
        );
    }

    public static JsonObject generate(List<File> jars) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", "intermed-compat-report-v1");
        root.addProperty("intermedVersion", InterMedVersion.BUILD_VERSION);
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("javaVersion", System.getProperty("java.version", "unknown"));
        root.add("hostContracts", safeReportSection("hostContracts", WitContractCatalog::toJson));
        root.add("runtime", safeReportSection("runtime", CompatibilityReportGenerator::runtimeSummary));

        JsonArray mods = new JsonArray();
        for (File jar : jars) {
            mods.add(reportEntry(jar));
        }
        root.add("mods", mods);
        root.addProperty("count", jars.size());
        return root;
    }

    private static JsonObject safeReportSection(String section, Supplier<JsonObject> generator) {
        try {
            return generator.get();
        } catch (RuntimeException | LinkageError failure) {
            JsonObject unavailable = new JsonObject();
            unavailable.addProperty("status", "unavailable");
            unavailable.addProperty("section", section);
            unavailable.addProperty("errorType", failure.getClass().getName());
            unavailable.addProperty("error", failure.getMessage() == null ? "" : failure.getMessage());
            unavailable.addProperty("diagnosticNote",
                "This compatibility report section failed to render, but diagnostics bundle generation must remain fail-closed and attach the error.");
            return unavailable;
        }
    }

    private static JsonObject runtimeSummary() {
        JsonObject runtime = new JsonObject();
        GraalVMSandbox.HostStatus espressoStatus = GraalVMSandbox.probeAvailability();
        WasmSandbox.HostStatus wasmStatus = WasmSandbox.probeAvailability();
        PolyglotSandboxManager.SandboxRuntimeDiagnostics sandboxDiagnostics = PolyglotSandboxManager.diagnostics();

        JsonObject sandboxes = new JsonObject();
        sandboxes.add("espresso", hostStatusJson(espressoStatus.isReady(), espressoStatus.state()));
        sandboxes.add("wasm", hostStatusJson(wasmStatus.isReady(), wasmStatus.state()));
        sandboxes.addProperty("hostContractDigest", sandboxDiagnostics.hostContractDigest());
        sandboxes.addProperty("hostContractFunctions", sandboxDiagnostics.hostExportCount());
        sandboxes.addProperty("javaBindingsClass", sandboxDiagnostics.javaBindingsClassName());
        InterMedNetworkBridge.NetworkBridgeDiagnostics networkDiagnostics = InterMedNetworkBridge.diagnostics();

        JsonObject network = new JsonObject();
        network.addProperty("protocolVersion", networkDiagnostics.protocolVersion());
        network.addProperty("transportChannel", networkDiagnostics.transportChannel().toString());
        network.addProperty("registeredChannels", networkDiagnostics.registeredChannels());
        network.addProperty("encodedEnvelopes", networkDiagnostics.encodedEnvelopes());
        network.addProperty("decodedEnvelopes", networkDiagnostics.decodedEnvelopes());
        network.addProperty("dispatchedEnvelopes", networkDiagnostics.dispatchedEnvelopes());
        network.addProperty("droppedEnvelopes", networkDiagnostics.droppedEnvelopes());
        network.addProperty("fallbackChannelMatches", networkDiagnostics.fallbackChannelMatches());

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
        network.add("channels", channels);

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
        network.add("forgeSimpleImpl", forge);

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
        network.add("neoForgePayloadBridge", neoForge);

        runtime.add("sandboxes", sandboxes);
        runtime.add("sandboxManager", sandboxDiagnostics.toJson());
        runtime.add("networkBridge", network);
        runtime.addProperty("securityStrictMode", RuntimeConfig.get().isSecurityStrictMode());
        runtime.addProperty("nativeSandboxFallbackEnabled", RuntimeConfig.get().isNativeSandboxFallbackEnabled());
        return runtime;
    }

    private static JsonObject hostStatusJson(boolean ready, String state) {
        JsonObject json = new JsonObject();
        json.addProperty("ready", ready);
        json.addProperty("state", state == null ? "unknown" : state);
        return json;
    }

    private static JsonObject reportEntry(File jar) {
        JsonObject entry = new JsonObject();
        entry.addProperty("file", jar.getName());
        entry.addProperty("sha256", sha256(jar.toPath()));
        try {
            Optional<NormalizedModMetadata> metadata = ModMetadataParser.parse(jar);
            if (metadata.isEmpty()) {
                Optional<DataDrivenArchiveDetector.Artifact> artifact = DataDrivenArchiveDetector.detect(jar);
                if (artifact.isPresent()) {
                    return dataDrivenEntry(entry, artifact.get());
                }
                entry.addProperty("status", "unsupported");
                return entry;
            }

            NormalizedModMetadata mod = metadata.get();
            SandboxPlan plan = PolyglotSandboxManager.planFor(mod);
            entry.addProperty("status", "supported");
            entry.addProperty("id", mod.id());
            entry.addProperty("version", mod.version());
            entry.addProperty("platform", mod.platform().name());
            entry.addProperty("clientResources", mod.hasClientResources());
            entry.addProperty("serverData", mod.hasServerData());

            JsonArray mixins = new JsonArray();
            mod.mixinConfigs().forEach(mixins::add);
            entry.add("mixins", mixins);

            JsonObject entrypoints = new JsonObject();
            JsonArray main = new JsonArray();
            mod.entrypoints("main").forEach(main::add);
            entrypoints.add("main", main);
            JsonArray client = new JsonArray();
            mod.entrypoints("client").forEach(client::add);
            entrypoints.add("client", client);
            entry.add("entrypoints", entrypoints);

            JsonArray reasons = new JsonArray();
            RiskyModRegistry.reasonsForMod(mod.id()).forEach(reasons::add);
            entry.add("riskyReasons", reasons);
            entry.add("sandbox", plan.toJson());
        } catch (Exception e) {
            entry.addProperty("status", "failed");
            entry.addProperty("error", e.getMessage());
        }
        return entry;
    }

    private static JsonObject dataDrivenEntry(JsonObject entry, DataDrivenArchiveDetector.Artifact artifact) {
        entry.addProperty("status", "data-driven");
        entry.addProperty("artifactType", artifact.artifactType());
        entry.addProperty("id", artifact.id());
        entry.addProperty("name", artifact.name());
        entry.addProperty("version", artifact.version());
        entry.addProperty("platform", artifact.platform());
        entry.addProperty("clientResources", artifact.clientResources());
        entry.addProperty("serverData", artifact.serverData());
        entry.add("mixins", new JsonArray());
        entry.add("entrypoints", DataDrivenArchiveDetector.emptyEntrypoints());
        entry.add("riskyReasons", new JsonArray());
        entry.add("sandbox", DataDrivenArchiveDetector.sandbox(artifact));
        return entry;
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
