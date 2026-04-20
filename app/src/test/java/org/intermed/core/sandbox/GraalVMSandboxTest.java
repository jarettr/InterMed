package org.intermed.core.sandbox;

import org.graalvm.polyglot.SandboxPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("strict-security")
class GraalVMSandboxTest {

    @BeforeEach
    void reset() {
        GraalVMSandbox.resetForTests();
    }

    @AfterEach
    void tearDown() {
        GraalVMSandbox.resetForTests();
    }

    @Test
    void usesSecureByDefaultEspressoPolicy() {
        assertEquals(SandboxPolicy.CONSTRAINED, GraalVMSandbox.sandboxPolicy());
        assertFalse(GraalVMSandbox.isNativeAccessAllowed());

        GraalVMSandbox sandbox = new GraalVMSandbox("secure-defaults");
        assertTrue(sandbox.diagnostics().contains("policy=CONSTRAINED"));
        assertTrue(sandbox.diagnostics().contains("nativeAccess=false"));
        sandbox.close();
    }

    @Test
    void defaultModIdIsUnknownForBlankConstruction() {
        GraalVMSandbox withNull = new GraalVMSandbox(null);
        GraalVMSandbox withBlank = new GraalVMSandbox("   ");
        try {
            assertTrue(withNull.diagnostics().contains("mod=unknown"));
            assertTrue(withBlank.diagnostics().contains("mod=unknown"));
        } finally {
            withNull.close();
            withBlank.close();
        }
    }

    @Test
    void diagnosticsContainProvidedModId() {
        GraalVMSandbox sandbox = new GraalVMSandbox("my-test-mod");
        try {
            assertTrue(sandbox.diagnostics().contains("mod=my-test-mod"),
                "diagnostics should include the modId: " + sandbox.diagnostics());
        } finally {
            sandbox.close();
        }
    }

    @Test
    void closedSandboxIsNoLongerInitialized() {
        GraalVMSandbox sandbox = new GraalVMSandbox("close-test");
        sandbox.close();
        assertFalse(sandbox.isInitialized(),
            "sandbox must not be initialized after close()");
    }

    @Test
    void twoSandboxesHaveIndependentModIds() {
        GraalVMSandbox alpha = new GraalVMSandbox("mod-alpha");
        GraalVMSandbox beta  = new GraalVMSandbox("mod-beta");
        try {
            assertTrue(alpha.diagnostics().contains("mod=mod-alpha"));
            assertTrue(beta.diagnostics().contains("mod=mod-beta"));
            assertFalse(alpha.diagnostics().contains("mod-beta"),
                "alpha sandbox must not contain beta's modId");
            assertFalse(beta.diagnostics().contains("mod-alpha"),
                "beta sandbox must not contain alpha's modId");
        } finally {
            alpha.close();
            beta.close();
        }
    }

    @Test
    void failsClosedWithDescriptiveMessageWhenEspressoUnavailable() {
        GraalVMSandbox sandbox = new GraalVMSandbox("espresso-probe");
        boolean initialized = sandbox.initialize();
        // Either succeeds (real Espresso present) or fails with a descriptive message.
        // Never silently returns false with a blank message.
        String msg = sandbox.initializationMessage();
        assertNotNull(msg, "initializationMessage must not be null");
        assertFalse(msg.isBlank(), "initializationMessage must not be blank after initialize()");
        if (!initialized) {
            assertTrue(
                msg.contains("espresso") || msg.contains("polyglot") || msg.contains("failed")
                    || msg.contains("rejected") || msg.contains("unavailable"),
                "Failure message must be descriptive, got: " + msg
            );
        } else {
            assertEquals("ready", msg);
        }
        sandbox.close();
    }

    @Test
    void probeAvailabilityIsCachedAcrossCalls() {
        GraalVMSandbox.HostStatus first  = GraalVMSandbox.probeAvailability();
        GraalVMSandbox.HostStatus second = GraalVMSandbox.probeAvailability();
        assertNotNull(first);
        assertSame(first, second, "probeAvailability() must return the same cached instance");
    }

    @Test
    void probeAvailabilityStatusIsCoherent() {
        GraalVMSandbox.HostStatus status = GraalVMSandbox.probeAvailability();
        assertNotNull(status.state(), "HostStatus state must not be null");
        assertFalse(status.state().isBlank(), "HostStatus state must not be blank");
        // javaLanguageAvailable can only be true if the host is also available.
        if (status.javaLanguageAvailable()) {
            assertTrue(status.hostAvailable(),
                "javaLanguageAvailable=true requires hostAvailable=true");
        }
    }

    @Test
    void executeEntrypointReturnsFailureForBlankEntrypointClass() {
        GraalVMSandbox sandbox = new GraalVMSandbox("blank-ep-test");
        try {
            GraalVMSandbox.EspressoExecutionResult result =
                sandbox.executeEntrypoint(null, "   ", null, true);
            assertFalse(result.success());
            assertTrue(result.message().contains("entrypoint-class-missing"),
                "Expected entrypoint-class-missing, got: " + result.message());
        } finally {
            sandbox.close();
        }
    }

    @Test
    void executeEntrypointReturnsFailureForNullEntrypointClass() {
        GraalVMSandbox sandbox = new GraalVMSandbox("null-ep-test");
        try {
            GraalVMSandbox.EspressoExecutionResult result =
                sandbox.executeEntrypoint(null, null, "onLoad", false);
            assertFalse(result.success());
        } catch (NullPointerException e) {
            // also acceptable — contract says entrypointClass must not be null
        } finally {
            sandbox.close();
        }
    }

    @Test
    void diagnosticsContainHostContractDigest() {
        GraalVMSandbox sandbox = new GraalVMSandbox("contract-test");
        try {
            assertTrue(sandbox.diagnostics().contains("hostContractDigest="),
                "diagnostics must include the host contract digest: " + sandbox.diagnostics());
        } finally {
            sandbox.close();
        }
    }
}
