package org.intermed.core.bridge.events;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import org.intermed.core.lifecycle.LifecycleManager;

import java.lang.reflect.Method;
import java.util.function.Consumer;

/**
 * Forge render-thread subscriber that drains the InterMed main-thread task
 * queue and dispatches HUD render callbacks from Fabric mods.
 *
 * <p>Registered on the Forge game bus. Only the render-side HUD dispatch lives
 * here; all server lifecycle / tick / player events are handled by
 * {@link org.intermed.core.bridge.InterMedEventBridge}.
 *
 * <h3>Why reflective registration</h3>
 * <p>Same root cause as {@link org.intermed.core.bridge.InterMedEventBridge}:
 * Forge event classes in the agent's fat JAR have not been through
 * {@code EventSubclassTransformer} and therefore lack the {@code LISTENER_LIST}
 * field the EventBus needs.  We use the context classloader (Forge's
 * {@code ModuleClassLoader}) to obtain a properly-transformed event class and
 * register via the 4-arg {@code addListener} overload.
 */
public class ForgeEventProxy {

    private static volatile boolean hooked = false;
    private static volatile boolean queueDrained = false;
    /** Rate-limit error logging to once per 5 s to avoid log spam. */
    private static long lastErrorTime = 0;

    /**
     * Registers this proxy on the Forge game event bus. Idempotent.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void hookIntoForge() {
        if (hooked) return;
        hooked = true;
        try {
            ClassLoader forgeCl = Thread.currentThread().getContextClassLoader();

            Class<?> minecraftForgeClass =
                Class.forName("net.minecraftforge.common.MinecraftForge", false, forgeCl);
            Object eventBus = minecraftForgeClass.getField("EVENT_BUS").get(null);

            Class<?> priorityClass =
                Class.forName("net.minecraftforge.eventbus.api.EventPriority", false, forgeCl);
            Object normal = priorityClass.getField("NORMAL").get(null);

            Class<?> eventClass =
                Class.forName("net.minecraftforge.client.event.RenderGuiOverlayEvent$Post",
                    false, forgeCl);

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
            if (addListenerM == null) throw new IllegalStateException("addListener(4-arg) not found");

            ForgeEventProxy proxy = new ForgeEventProxy();
            addListenerM.invoke(eventBus, normal, false, eventClass,
                (Consumer) proxy::onRenderOverlay);
            System.out.println("\033[1;32m[ForgeEventProxy] Registered render-thread proxy.\033[0m");
        } catch (Exception e) {
            System.err.println("\033[1;31m[ForgeEventProxy] Registration failed: "
                + e.getMessage() + "\033[0m");
        }
    }

    public static void resetForTests() {
        hooked = false;
        queueDrained = false;
        lastErrorTime = 0;
    }

    // ── Render overlay subscriber ─────────────────────────────────────────────

    /**
     * Handles {@code RenderGuiOverlayEvent$Post} arriving via the Forge game bus.
     * Accepts {@code Object} to avoid a class-identity cast between the
     * system-classloader copy and Forge's classloader copy of the event class.
     */
    public void onRenderOverlay(Object event) {
        // ── Drain deferred main-thread tasks once ─────────────────────────────
        if (!queueDrained) {
            int count = LifecycleManager.dispatchMainThreadTasks();
            if (count > 0) {
                System.out.printf("\033[1;32m[ForgeEventProxy] Dispatched %d deferred main-thread task(s).%n\033[0m", count);
                injectFabricAssets();
                queueDrained = true;
            }
        }

        // ── HUD callbacks ─────────────────────────────────────────────────────
        if (HudRenderCallback.LISTENERS.isEmpty()) return;

        try {
            String overlayPath = extractOverlayPath(event);
            if (!"food_level".equals(overlayPath) && !"player_health".equals(overlayPath)) return;

            Object guiGraphics = invokeMethod(event, "getGuiGraphics", "m_280063_");
            Object partialTick = invokeMethod(event, "getPartialTick", "m_280055_");
            if (guiGraphics == null || partialTick == null) return;

            float tick = partialTick instanceof Number n ? n.floatValue() : 0f;
            for (HudRenderCallback listener : HudRenderCallback.LISTENERS) {
                try {
                    listener.onHudRender(guiGraphics, tick);
                } catch (Exception e) {
                    logRateLimited("[ForgeEventProxy] HUD listener threw: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            logRateLimited("[ForgeEventProxy] Render event processing failed: " + e.getMessage());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static String extractOverlayPath(Object event) {
        try {
            Object overlay  = invokeMethod(event, "getOverlay", "m_280273_");
            if (overlay == null) return null;
            Object location = invokeMethod(overlay, "id", "m_280053_");
            if (location == null) return null;
            Object path     = invokeMethod(location, "getPath", "m_135827_");
            return path instanceof String s ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Reflectively invokes a method by its clean name first, falling back to
     * the SRG-obfuscated name for older Forge/MCP mappings.
     */
    private static Object invokeMethod(Object target, String cleanName, String srgName) {
        if (target == null) return null;
        for (String name : new String[]{cleanName, srgName}) {
            try {
                Method m = target.getClass().getMethod(name);
                return m.invoke(target);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static void injectFabricAssets() {
        try {
            Class<?> injector = Class.forName("org.intermed.core.bridge.assets.AssetInjector");
            injector.getMethod("injectFabricAssets").invoke(null);
        } catch (Exception e) {
            System.err.println("[ForgeEventProxy] AssetInjector not available: " + e.getMessage());
        }
    }

    private static void logRateLimited(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastErrorTime > 5_000L) {
            System.err.println(msg);
            lastErrorTime = now;
        }
    }
}
