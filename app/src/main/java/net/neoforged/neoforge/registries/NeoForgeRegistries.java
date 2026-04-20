package net.neoforged.neoforge.registries;

public final class NeoForgeRegistries {

    public static final ForgeRegistries.FakeRegistry FLUID_TYPES = new ForgeRegistries.FakeRegistry();

    private NeoForgeRegistries() {}

    public static void reset() {
        FLUID_TYPES.reset();
    }
}
