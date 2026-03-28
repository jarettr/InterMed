package org.intermed.core;

import org.intermed.core.lifecycle.LifecycleManager;
import org.intermed.core.transformer.InterMedTransformer;
import org.intermed.core.security.KernelContext;
import java.lang.instrument.Instrumentation;
import java.io.PrintStream;
import java.io.File;
import java.util.jar.JarFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ТОЧКА ВХОДА ГИПЕРВИЗОРА (Core Engine).
 * Выполняет Фазу 0 и устанавливает барьер синхронизации.
 * АННОТАЦИЯ @Mod делает наш Гипервизор видимым для внутренних систем Forge.
 */
@net.minecraftforge.fml.common.Mod("intermed")
public class InterMedKernel {
    
    // Барьер синхронизации для обеспечения быстрой загрузки меню
    public static final CountDownLatch BOOT_BARRIER = new CountDownLatch(1);

    // Этот конструктор вызовет сам Forge!
    public InterMedKernel() {
        System.out.println("\033[1;35m[InterMed] Hypervisor successfully hooked into Forge Mod Bus!\033[0m");
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        // Установка кодировки для логов гипервизора
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        System.out.println("\033[1;36m==================================================");
        System.out.println("   🛡️ INTERMED HYPERVISOR v8.0 - CORE ONLINE");
        System.out.println("==================================================\033[0m");

        // Все действия ядра в привилегированном контексте
        KernelContext.execute(() -> {
            try {
                // 1. Промоушен JAR в глобальную видимость
                String jarPath = new File(InterMedKernel.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
                JarFile agentJar = new JarFile(jarPath);
                inst.appendToBootstrapClassLoaderSearch(agentJar);
                inst.appendToSystemClassLoaderSearch(agentJar);
                
                // Кладём Instrumentation в системные свойства
                System.getProperties().put("intermed.instrumentation", inst);
                System.out.println("[Kernel] Global Class Visibility: ENABLED");

                // 2. ЗАПУСК ФАЗЫ 0 (Словарь)
                LifecycleManager.startPhase0_Preloader();

                // 3. Регистрация главного трансформера-диспетчера
                inst.addTransformer(new InterMedTransformer(), true);

                // 4. БАРЬЕР СИНХРОНИЗАЦИИ
                System.out.println("[Kernel] Synchronization barrier active (1500ms)...");
                BOOT_BARRIER.await(1500, TimeUnit.MILLISECONDS);
                
                System.out.println("\033[1;32m[Kernel] BOOT SUCCESS. Hypervisor is watching classload events.\033[0m");

            } catch (Exception e) {
                System.err.println("\033[1;31m[Kernel] CRITICAL BOOT ERROR: " + e.getMessage() + "\033[0m");
                e.printStackTrace();
            }
            return null;
        });
    }
}