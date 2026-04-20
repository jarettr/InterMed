package org.intermed.core.sandbox;

/**
 * Marker for host-side proxy objects that represent a guest entrypoint already
 * initialized inside a sandbox runtime.
 */
public interface SandboxedEntrypoint {

    SandboxExecutionResult lastSandboxResult();

    /**
     * Releases any resources held by this entrypoint instance.
     * Called by {@link org.intermed.core.sandbox.SupervisorNode} when the child is
     * stopped or restarted (ТЗ 3.5.7).
     *
     * <p>The default implementation is a no-op; sandbox implementations that hold
     * native resources (GraalVM contexts, Wasm instances) should override this.
     */
    default void teardown() {}
}
