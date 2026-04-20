package org.intermed.core.bridge;

import net.minecraft.resources.ResourceLocation;
import org.intermed.core.classloading.TcclInterceptor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NeoForge payload compatibility bridge with reflective runtime probing.
 */
public final class NeoForgeNetworkBridge {

    private static final String NEOFORGE_CONTEXT_CLASS = "net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext";
    private static final String NEOFORGE_MOD_LOADING_CONTEXT_CLASS = "net.neoforged.fml.ModLoadingContext";
    private static final String NEOFORGE_PRIORITY_CLASS = "net.neoforged.bus.api.EventPriority";
    private static final String[] REGISTER_EVENT_CLASSES = {
        "net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent",
        "net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent"
    };

    private static final Map<ResourceLocation, PayloadState> PAYLOADS = new ConcurrentHashMap<>();
    private static final AtomicBoolean REGISTRATION_PROBE_RUNNING = new AtomicBoolean(false);
    private static final AtomicLong ENCODED_ENVELOPES = new AtomicLong();
    private static final AtomicLong OBSERVED_REGISTRATION_EVENTS = new AtomicLong();

    private static volatile boolean listenersRegistered = false;
    private static volatile String observedEventClass = "none";
    private static volatile String observedRegistrarStyle = "unobserved";

    private NeoForgeNetworkBridge() {}

    public static void scheduleRegistrationProbe() {
        if (listenersRegistered || !REGISTRATION_PROBE_RUNNING.compareAndSet(false, true)) {
            return;
        }
        Thread probe = TcclInterceptor.contextAwareFactory(r -> {
            Thread t = new Thread(r, "intermed-neoforge-network-probe");
            t.setDaemon(true);
            return t;
        }).newThread(() -> {
            try {
                for (int attempt = 0; attempt < 200 && !listenersRegistered; attempt++) {
                    if (registerIfAvailable()) {
                        return;
                    }
                    Thread.sleep(attempt < 20 ? 25L : 50L);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                if (!listenersRegistered) {
                    REGISTRATION_PROBE_RUNNING.set(false);
                }
            }
        });
        probe.start();
    }

    public static boolean registerIfAvailable() {
        if (listenersRegistered) {
            return true;
        }
        synchronized (NeoForgeNetworkBridge.class) {
            if (listenersRegistered) {
                return true;
            }
            try {
                Object eventBus = resolveModEventBus();
                if (eventBus == null) {
                    return false;
                }
                boolean hooked = false;
                for (String eventClassName : REGISTER_EVENT_CLASSES) {
                    hooked |= registerListener(eventBus, eventClassName, NeoForgeNetworkBridge::onRegisterPayloads);
                }
                if (!hooked) {
                    return false;
                }
                listenersRegistered = true;
                REGISTRATION_PROBE_RUNNING.set(true);
                System.out.println("[NeoForgeNetworkBridge] Runtime payload listeners registered on NeoForge mod bus.");
                return true;
            } catch (ReflectiveOperationException | LinkageError e) {
                return false;
            }
        }
    }

    public static void onRegisterPayloads(Object event) {
        if (event == null) {
            return;
        }
        observedEventClass = event.getClass().getName();
        observedRegistrarStyle = detectRegistrarStyle(event);
        OBSERVED_REGISTRATION_EVENTS.incrementAndGet();
    }

    public static PayloadHandle payload(ResourceLocation payloadId, String protocolVersion, String ownerModId) {
        Objects.requireNonNull(payloadId, "payloadId");
        return new PayloadHandle(PAYLOADS.compute(payloadId, (ignored, existing) -> {
            if (existing == null) {
                return new PayloadState(payloadId, protocolVersion, ownerModId);
            }
            existing.verifyProtocol(protocolVersion);
            existing.touchOwner(ownerModId);
            return existing;
        }));
    }

    public static List<PayloadDescriptor> snapshotPayloads() {
        List<PayloadDescriptor> payloads = new ArrayList<>(PAYLOADS.size());
        PAYLOADS.values().forEach(state -> payloads.add(state.descriptor()));
        payloads.sort(Comparator.comparing(descriptor -> descriptor.payloadId().toString()));
        return List.copyOf(payloads);
    }

    public static NeoForgeNetworkDiagnostics diagnostics() {
        return new NeoForgeNetworkDiagnostics(
            listenersRegistered,
            observedEventClass,
            observedRegistrarStyle,
            OBSERVED_REGISTRATION_EVENTS.get(),
            snapshotPayloads().size(),
            ENCODED_ENVELOPES.get()
        );
    }

    public static void clear() {
        PAYLOADS.clear();
        ENCODED_ENVELOPES.set(0);
        OBSERVED_REGISTRATION_EVENTS.set(0);
        observedEventClass = "none";
        observedRegistrarStyle = "unobserved";
    }

    public static void resetForTests() {
        clear();
        listenersRegistered = false;
        REGISTRATION_PROBE_RUNNING.set(false);
    }

    public interface ClientPayloadHandler<T> {
        void handle(Object client, Object handler, T payload, Object responseSender);
    }

    public interface ServerPayloadHandler<T> {
        void handle(Object server, Object player, Object handler, T payload, Object responseSender);
    }

    public record PayloadDescriptor(ResourceLocation payloadId,
                                    String protocolVersion,
                                    String ownerModId,
                                    boolean clientbound,
                                    boolean serverbound,
                                    String payloadType) {
    }

    public record NeoForgeNetworkDiagnostics(boolean listenersRegistered,
                                             String observedEventClass,
                                             String observedRegistrarStyle,
                                             long observedRegistrationEvents,
                                             int registeredPayloads,
                                             long encodedEnvelopes) {
    }

    public static final class PayloadHandle {
        private final PayloadState state;

        private PayloadHandle(PayloadState state) {
            this.state = state;
        }

        public ResourceLocation payloadId() {
            return state.payloadId;
        }

        public String protocolVersion() {
            return state.protocolVersion;
        }

        public <T> void registerClientbound(InterMedNetworkBridge.PayloadCodec<T> codec,
                                            ClientPayloadHandler<T> handler) {
            state.registerClientbound(codec, handler);
        }

        public <T> void registerServerbound(InterMedNetworkBridge.PayloadCodec<T> codec,
                                            ServerPayloadHandler<T> handler) {
            state.registerServerbound(codec, handler);
        }

        public void registerClientboundUtf8(ClientPayloadHandler<String> handler) {
            registerClientbound(InterMedNetworkBridge.PayloadCodec.utf8String(), handler);
        }

        public void registerServerboundUtf8(ServerPayloadHandler<String> handler) {
            registerServerbound(InterMedNetworkBridge.PayloadCodec.utf8String(), handler);
        }

        public void registerBidirectionalUtf8(ClientPayloadHandler<String> clientHandler,
                                              ServerPayloadHandler<String> serverHandler) {
            registerClientboundUtf8(clientHandler);
            registerServerboundUtf8(serverHandler);
        }

        public <T> byte[] createClientboundFrame(T payload) {
            ENCODED_ENVELOPES.incrementAndGet();
            return state.createClientboundFrame(payload);
        }

        public <T> byte[] createServerboundFrame(T payload) {
            ENCODED_ENVELOPES.incrementAndGet();
            return state.createServerboundFrame(payload);
        }

        public byte[] createClientboundUtf8Frame(String payload) {
            return createClientboundFrame(payload);
        }

        public byte[] createServerboundUtf8Frame(String payload) {
            return createServerboundFrame(payload);
        }
    }

    private static final class PayloadState {
        private final ResourceLocation payloadId;
        private final String protocolVersion;
        private volatile String ownerModId;
        private volatile String payloadType = "unbound";
        private volatile InterMedNetworkBridge.PayloadCodec<Object> clientCodec;
        private volatile InterMedNetworkBridge.PayloadCodec<Object> serverCodec;

        private PayloadState(ResourceLocation payloadId, String protocolVersion, String ownerModId) {
            this.payloadId = payloadId;
            this.protocolVersion = protocolVersion == null || protocolVersion.isBlank() ? "1" : protocolVersion;
            this.ownerModId = ownerModId == null || ownerModId.isBlank() ? payloadId.getNamespace() : ownerModId;
        }

        private void verifyProtocol(String candidateProtocol) {
            String normalized = candidateProtocol == null || candidateProtocol.isBlank() ? "1" : candidateProtocol;
            if (!Objects.equals(protocolVersion, normalized)) {
                throw new IllegalStateException("NeoForge payload " + payloadId + " already uses protocol "
                    + protocolVersion + ", cannot redefine as " + normalized);
            }
        }

        private void touchOwner(String ownerModId) {
            if (ownerModId != null && !ownerModId.isBlank()) {
                this.ownerModId = ownerModId;
            }
        }

        private <T> void registerClientbound(InterMedNetworkBridge.PayloadCodec<T> codec,
                                             ClientPayloadHandler<T> handler) {
            Objects.requireNonNull(codec, "codec");
            Objects.requireNonNull(handler, "handler");
            payloadType = payloadType(codec);
            clientCodec = castCodec(codec);
            InterMedNetworkBridge.registerTypedClientReceiver(payloadId, ownerModId, codec, handler::handle);
        }

        private <T> void registerServerbound(InterMedNetworkBridge.PayloadCodec<T> codec,
                                             ServerPayloadHandler<T> handler) {
            Objects.requireNonNull(codec, "codec");
            Objects.requireNonNull(handler, "handler");
            payloadType = payloadType(codec);
            serverCodec = castCodec(codec);
            InterMedNetworkBridge.registerTypedServerReceiver(payloadId, ownerModId, codec, handler::handle);
        }

        @SuppressWarnings("unchecked")
        private <T> byte[] createClientboundFrame(T payload) {
            if (clientCodec == null) {
                throw new IllegalStateException("No clientbound NeoForge payload registered for " + payloadId);
            }
            return InterMedNetworkBridge.encodeClientbound(payloadId, ownerModId, payload, (InterMedNetworkBridge.PayloadCodec<T>) clientCodec);
        }

        @SuppressWarnings("unchecked")
        private <T> byte[] createServerboundFrame(T payload) {
            if (serverCodec == null) {
                throw new IllegalStateException("No serverbound NeoForge payload registered for " + payloadId);
            }
            return InterMedNetworkBridge.encodeServerbound(payloadId, ownerModId, payload, (InterMedNetworkBridge.PayloadCodec<T>) serverCodec);
        }

        private PayloadDescriptor descriptor() {
            return new PayloadDescriptor(payloadId, protocolVersion, ownerModId, clientCodec != null, serverCodec != null, payloadType);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> InterMedNetworkBridge.PayloadCodec<Object> castCodec(InterMedNetworkBridge.PayloadCodec<T> codec) {
        return (InterMedNetworkBridge.PayloadCodec<Object>) codec;
    }

    private static String detectRegistrarStyle(Object event) {
        for (Method method : event.getClass().getMethods()) {
            if (method.getName().equals("registrar")
                && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == String.class) {
                return "string-registrar";
            }
        }
        return "opaque";
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean registerListener(Object eventBus,
                                            String eventClassName,
                                            java.util.function.Consumer<Object> listener) throws ReflectiveOperationException {
        Class<?> eventType;
        try {
            eventType = loadClass(eventClassName);
        } catch (ClassNotFoundException ignored) {
            return false;
        }

        Class<?> priorityType = loadClass(NEOFORGE_PRIORITY_CLASS);
        Object normalPriority = Enum.valueOf((Class<? extends Enum>) priorityType.asSubclass(Enum.class), "NORMAL");
        Method addListener = eventBus.getClass().getMethod(
            "addListener",
            priorityType,
            boolean.class,
            Class.class,
            java.util.function.Consumer.class
        );
        addListener.invoke(eventBus, normalPriority, false, eventType, listener);
        return true;
    }

    private static Object resolveModEventBus() throws ReflectiveOperationException {
        Object javaFmlContext = resolveStaticContext(NEOFORGE_CONTEXT_CLASS, "get");
        Object eventBus = invokeNoArg(javaFmlContext, "getModEventBus");
        if (eventBus != null) {
            return eventBus;
        }

        Object modLoadingContext = resolveStaticContext(NEOFORGE_MOD_LOADING_CONTEXT_CLASS, "get");
        Object activeContainer = invokeNoArg(modLoadingContext, "getActiveContainer");
        return invokeNoArg(activeContainer, "getEventBus");
    }

    private static Object resolveStaticContext(String className, String factoryMethod) throws ReflectiveOperationException {
        try {
            Class<?> type = loadClass(className);
            Method factory = type.getMethod(factoryMethod);
            return factory.invoke(null);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Class<?> loadClass(String className) throws ClassNotFoundException {
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            try {
                return Class.forName(className, false, contextLoader);
            } catch (ClassNotFoundException ignored) {
                // Fall back to the InterMed class loader.
            }
        }
        return Class.forName(className, false, NeoForgeNetworkBridge.class.getClassLoader());
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
