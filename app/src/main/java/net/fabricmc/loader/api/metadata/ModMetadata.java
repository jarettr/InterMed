package net.fabricmc.loader.api.metadata;

import net.fabricmc.loader.api.Version;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ModMetadata {
    default String getType() {
        return "fabric";
    }

    String getId();

    default Collection<String> getProvides() {
        return List.of();
    }

    default Version getVersion() {
        try {
            return Version.parse("0.0.0");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    default ModEnvironment getEnvironment() {
        return ModEnvironment.UNIVERSAL;
    }

    default Collection<ModDependency> getDependencies() {
        return List.of();
    }

    default Collection<ModDependency> getDepends() {
        return getDependencies().stream()
            .filter(dependency -> dependency.getKind() == ModDependency.Kind.DEPENDS)
            .toList();
    }

    default Collection<ModDependency> getRecommends() {
        return getDependencies().stream()
            .filter(dependency -> dependency.getKind() == ModDependency.Kind.RECOMMENDS)
            .toList();
    }

    default Collection<ModDependency> getSuggests() {
        return getDependencies().stream()
            .filter(dependency -> dependency.getKind() == ModDependency.Kind.SUGGESTS)
            .toList();
    }

    default Collection<ModDependency> getConflicts() {
        return getDependencies().stream()
            .filter(dependency -> dependency.getKind() == ModDependency.Kind.CONFLICTS)
            .toList();
    }

    default Collection<ModDependency> getBreaks() {
        return getDependencies().stream()
            .filter(dependency -> dependency.getKind() == ModDependency.Kind.BREAKS)
            .toList();
    }

    default String getName() {
        return getId();
    }

    default String getDescription() {
        return "";
    }

    default Collection<Person> getAuthors() {
        return List.of();
    }

    default Collection<Person> getContributors() {
        return List.of();
    }

    default ContactInformation getContact() {
        return ContactInformation.EMPTY;
    }

    default Collection<String> getLicense() {
        return List.of();
    }

    default Optional<String> getIconPath(int size) {
        return Optional.empty();
    }

    default boolean containsCustomValue(String key) {
        return getCustomValues().containsKey(key);
    }

    default CustomValue getCustomValue(String key) {
        return getCustomValues().get(key);
    }

    default Map<String, CustomValue> getCustomValues() {
        return Map.of();
    }

    default boolean containsCustomElement(String key) {
        return containsCustomValue(key);
    }
}
