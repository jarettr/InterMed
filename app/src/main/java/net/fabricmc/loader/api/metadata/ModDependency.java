package net.fabricmc.loader.api.metadata;

import java.util.Collection;
import java.util.List;

public interface ModDependency {
    Kind getKind();

    String getModId();

    Collection<String> getVersionRequirements();

    default boolean matches(String version) {
        return true;
    }

    enum Kind {
        DEPENDS,
        RECOMMENDS,
        SUGGESTS,
        CONFLICTS,
        BREAKS
    }

    static ModDependency of(Kind kind, String modId) {
        return new ModDependency() {
            @Override
            public Kind getKind() {
                return kind;
            }

            @Override
            public String getModId() {
                return modId;
            }

            @Override
            public Collection<String> getVersionRequirements() {
                return List.of();
            }
        };
    }
}
