package org.intermed.core.transformer;

import org.intermed.core.bridge.NeoForgeEventBridge;
import org.intermed.core.bridge.NeoForgeNetworkBridge;
import org.intermed.core.bridge.ForgeEventBridge;
import org.intermed.core.classloading.LazyInterMedClassLoader;
import org.intermed.core.util.MappingManager;
import org.intermed.core.lifecycle.LifecycleManager;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Критический компонент (Microkernel Layer).
 * Отвечает ТОЛЬКО за отслеживание жизненного цикла игры и запуск фаз инициализации.
 * Никаких жестких манипуляций с байт-кодом здесь больше нет (ТЗ 3.1.7).
 */
public class InterMedTransformer implements ClassFileTransformer {
    private static boolean bridgeStarted = false;
    private static boolean deferredBootstrapReleased = false;
    private static final AtomicInteger classCounter = new AtomicInteger(0);

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        
        if (className == null) return null;

        int count = classCounter.incrementAndGet();
        if (count % 500 == 0) {
            System.out.println("[Kernel] Tracking load state... Class count: " + count);
        }

        String realName = MappingManager.translate(className);
        String nameToCheck = (realName != null) ? realName : className;
        boolean titleScreenTrigger = nameToCheck.contains("gui/screens/TitleScreen");
        boolean dedicatedServerTrigger = nameToCheck.contains("server/MinecraftServer");
        if (titleScreenTrigger || dedicatedServerTrigger) {
            LazyInterMedClassLoader.registerRuntimeClassLoader(loader);
        }

        if (nameToCheck.startsWith("net/neoforged/") || nameToCheck.startsWith("net.neoforged.")) {
            NeoForgeEventBridge.scheduleRegistrationProbe();
            NeoForgeNetworkBridge.scheduleRegistrationProbe();
        }
        if (nameToCheck.startsWith("net/minecraftforge/") || nameToCheck.startsWith("net.minecraftforge.")) {
            ForgeEventBridge.scheduleRegistrationProbe();
        }

        if (!deferredBootstrapReleased
            && Boolean.getBoolean("intermed.deferDeepBootstrap")
            && (titleScreenTrigger || dedicatedServerTrigger)) {
            deferredBootstrapReleased = true;
            System.out.println("[Kernel] Releasing deferred Phase 0 bootstrap at: " + nameToCheck);
            LifecycleManager.startPhase0_Preloader();
        }

        // --- ТРИГГЕР ГЛАВНОГО МЕНЮ (Снятие барьера Фазы 0) ---
        if (!bridgeStarted && (titleScreenTrigger || dedicatedServerTrigger)) {
            System.out.println("\033[1;35m[Lifecycle] CRITICAL GAME STATE REACHED: " + nameToCheck + "\033[0m");
            bridgeStarted = true;
            
            // Client-only render hooks must not be resolved on dedicated server.
            try {
                if (titleScreenTrigger) {
                    org.intermed.core.bridge.events.ForgeEventProxy.hookIntoForge();
                    ForgeEventBridge.registerIfAvailable();
                    org.intermed.core.bridge.InterMedEventBridge.initialize();
                } else {
                    ForgeEventBridge.scheduleRegistrationProbe();
                    org.intermed.core.bridge.InterMedEventBridge.scheduleRegistrationProbe();
                }
            } catch (Exception e) {
                System.err.println("[Kernel] Failed to inject Forge hook: " + e.getMessage());
            }

            // The dedicated-server path is still defining MinecraftServer at this
            // point. Starting Phase 1 here can re-enter loader work too early, so
            // the real server path resumes from the lifecycle callbacks instead.
            if (titleScreenTrigger) {
                LifecycleManager.startPhase1_BackgroundAssembly();
            } else {
                System.out.println("[Lifecycle] Dedicated server trigger detected; deferring Phase 1 until SERVER_STARTING.");
            }
        }

        // Возвращаем null. JVM сама загрузит классы без изменений.
        // За ремаппинг и миксины теперь отвечает LazyInterMedClassLoader.
        return null; 
    }
}
