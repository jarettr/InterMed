package org.intermed.core.registry;

import net.bytebuddy.asm.Advice;

/**
 * ByteBuddy {@link Advice} applied to registry <em>read</em> methods
 * ({@code get}, {@code getValue}, {@code getOptional}, etc.) on all Registry
 * classes (ТЗ 3.2.2, Requirement 3 — Java Agent, read interception).
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>If {@link VirtualRegistry} holds a value for the requested key, that
 *       value is returned instead of (or in addition to) the vanilla result.</li>
 *   <li>The vanilla result is used as a fallback: if the virtual registry has
 *       no entry, the original return value is kept unchanged.</li>
 *   <li>The advice is applied on <em>exit</em> so we always see the vanilla
 *       result first and can decide whether to override it.</li>
 * </ul>
 */
public final class RegistryGetAdvice {

    private RegistryGetAdvice() {}

    /**
     * After the original registry get returns, check whether the virtual
     * registry has an override for the same key.
     *
     * @param args     All formal arguments to the method (key is the last one).
     * @param returned The return value produced by the original method; may be
     *                 reassigned to the virtual value.
     */
    @Advice.OnMethodExit
    public static void onGetExit(
            @Advice.This(optional = true) Object self,
            @Advice.AllArguments Object[] args,
            @Advice.Return(readOnly = false) Object returned) {
        Object virtual = VirtualRegistryService.lookupValueForCurrentMod(self, args);
        if (virtual != null) {
            returned = virtual;
        }
    }
}
