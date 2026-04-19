package net.neoforged.neoforge.registries;

import java.util.ArrayList;
import java.util.List;

public final class ForgeRegistries {

    public static final FakeRegistry BLOCKS = new FakeRegistry();

    private ForgeRegistries() {}

    public static void reset() {
        BLOCKS.reset();
    }

    public static final class FakeRegistry {
        private final List<String> registrations = new ArrayList<>();

        public void register(Object id, Object entry) {
            registrations.add(String.valueOf(id) + "=" + String.valueOf(entry));
        }

        public boolean contains(String id, Object entry) {
            return registrations.contains(id + "=" + String.valueOf(entry));
        }

        private void reset() {
            registrations.clear();
        }
    }
}
