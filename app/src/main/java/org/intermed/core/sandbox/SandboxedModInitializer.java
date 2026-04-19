package org.intermed.core.sandbox;

import net.fabricmc.api.ModInitializer;

import java.util.Objects;

/**
 * Host-side proxy representing a Fabric main entrypoint that has already been
 * executed inside Espresso or Wasm.
 */
public final class SandboxedModInitializer implements ModInitializer, SandboxedEntrypoint {

    private final SandboxExecutionResult result;

    public SandboxedModInitializer(SandboxExecutionResult result) {
        this.result = Objects.requireNonNull(result, "result");
    }

    @Override
    public void onInitialize() {
        // The guest entrypoint already ran during lifecycle assembly.
    }

    @Override
    public SandboxExecutionResult lastSandboxResult() {
        return result;
    }
}
