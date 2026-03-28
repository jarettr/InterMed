package org.intermed.core.resolver;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mattwelke.packdependencies.Dependency;
import com.mattwelke.packdependencies.Package;
import com.mattwelke.packdependencies.Version;
import com.mattwelke.packdependencies.VersionConstraint;
import com.mattwelke.packdependencies.VersionUnion;
import com.mattwelke.packdependencies.collect.ImmutableMap;
import com.mattwelke.packdependencies.collect.ImmutableSet;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A dependency resolver that uses a pure Java implementation of the PubGrub algorithm.
 * This class is responsible for taking a set of mod manifests, parsing their dependencies,
 * and producing a single, conflict-free set of mod versions to load.
 * This fulfills Requirement 3.2.1 of the InterMed technical specification.
 */
public class PubGrubResolver {

    private static final Gson GSON = new Gson();

    /**
     * Resolves dependencies using the PubGrub algorithm.
     *
     * @param jsonManifests A JSON string representing a list of mod manifests.
     *                      Each manifest should have "id", "version", and "dependencies".
     * @return A JSON string representing the resolved dependency graph.
     */
    public String resolve(String jsonManifests) {
        JsonArray manifests = GSON.fromJson(jsonManifests, JsonArray.class);
        Map<String, Package> packages = new HashMap<>();
        Map<Package, Map<Version, java.util.List<Dependency>>> allDeps = new HashMap<>();
        Map<Package, ImmutableSet<Version>> allVersions = new HashMap<>();

        // 1. Parse all packages, versions, and dependencies from the manifests
        for (JsonElement manifestElement : manifests) {
            JsonObject manifest = manifestElement.getAsJsonObject();
            String modId = manifest.get("id").getAsString();
            String versionStr = manifest.get("version").getAsString();

            Package pkg = packages.computeIfAbsent(modId, Package::new);
            Version version = new Version(versionStr);
            
            allVersions.computeIfAbsent(pkg, k -> com.mattwelke.packdependencies.collect.ImmutableSet.of(version));


            java.util.List<Dependency> deps = new java.util.ArrayList<>();
            if (manifest.has("dependencies")) {
                for (JsonElement depElement : manifest.getAsJsonArray("dependencies")) {
                    JsonObject depObj = depElement.getAsJsonObject();
                    String depId = depObj.get("id").getAsString();
                    String versionConstraintStr = depObj.get("version").getAsString();
                    
                    Package depPkg = packages.computeIfAbsent(depId, Package::new);
                    VersionConstraint constraint = parseVersionConstraint(versionConstraintStr);
                    deps.add(new Dependency(depPkg, constraint));
                }
            }
             allDeps.computeIfAbsent(pkg, k -> new HashMap<>()).put(version, deps);
        }

        // 2. Define the root dependencies (all mods are root dependencies)
        java.util.List<Dependency> rootDependencies = packages.values().stream()
                .map(pkg -> new Dependency(pkg, VersionConstraint.any()))
                .collect(Collectors.toList());

        // 3. Run the resolution algorithm
        try {
            com.mattwelke.packdependencies.Resolution resolution = new com.mattwelke.packdependencies.Resolution(
                ImmutableMap.copyOf(allVersions),
                allDeps.entrySet().stream().collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, e -> ImmutableMap.copyOf(e.getValue()))),
                rootDependencies
            );
            Map<Package, Version> result = resolution.resolve();

            // 4. Format the result into a JSON object
            JsonObject resultJson = new JsonObject();
            resultJson.addProperty("status", "success");
            JsonObject resolvedPackages = new JsonObject();
            for (Map.Entry<Package, Version> entry : result.entrySet()) {
                resolvedPackages.addProperty(entry.getKey().toString(), entry.getValue().toString());
            }
            resultJson.add("packages", resolvedPackages);
            
            return GSON.toJson(resultJson);

        } catch (Exception e) {
            System.err.println("[PubGrub] Dependency resolution failed: " + e.getMessage());
            JsonObject errorJson = new JsonObject();
            errorJson.addProperty("status", "error");
            errorJson.addProperty("message", e.getMessage());
            return GSON.toJson(errorJson);
        }
    }

    private VersionConstraint parseVersionConstraint(String constraintStr) {
        // This is a simplified parser. A real implementation would need to handle complex ranges.
        if (constraintStr.equals("*") || constraintStr.equals("any")) {
            return VersionConstraint.any();
        }
        // Example: ">=1.2.3"
        if (constraintStr.startsWith(">=")) {
            return new VersionUnion.Builder()
                .add(new VersionConstraint.Range(new Version(constraintStr.substring(2)), true, null, false))
                .build();
        }
        // Example: ">1.2.3"
        if (constraintStr.startsWith(">")) {
            return new VersionUnion.Builder()
                .add(new VersionConstraint.Range(new Version(constraintStr.substring(1)), false, null, false))
                .build();
        }
        // Exact version
        return new VersionConstraint.Exact(new Version(constraintStr));
    }
}
