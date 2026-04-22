package org.intermed.core.bridge;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.intermed.core.lifecycle.LifecycleManager;

import java.lang.reflect.Method;

/**
 * Forge-hosted bridge that translates Forge/NeoForge server lifecycle events
 * into the corresponding Fabric event callbacks.
 *
 * <h3>Event mapping</h3>
 * <table>
 *   <tr><th>Forge event</th><th>Fabric event</th></tr>
 *   <tr><td>{@code ServerStartingEvent}</td><td>{@link ServerLifecycleEvents#SERVER_STARTING}</td></tr>
 *   <tr><td>{@code ServerStartedEvent}</td><td>{@link ServerLifecycleEvents#SERVER_STARTED}</td></tr>
 *   <tr><td>{@code ServerStoppingEvent}</td><td>{@link ServerLifecycleEvents#SERVER_STOPPING}</td></tr>
 *   <tr><td>{@code ServerStoppedEvent}</td><td>{@link ServerLifecycleEvents#SERVER_STOPPED}</td></tr>
 *   <tr><td>{@code LevelEvent.Load}</td><td>{@link ServerWorldEvents#LOAD}</td></tr>
 *   <tr><td>{@code LevelEvent.Unload}</td><td>{@link ServerWorldEvents#UNLOAD}</td></tr>
 * </table>
 *
 * <p>Registered on the Forge <em>game</em> event bus (not the mod bus) via
 * {@link MinecraftForge#EVENT_BUS} so that it receives events from all phases.
 *
 * <p>Each Fabric callback invocation is guarded by a try-catch so a misbehaving
 * listener never prevents remaining listeners or the server from proceeding.
 */
public class FabricLifecycleBridge {

    private static volatile boolean registered = false;

    /**
     * Registers this bridge on the Forge game event bus.  Idempotent — calling
     * multiple times is safe.  Should be called from the mod constructor or
     * {@code FMLCommonSetupEvent}.
     */
    public static void register() {
        if (registered) return;
        registered = true;
        MinecraftForge.EVENT_BUS.register(new FabricLifecycleBridge());
        System.out.println("[FabricLifecycleBridge] Registered on Forge event bus.");
    }

    // ── Convenience boot methods (kept for callers that use them) ─────────────

    public static void bootMainEntrypoints() {
        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.startPhase1_BackgroundAssembly();
    }

    public static void bootSynchronously() {
        LifecycleManager.startPhase0_Preloader();
        LifecycleManager.assembleNow();
    }

    public static int dispatchPendingMainThreadTasks() {
        return LifecycleManager.dispatchMainThreadTasks();
    }

    // ── Forge → Fabric lifecycle event translations ───────────────────────────

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        Object server = extractServer(event);
        System.out.println("[FabricLifecycleBridge] SERVER_STARTING");
        LifecycleManager.startPhase1_BackgroundAssembly();
        safeInvoke(() ->
            ServerLifecycleEvents.SERVER_STARTING.invoker().onServerStarting(server));
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        Object server = extractServer(event);
        System.out.println("[FabricLifecycleBridge] SERVER_STARTED");
        safeInvoke(() ->
            ServerLifecycleEvents.SERVER_STARTED.invoker().onServerStarted(server));
        // Signal the boot barrier so InterMed knows the server is fully up
        org.intermed.core.InterMedKernel.BOOT_BARRIER.countDown();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        Object server = extractServer(event);
        System.out.println("[FabricLifecycleBridge] SERVER_STOPPING");
        safeInvoke(() ->
            ServerLifecycleEvents.SERVER_STOPPING.invoker().onServerStopping(server));
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        Object server = extractServer(event);
        System.out.println("[FabricLifecycleBridge] SERVER_STOPPED");
        safeInvoke(() ->
            ServerLifecycleEvents.SERVER_STOPPED.invoker().onServerStopped(server));
    }

    // ── Forge → Fabric world (level) event translations ───────────────────────

    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        Object level = event.getLevel();
        Object server = extractServerFromLevel(level);
        if (server == null) return; // client-side level — not a server world event
        safeInvoke(() ->
            ServerWorldEvents.LOAD.invoker().onWorldLoad(server, level));
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        Object level = event.getLevel();
        Object server = extractServerFromLevel(level);
        if (server == null) return;
        safeInvoke(() ->
            ServerWorldEvents.UNLOAD.invoker().onWorldUnload(server, level));
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    public static void resetForTests() {
        registered = false;
        ServerLifecycleEvents.resetForTests();
        ServerWorldEvents.resetForTests();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extracts the MinecraftServer instance from a Forge server lifecycle event
     * using {@code getServer()} via reflection so we don't need a hard compile
     * dependency on the Minecraft server type.
     */
    private static Object extractServer(Object event) {
        if (event == null) return null;
        try {
            Method m = event.getClass().getMethod("getServer");
            return m.invoke(event);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the server from a {@code ServerLevel} / {@code LevelAccessor}.
     * Returns {@code null} for client-side levels (which have no server reference).
     */
    private static Object extractServerFromLevel(Object level) {
        if (level == null) return null;
        // Server levels implement isClientSide() == false and expose getServer()
        try {
            Method isClient = level.getClass().getMethod("isClientSide");
            if (Boolean.TRUE.equals(isClient.invoke(level))) return null;
        } catch (Exception ignored) {}
        try {
            Method getServer = level.getClass().getMethod("getServer");
            return getServer.invoke(level);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Invokes a Fabric event invoker, catching and logging any exception so
     * a failing listener never prevents other listeners from running.
     */
    private static void safeInvoke(Runnable runnable) {
        try {
            runnable.run();
        } catch (Exception e) {
            System.err.printf("[FabricLifecycleBridge] Exception in Fabric lifecycle listener: %s: %s%n",
                e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
