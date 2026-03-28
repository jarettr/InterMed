package org.intermed.core.db;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class DatabaseManager {
    private static Connection connection;

    public static void initialize() {
        try {
            // Force load the driver
            Class.forName("org.sqlite.JDBC");

            Path dbPath = Paths.get(System.getProperty("user.home"), ".intermed", "intermed_v8.db");
            File dbDir = dbPath.getParent().toFile();
            if (!dbDir.exists()) dbDir.mkdirs();

            String url = "jdbc:sqlite:" + dbPath.toAbsolutePath().toString();
            connection = DriverManager.getConnection(url);

            System.out.println("[Database] Local SQLite storage connected.");

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("CREATE TABLE IF NOT EXISTS mod_capabilities (mod_id TEXT, capability TEXT, is_allowed INTEGER, PRIMARY KEY (mod_id, capability))");
                stmt.execute("CREATE TABLE IF NOT EXISTS aot_index (class_name TEXT PRIMARY KEY, hash TEXT, file_path TEXT)");
            }
            System.out.println("[Database] Schema sync: SUCCESS.");
        } catch (Exception e) {
            System.err.println("[Database] FAILED TO INIT: " + e.getMessage());
        }
    }

    public static Connection getConnection() {
        return connection;
    }
}