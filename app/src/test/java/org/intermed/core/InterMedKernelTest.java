package org.intermed.core;

import org.junit.jupiter.api.Test;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterMedKernelTest {

    @Test
    void bootstrapFailsClosedWhenBootstrapSupportJarIsMissing() throws Exception {
        Path agentJar = Files.createTempFile("intermed-kernel-", ".jar");
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;

        try {
            IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> InterMedKernel.bootstrap(agentJar, null, "premain"));

            assertTrue(exception.getMessage().contains("bootstrap aborted"));
            Throwable cause = assertInstanceOf(IllegalStateException.class, exception.getCause());
            assertTrue(cause.getMessage().contains("Missing bootstrap support jar"));
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }
}
