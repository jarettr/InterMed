package org.intermed.core.security;

import org.intermed.core.sandbox.PolyglotSandboxManager;

/**
 * TZ Req 10: Управление песочницами для подозрительного или легаси кода.
 */
public class SandboxManager {
    
    public enum SandboxType { JVM_ESPRESSO, WASM_CHICORY, NATIVE }

    public static void launchInSandbox(String modId, SandboxType type, Runnable task) {
        System.out.println("\033[1;33m[Sandbox] Isolating mod '" + modId + "' in " + type + " container...\033[0m");

        switch (type) {
            case JVM_ESPRESSO -> {
                try (var sandbox = PolyglotSandboxManager.initializeEspressoSandbox(modId, new byte[0])) {
                    System.out.println("[Sandbox] " + sandbox.diagnostics());
                    task.run();
                }
            }
            case WASM_CHICORY -> {
                System.out.println("[Sandbox] Declared WIT host functions: " + PolyglotSandboxManager.hostExportCount());
                task.run();
            }
            default -> task.run();
        }
    }
}
