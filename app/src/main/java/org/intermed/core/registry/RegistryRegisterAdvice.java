package org.intermed.core.registry;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

/**
 * ByteBuddy {@link Advice} applied to {@code register()} methods on all
 * Registry classes loaded by the JVM (ТЗ 3.2.2, Requirement 3 — Java Agent).
 *
 * <p>This runs <em>before</em> the {@link RegistryHookTransformer} ASM pass,
 * giving a second, earlier intercept point that catches classes loaded by the
 * bootstrap class loader (e.g. vanilla Minecraft registry classes that are
 * loaded before mod class loaders are set up).
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>On entry, if both {@code key} and {@code value} are non-null, the pair
 *       is mirrored into {@link VirtualRegistry}.</li>
 *   <li>The advice is <em>non-intrusive</em>: it never alters the arguments or
 *       return value, so the original registry logic is unaffected.</li>
 * </ul>
 */
public final class RegistryRegisterAdvice {

    private RegistryRegisterAdvice() {}

    /**
     * Intercepts a two-argument {@code register(key, value)} or three-argument
     * {@code register(registry, key, value)} call.
     *
     * <p>ByteBuddy maps {@code @Argument(0)} to the first formal parameter after
     * "this" for virtual methods, so for a static register(Registry, Key, Value)
     * the indices are 0, 1, 2; for an instance register(Key, Value) they are 0, 1.
     *
     * <p>Because argument count varies across ecosystems (Fabric static vs Forge
     * instance), we use {@code Object} types and defensive null checks rather
     * than binding to specific parameter positions.
     */
    @Advice.OnMethodEnter
    public static void onRegisterEnter(
            @Advice.This(optional = true) Object self,
            @Advice.AllArguments Object[] args) {
        if (args == null) return;
        // Pattern: (..., key, value) — last two args are key + value
        if (args.length >= 2) {
            Object key   = args[args.length - 2];
            Object value = args[args.length - 1];
            if (key != null && value != null) {
                try {
                    Object registryCandidate = self != null ? self : (args.length > 2 ? args[0] : null);
                    VirtualRegistryService.registerVirtualized(
                        CapabilityManager.currentModIdOr("unknown"),
                        RegistryIdentity.scopeOf(registryCandidate),
                        String.valueOf(key),
                        -1,
                        value
                    );
                } catch (RuntimeException ignored) {
                    // Best-effort advice: host registration must continue even if
                    // the mirror cannot be updated.
                }
            }
        }
    }
}
