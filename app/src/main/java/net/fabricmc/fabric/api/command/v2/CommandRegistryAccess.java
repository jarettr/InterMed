package net.fabricmc.fabric.api.command.v2;

public interface CommandRegistryAccess {

    CommandRegistryAccess EMPTY = new CommandRegistryAccess() {
    };

    static CommandRegistryAccess empty() {
        return EMPTY;
    }

    default Object registryManager() {
        return null;
    }

    default Object commandBuildContext() {
        return null;
    }
}
