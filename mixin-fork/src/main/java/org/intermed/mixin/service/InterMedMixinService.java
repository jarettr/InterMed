package org.intermed.mixin.service;

import com.google.common.collect.ImmutableList;
import org.intermed.mixin.InterMedMixinBootstrap;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinService;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.ITransformer;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.logging.LoggerAdapterDefault;
import org.spongepowered.asm.util.ReEntranceLock;

import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Full {@link IMixinService} implementation for the InterMed Mixin fork
 * (ТЗ 3.2.3, Requirement 4 — Custom Mixin Fork).
 *
 * <h3>Service discovery</h3>
 * Registered via
 * {@code META-INF/services/org.spongepowered.asm.service.IMixinService}
 * so SpongePowered's {@code MixinService} locator picks it up automatically
 * through {@code ServiceLoader} — no reflection hacks required.
 *
 * <h3>MixinExtras</h3>
 * {@link #init()} calls {@link InterMedMixinBootstrap#init()} which bootstraps
 * MixinExtras and enables {@code @Local}, {@code @WrapOperation},
 * {@code @ModifyExpressionValue}, and {@code @ModifyReturnValue}.
 */
public final class InterMedMixinService implements IMixinService {

    private final ReEntranceLock         lock             = new ReEntranceLock(1);
    private final IClassProvider         classProvider    = new InterMedClassProvider();
    private final IClassBytecodeProvider bytecodeProvider = new InterMedBytecodeProvider();
    private final IContainerHandle       primaryContainer = new InterMedMixinContainer("InterMed");
    private final ITransformerProvider   transformerProvider = new NoopTransformerProvider();
    private final IMixinAuditTrail       auditTrail          = new LoggingAuditTrail();
    private final Set<String>            invalidClasses      = ConcurrentHashMap.newKeySet();

    // -------------------------------------------------------------------------
    // Identity
    // -------------------------------------------------------------------------

    @Override public String getName()    { return "InterMed"; }
    @Override public boolean isValid()   { return true; }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void prepare() {}

    @Override
    public MixinEnvironment.Phase getInitialPhase() {
        return MixinEnvironment.Phase.PREINIT;
    }

    /**
     * Called by Mixin after environment selection.  Bootstraps MixinExtras so
     * that {@code @Local} and companion annotations are available to all mods.
     */
    @Override
    public void init() {
        InterMedMixinBootstrap.init();
    }

    @Override public void offer(IMixinInternal internal) {}
    @Override public void beginPhase() {}
    @Override public void checkEnv(Object bootStrap) {}

    // -------------------------------------------------------------------------
    // Class resolution
    // -------------------------------------------------------------------------

    @Override public IClassProvider         getClassProvider()    { return classProvider;    }
    @Override public IClassBytecodeProvider getBytecodeProvider() { return bytecodeProvider; }

    @Override public ITransformerProvider   getTransformerProvider() { return transformerProvider; }

    @Override
    public IClassTracker getClassTracker() {
        return new IClassTracker() {
            @Override
            public void registerInvalidClass(String className) {
                invalidClasses.add(className);
                System.out.printf("[MixinFork] Invalidated class: %s%n", className);
            }
            @Override
            public boolean isClassLoaded(String className) {
                var src = InterMedMixinBootstrap.getClassSource();
                return src != null && src.isClassLoaded(className);
            }
            @Override
            public String getClassRestrictions(String className) {
                return invalidClasses.contains(className) ? "PACKAGE_CLASSLOADER_EXCLUSION" : "";
            }
        };
    }

    @Override public IMixinAuditTrail getAuditTrail() { return auditTrail; }

    // -------------------------------------------------------------------------
    // Container / platform
    // -------------------------------------------------------------------------

    @Override
    public Collection<String> getPlatformAgents() {
        return ImmutableList.of("org.intermed.core.mixin.InterMedPlatformAgent");
    }

    @Override public IContainerHandle getPrimaryContainer() { return primaryContainer; }
    @Override public Collection<IContainerHandle> getMixinContainers() { return ImmutableList.of(primaryContainer); }

    // -------------------------------------------------------------------------
    // Resources
    // -------------------------------------------------------------------------

    @Override
    public InputStream getResourceAsStream(String name) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) {
            InputStream stream = cl.getResourceAsStream(name);
            if (stream != null) {
                return stream;
            }
        }

        ClassLoader localLoader = InterMedMixinService.class.getClassLoader();
        if (localLoader != null && localLoader != cl) {
            InputStream stream = localLoader.getResourceAsStream(name);
            if (stream != null) {
                return stream;
            }
        }

        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        return systemLoader != null ? systemLoader.getResourceAsStream(name) : null;
    }

    // -------------------------------------------------------------------------
    // Environment
    // -------------------------------------------------------------------------

    @Override
    public String getSideName() {
        String configured = firstNonBlank(
            System.getProperty("runtime.env"),
            System.getProperty("intermed.env"),
            System.getProperty("fabric.env")
        );
        return "server".equalsIgnoreCase(configured)
            ? MixinEnvironment.Side.SERVER.name()
            : MixinEnvironment.Side.CLIENT.name();
    }
    @Override public ReEntranceLock getReEntranceLock() { return lock; }
    @Override public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() { return MixinEnvironment.CompatibilityLevel.JAVA_17; }
    @Override public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() { return MixinEnvironment.CompatibilityLevel.MAX_SUPPORTED; }
    @Override public ILogger getLogger(String name) { return new LoggerAdapterDefault(name); }

    private static final class NoopTransformerProvider implements ITransformerProvider {
        private final Collection<ITransformer> transformers = Collections.emptyList();

        @Override
        public Collection<ITransformer> getTransformers() {
            return transformers;
        }

        @Override
        public Collection<ITransformer> getDelegatedTransformers() {
            return transformers;
        }

        @Override
        public void addTransformerExclusion(String name) {
            // InterMed manages exclusions in its own transformation pipeline.
        }
    }

    private static final class LoggingAuditTrail implements IMixinAuditTrail {
        @Override
        public void onApply(String className, String mixinName) {
            System.out.printf("[MixinFork] apply %s -> %s%n", mixinName, className);
        }

        @Override
        public void onPostProcess(String className) {
            System.out.printf("[MixinFork] post-process %s%n", className);
        }

        @Override
        public void onGenerate(String className, String generatorName) {
            System.out.printf("[MixinFork] generate %s via %s%n", className, generatorName);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
