package org.intermed.core;

import org.intermed.core.lifecycle.LifecycleManager;
import org.intermed.core.bridge.NeoForgeEventBridge;
import org.intermed.core.bridge.NeoForgeNetworkBridge;
import org.intermed.core.bridge.ForgeEventBridge;
import org.intermed.core.mixin.MixinTransmogrifier;
import org.intermed.core.transformer.InterMedTransformer;
import org.intermed.core.security.KernelContext;
import org.intermed.security.InterMedAgent;
import java.lang.instrument.Instrumentation;
import java.io.PrintStream;
import java.util.jar.JarFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
        bootstrap(agentArgs, inst, "premain");
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        bootstrap(agentArgs, inst, "agentmain");
    }

    private static void bootstrap(String agentArgs, Instrumentation inst, String phase) {
        Path codeSource = resolveKernelCodeSource();
        bootstrap(codeSource, inst, phase);
    }

    static void bootstrap(Path codeSource, Instrumentation inst, String phase) {
        // Установка кодировки для логов гипервизора
        System.setOut(new PrintStream(System.out, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(System.err, true, StandardCharsets.UTF_8));

        System.out.println("\033[1;36m==================================================");
        System.out.println("   🛡️ INTERMED HYPERVISOR " + InterMedVersion.DISPLAY_VERSION + " - CORE ONLINE");
        System.out.println("   Bootstrap Phase: " + phase);
        System.out.println("==================================================\033[0m");

        try {
            boolean deferDeepBootstrap = shouldDeferDeepBootstrap(codeSource);
            // Все действия ядра в привилегированном контексте
            KernelContext.executeWithException(() -> {
                // 1. Promote only the bootstrap helper surface globally.
                Path bootstrapJarPath = resolveBootstrapSupportJar(codeSource);
                JarFile bootstrapJar = new JarFile(bootstrapJarPath.toFile());
                inst.appendToBootstrapClassLoaderSearch(bootstrapJar);
                
                // Кладём Instrumentation в системные свойства
                System.getProperties().put("intermed.instrumentation", inst);
                System.out.println("[Kernel] Bootstrap support visibility: " + bootstrapJarPath.getFileName());

                // 2. Install mandatory security and registry intercepts first.
                InterMedAgent.install(inst);

                // 2a. Proactively attach NeoForge bridge listeners as soon as
                // the runtime exposes the NeoForge mod event bus.
                ForgeEventBridge.scheduleRegistrationProbe();
                NeoForgeEventBridge.scheduleRegistrationProbe();
                NeoForgeNetworkBridge.scheduleRegistrationProbe();

                if (deferDeepBootstrap) {
                    System.setProperty("intermed.deferDeepBootstrap", "true");
                    System.out.println("[Kernel] Fabric agent path detected. Deferring mixin/runtime Phase 0 bootstrap.");
                } else {
                    System.clearProperty("intermed.deferDeepBootstrap");

                    // 2b. Prepare the DAG-backed class source before the Mixin runtime
                    // selects its IMixinService, then verify the canonical fork wins.
                    LifecycleManager.prepareMixinClassSource();
                    MixinTransmogrifier.bootstrapTransmogrification();

                    // 3. ЗАПУСК ФАЗЫ 0 (Словарь)
                    LifecycleManager.startPhase0_Preloader();
                }

                // 4. Регистрация главного трансформера-диспетчера
                inst.addTransformer(new InterMedTransformer(), true);

                // 5. БАРЬЕР СИНХРОНИЗАЦИИ
                System.out.println("[Kernel] Synchronization barrier active (1500ms)...");
                BOOT_BARRIER.await(1500, TimeUnit.MILLISECONDS);

                return null;
            });
        } catch (Throwable failure) {
            handleCriticalBootstrapFailure(phase, failure);
        }
        System.out.println("\033[1;32m[Kernel] BOOT SUCCESS. Hypervisor is watching classload events.\033[0m");
    }

    private static Path resolveKernelCodeSource() {
        try {
            return Path.of(
                InterMedKernel.class.getProtectionDomain().getCodeSource().getLocation().toURI()
            ).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to resolve InterMed kernel code source", e);
        }
    }

    private static void handleCriticalBootstrapFailure(String phase, Throwable failure) {
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        System.err.println("\033[1;31m[Kernel] CRITICAL BOOT ERROR: " + failure.getMessage() + "\033[0m");
        failure.printStackTrace();
        throw new IllegalStateException(
            "InterMed bootstrap aborted during " + phase + " because a critical hypervisor component failed to initialize",
            failure
        );
    }

    private static Path resolveBootstrapSupportJar(Path codeSource) {
        if (!Files.isRegularFile(codeSource)) {
            throw new IllegalStateException("Agent code source is not a jar: " + codeSource);
        }
        String fileName = codeSource.getFileName().toString();
        String bootstrapFileName = fileName.endsWith(".jar")
            ? fileName.substring(0, fileName.length() - 4) + "-bootstrap.jar"
            : fileName + "-bootstrap.jar";
        Path sibling = codeSource.resolveSibling(bootstrapFileName);
        if (!Files.isRegularFile(sibling)) {
            Path genericSibling = resolveGenericBootstrapSibling(codeSource, fileName);
            if (genericSibling != null && Files.isRegularFile(genericSibling)) {
                ensureBootstrapJarCompatibility(codeSource, genericSibling);
                return genericSibling;
            }
            throw new IllegalStateException("Missing bootstrap support jar: " + sibling);
        }
        ensureBootstrapJarCompatibility(codeSource, sibling);
        return sibling;
    }

    private static void ensureBootstrapJarCompatibility(Path codeSource, Path bootstrapJar) {
        try {
            String agentBuildId = readManifestAttribute(codeSource, "InterMed-Build-Id");
            String bootstrapBuildId = readManifestAttribute(bootstrapJar, "InterMed-Build-Id");
            if (agentBuildId == null || bootstrapBuildId == null) {
                System.out.println("[Kernel] Bootstrap compatibility metadata missing; continuing without build-id validation.");
                return;
            }
            if (!agentBuildId.equals(bootstrapBuildId)) {
                throw new IllegalStateException(
                    "Bootstrap support jar was built from a different InterMed build. " +
                        "Rebuild both artifacts before launching. agent=" + codeSource.getFileName() +
                        " [" + agentBuildId + "], bootstrap=" + bootstrapJar.getFileName() +
                        " [" + bootstrapBuildId + "]"
                );
            }
        } catch (IllegalStateException incompatible) {
            throw incompatible;
        } catch (Exception e) {
            throw new IllegalStateException(
                "Unable to validate bootstrap support jar compatibility for " + bootstrapJar,
                e
            );
        }
    }

    private static String readManifestAttribute(Path jarPath, String attributeName) throws Exception {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            if (jarFile.getManifest() == null) {
                return null;
            }
            String value = jarFile.getManifest().getMainAttributes().getValue(attributeName);
            if (value == null || value.isBlank()) {
                return null;
            }
            return value.trim();
        }
    }

    private static Path resolveGenericBootstrapSibling(Path codeSource, String fileName) {
        if (!fileName.endsWith("-fabric.jar")) {
            return null;
        }
        String genericBootstrap = fileName.substring(0, fileName.length() - "-fabric.jar".length())
            + "-bootstrap.jar";
        return codeSource.resolveSibling(genericBootstrap);
    }

    private static boolean shouldDeferDeepBootstrap(Path codeSource) {
        if (codeSource == null) {
            return false;
        }
        return codeSource.getFileName().toString().endsWith("-fabric.jar");
    }
}
