package org.intermed.core.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.intermed.core.InterMedVersion;
import org.objectweb.asm.Type;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Produces a machine-readable API gap matrix for the frozen alpha bridge scope.
 *
 * <p>The catalog is intentionally curated and small: it covers high-signal
 * loader/API bridge symbols that external alpha reports are likely to hit. It is
 * not a parity claim for full Fabric API, Forge, or NeoForge.</p>
 */
public final class ApiGapMatrixGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ApiGapMatrixGenerator() {}

    public static void writeReport(Path output) throws Exception {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, GSON.toJson(generate()), StandardCharsets.UTF_8);
    }

    public static JsonObject generate() {
        return generate(ApiGapMatrixGenerator.class.getClassLoader());
    }

    static JsonObject generate(ClassLoader loader) {
        List<ApiSymbolExpectation> catalog = catalog();
        List<ApiSymbolResult> results = catalog.stream()
            .map(expectation -> inspect(expectation, loader))
            .sorted(Comparator
                .comparing((ApiSymbolResult result) -> result.expectation().ecosystem())
                .thenComparing(result -> result.expectation().area())
                .thenComparing(result -> result.expectation().owner())
                .thenComparing(result -> result.expectation().memberName() == null ? "" : result.expectation().memberName())
                .thenComparing(result -> result.expectation().descriptor() == null ? "" : result.expectation().descriptor()))
            .toList();

        JsonObject root = new JsonObject();
        root.addProperty("schema", "intermed-api-gap-matrix-v1");
        root.addProperty("intermedVersion", InterMedVersion.BUILD_VERSION);
        root.addProperty("generatedAt", Instant.now().toString());
        root.add("scope", scope());
        root.add("summary", summary(results));
        root.add("symbols", symbols(results));
        root.add("missing", missing(results));
        return root;
    }

    private static JsonObject scope() {
        JsonObject scope = new JsonObject();
        scope.addProperty("minecraft", "1.20.1");
        JsonArray ecosystems = new JsonArray();
        ecosystems.add("Fabric");
        ecosystems.add("Forge");
        ecosystems.add("NeoForge");
        scope.add("ecosystems", ecosystems);
        scope.addProperty("claim",
            "Curated alpha bridge surface only; this is not full API parity.");
        return scope;
    }

    private static JsonObject summary(List<ApiSymbolResult> results) {
        JsonObject summary = new JsonObject();
        summary.addProperty("total", results.size());
        summary.addProperty("present", results.stream().filter(ApiSymbolResult::present).count());
        summary.addProperty("missing", results.stream().filter(result -> !result.present()).count());
        summary.add("byEcosystem", groupedSummary(results, ApiSymbolExpectation::ecosystem));
        summary.add("byStage", groupedSummary(results, ApiSymbolExpectation::stage));
        summary.add("byPriority", groupedSummary(results, ApiSymbolExpectation::priority));
        return summary;
    }

    private static JsonObject groupedSummary(List<ApiSymbolResult> results,
                                             java.util.function.Function<ApiSymbolExpectation, String> classifier) {
        Map<String, GroupCount> counts = new LinkedHashMap<>();
        for (ApiSymbolResult result : results) {
            String key = classifier.apply(result.expectation());
            counts.computeIfAbsent(key, ignored -> new GroupCount()).add(result.present());
        }
        JsonObject json = new JsonObject();
        counts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                JsonObject item = new JsonObject();
                item.addProperty("total", entry.getValue().total);
                item.addProperty("present", entry.getValue().present);
                item.addProperty("missing", entry.getValue().missing);
                json.add(entry.getKey(), item);
            });
        return json;
    }

    private static JsonArray symbols(List<ApiSymbolResult> results) {
        JsonArray array = new JsonArray();
        for (ApiSymbolResult result : results) {
            array.add(result.toJson());
        }
        return array;
    }

    private static JsonArray missing(List<ApiSymbolResult> results) {
        JsonArray array = new JsonArray();
        results.stream()
            .filter(result -> !result.present())
            .forEach(result -> array.add(result.toJson()));
        return array;
    }

    private static ApiSymbolResult inspect(ApiSymbolExpectation expectation, ClassLoader loader) {
        try {
            Class<?> owner = loadClass(expectation.owner(), loader);
            return switch (expectation.kind()) {
                case "class" -> ApiSymbolResult.present(expectation);
                case "method" -> inspectMethod(expectation, owner, loader);
                case "field" -> inspectField(expectation, owner);
                default -> ApiSymbolResult.missing(expectation, "unknown symbol kind: " + expectation.kind());
            };
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return ApiSymbolResult.missing(expectation, "class not found: " + expectation.owner());
        } catch (LinkageError e) {
            return ApiSymbolResult.missing(expectation, "linkage error: " + e.getClass().getSimpleName());
        }
    }

    private static ApiSymbolResult inspectMethod(ApiSymbolExpectation expectation,
                                                 Class<?> owner,
                                                 ClassLoader loader) {
        if (expectation.memberName() == null || expectation.descriptor() == null) {
            return ApiSymbolResult.missing(expectation, "method expectation missing name or descriptor");
        }
        try {
            Class<?>[] parameters = parameterTypes(expectation.descriptor(), loader);
            Method method = owner.getMethod(expectation.memberName(), parameters);
            String actualDescriptor = Type.getMethodDescriptor(method);
            if (!Objects.equals(expectation.descriptor(), actualDescriptor)) {
                return ApiSymbolResult.missing(expectation,
                    "descriptor mismatch: found " + actualDescriptor);
            }
            return ApiSymbolResult.present(expectation);
        } catch (ReflectiveOperationException | LinkageError e) {
            return ApiSymbolResult.missing(expectation, "method not found: "
                + expectation.memberName() + expectation.descriptor());
        }
    }

    private static ApiSymbolResult inspectField(ApiSymbolExpectation expectation, Class<?> owner) {
        if (expectation.memberName() == null) {
            return ApiSymbolResult.missing(expectation, "field expectation missing name");
        }
        try {
            Field field = owner.getField(expectation.memberName());
            if (expectation.descriptor() != null
                    && !Objects.equals(expectation.descriptor(), Type.getDescriptor(field.getType()))) {
                return ApiSymbolResult.missing(expectation,
                    "descriptor mismatch: found " + Type.getDescriptor(field.getType()));
            }
            return ApiSymbolResult.present(expectation);
        } catch (ReflectiveOperationException | LinkageError e) {
            return ApiSymbolResult.missing(expectation, "field not found: " + expectation.memberName());
        }
    }

    private static Class<?>[] parameterTypes(String methodDescriptor, ClassLoader loader) throws ClassNotFoundException {
        Type[] arguments = Type.getArgumentTypes(methodDescriptor);
        Class<?>[] types = new Class<?>[arguments.length];
        for (int index = 0; index < arguments.length; index++) {
            types[index] = classFor(arguments[index], loader);
        }
        return types;
    }

    private static Class<?> classFor(Type type, ClassLoader loader) throws ClassNotFoundException {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> boolean.class;
            case Type.BYTE -> byte.class;
            case Type.CHAR -> char.class;
            case Type.SHORT -> short.class;
            case Type.INT -> int.class;
            case Type.FLOAT -> float.class;
            case Type.LONG -> long.class;
            case Type.DOUBLE -> double.class;
            case Type.VOID -> void.class;
            case Type.ARRAY -> Class.forName(type.getClassName(), false, loader);
            case Type.OBJECT -> loadClass(type.getClassName(), loader);
            default -> throw new ClassNotFoundException("Unsupported descriptor type: " + type);
        };
    }

    private static Class<?> loadClass(String className, ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(className, false, loader);
    }

    private static List<ApiSymbolExpectation> catalog() {
        List<ApiSymbolExpectation> symbols = new ArrayList<>();

        fabricLoader(symbols);
        fabricEventsAndNetworking(symbols);
        forgeBridge(symbols);
        neoForgeBridge(symbols);
        knownBetaGaps(symbols);

        return List.copyOf(symbols);
    }

    private static void fabricLoader(List<ApiSymbolExpectation> symbols) {
        symbols.add(cls("Fabric", "loader", "alpha", "P0", "net.fabricmc.api.ModInitializer"));
        symbols.add(method("Fabric", "loader", "alpha", "P0", "net.fabricmc.api.ModInitializer", "onInitialize", "()V"));
        symbols.add(cls("Fabric", "loader", "alpha", "P0", "net.fabricmc.api.ClientModInitializer"));
        symbols.add(method("Fabric", "loader", "alpha", "P0", "net.fabricmc.api.ClientModInitializer", "onInitializeClient", "()V"));
        symbols.add(cls("Fabric", "loader", "alpha", "P0", "net.fabricmc.api.DedicatedServerModInitializer"));
        symbols.add(method("Fabric", "loader", "alpha", "P0", "net.fabricmc.api.DedicatedServerModInitializer", "onInitializeServer", "()V"));
        symbols.add(cls("Fabric", "loader", "alpha", "P0", "net.fabricmc.api.EnvType"));
        symbols.add(cls("Fabric", "loader", "alpha", "P1", "net.fabricmc.api.Environment"));
        symbols.add(method("Fabric", "loader", "alpha", "P1", "net.fabricmc.api.Environment", "value", "()Lnet/fabricmc/api/EnvType;"));

        symbols.add(cls("Fabric", "loader", "alpha", "P0", "net.fabricmc.loader.api.FabricLoader"));
        symbols.add(method("Fabric", "loader", "alpha", "P0", "net.fabricmc.loader.api.FabricLoader", "getInstance", "()Lnet/fabricmc/loader/api/FabricLoader;"));
        symbols.add(method("Fabric", "loader", "alpha", "P0", "net.fabricmc.loader.api.FabricLoader", "isModLoaded", "(Ljava/lang/String;)Z"));
        symbols.add(method("Fabric", "loader", "alpha", "P0", "net.fabricmc.loader.api.FabricLoader", "getModContainer", "(Ljava/lang/String;)Ljava/util/Optional;"));
        symbols.add(method("Fabric", "loader", "alpha", "P1", "net.fabricmc.loader.api.FabricLoader", "getEntrypoints", "(Ljava/lang/String;Ljava/lang/Class;)Ljava/util/List;"));
        symbols.add(method("Fabric", "loader", "alpha", "P1", "net.fabricmc.loader.api.FabricLoader", "getEntrypointContainers", "(Ljava/lang/String;Ljava/lang/Class;)Ljava/util/List;"));
        symbols.add(method("Fabric", "loader", "alpha", "P1", "net.fabricmc.loader.api.FabricLoader", "getConfigDir", "()Ljava/nio/file/Path;"));
        symbols.add(method("Fabric", "loader", "alpha", "P1", "net.fabricmc.loader.api.FabricLoader", "getGameDir", "()Ljava/nio/file/Path;"));
        symbols.add(method("Fabric", "loader", "alpha", "P1", "net.fabricmc.loader.api.FabricLoader", "getEnvironmentType", "()Lnet/fabricmc/api/EnvType;"));
        symbols.add(method("Fabric", "loader", "alpha", "P2", "net.fabricmc.loader.api.FabricLoader", "isDevelopmentEnvironment", "()Z"));

        symbols.add(cls("Fabric", "loader", "alpha", "P0", "net.fabricmc.loader.api.ModContainer"));
        symbols.add(method("Fabric", "loader", "alpha", "P0", "net.fabricmc.loader.api.ModContainer", "getMetadata", "()Lnet/fabricmc/loader/api/metadata/ModMetadata;"));
        symbols.add(method("Fabric", "loader", "alpha", "P1", "net.fabricmc.loader.api.ModContainer", "findPath", "(Ljava/lang/String;)Ljava/util/Optional;"));
        symbols.add(method("Fabric", "loader", "alpha", "P1", "net.fabricmc.loader.api.ModContainer", "getRootPaths", "()Ljava/util/List;"));
        symbols.add(cls("Fabric", "loader", "alpha", "P0", "net.fabricmc.loader.api.metadata.ModMetadata"));
        symbols.add(method("Fabric", "loader", "alpha", "P0", "net.fabricmc.loader.api.metadata.ModMetadata", "getId", "()Ljava/lang/String;"));
        symbols.add(method("Fabric", "loader", "alpha", "P1", "net.fabricmc.loader.api.metadata.ModMetadata", "getVersion", "()Ljava/lang/String;"));
        symbols.add(method("Fabric", "loader", "alpha", "P1", "net.fabricmc.loader.api.metadata.ModMetadata", "getName", "()Ljava/lang/String;"));
        symbols.add(cls("Fabric", "loader", "alpha", "P1", "net.fabricmc.loader.api.entrypoint.EntrypointContainer"));
        symbols.add(method("Fabric", "loader", "alpha", "P1", "net.fabricmc.loader.api.entrypoint.EntrypointContainer", "getEntrypoint", "()Ljava/lang/Object;"));
        symbols.add(method("Fabric", "loader", "alpha", "P1", "net.fabricmc.loader.api.entrypoint.EntrypointContainer", "getProvider", "()Lnet/fabricmc/loader/api/ModContainer;"));
        symbols.add(method("Fabric", "loader", "alpha", "P2", "net.fabricmc.loader.api.entrypoint.EntrypointContainer", "getDefinition", "()Ljava/lang/String;"));
    }

    private static void fabricEventsAndNetworking(List<ApiSymbolExpectation> symbols) {
        symbols.add(cls("Fabric", "event", "alpha", "P0", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents"));
        symbols.add(field("Fabric", "event", "alpha", "P0", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents", "SERVER_STARTING", "Lorg/intermed/core/monitor/LockFreeEvent;"));
        symbols.add(field("Fabric", "event", "alpha", "P0", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents", "SERVER_STARTED", "Lorg/intermed/core/monitor/LockFreeEvent;"));
        symbols.add(field("Fabric", "event", "alpha", "P1", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents", "SERVER_STOPPING", "Lorg/intermed/core/monitor/LockFreeEvent;"));
        symbols.add(field("Fabric", "event", "alpha", "P1", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents", "SERVER_STOPPED", "Lorg/intermed/core/monitor/LockFreeEvent;"));
        symbols.add(cls("Fabric", "event", "alpha", "P0", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents"));
        symbols.add(field("Fabric", "event", "alpha", "P0", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents", "START_SERVER_TICK", "Lorg/intermed/core/monitor/LockFreeEvent;"));
        symbols.add(field("Fabric", "event", "alpha", "P0", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents", "END_SERVER_TICK", "Lorg/intermed/core/monitor/LockFreeEvent;"));
        symbols.add(cls("Fabric", "event", "alpha", "P1", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents"));
        symbols.add(field("Fabric", "event", "alpha", "P1", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents", "LOAD", "Lorg/intermed/core/monitor/LockFreeEvent;"));
        symbols.add(field("Fabric", "event", "alpha", "P1", "net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents", "UNLOAD", "Lorg/intermed/core/monitor/LockFreeEvent;"));

        symbols.add(cls("Fabric", "network", "alpha", "P0", "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking"));
        symbols.add(method("Fabric", "network", "alpha", "P0", "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking", "registerGlobalReceiver", "(Lnet/minecraft/resources/ResourceLocation;Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$PlayChannelHandler;)Z"));
        symbols.add(method("Fabric", "network", "alpha", "P1", "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking", "registerUtf8Receiver", "(Lnet/minecraft/resources/ResourceLocation;Lnet/fabricmc/fabric/api/networking/v1/ServerPlayNetworking$Utf8PlayChannelHandler;)Z"));
        symbols.add(method("Fabric", "network", "alpha", "P1", "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking", "transportChannel", "()Lnet/minecraft/resources/ResourceLocation;"));
        symbols.add(method("Fabric", "network", "alpha", "P1", "net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking", "snapshotChannels", "()Ljava/util/List;"));
        symbols.add(cls("Fabric", "network", "alpha", "P1", "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking"));
        symbols.add(method("Fabric", "network", "alpha", "P1", "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking", "registerGlobalReceiver", "(Lnet/minecraft/resources/ResourceLocation;Lnet/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking$PlayChannelHandler;)Z"));
        symbols.add(method("Fabric", "network", "alpha", "P1", "net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking", "transportChannel", "()Lnet/minecraft/resources/ResourceLocation;"));

        symbols.add(cls("Fabric", "resources", "alpha", "P1", "net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener"));
        symbols.add(method("Fabric", "resources", "alpha", "P1", "net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener", "getFabricId", "()Lnet/minecraft/resources/ResourceLocation;"));
        symbols.add(method("Fabric", "resources", "alpha", "P2", "net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener", "getFabricDependencies", "()Ljava/util/Collection;"));
        symbols.add(cls("Fabric", "client-render", "alpha", "P2", "net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback"));
        symbols.add(field("Fabric", "client-render", "alpha", "P2", "net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback", "LISTENERS", "Ljava/util/List;"));
        symbols.add(method("Fabric", "client-render", "alpha", "P2", "net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback", "onHudRender", "(Ljava/lang/Object;F)V"));
    }

    private static void forgeBridge(List<ApiSymbolExpectation> symbols) {
        symbols.add(cls("Forge", "host-api", "alpha", "P0", "net.minecraftforge.eventbus.api.SubscribeEvent"));
        symbols.add(cls("Forge", "host-api", "alpha", "P0", "net.minecraftforge.fml.common.Mod"));
        symbols.add(cls("Forge", "registry", "alpha", "P0", "net.minecraftforge.registries.RegisterEvent"));
        symbols.add(cls("Forge", "registry", "alpha", "P0", "net.minecraftforge.registries.ForgeRegistries"));
        symbols.add(field("Forge", "registry", "alpha", "P0", "net.minecraftforge.registries.ForgeRegistries", "BLOCKS", null));
        symbols.add(field("Forge", "registry", "alpha", "P0", "net.minecraftforge.registries.ForgeRegistries", "ITEMS", null));
        symbols.add(field("Forge", "registry", "alpha", "P1", "net.minecraftforge.registries.ForgeRegistries", "ENTITY_TYPES", null));
        symbols.add(field("Forge", "registry", "alpha", "P1", "net.minecraftforge.registries.ForgeRegistries", "SOUND_EVENTS", null));
        symbols.add(cls("Forge", "resources", "alpha", "P1", "net.minecraftforge.event.AddPackFindersEvent"));

        symbols.add(cls("Forge", "bridge", "alpha", "P0", "org.intermed.core.bridge.ForgeEventBridge"));
        symbols.add(method("Forge", "bridge", "alpha", "P0", "org.intermed.core.bridge.ForgeEventBridge", "onRegister", "(Lnet/minecraftforge/registries/RegisterEvent;)V"));
        symbols.add(method("Forge", "bridge", "alpha", "P1", "org.intermed.core.bridge.ForgeEventBridge", "onPackFinder", "(Lnet/minecraftforge/event/AddPackFindersEvent;)V"));
        symbols.add(cls("Forge", "network", "alpha", "P1", "org.intermed.core.bridge.ForgeNetworkBridge"));
        symbols.add(method("Forge", "network", "alpha", "P1", "org.intermed.core.bridge.ForgeNetworkBridge", "channel", "(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;)Lorg/intermed/core/bridge/ForgeNetworkBridge$SimpleChannelHandle;"));
        symbols.add(method("Forge", "network", "alpha", "P1", "org.intermed.core.bridge.ForgeNetworkBridge", "diagnostics", "()Lorg/intermed/core/bridge/ForgeNetworkBridge$ForgeNetworkDiagnostics;"));
    }

    private static void neoForgeBridge(List<ApiSymbolExpectation> symbols) {
        symbols.add(cls("NeoForge", "host-api", "alpha", "P0", "net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext"));
        symbols.add(cls("NeoForge", "host-api", "alpha", "P1", "net.neoforged.bus.api.EventPriority"));
        symbols.add(cls("NeoForge", "registry", "alpha", "P0", "net.neoforged.neoforge.registries.RegisterEvent"));
        symbols.add(cls("NeoForge", "registry", "alpha", "P0", "net.neoforged.neoforge.registries.ForgeRegistries"));
        symbols.add(cls("NeoForge", "registry", "alpha", "P1", "net.neoforged.neoforge.registries.NeoForgeRegistries"));
        symbols.add(cls("NeoForge", "resources", "alpha", "P1", "net.neoforged.neoforge.event.AddPackFindersEvent"));
        symbols.add(cls("NeoForge", "network", "alpha", "P1", "net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent"));
        symbols.add(cls("NeoForge", "network", "alpha", "P1", "net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent"));

        symbols.add(cls("NeoForge", "bridge", "alpha", "P0", "org.intermed.core.bridge.NeoForgeEventBridge"));
        symbols.add(method("NeoForge", "bridge", "alpha", "P0", "org.intermed.core.bridge.NeoForgeEventBridge", "registerIfAvailable", "()Z"));
        symbols.add(method("NeoForge", "bridge", "alpha", "P0", "org.intermed.core.bridge.NeoForgeEventBridge", "onRegister", "(Ljava/lang/Object;)V"));
        symbols.add(method("NeoForge", "bridge", "alpha", "P1", "org.intermed.core.bridge.NeoForgeEventBridge", "onAddPackFinders", "(Ljava/lang/Object;)V"));
        symbols.add(cls("NeoForge", "network", "alpha", "P1", "org.intermed.core.bridge.NeoForgeNetworkBridge"));
        symbols.add(method("NeoForge", "network", "alpha", "P1", "org.intermed.core.bridge.NeoForgeNetworkBridge", "registerIfAvailable", "()Z"));
        symbols.add(method("NeoForge", "network", "alpha", "P1", "org.intermed.core.bridge.NeoForgeNetworkBridge", "payload", "(Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;Ljava/lang/String;)Lorg/intermed/core/bridge/NeoForgeNetworkBridge$PayloadHandle;"));
        symbols.add(method("NeoForge", "network", "alpha", "P1", "org.intermed.core.bridge.NeoForgeNetworkBridge", "diagnostics", "()Lorg/intermed/core/bridge/NeoForgeNetworkBridge$NeoForgeNetworkDiagnostics;"));
    }

    private static void knownBetaGaps(List<ApiSymbolExpectation> symbols) {
        symbols.add(cls("Fabric", "command", "beta", "P1", "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback"));
        symbols.add(cls("Fabric", "block-builder", "beta", "P1", "net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings"));
        symbols.add(cls("Fabric", "player-event", "beta", "P2", "net.fabricmc.fabric.api.event.player.UseBlockCallback"));
        symbols.add(cls("Forge", "capabilities", "beta", "P1", "net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent"));
        symbols.add(cls("NeoForge", "capabilities", "beta", "P1", "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent"));
    }

    private static ApiSymbolExpectation cls(String ecosystem, String area, String stage, String priority, String owner) {
        return new ApiSymbolExpectation(ecosystem, area, stage, priority, "class", owner, null, null);
    }

    private static ApiSymbolExpectation method(String ecosystem,
                                               String area,
                                               String stage,
                                               String priority,
                                               String owner,
                                               String memberName,
                                               String descriptor) {
        return new ApiSymbolExpectation(ecosystem, area, stage, priority, "method", owner, memberName, descriptor);
    }

    private static ApiSymbolExpectation field(String ecosystem,
                                              String area,
                                              String stage,
                                              String priority,
                                              String owner,
                                              String memberName,
                                              String descriptor) {
        return new ApiSymbolExpectation(ecosystem, area, stage, priority, "field", owner, memberName, descriptor);
    }

    private record ApiSymbolExpectation(String ecosystem,
                                        String area,
                                        String stage,
                                        String priority,
                                        String kind,
                                        String owner,
                                        String memberName,
                                        String descriptor) {
        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("ecosystem", ecosystem);
            json.addProperty("area", area);
            json.addProperty("stage", stage);
            json.addProperty("priority", priority);
            json.addProperty("kind", kind);
            json.addProperty("owner", owner);
            if (memberName != null) {
                json.addProperty("member", memberName);
            }
            if (descriptor != null) {
                json.addProperty("descriptor", descriptor);
            }
            return json;
        }
    }

    private record ApiSymbolResult(ApiSymbolExpectation expectation, boolean present, String reason) {
        private static ApiSymbolResult present(ApiSymbolExpectation expectation) {
            return new ApiSymbolResult(expectation, true, "present");
        }

        private static ApiSymbolResult missing(ApiSymbolExpectation expectation, String reason) {
            return new ApiSymbolResult(expectation, false, reason);
        }

        private JsonObject toJson() {
            JsonObject json = expectation.toJson();
            json.addProperty("status", present ? "present" : "missing");
            json.addProperty("reason", reason);
            json.addProperty("id", id());
            return json;
        }

        private String id() {
            StringBuilder builder = new StringBuilder(expectation.ecosystem())
                .append(':')
                .append(expectation.owner());
            if (expectation.memberName() != null) {
                builder.append('#').append(expectation.memberName());
            }
            if (expectation.descriptor() != null) {
                builder.append(expectation.descriptor());
            }
            return builder.toString();
        }
    }

    private static final class GroupCount {
        private long total;
        private long present;
        private long missing;

        private void add(boolean isPresent) {
            total++;
            if (isPresent) {
                present++;
            } else {
                missing++;
            }
        }
    }
}
