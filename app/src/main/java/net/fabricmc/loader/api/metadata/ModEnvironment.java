package net.fabricmc.loader.api.metadata;

import net.fabricmc.api.EnvType;

public enum ModEnvironment {
    CLIENT,
    SERVER,
    UNIVERSAL;

    public boolean matches(EnvType type) {
        return this == UNIVERSAL
            || (this == CLIENT && type == EnvType.CLIENT)
            || (this == SERVER && type == EnvType.SERVER);
    }
}
