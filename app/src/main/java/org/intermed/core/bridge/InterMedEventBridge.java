package org.intermed.core.bridge;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.intermed.core.InterMedKernel;
import org.intermed.core.classloading.TcclInterceptor;
import org.intermed.core.event.MainThreadAffinityDispatcher;
import org.intermed.core.lifecycle.LifecycleManager;
import org.intermed.core.monitor.ObservabilityMonitor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Central Forge → Fabric event translation bridge.
 *
 * <p>Registered on the Forge <em>game</em> bus (not mod bus) so it receives
 * runtime server/world/player/tick events from all phases.
 *
 * <h3>Why purely reflective registration</h3>
 * <p>InterMed runs as a javaagent whose fat JAR is on the <em>system</em>
 * classloader (JVM {@code -javaagent:} classpath).  Architectury Loom puts
 * Forge's own JARs on that same runtime classpath, so the system classloader
 * finds and loads Forge event classes <em>before</em> Forge's
 * {@code EventSubclassTransformer} can inject the {@code LISTENER_LIST} field
 * into them.  When {@code EventBus.addListener()} later calls
 * {@code MethodHandles.privateLookupIn(eventClass).findStaticGetter(...,
 * "LISTENER_LIST", ...)} on the un-transformed class it gets a
 * {@code NoSuchFieldException}, wrapped as
 * {@code "Error computing listener list for …"}.
 *
 * <p>The fix: at registration time, load every Forge event class explicitly
 * through {@code Thread.currentThread().getContextClassLoader()} — which, at
 * the TitleScreen trigger point, is Forge's {@code ModuleClassLoader}.  That
 * loader <em>has</em> already applied {@code EventSubclassTransformer}, so its
 * class objects carry a valid {@code LISTENER_LIST}.  Listeners are then
 * registered with the explicit
 * {@code addListener(EventPriority, boolean, Class<T>, Consumer<T>)} overload
 * (called reflectively to avoid static references to Forge classes that would
 * be resolved by the wrong classloader).  Handler methods accept {@code Object}
 * so no class-identity cast is needed when the event fires.
 *
 * <h3>Event coverage</h3>
 * <ul>
 *   <li><b>Tick</b> — {@code TickEvent.ServerTickEvent} →
 *       {@link ServerTickEvents#START_SERVER_TICK} /
 *       {@link ServerTickEvents#END_SERVER_TICK}</li>
 *   <li><b>Server lifecycle</b> — Starting/Started/Stopping/Stopped →
 *       {@link ServerLifecycleEvents}</li>
 *   <li><b>World</b> — {@code LevelEvent.Load} / {@code LevelEvent.Unload} →
 *       {@link ServerWorldEvents}</li>
 *   <li><b>Player</b> — {@code PlayerEvent.PlayerLoggedInEvent} /
 *       {@code PlayerEvent.PlayerLoggedOutEvent} →
 *       {@link ServerPlayConnectionEvents}</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * All Forge server-side events are dispatched on the server tick thread.
 * The {@code initialized} flag and the Fabric invoker calls are therefore
 * effectively single-threaded and do not need additional locking.
 */
public class InterMedEventBridge {

    private static volatile boolean initialized = false;
    private static final AtomicBoolean REGISTRATION_PROBE_RUNNING = new AtomicBoolean(false);

    /**
     * Registers this bridge on the Forge game event bus. Idempotent.
     * Must be called when Forge's {@code ModuleClassLoader} is already the
     * context classloader (i.e. from the TitleScreen class-load trigger).
     */
    public static void initialize() {
        if (registerIfAvailable()) {
            return;
        }
        System.err.println("[InterMedEventBridge] Forge game event bus is not ready yet.");
    }

    public static void scheduleRegistrationProbe() {
        if (initialized || !REGISTRATION_PROBE_RUNNING.compareAndSet(false, true)) {
            return;
        }

        Thread probe = TcclInterceptor.contextAwareFactory().newThread(() -> {
            try {
                for (int attempt = 0; attempt < 200 && !initialized; attempt++) {
                    if (registerIfAvailable()) {
                        return;
                    }
                    Thread.sleep(attempt < 20 ? 25L : 50L);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                if (!initialized) {
                    REGISTRATION_PROBE_RUNNING.set(false);
                }
            }
        });
        probe.setName("intermed-forge-event-bridge-probe");
        probe.setDaemon(true);
        probe.start();
    }

    public static void resetForTests() {
        initialized = false;
        REGISTRATION_PROBE_RUNNING.set(false);
        ServerTickEvents.resetForTests();
        ServerPlayConnectionEvents.resetForTests();
        ServerLifecycleEvents.resetForTests();
        ServerWorldEvents.resetForTests();
    }

    private static boolean registerIfAvailable() {
        if (initialized) {
            return true;
        }

        synchronized (InterMedEventBridge.class) {
            if (initialized) {
                return true;
            }

            ClassLoader forgeCl = Thread.currentThread().getContextClassLoader();
            if (forgeCl == null) {
                return false;
            }

            InterMedEventBridge bridge = new InterMedEventBridge();
            if (!registerBusListeners(forgeCl, bridge)) {
                return false;
            }

            initialized = true;
            REGISTRATION_PROBE_RUNNING.set(true);
            System.out.println("\033[1;36m[InterMed] Initializing event bridge (Forge → Fabric)...\033[0m");
            System.out.println("\033[1;36m[InterMed] Event bridge registered (9 listeners).\033[0m");
            return true;
        }
    }

    // ── Registration ──────────────────────────────────────────────────────────

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean registerBusListeners(ClassLoader forgeCl, InterMedEventBridge bridge) {
        try {
            // Obtain the real Forge MinecraftForge.EVENT_BUS through forgeCl so we get
            // the bus that Forge actually fires events on (not a dead copy from the fat JAR).
            Class<?> minecraftForgeClass =
                Class.forName("net.minecraftforge.common.MinecraftForge", false, forgeCl);
            Object eventBus = minecraftForgeClass.getField("EVENT_BUS").get(null);

            // EventPriority.NORMAL resolved via forgeCl to avoid enum-identity mismatch.
            Class<?> priorityClass =
                Class.forName("net.minecraftforge.eventbus.api.EventPriority", false, forgeCl);
            Object normal = priorityClass.getField("NORMAL").get(null);

            // Locate addListener(EventPriority, boolean, Class, Consumer) on the bus.
            Method addListenerM = null;
            for (Method m : eventBus.getClass().getMethods()) {
                if ("addListener".equals(m.getName()) && m.getParameterCount() == 4
                        && m.getParameterTypes()[1] == boolean.class
                        && m.getParameterTypes()[2] == Class.class
                        && Consumer.class.isAssignableFrom(m.getParameterTypes()[3])) {
                    addListenerM = m;
                    break;
                }
            }
            if (addListenerM == null) {
                return false;
            }
            final Method addListener = addListenerM;

            // Register each listener.  Event classes are fetched via forgeCl so their
            // LISTENER_LIST field is present.  Handlers accept Object — no cast needed.
            register(addListener, eventBus, normal, forgeCl,
                "net.minecraftforge.event.TickEvent$ServerTickEvent",
                e -> bridge.onServerTick(e));
            register(addListener, eventBus, normal, forgeCl,
                "net.minecraftforge.event.server.ServerStartingEvent",
                e -> bridge.onServerStarting(e));
            register(addListener, eventBus, normal, forgeCl,
                "net.minecraftforge.event.server.ServerStartedEvent",
                e -> bridge.onServerStarted(e));
            register(addListener, eventBus, normal, forgeCl,
                "net.minecraftforge.event.server.ServerStoppingEvent",
                e -> bridge.onServerStopping(e));
            register(addListener, eventBus, normal, forgeCl,
                "net.minecraftforge.event.server.ServerStoppedEvent",
                e -> bridge.onServerStopped(e));
            register(addListener, eventBus, normal, forgeCl,
                "net.minecraftforge.event.level.LevelEvent$Load",
                e -> bridge.onLevelLoad(e));
            register(addListener, eventBus, normal, forgeCl,
                "net.minecraftforge.event.level.LevelEvent$Unload",
                e -> bridge.onLevelUnload(e));
            register(addListener, eventBus, normal, forgeCl,
                "net.minecraftforge.event.entity.player.PlayerEvent$PlayerLoggedInEvent",
                e -> bridge.onPlayerJoin(e));
            register(addListener, eventBus, normal, forgeCl,
                "net.minecraftforge.event.entity.player.PlayerEvent$PlayerLoggedOutEvent",
                e -> bridge.onPlayerLeave(e));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void register(Method addListenerMethod, Object eventBus, Object priority,
                                  ClassLoader forgeCl, String eventClassName,
                                  Consumer<Object> handler) {
        try {
            Class<?> eventClass = Class.forName(eventClassName, false, forgeCl);
            addListenerMethod.invoke(eventBus, priority, false, eventClass,
                (Consumer) handler);
        } catch (Exception e) {
            System.err.printf("[InterMedEventBridge] Failed to register %s: %s%n",
                eventClassName, e.getMessage());
        }
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    /**
     * Server tick — routed to Fabric {@link ServerTickEvents} and
     * {@link ObservabilityMonitor} for EWMA/CUSUM tracking.
     * Accepts {@code Object} to avoid a class-identity cast between the
     * system-classloader copy and Forge's classloader copy of the event class.
     */
    public void onServerTick(Object event) {
        Object phase = getFieldValue(event, "phase");
        if (phase != null && "START".equals(phase.toString())) {
            ObservabilityMonitor.onTickStart();
            // Drain RENDER-affinity events queued from async threads (ТЗ 3.5.2)
            MainThreadAffinityDispatcher.drainMainThreadQueue();
            Object server = invokeNoArg(event, "getServer");
            safeInvoke("ServerTickEvents.START", () ->
                ServerTickEvents.START_SERVER_TICK.invoker().onStartTick(server));
            return;
        }
        // END phase
        Object server = invokeNoArg(event, "getServer");
        LifecycleManager.dispatchMainThreadTasks();
        safeInvoke("ServerTickEvents.END", () ->
            ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server));
        ObservabilityMonitor.onTickEnd("intermed_core");
    }

    // ── Server lifecycle ──────────────────────────────────────────────────────

    public void onServerStarting(Object event) {
        Object server = extractServer(event);
        LifecycleManager.startPhase1_BackgroundAssembly();
        safeInvoke("ServerLifecycleEvents.SERVER_STARTING", () ->
            ServerLifecycleEvents.SERVER_STARTING.invoker().onServerStarting(server));
    }

    public void onServerStarted(Object event) {
        Object server = extractServer(event);
        // Register the current (server tick) thread as the main thread for affinity routing (ТЗ 3.5.2)
        MainThreadAffinityDispatcher.registerMainThread();
        safeInvoke("ServerLifecycleEvents.SERVER_STARTED", () ->
            ServerLifecycleEvents.SERVER_STARTED.invoker().onServerStarted(server));
        // Release the kernel boot barrier so premain/agentmain can proceed past the await.
        InterMedKernel.BOOT_BARRIER.countDown();
    }

    public void onServerStopping(Object event) {
        Object server = extractServer(event);
        safeInvoke("ServerLifecycleEvents.SERVER_STOPPING", () ->
            ServerLifecycleEvents.SERVER_STOPPING.invoker().onServerStopping(server));
    }

    public void onServerStopped(Object event) {
        Object server = extractServer(event);
        safeInvoke("ServerLifecycleEvents.SERVER_STOPPED", () ->
            ServerLifecycleEvents.SERVER_STOPPED.invoker().onServerStopped(server));
    }

    // ── World (level) ─────────────────────────────────────────────────────────

    public void onLevelLoad(Object event) {
        Object level = invokeNoArg(event, "getLevel");
        Object server = extractServerFromLevel(level);
        if (server == null) return; // client level — skip
        safeInvoke("ServerWorldEvents.LOAD", () ->
            ServerWorldEvents.LOAD.invoker().onWorldLoad(server, level));
    }

    public void onLevelUnload(Object event) {
        Object level = invokeNoArg(event, "getLevel");
        Object server = extractServerFromLevel(level);
        if (server == null) return;
        safeInvoke("ServerWorldEvents.UNLOAD", () ->
            ServerWorldEvents.UNLOAD.invoker().onWorldUnload(server, level));
    }

    // ── Player ────────────────────────────────────────────────────────────────

    public void onPlayerJoin(Object event) {
        Object player = invokeNoArg(event, "getEntity");
        Object server = invokeNoArg(player, "getServer");
        Object sender = resolveJoinSender(event, player);
        safeInvoke("ServerPlayConnectionEvents.INIT", () ->
            ServerPlayConnectionEvents.INIT.invoker().onPlayInit(player, server));
        safeInvoke("ServerPlayConnectionEvents.JOIN", () ->
            ServerPlayConnectionEvents.JOIN.invoker().onPlayReady(player, sender, server));
    }

    public void onPlayerLeave(Object event) {
        Object player = invokeNoArg(event, "getEntity");
        Object server = invokeNoArg(player, "getServer");
        safeInvoke("ServerPlayConnectionEvents.DISCONNECT", () ->
            ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(player, server));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Object extractServer(Object event) {
        return invokeNoArg(event, "getServer");
    }

    private static Object extractServerFromLevel(Object level) {
        if (level == null) return null;
        try {
            Method isClient = level.getClass().getMethod("isClientSide");
            if (Boolean.TRUE.equals(isClient.invoke(level))) return null;
        } catch (Exception ignored) {}
        return invokeNoArg(level, "getServer");
    }

    private static Object resolveJoinSender(Object event, Object player) {
        Object sender = invokeNoArg(event, "getSender");
        if (sender != null) {
            return sender;
        }
        sender = invokeNoArg(event, "getResponseSender");
        if (sender != null) {
            return sender;
        }
        sender = invokeNoArg(event, "getConnection");
        if (sender != null) {
            return sender;
        }
        sender = getFieldValue(event, "connection");
        if (sender != null) {
            return sender;
        }
        sender = invokeNoArg(player, "getConnection");
        if (sender != null) {
            return sender;
        }
        sender = invokeNoArg(player, "connection");
        if (sender != null) {
            return sender;
        }
        return getFieldValue(player, "connection");
    }

    /** Invokes a public no-arg method on {@code target}, returning null on failure. */
    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null || methodName == null) return null;
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /** Reads a public field from {@code target}, returning null on failure. */
    private static Object getFieldValue(Object target, String fieldName) {
        if (target == null) return null;
        try {
            Field f = target.getClass().getField(fieldName);
            return f.get(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void safeInvoke(String label, Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            System.err.printf("[InterMedEventBridge] Exception in '%s' listener: %s: %s%n",
                label, e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
