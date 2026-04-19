package org.intermed.core.sandbox;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("strict-security")
class WitContractCatalogTest {

    @Test
    void exportsOnlyWitCompatibleSandboxApiMethods() {
        Set<String> exported = WitContractCatalog.hostFunctions().stream()
            .map(WitContractCatalog.WitFunction::name)
            .collect(java.util.stream.Collectors.toSet());

        assertTrue(exported.contains("current-mod-id"));
        assertTrue(exported.contains("current-sandbox-mode"));
        assertTrue(exported.contains("current-sandbox-mode-id"));
        assertTrue(exported.contains("has-current-capability"));
        assertTrue(exported.contains("current-tps"));
        assertFalse(exported.contains("registry-get"));
        assertFalse(exported.contains("registry-get-by-id"));
        assertFalse(exported.contains("registry-raw-id"));
    }

    @Test
    void publishesStableContractDigestAndGeneratedJavaBindings() {
        String digest = WitContractCatalog.contractDigest();
        String bindings = WitContractCatalog.renderJavaBindings();
        String secondDigest = WitContractCatalog.contractDigest();

        assertEquals(64, digest.length());
        assertEquals(digest, secondDigest);
        assertTrue(bindings.contains("class InterMedSandboxHost"));
        assertTrue(bindings.contains("currentModId()"));
        assertTrue(bindings.contains("sandboxHostContractDigest()"));
        assertEquals("org.intermed.sandbox.guest.InterMedSandboxHost", WitContractCatalog.defaultJavaBindingsClassName());
    }
}
