package org.intermed.mixin;

import java.lang.reflect.InvocationTargetException;

import org.spongepowered.asm.mixin.MixinEnvironment;

/**
 * Entry point for the InterMed Mixin fork (ТЗ 3.2.3, Requirement 4).
 *
 * <h3>Boot sequence</h3>
 * <ol>
 *   <li>The launcher (or {@code MixinTransmogrifier}) calls
 *       {@link #registerClassSource(IClassBytesSource)} with a reference to the
 *       ClassLoader DAG.</li>
 *   <li>{@link #init()} is called once to trigger MixinExtras bootstrapping.
 *       The custom {@link org.intermed.mixin.service.InterMedMixinService} is
 *       discovered automatically via the
 *       {@code META-INF/services/org.spongepowered.asm.service.IMixinService}
 *       service file — no reflection hacks required.</li>
 * </ol>
 */
public final class InterMedMixinBootstrap {

    public enum MixinExtrasState {
        NOT_ATTEMPTED,
        DEFERRED,
        ACTIVE,
        MISSING,
        FAILED
    }

    private static volatile IClassBytesSource classSource;
    private static volatile boolean           initialised = false;
    private static volatile boolean           initialising = false;
    private static volatile boolean           serviceBootstrapInvoked = false;
    private static volatile MixinExtrasState  mixinExtrasState = MixinExtrasState.NOT_ATTEMPTED;
    private static volatile String            mixinExtrasMessage = "not-attempted";
    private static volatile String            mixinExtrasVersion = "unknown";
    private static volatile String            lastReportedSummary = "";

    private InterMedMixinBootstrap() {}

    // -------------------------------------------------------------------------
    // Registration
    // -------------------------------------------------------------------------

    /**
     * Registers the ClassLoader DAG source used by {@code InterMedMixinService}
     * to resolve and load classes.  Must be called <em>before</em> Mixin
     * initialises.
     */
    public static void registerClassSource(IClassBytesSource source) {
        if (source == null) {
            return;
        }
        classSource = source;
    }

    /** Returns the registered class source, or {@code null} if not yet set. */
    public static IClassBytesSource getClassSource() {
        return classSource;
    }

    public static boolean hasClassSource() {
        return classSource != null;
    }

    public static void noteServiceBootstrapInvoked() {
        serviceBootstrapInvoked = true;
    }

    public static boolean wasServiceBootstrapInvoked() {
        return serviceBootstrapInvoked;
    }

    public static MixinExtrasState getMixinExtrasState() {
        return mixinExtrasState;
    }

    public static String getMixinExtrasMessage() {
        return mixinExtrasMessage;
    }

    public static String getMixinExtrasVersion() {
        return mixinExtrasVersion;
    }

    public static String diagnostics() {
        return "classSource=" + hasClassSource()
            + ", initialised=" + initialised
            + ", initialising=" + initialising
            + ", bootstrapService=" + serviceBootstrapInvoked
            + ", mixinExtras=" + mixinExtrasState
            + ", mixinExtrasMessage=" + mixinExtrasMessage
            + ", mixinExtrasVersion=" + mixinExtrasVersion;
    }

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    /**
     * Bootstraps the InterMed Mixin fork:
     * <ol>
     *   <li>Initialises MixinExtras (adds {@code @Local}, {@code @WrapOperation},
     *       {@code @ModifyExpressionValue}, etc.) via reflection so the fork
     *       compiles even when MixinExtras is not on the compile-time path.</li>
     *   <li>Logs a summary of available extensions.</li>
     * </ol>
     *
     * <p>Idempotent — safe to call multiple times.
     */
    public static synchronized void init() {
        if (initialising) return;
        if (initialised && mixinExtrasState != MixinExtrasState.DEFERRED) return;
        initialising = true;

        try {
            if (!initialised) {
                System.out.println("\033[1;35m[MixinFork] Bootstrapping InterMed Mixin fork...\033[0m");
            }

            bootstrapMixinExtras();

            initialised = true;
            String suffix = switch (mixinExtrasState) {
                case DEFERRED -> "MixinExtras deferred until active transformer is available.";
                case ACTIVE -> "MixinExtras extensions active.";
                case MISSING -> "MixinExtras not present; core Mixin runtime only.";
                case FAILED -> "MixinExtras bootstrap failed; running in degraded mode.";
                case NOT_ATTEMPTED -> "MixinExtras were not attempted.";
            };
            reportStateIfChanged(suffix);
        } finally {
            initialising = false;
        }
    }

    public static boolean isInitialised() {
        return initialised;
    }

    public static void resetForTests() {
        classSource = null;
        initialised = false;
        initialising = false;
        serviceBootstrapInvoked = false;
        mixinExtrasState = MixinExtrasState.NOT_ATTEMPTED;
        mixinExtrasMessage = "not-attempted";
        mixinExtrasVersion = "unknown";
        lastReportedSummary = "";
    }

    // -------------------------------------------------------------------------
    // MixinExtras bootstrap
    // -------------------------------------------------------------------------

    /**
     * Calls {@code MixinExtrasBootstrap.init()} via reflection so that:
     * <ul>
     *   <li>{@code @Local} (local variable capture in injectors) is available.</li>
     *   <li>{@code @WrapOperation} (method-call wrapping) is available.</li>
     *   <li>{@code @ModifyExpressionValue} is available.</li>
     *   <li>{@code @ModifyReturnValue} is available.</li>
     *   <li>All other MixinExtras annotations are available.</li>
     * </ul>
     *
     * <p>If MixinExtras is not on the runtime classpath the failure is soft
     * (warning only) so that InterMed works in environments without it.
     */
    private static void bootstrapMixinExtras() {
        try {
            Class<?> bootstrap = Class.forName(
                "com.llamalad7.mixinextras.MixinExtrasBootstrap");
            try {
                Object version = bootstrap.getMethod("getVersion").invoke(null);
                mixinExtrasVersion = version != null ? String.valueOf(version) : "unknown";
            } catch (ReflectiveOperationException ignored) {
                mixinExtrasVersion = "unknown";
            }
            if (!isMixinTransformerReady()) {
                mixinExtrasState = MixinExtrasState.DEFERRED;
                mixinExtrasMessage = "awaiting-active-transformer";
                return;
            }
            bootstrap.getMethod("init").invoke(null);
            mixinExtrasState = MixinExtrasState.ACTIVE;
            mixinExtrasMessage = "active";
            System.out.println("[MixinFork] MixinExtras bootstrapped successfully."
                + ("unknown".equals(mixinExtrasVersion) ? "" : " version=" + mixinExtrasVersion));
            System.out.println("[MixinFork]   @Local, @WrapOperation, @ModifyExpressionValue, "
                + "@ModifyReturnValue — all active.");
        } catch (ClassNotFoundException e) {
            mixinExtrasState = MixinExtrasState.MISSING;
            mixinExtrasMessage = "class-not-found";
            mixinExtrasVersion = "missing";
            System.err.println("\033[1;33m[MixinFork] WARNING: MixinExtras not found on classpath. "
                + "@Local and companion annotations will not be available.\033[0m");
        } catch (Exception e) {
            mixinExtrasState = MixinExtrasState.FAILED;
            mixinExtrasMessage = describeBootstrapFailure(e);
            System.err.println("[MixinFork] MixinExtras bootstrap failed: " + mixinExtrasMessage);
        }
    }

    private static boolean isMixinTransformerReady() {
        try {
            return MixinEnvironment.getCurrentEnvironment() != null
                && MixinEnvironment.getCurrentEnvironment().getActiveTransformer() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void reportStateIfChanged(String suffix) {
        String summary = mixinExtrasState + "|" + mixinExtrasMessage + "|" + mixinExtrasVersion;
        if (summary.equals(lastReportedSummary)) {
            return;
        }
        lastReportedSummary = summary;
        System.out.println("\033[1;32m[MixinFork] Mixin fork ready. " + suffix + "\033[0m");
    }

    private static String describeBootstrapFailure(Exception exception) {
        if (exception instanceof InvocationTargetException invocationTargetException
            && invocationTargetException.getCause() != null) {
            Throwable cause = invocationTargetException.getCause();
            return cause.getClass().getSimpleName() + ":" + String.valueOf(cause.getMessage());
        }
        return exception.getClass().getSimpleName() + ":" + String.valueOf(exception.getMessage());
    }
}
