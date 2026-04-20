package net.neoforged.neoforge.registries;

public final class RegisterEvent {

    private final RegistryKey registryKey;

    public RegisterEvent(String location) {
        this.registryKey = new RegistryKey(location);
    }

    public RegistryKey getRegistryKey() {
        return registryKey;
    }

    public static final class RegistryKey {
        private final String location;

        private RegistryKey(String location) {
            this.location = location;
        }

        public String location() {
            return location;
        }
    }
}
