package org.intermed.core.resolver;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class VirtualDependencyMapTest {

    @Test
    void resolvesKnownVirtualDependenciesToBridgeTargetsWithBaselines() {
        assertVirtual("fabric-api", "intermed-fabric-bridge");
        assertVirtual("forge", "intermed-forge-bridge");
        assertVirtual("neoforge", "intermed-neoforge-bridge");
        assertVirtual("minecraft", "intermed-minecraft-runtime");
        assertVirtual("java", "intermed-java-runtime");
    }

    @Test
    void preservesDeclaredConstraintsForVirtualBridgeResolution() {
        assertEquals("[21,)", VirtualDependencyMap.effectiveConstraint("neoforge", "[21,)"));
        assertEquals(">=1.20.1", VirtualDependencyMap.effectiveConstraint("minecraft", ">=1.20.1"));
        assertEquals(">=21", VirtualDependencyMap.effectiveConstraint("java", ">=21"));
    }

    @Test
    void prefersDiscoveredConcreteFabricApiVersionForBridgeCompatibility() {
        assertEquals(
            "0.92.3+1.20.1",
            VirtualDependencyMap.bridgeCompatibilityVersionForBridge(
                "intermed-fabric-bridge",
                Map.of("fabric-api", "0.92.3+1.20.1")
            )
        );
    }

    @Test
    void leavesUnknownDependenciesUntouched() {
        assertEquals("some-library", VirtualDependencyMap.substituteChecked("some-library"));
        assertEquals("^1.2.0", VirtualDependencyMap.effectiveConstraint("some-library", "^1.2.0"));
    }

    private static void assertVirtual(String dependencyId, String expectedBridgeId) {
        assertEquals(expectedBridgeId, VirtualDependencyMap.substituteChecked(dependencyId));
        assertNotEquals(dependencyId, VirtualDependencyMap.substituteChecked(dependencyId));
        assertFalse(VirtualDependencyMap.bridgeCompatibilityVersionForBridge(expectedBridgeId).isBlank());
    }
}
