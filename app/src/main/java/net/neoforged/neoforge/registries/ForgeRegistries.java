package net.neoforged.neoforge.registries;

import java.util.ArrayList;
import java.util.List;

public final class ForgeRegistries {

    public static final FakeRegistry BLOCKS = new FakeRegistry();
    public static final FakeRegistry ITEMS = new FakeRegistry();
    public static final FakeRegistry ENTITY_TYPES = new FakeRegistry();
    public static final FakeRegistry SOUND_EVENTS = new FakeRegistry();
    public static final FakeRegistry ENCHANTMENTS = new FakeRegistry();
    public static final FakeRegistry MOB_EFFECTS = new FakeRegistry();
    public static final FakeRegistry POTIONS = new FakeRegistry();
    public static final FakeRegistry CREATIVE_MODE_TABS = new FakeRegistry();
    public static final FakeRegistry RECIPE_TYPES = new FakeRegistry();
    public static final FakeRegistry PARTICLE_TYPES = new FakeRegistry();
    public static final FakeRegistry BLOCK_ENTITY_TYPES = new FakeRegistry();
    public static final FakeRegistry FEATURES = new FakeRegistry();

    private static final List<FakeRegistry> ALL = List.of(
        BLOCKS,
        ITEMS,
        ENTITY_TYPES,
        SOUND_EVENTS,
        ENCHANTMENTS,
        MOB_EFFECTS,
        POTIONS,
        CREATIVE_MODE_TABS,
        RECIPE_TYPES,
        PARTICLE_TYPES,
        BLOCK_ENTITY_TYPES,
        FEATURES
    );

    private ForgeRegistries() {}

    public static void reset() {
        ALL.forEach(FakeRegistry::reset);
    }

    public static final class FakeRegistry {
        private final List<String> registrations = new ArrayList<>();

        public void register(Object id, Object entry) {
            registrations.add(String.valueOf(id) + "=" + String.valueOf(entry));
        }

        public boolean contains(String id, Object entry) {
            return registrations.contains(id + "=" + String.valueOf(entry));
        }

        void reset() {
            registrations.clear();
        }
    }
}
