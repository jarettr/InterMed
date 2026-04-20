package org.intermed.cli;

import org.intermed.core.InterMedVersion;
import org.intermed.core.db.DatabaseManager;
import org.intermed.core.metadata.RuntimeModIndex;
import org.intermed.core.monitor.ObservabilityMonitor;
import org.intermed.core.monitor.PerformanceMonitor;
import org.intermed.core.security.Capability;
import org.intermed.core.security.SecurityPolicy;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.stream.Collectors;

/**
 * Interactive runtime console for InterMed administrators.
 *
 * <p>Runs on a virtual thread so it never blocks the server tick loop.
 * All commands operate on live in-process state and persist capability
 * changes to the local SQLite database so they survive server restarts.
 *
 * <h3>Available commands</h3>
 * <pre>
 *   help                               — list commands
 *   status                             — TPS, CUSUM, tick EWMA
 *   mods                               — list all loaded mod IDs
 *   capabilities &lt;mod_id&gt;             — show granted capabilities for a mod
 *   grant   &lt;mod_id&gt; &lt;CAPABILITY&gt;    — grant a capability and persist it
 *   revoke  &lt;mod_id&gt; &lt;CAPABILITY&gt;    — revoke a capability and persist it
 *   throttled                          — list currently throttled mods
 *   quit / exit                        — stop the console thread
 * </pre>
 *
     * <p>Valid capability names match {@link Capability} enum constants:
     * {@code FILE_READ}, {@code FILE_WRITE}, {@code NETWORK_CONNECT},
     * {@code MEMORY_ACCESS}, {@code UNSAFE_ACCESS}, {@code REFLECTION_ACCESS}, {@code PROCESS_SPAWN},
     * {@code NATIVE_LIBRARY}.
     */
public final class InterMedCLI {

    private InterMedCLI() {}

    public static void startConsoleThread() {
        Thread.ofVirtual().name("InterMed-CLI").start(InterMedCLI::runLoop);
    }

    private static void runLoop() {
        println("\033[1;32m[CLI] InterMed console ready. Type 'help' for commands.\033[0m");
        Scanner scanner = new Scanner(System.in);

        while (true) {
            try {
                String line = scanner.nextLine();
                if (line == null) break; // stdin closed
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+");
                String cmd = parts[0].toLowerCase(Locale.ROOT);

                switch (cmd) {
                    case "help"         -> printHelp();
                    case "status"       -> printStatus();
                    case "mods"         -> printMods();
                    case "capabilities" -> printCapabilities(parts);
                    case "grant"        -> handleGrant(parts);
                    case "revoke"       -> handleRevoke(parts);
                    case "throttled"    -> printThrottled();
                    case "quit", "exit" -> {
                        println("[CLI] Console thread stopped.");
                        return;
                    }
                    default -> println("[CLI] Unknown command '" + cmd + "'. Type 'help'.");
                }
            } catch (Exception e) {
                System.err.println("[CLI] Error: " + e.getMessage());
            }
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    private static void printHelp() {
        println("┌─ InterMed CLI commands ──────────────────────────────────────────┐");
        println("│ help                              — this message                  │");
        println("│ status                            — TPS, EWMA tick, CUSUM stats   │");
        println("│ mods                              — list all loaded mod IDs        │");
        println("│ capabilities <mod_id>             — show capabilities for a mod    │");
        println("│ grant  <mod_id> <CAPABILITY>      — grant capability (persisted)   │");
        println("│ revoke <mod_id> <CAPABILITY>      — revoke capability (persisted)  │");
        println("│ throttled                         — list currently throttled mods  │");
        println("│ quit / exit                       — stop this console thread       │");
        println("└──────────────────────────────────────────────────────────────────┘");
        println("  Valid capabilities: " + Arrays.stream(Capability.values())
            .map(Capability::name).collect(Collectors.joining(", ")));
    }

    private static void printStatus() {
        double tps      = PerformanceMonitor.getTps();
        double cusumLow = PerformanceMonitor.getCusumLow();
        double cusumHigh= PerformanceMonitor.getCusumHigh();
        double tickMs   = ObservabilityMonitor.getEwmaTickTime();

        String tpsColor = tps >= 19.0 ? "\033[1;32m" : tps >= 15.0 ? "\033[1;33m" : "\033[1;31m";
        println("┌─ InterMed Status ───────────────────────────────────────────────┐");
        println("│ Version     : InterMed " + InterMedVersion.DISPLAY_VERSION);
        println("│ TPS (EWMA)  : " + tpsColor + String.format("%.2f", tps) + "\033[0m");
        println("│ Tick EWMA   : " + String.format("%.1f ms", tickMs));
        println("│ CUSUM low   : " + String.format("%.2f", cusumLow)
            + (cusumLow > 4.0 ? "  \033[1;31m[!]\033[0m" : ""));
        println("│ CUSUM high  : " + String.format("%.2f", cusumHigh));
        println("│ DB available: " + DatabaseManager.isAvailable());
        println("│ Mods loaded : " + RuntimeModIndex.allMods().size());
        println("└────────────────────────────────────────────────────────────────┘");
    }

    private static void printMods() {
        Collection<org.intermed.core.metadata.NormalizedModMetadata> mods = RuntimeModIndex.allMods();
        if (mods.isEmpty()) {
            println("[CLI] No mods loaded.");
            return;
        }
        println("[CLI] Loaded mods (" + mods.size() + "):");
        mods.stream()
            .sorted((a, b) -> a.id().compareToIgnoreCase(b.id()))
            .forEach(m -> println("  " + m.id() + "  " + (m.version() != null ? m.version() : "?")));
    }

    private static void printCapabilities(String[] parts) {
        if (parts.length < 2) {
            println("[CLI] Usage: capabilities <mod_id>");
            return;
        }
        String modId = parts[1];
        List<DatabaseManager.CapabilityRow> rows = DatabaseManager.loadCapabilitiesForMod(modId);
        if (rows.isEmpty()) {
            println("[CLI] No persisted capabilities for '" + modId + "'.");
            println("      (Manifest-declared permissions are not listed here.)");
            return;
        }
        println("[CLI] Persisted capabilities for '" + modId + "':");
        for (DatabaseManager.CapabilityRow row : rows) {
            String status = row.allowed() ? "\033[1;32mGRANTED\033[0m" : "\033[1;31mREVOKED\033[0m";
            println("  " + row.capability() + "  →  " + status);
        }
    }

    private static void handleGrant(String[] parts) {
        if (parts.length < 3) {
            println("[CLI] Usage: grant <mod_id> <CAPABILITY>");
            return;
        }
        String modId      = parts[1];
        String capName    = parts[2].toUpperCase(Locale.ROOT);
        Capability cap    = parseCapability(capName);
        if (cap == null) return;

        SecurityPolicy.grantCapability(modId, cap);
        boolean persisted = DatabaseManager.upsertCapability(modId, cap.name(), true);
        println("\033[1;32m[CLI] Granted " + cap + " to '" + modId + "'"
            + (persisted ? " (persisted)" : " (in-memory only — DB unavailable)") + ".\033[0m");
    }

    private static void handleRevoke(String[] parts) {
        if (parts.length < 3) {
            println("[CLI] Usage: revoke <mod_id> <CAPABILITY>");
            return;
        }
        String modId   = parts[1];
        String capName = parts[2].toUpperCase(Locale.ROOT);
        Capability cap = parseCapability(capName);
        if (cap == null) return;

        SecurityPolicy.revokeCapability(modId, cap);
        boolean persisted = DatabaseManager.upsertCapability(modId, cap.name(), false);
        println("\033[1;33m[CLI] Revoked " + cap + " from '" + modId + "'"
            + (persisted ? " (persisted)" : " (in-memory only — DB unavailable)") + ".\033[0m");
    }

    private static void printThrottled() {
        List<String> modIds = RuntimeModIndex.allMods().stream()
            .map(org.intermed.core.metadata.NormalizedModMetadata::id)
            .filter(ObservabilityMonitor::isModThrottled)
            .sorted()
            .toList();
        if (modIds.isEmpty()) {
            println("[CLI] No mods are currently throttled.");
        } else {
            println("[CLI] Throttled mods (" + modIds.size() + "):");
            modIds.forEach(id -> println("  " + id));
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Capability parseCapability(String name) {
        try {
            return Capability.valueOf(name);
        } catch (IllegalArgumentException e) {
            println("[CLI] Unknown capability '" + name + "'. Valid values: "
                + Arrays.stream(Capability.values()).map(Capability::name)
                    .collect(Collectors.joining(", ")));
            return null;
        }
    }

    private static void println(String msg) {
        System.out.println(msg);
    }
}
