package org.intermed.core.resolver;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.intermed.core.config.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PubGrubResolverTest {

    private final PubGrubResolver resolver = new PubGrubResolver();

    @AfterEach
    void tearDown() {
        System.clearProperty("resolver.allow.fallback");
        RuntimeConfig.resetForTests();
    }

    @Test
    void resolvesSimpleDependencyGraph() {
        JsonArray manifests = new JsonArray();
        manifests.add(manifest("core", "1.0.0"));
        manifests.add(manifest("addon", "1.0.0", dep("core", ">=1.0.0")));

        JsonObject result = JsonParser.parseString(resolver.resolve(manifests.toString())).getAsJsonObject();
        assertEquals("success", result.get("status").getAsString());
        assertEquals("1.0.0", result.getAsJsonObject("packages").get("core").getAsString());
        assertEquals("1.0.0", result.getAsJsonObject("packages").get("addon").getAsString());
    }

    @Test
    void reportsMissingDependency() {
        JsonArray manifests = new JsonArray();
        manifests.add(manifest("addon", "1.0.0", dep("missing", "*")));

        JsonObject result = JsonParser.parseString(resolver.resolve(manifests.toString())).getAsJsonObject();
        assertEquals("error", result.get("status").getAsString());
        assertTrue(result.get("message").getAsString().contains("missing"));
    }

    @Test
    void allowsSoftMissingDependenciesOnlyWhenFallbackIsExplicitlyEnabled() {
        System.setProperty("resolver.allow.fallback", "true");
        RuntimeConfig.reload();

        var plan = resolver.resolvePlan(java.util.List.of(
            manifestRecord("addon", "1.0.0", depRecord("missing", "*"))
        ));

        assertEquals("1.0.0", plan.versionOf("addon"));
        assertTrue(plan.softMissingDependencies().contains("missing"));
        assertEquals(1, plan.diagnostics().size());
        assertEquals("SOFT_MISSING_DEPENDENCY", plan.diagnostics().get(0).code());
    }

    @Test
    void reportsVersionConstraintMismatch() {
        JsonArray manifests = new JsonArray();
        manifests.add(manifest("core", "1.0.0"));
        manifests.add(manifest("addon", "1.0.0", dep("core", ">=2.0.0")));

        JsonObject result = JsonParser.parseString(resolver.resolve(manifests.toString())).getAsJsonObject();
        assertEquals("error", result.get("status").getAsString());
        assertTrue(result.get("message").getAsString().contains("Unable to resolve"));
    }

    @Test
    void explainsConcreteVersionClauseUnsatForSingleCandidatePackage() {
        ResolutionException error = assertThrows(ResolutionException.class, () ->
            resolver.resolvePlan(java.util.List.of(
                manifestRecord("addon", "1.0.0", depRecord("core", ">=2.0.0")),
                manifestRecord("core", "1.0.0")
            ))
        );

        assertEquals("UNSATISFIABLE_CONSTRAINTS", error.code());
        assertEquals("core", error.moduleId());
        assertTrue(error.requirements().stream().anyMatch(req -> req.contains("addon@1.0.0")));
        assertEquals(List.of("1.0.0"), error.availableVersions());
        assertTrue(error.getMessage().contains("available versions"));
    }

    @Test
    void selectsHighestCompatibleVersionWhenMultipleCandidatesExist() {
        JsonArray manifests = new JsonArray();
        manifests.add(manifest("core", "1.0.0"));
        manifests.add(manifest("core", "2.1.0"));
        manifests.add(manifest("addon", "1.0.0", dep("core", "^2.0.0")));

        JsonObject result = JsonParser.parseString(resolver.resolve(manifests.toString())).getAsJsonObject();
        assertEquals("success", result.get("status").getAsString());
        assertEquals("2.1.0", result.getAsJsonObject("packages").get("core").getAsString());
    }

    @Test
    void backtracksWhenFirstCandidateCreatesTransitiveConflict() {
        var plan = resolver.resolvePlan(java.util.List.of(
            manifestRecord("root", "1.0.0",
                depRecord("feature", "*"),
                depRecord("helper", "*")),
            manifestRecord("feature", "2.0.0", depRecord("shared", "^2.0.0")),
            manifestRecord("feature", "1.0.0", depRecord("shared", "^1.0.0")),
            manifestRecord("helper", "1.0.0", depRecord("shared", "^1.0.0")),
            manifestRecord("shared", "1.5.0"),
            manifestRecord("shared", "2.5.0")
        ));

        assertEquals("1.0.0", plan.versionOf("feature"),
            "Resolver must backtrack from feature@2.0.0 to a compatible branch");
        assertEquals("1.5.0", plan.versionOf("shared"));
    }

    @Test
    void substitutesVirtualDependenciesBeforeResolution() {
        var plan = resolver.resolvePlan(java.util.List.of(
            manifestRecord("demo-mod", "1.0.0", depRecord("fabric-api", "*")),
            manifestRecord("intermed-fabric-bridge", "1.0.0")
        ));

        assertEquals("1.0.0", plan.versionOf("intermed-fabric-bridge"));
        assertTrue(plan.depsOf("demo-mod").contains("intermed-fabric-bridge"));
        assertEquals(RuntimeModuleKind.BRIDGE, plan.moduleKindOf("intermed-fabric-bridge"));
    }

    @Test
    void substitutesNeoForgeVirtualDependenciesBeforeResolution() {
        var plan = resolver.resolvePlan(java.util.List.of(
            manifestRecord("neo-demo", "1.0.0", depRecord("neoforge", "[21,)")),
            manifestRecord("intermed-neoforge-bridge", "21.0.0")
        ));

        assertEquals("21.0.0", plan.versionOf("intermed-neoforge-bridge"));
        assertTrue(plan.depsOf("neo-demo").contains("intermed-neoforge-bridge"));
    }

    @Test
    void preservesVirtualConstraintsWhenSelectingBridgeCandidates() {
        var plan = resolver.resolvePlan(java.util.List.of(
            manifestRecord("neo-demo", "1.0.0", depRecord("neoforge", "[21,)")),
            manifestRecord("intermed-neoforge-bridge", "20.0.0"),
            manifestRecord("intermed-neoforge-bridge", "21.1.0")
        ));

        assertEquals("21.1.0", plan.versionOf("intermed-neoforge-bridge"));
    }

    @Test
    void failsWhenVirtualBridgeCandidateDoesNotSatisfyDeclaredConstraint() {
        IllegalStateException error = assertThrows(IllegalStateException.class, () ->
            resolver.resolvePlan(java.util.List.of(
                manifestRecord("neo-demo", "1.0.0", depRecord("neoforge", "[21,)")),
                manifestRecord("intermed-neoforge-bridge", "20.0.0")
            ))
        );

        assertTrue(error.getMessage().contains("intermed-neoforge-bridge"));
    }

    @Test
    void splitsMinecraftAndJavaVirtualDependenciesIntoSeparateRuntimeNodes() {
        var plan = resolver.resolvePlan(java.util.List.of(
            manifestRecord("compat-demo", "1.0.0",
                depRecord("minecraft", ">=1.20.1"),
                depRecord("java", ">=21")),
            manifestRecord("intermed-minecraft-runtime", "1.20.1"),
            manifestRecord("intermed-java-runtime", "21.0.0")
        ));

        assertTrue(plan.depsOf("compat-demo").contains("intermed-minecraft-runtime"));
        assertTrue(plan.depsOf("compat-demo").contains("intermed-java-runtime"));
        assertEquals(RuntimeModuleKind.PLATFORM_RUNTIME, plan.moduleKindOf("intermed-minecraft-runtime"));
        assertEquals(RuntimeModuleKind.PLATFORM_RUNTIME, plan.moduleKindOf("intermed-java-runtime"));
    }

    @Test
    void reportsCycleInResolvedGraph() {
        ResolutionException error = assertThrows(ResolutionException.class, () ->
            resolver.resolvePlan(java.util.List.of(
                manifestRecord("a", "1.0.0", depRecord("b", "*")),
                manifestRecord("b", "1.0.0", depRecord("a", "*"))
            ))
        );
        assertEquals("CYCLIC_DEPENDENCY_GRAPH", error.code());
        assertTrue(error.getMessage().contains("Cyclic dependency graph"));
    }

    @Test
    void producesDeterministicPlanIndependentOfManifestOrder() {
        List<PubGrubResolver.ModManifest> manifests = new java.util.ArrayList<>(List.of(
            manifestRecord("root", "1.0.0", depRecord("beta", "*"), depRecord("alpha", "*")),
            manifestRecord("alpha", "2.0.0", depRecord("shared", "^2.0.0")),
            manifestRecord("alpha", "1.0.0", depRecord("shared", "^1.0.0")),
            manifestRecord("beta", "1.0.0", depRecord("shared", "^1.0.0")),
            manifestRecord("shared", "2.0.0"),
            manifestRecord("shared", "1.5.0")
        ));

        ResolvedPlan baseline = resolver.resolvePlan(manifests);
        Collections.reverse(manifests);
        ResolvedPlan reversed = resolver.resolvePlan(manifests);

        assertEquals(baseline.resolvedVersions(), reversed.resolvedVersions());
        assertEquals(baseline.dependencyEdges(), reversed.dependencyEdges());
        assertEquals(baseline.loadOrder(), reversed.loadOrder());
        assertEquals(baseline.moduleKinds(), reversed.moduleKinds());
    }

    @Test
    void exposesExplainableFailureMetadataForIncompatibleRanges() {
        ResolutionException error = assertThrows(ResolutionException.class, () ->
            resolver.resolvePlan(java.util.List.of(
                manifestRecord("root", "1.0.0",
                    depRecord("shared", "^1.0.0"),
                    depRecord("helper", "*")),
                manifestRecord("helper", "1.0.0", depRecord("shared", "^2.0.0")),
                manifestRecord("shared", "1.5.0"),
                manifestRecord("shared", "2.5.0")
            ))
        );

        assertEquals("UNSATISFIABLE_CONSTRAINTS", error.code());
        assertEquals("shared", error.moduleId());
        assertTrue(error.requirements().stream().anyMatch(req -> req.contains("root@1.0.0")));
        assertEquals(List.of("2.5.0", "1.5.0"), error.availableVersions());
        assertTrue(error.getMessage().contains("available versions"));
    }

    private static JsonObject manifest(String id, String version, JsonObject... deps) {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("id", id);
        manifest.addProperty("version", version);
        JsonArray dependencies = new JsonArray();
        for (JsonObject dep : deps) {
            dependencies.add(dep);
        }
        manifest.add("dependencies", dependencies);
        return manifest;
    }

    private static JsonObject dep(String id, String version) {
        JsonObject dep = new JsonObject();
        dep.addProperty("id", id);
        dep.addProperty("version", version);
        return dep;
    }

    private static PubGrubResolver.ModManifest manifestRecord(String id, String version,
                                                              PubGrubResolver.ModManifest.DepSpec... deps) {
        return new PubGrubResolver.ModManifest(id, version, java.util.List.of(deps));
    }

    private static PubGrubResolver.ModManifest.DepSpec depRecord(String id, String version) {
        return new PubGrubResolver.ModManifest.DepSpec(id, version);
    }
}
