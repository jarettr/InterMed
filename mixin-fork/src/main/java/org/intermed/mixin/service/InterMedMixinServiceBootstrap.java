package org.intermed.mixin.service;

import org.intermed.mixin.InterMedMixinBootstrap;
import org.spongepowered.asm.service.IMixinServiceBootstrap;

/**
 * Early bootstrap hook for the InterMed Mixin service.
 *
 * <p>Mixin invokes service bootstraps before selecting the concrete
 * {@code IMixinService}, which gives InterMed one deterministic place to:
 * <ul>
 *   <li>announce the canonical service class name to the runtime,</li>
 *   <li>mark bootstrap diagnostics for later verification.</li>
 * </ul>
 *
 * <p>It intentionally does <strong>not</strong> bootstrap MixinExtras here.
 * This callback fires before the canonical Mixin runtime is fully initialised,
 * so InterMed defers MixinExtras setup to {@link InterMedMixinBootstrap#init()}
 * after {@code MixinBootstrap.init()} completes.
 */
public final class InterMedMixinServiceBootstrap implements IMixinServiceBootstrap {

    @Override
    public String getName() {
        return "InterMed";
    }

    @Override
    public String getServiceClassName() {
        return InterMedMixinService.class.getName();
    }

    @Override
    public void bootstrap() {
        InterMedMixinBootstrap.noteServiceBootstrapInvoked();
    }
}
