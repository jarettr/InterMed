package org.intermed.core.mixin;

import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.IPlatformAgent;
import org.spongepowered.asm.mixin.MixinEnvironment;

public class InterMedPlatformAgent implements IPlatformAgent {

    @Override
    public void init() {
    }

    @Override
    public String getSideName() {
        return "CLIENT";
    }

    @Override
    public void inject() {
    }

    @Override
    public String getLaunchTarget() {
        return null;
    }

    @Override
    public void setMixin(IMixinInternal mixin) {
    }
}
