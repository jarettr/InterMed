package org.intermed.core.db;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin SQLite persistence layer for InterMed runtime state.
 *
 * <h3>Tables</h3>
 * <ul>
 *   <li>{@code mod_capabilities} — capability grants/revocations applied at
 *       runtime via the CLI.  Loaded back into {@link org.intermed.core.security.SecurityPolicy}
 *       on every boot so CLI changes survive server restarts.</li>
 *   <li>{@code aot_index} — AOT bytecode cache index (class name → hash → path).
 *       Used by {@link org.intermed.core.cache.AOTCacheManager} for fast miss-detection
 *       without hitting the filesystem.</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * All public methods synchronize on the class monitor.  SQLite's default
 * journal mode is safe for single-writer / single-connection use; no
 * additional locking is required.
 */
public final class DatabaseManager {

    private static final Path DB_PATH =
        Paths.get(System.getProperty("user.home"), ".intermed", "intermed_v8.db");

    private static Connection connection;

    private DatabaseManager() {}

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public static synchronized void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");

            Path dbDir = DB_PATH.getParent();
            if (!Files.exists(dbDir)) {
                Files.createDirectories(dbDir);
            }

            String url = "jdbc:sqlite:" + DB_PATH.toAbsolutePath();
            connection = DriverManager.getConnection(url);

            // Improve write throughput without sacrificing crash-safety.
            try (Statement s = connection.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=NORMAL");
            }

            applySchema();
            System.out.println("[Database] Connected: " + DB_PATH);
        } catch (Exception e) {
            System.err.println("[Database] FAILED TO INIT: " + e.getMessage());
        }
    }

    private static void applySchema() throws Exception {
        try (Statement s = connection.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS mod_capabilities (
                    mod_id      TEXT    NOT NULL,
                    capability  TEXT    NOT NULL,
                    is_allowed  INTEGER NOT NULL DEFAULT 1,
                    granted_at  TEXT    NOT NULL DEFAULT (datetime('now')),
                    PRIMARY KEY (mod_id, capability)
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS aot_index (
                    class_name  TEXT PRIMARY KEY,
                    hash        TEXT NOT NULL,
                    file_path   TEXT NOT NULL
                )""");
        }
        System.out.println("[Database] Schema sync: SUCCESS.");
    }

    /**
     * Closes the database connection.  Safe to call even if {@link #initialize()}
     * was never called or failed.
     */
    public static synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                System.err.println("[Database] Error closing connection: " + e.getMessage());
            } finally {
                connection = null;
            }
        }
    }

    // ── Capability persistence ────────────────────────────────────────────────

    /**
     * Persists a capability grant (or revocation) for a mod.
     *
     * @param modId      mod id
     * @param capability capability name, e.g. {@code "FILE_READ"}
     * @param allowed    {@code true} = grant, {@code false} = revoke
     * @return {@code true} on success
     */
    public static synchronized boolean upsertCapability(String modId, String capability, boolean allowed) {
        if (!isAvailable()) return false;
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO mod_capabilities (mod_id, capability, is_allowed, granted_at)
                VALUES (?, ?, ?, datetime('now'))
                ON CONFLICT(mod_id, capability) DO UPDATE SET
                    is_allowed = excluded.is_allowed,
                    granted_at = excluded.granted_at
                """)) {
            ps.setString(1, modId);
            ps.setString(2, capability);
            ps.setInt(3, allowed ? 1 : 0);
            ps.executeUpdate();
            return true;
        } catch (Exception e) {
            System.err.println("[Database] upsertCapability failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Removes a capability row entirely (as opposed to marking it revoked).
     *
     * @return {@code true} if a row was deleted
     */
    public static synchronized boolean deleteCapability(String modId, String capability) {
        if (!isAvailable()) return false;
        try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM mod_capabilities WHERE mod_id = ? AND capability = ?")) {
            ps.setString(1, modId);
            ps.setString(2, capability);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            System.err.println("[Database] deleteCapability failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Returns all persisted capability rows, ordered by mod id then capability.
     * Used at boot to restore CLI-granted permissions into {@link org.intermed.core.security.SecurityPolicy}.
     */
    public static synchronized List<CapabilityRow> loadAllCapabilities() {
        List<CapabilityRow> rows = new ArrayList<>();
        if (!isAvailable()) return rows;
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT mod_id, capability, is_allowed FROM mod_capabilities ORDER BY mod_id, capability")) {
            while (rs.next()) {
                rows.add(new CapabilityRow(
                    rs.getString("mod_id"),
                    rs.getString("capability"),
                    rs.getInt("is_allowed") == 1
                ));
            }
        } catch (Exception e) {
            System.err.println("[Database] loadAllCapabilities failed: " + e.getMessage());
        }
        return rows;
    }

    /**
     * Returns all capability rows for a single mod.
     */
    public static synchronized List<CapabilityRow> loadCapabilitiesForMod(String modId) {
        List<CapabilityRow> rows = new ArrayList<>();
        if (!isAvailable() || modId == null) return rows;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT mod_id, capability, is_allowed FROM mod_capabilities WHERE mod_id = ? ORDER BY capability")) {
            ps.setString(1, modId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new CapabilityRow(
                        rs.getString("mod_id"),
                        rs.getString("capability"),
                        rs.getInt("is_allowed") == 1
                    ));
                }
            }
        } catch (Exception e) {
            System.err.println("[Database] loadCapabilitiesForMod failed: " + e.getMessage());
        }
        return rows;
    }

    // ── AOT index ─────────────────────────────────────────────────────────────

    public static synchronized boolean upsertAotEntry(String className, String hash, String filePath) {
        if (!isAvailable()) return false;
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO aot_index (class_name, hash, file_path) VALUES (?, ?, ?)
                ON CONFLICT(class_name) DO UPDATE SET hash = excluded.hash, file_path = excluded.file_path
                """)) {
            ps.setString(1, className);
            ps.setString(2, hash);
            ps.setString(3, filePath);
            ps.executeUpdate();
            return true;
        } catch (Exception e) {
            System.err.println("[Database] upsertAotEntry failed: " + e.getMessage());
            return false;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public static synchronized boolean isAvailable() {
        return connection != null;
    }

    public static synchronized Connection getConnection() {
        return connection;
    }

    // ── Value types ───────────────────────────────────────────────────────────

    /**
     * A single row from {@code mod_capabilities}.
     *
     * @param modId      mod id
     * @param capability capability name
     * @param allowed    {@code true} = granted, {@code false} = explicitly revoked
     */
    public record CapabilityRow(String modId, String capability, boolean allowed) {}
}
