package org.intermed.core.registry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryCompatibilityContractTest {

    @Test
    void exposesBinaryOwnerSetsForPayloadAndFacadeSurfaces() {
        assertTrue(RegistryCompatibilityContract.payloadLookupBinaryOwners().contains("net.minecraft.core.Registry"));
        assertTrue(RegistryCompatibilityContract.payloadLookupBinaryOwners().contains("net.minecraft.core.DefaultedRegistry"));
        assertTrue(RegistryCompatibilityContract.facadeBinaryOwners().contains("net.minecraft.core.RegistryAccess"));
        assertTrue(RegistryCompatibilityContract.facadeBinaryOwners().contains("net.minecraft.resources.BuiltInRegistries"));
    }
}
