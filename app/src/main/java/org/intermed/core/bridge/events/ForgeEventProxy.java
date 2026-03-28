package org.intermed.core.bridge.events;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import org.intermed.core.lifecycle.LifecycleManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class ForgeEventProxy {

    private static boolean hooked = false;
    private static long lastErrorTime = 0;
    private static boolean queueDrained = false; 

    public static void hookIntoForge() {
        if (hooked) return;
        try {
            System.out.println("\033[1;34m[Matrix:ForgeHook] Executing Render-Thread Injection...\033[0m");

            ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
            
            Class<?> forgeClass = Class.forName("net.minecraftforge.common.MinecraftForge", true, gameLoader);
            Field busField = forgeClass.getField("EVENT_BUS");
            Object eventBus = busField.get(null);

            Method registerMethod = eventBus.getClass().getMethod("register", Object.class);
            registerMethod.invoke(eventBus, new InternalRenderSpy());
            
            hooked = true;
            System.out.println("\033[1;32m[Matrix:ForgeHook] SUCCESS! Render-Thread Listener active.\033[0m");
            
        } catch (Exception e) {
            System.err.println("\033[1;31m[Matrix:ForgeHook] CRITICAL FAILURE: " + e.getMessage() + "\033[0m");
        }
    }

    private static Method findMethod(Class<?> clazz, String cleanName, String srgName, Class<?>... parameterTypes) {
        try {
            return clazz.getMethod(cleanName, parameterTypes);
        } catch (NoSuchMethodException e) {
            try {
                return clazz.getMethod(srgName, parameterTypes);
            } catch (NoSuchMethodException ex) {
                return null;
            }
        }
    }

    /**
     * Шпион, который сидит прямо в цикле рендера.
     * Он гарантированно вызывается каждый кадр.
     */
    public static class InternalRenderSpy {
        
        // Мы используем Object в параметре, но Фордж прочитает аннотацию!
        @net.minecraftforge.eventbus.api.SubscribeEvent
        public void onRenderTick(Object event) {
            // Проверяем, что это правильное событие, чтобы не спамить рефлексией на каждый чих
            if (!event.getClass().getName().contains("RenderGuiOverlayEvent$Post")) return;

            // --- ГАРАНТИРОВАННЫЙ ДИСПЕТЧЕР ЗАДАЧ ---
            // Выполняем это только один раз, когда игра уже начала рисовать меню
            if (!queueDrained) {
                Runnable task;
                int count = 0;
                while ((task = LifecycleManager.MAIN_THREAD_TASKS.poll()) != null) {
                    try {
                        task.run();
                        count++;
                    } catch (Exception e) {
                        System.err.println("[Matrix:Dispatcher] Task failed: " + e.getMessage());
                    }
                }
                if (count > 0) {
                    System.out.println("\033[1;32m[Matrix:Dispatcher] Successfully deployed " + count + " mods into Main Thread!\033[0m");
                    
                    // --- СТОЛП 3: АКТИВАЦИЯ ТЕКСТУР ---
                    // Как только моды загрузились, подтягиваем их текстуры!
                    try {
                        Class<?> injector = Class.forName("org.intermed.core.bridge.assets.AssetInjector");
                        injector.getMethod("injectFabricAssets").invoke(null);
                    } catch (Exception e) {
                        System.err.println("[Matrix:Dispatcher] Failed to call AssetInjector: " + e.getMessage());
                    }
                    
                    queueDrained = true;
                }
            }
            // ---------------------------------------

            // Если никто из модов не подписался на HUD, выходим
            if (HudRenderCallback.LISTENERS.isEmpty()) return;

            try {
                Method getOverlayMethod = findMethod(event.getClass(), "getOverlay", "m_280273_");
                if (getOverlayMethod == null) return;
                
                Object overlay = getOverlayMethod.invoke(event);
                
                Method getIdMethod = findMethod(overlay.getClass(), "id", "m_280053_");
                if (getIdMethod == null) return;
                Object resourceLocation = getIdMethod.invoke(overlay);
                
                Method getPathMethod = findMethod(resourceLocation.getClass(), "getPath", "m_135827_");
                String path = (String) getPathMethod.invoke(resourceLocation);

                // Отрисовываем Fabric-моды только поверх еды/здоровья
                if ("food_level".equals(path) || "player_health".equals(path)) {
                    
                    Method getGraphicsMethod = findMethod(event.getClass(), "getGuiGraphics", "m_280063_");
                    Method getTickMethod = findMethod(event.getClass(), "getPartialTick", "m_280055_");
                    
                    if (getGraphicsMethod != null && getTickMethod != null) {
                        Object guiGraphics = getGraphicsMethod.invoke(event);
                        float partialTick = (float) getTickMethod.invoke(event);

                        for (HudRenderCallback listener : HudRenderCallback.LISTENERS) {
                            listener.onHudRender(guiGraphics, partialTick);
                        }
                    }
                }
            } catch (Exception e) {
                long now = System.currentTimeMillis();
                if (now - lastErrorTime > 5000) {
                    System.err.println("[Matrix:Render] Exception: " + e.getMessage());
                    lastErrorTime = now;
                }
            }
        }
    }
}