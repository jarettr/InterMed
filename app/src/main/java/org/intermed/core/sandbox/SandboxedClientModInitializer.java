package org.intermed.core.sandbox;

import net.fabricmc.api.ClientModInitializer;

import java.util.Objects;

/**
 * Host-side proxy representing a Fabric client entrypoint executed in a
 * sandboxed runtime.
 */
public final class SandboxedClientModInitializer implements ClientModInitializer, SandboxedEntrypoint {

    private final SandboxExecutionResult result;

    public SandboxedClientModInitializer(SandboxExecutionResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    @Override
    public void onInitializeClient() {
        // The guest entrypoint already ran during lifecycle assembly.
    }

    @Override
    public SandboxExecutionResult lastSandboxResult() {
        return result;
    }
}
