package org.intermed.core.sandbox;

import net.fabricmc.api.DedicatedServerModInitializer;

import java.util.Objects;

/**
 * Host-side proxy representing a Fabric dedicated-server entrypoint executed
 * in a sandboxed runtime.
 */
public final class SandboxedServerModInitializer implements DedicatedServerModInitializer, SandboxedEntrypoint {

    private final SandboxExecutionResult result;

    public SandboxedServerModInitializer(SandboxExecutionResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    @Override
    public void onInitializeServer() {
        // The guest entrypoint already ran during lifecycle assembly.
    }

    @Override
    public SandboxExecutionResult lastSandboxResult() {
        return result;
    }
}
