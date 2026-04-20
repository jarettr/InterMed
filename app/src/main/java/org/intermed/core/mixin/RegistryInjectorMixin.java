package org.intermed.core.mixin;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import org.intermed.core.bridge.RegistryCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Registry.class)
public class RegistryInjectorMixin {

    @Inject(
        method = "register(Lnet/minecraft/core/Registry;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/Object;)Ljava/lang/Object;",
        at = @At("HEAD")
    )
    private static <V, T extends V> void onRegister(Registry<V> registry,
                                                    ResourceLocation id,
                                                    T entry,
                                                    CallbackInfoReturnable<T> cir) {
        // НИКАКИХ System.out.println ЗДЕСЬ! Это вызывает deadlock.
        // Просто молча передаем данные на склад.
        if (id != null && entry != null) {
            RegistryCache.harvest(id, entry);
        }
    }
}
