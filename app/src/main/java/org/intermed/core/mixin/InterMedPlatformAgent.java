package org.intermed.core.mixin;

import org.intermed.mixin.InterMedMixinBootstrap;
import org.spongepowered.asm.launch.platform.IMixinPlatformAgent;
import org.spongepowered.asm.launch.platform.MixinPlatformAgentAbstract;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Platform agent for the InterMed Mixin fork.
 *
 * <p>This agent is discovered through {@code IMixinService#getPlatformAgents()}
 * and gives InterMed one authoritative place to:
 * <ul>
 *   <li>register the platform's own mixin config,</li>
 *   <li>force a supported compatibility level early,</li>
 *   <li>bootstrap the custom Mixin fork and MixinExtras integration.</li>
 * </ul>
 */
public class InterMedPlatformAgent extends MixinPlatformAgentAbstract {

    private static final AtomicBoolean CONFIG_REGISTERED = new AtomicBoolean(false);
    private static final AtomicBoolean BOOTSTRAPPED = new AtomicBoolean(false);
    private static final Set<String> EXTERNAL_CONFIGS = ConcurrentHashMap.newKeySet();
    private static final String INTERMED_MIXIN_CONFIG = "intermed.mixins.json";

    @Override
    public IMixinPlatformAgent.AcceptResult accept(
        org.spongepowered.asm.launch.platform.MixinPlatformManager manager,
        org.spongepowered.asm.launch.platform.container.IContainerHandle handle
    ) {
        IMixinPlatformAgent.AcceptResult result = super.accept(manager, handle);
        logger.debug("InterMed platform agent accepted container {}", handle);
        return result;
    }

    @Override
    public String getPhaseProvider() {
        return null;
    }

    @Override
    public void prepare() {
        prepareFromLifecycle();
    }

    @Override
    public void initPrimaryContainer() {
        prepareFromLifecycle();
    }

    @Override
    public void inject() {
        if (BOOTSTRAPPED.compareAndSet(false, true)) {
            InterMedMixinBootstrap.init();
            logger.info("InterMed platform agent injected custom Mixin bootstrap.");
        }
    }

    public static void prepareFromLifecycle() {
        ensureCompatibilityLevel();
        registerInterMedConfig();
    }

    public static void resetForTests() {
        CONFIG_REGISTERED.set(false);
        BOOTSTRAPPED.set(false);
        EXTERNAL_CONFIGS.clear();
    }

    static Set<String> registeredExternalConfigsForTests() {
        return Set.copyOf(EXTERNAL_CONFIGS);
    }

    public static void registerExternalMixinConfig(String configPath) {
        if (configPath == null || configPath.isBlank()) {
            return;
        }
        ensureCompatibilityLevel();
        if (EXTERNAL_CONFIGS.add(configPath)) {
            try {
                Mixins.addConfiguration(configPath);
                logger.info("Registered external mixin config {}", configPath);
            } catch (Throwable throwable) {
                logger.warn("Deferred external mixin config {}: {}", configPath, throwable.toString());
            }
        }
    }

    private static void registerInterMedConfig() {
        if (CONFIG_REGISTERED.compareAndSet(false, true)) {
            Mixins.addConfiguration(INTERMED_MIXIN_CONFIG);
            logger.info("Registered InterMed mixin config {}", INTERMED_MIXIN_CONFIG);
        }
    }

    private static void ensureCompatibilityLevel() {
        try {
            MixinEnvironment.CompatibilityLevel targetLevel = preferredCompatibilityLevel();
            if (MixinEnvironment.getCompatibilityLevel().isLessThan(targetLevel)) {
                MixinEnvironment.setCompatibilityLevel(targetLevel);
            }
        } catch (IllegalArgumentException ex) {
            logger.warn("Failed to elevate Mixin compatibility level: {}", ex.getMessage());
        }
    }

    private static MixinEnvironment.CompatibilityLevel preferredCompatibilityLevel() {
        MixinEnvironment.CompatibilityLevel java21 = tryResolveCompatibility("JAVA_21");
        if (java21 != null) {
            return java21;
        }

        MixinEnvironment.CompatibilityLevel java18 = tryResolveCompatibility("JAVA_18");
        if (java18 != null) {
            return java18;
        }

        return MixinEnvironment.CompatibilityLevel.JAVA_17;
    }

    private static MixinEnvironment.CompatibilityLevel tryResolveCompatibility(String levelName) {
        try {
            return MixinEnvironment.CompatibilityLevel.valueOf(levelName);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
