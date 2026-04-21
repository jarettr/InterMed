package org.intermed.harness.bootstrap;

import org.intermed.harness.HarnessConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Orchestrates the one-time environment setup:
 * <ol>
 *   <li>Downloads the vanilla Minecraft server JAR from Mojang's CDN.</li>
 *   <li>Installs the Forge server base (runs the Forge installer headlessly).</li>
 *   <li>Installs the NeoForge server base (runs the NeoForge installer headlessly).</li>
 *   <li>Installs the Fabric server base (downloads fabric-server-launch.jar).</li>
 * </ol>
 *
 * <p>All artefacts are stored under {@code config.cacheDir()} and re-used on
 * subsequent runs via presence markers. Re-run the {@code bootstrap} command
 * only when upgrading to a new MC or loader version.
 */
public final class EnvironmentBootstrap {

    private final HarnessConfig config;

    public EnvironmentBootstrap(HarnessConfig config) {
        this.config = config;
    }

    /**
     * Run the full bootstrap. Safe to call multiple times; already-present
     * artefacts are skipped.
     *
     * @throws BootstrapException if any required download or installation fails
     */
    public void run() throws BootstrapException {
        System.out.println("=== InterMed Test Harness — Bootstrap ===");
        System.out.println("MC version : " + config.mcVersion);
        System.out.println("Forge      : " + config.forgeVersion);
        System.out.println("NeoForge   : " + config.neoforgeVersion);
        System.out.println("Output dir : " + config.outputDir);
        System.out.println();

        try {
            Files.createDirectories(config.cacheDir());
            Files.createDirectories(config.modsCache());

            // 1. Vanilla server JAR (needed by Forge installer for library mapping)
            var fetcher = new VanillaServerFetcher();
            fetcher.fetch(config.mcVersion, config.cacheDir());

            // 2. Forge server base
            if (config.loaderFilter == HarnessConfig.LoaderFilter.ALL
                    || config.loaderFilter == HarnessConfig.LoaderFilter.FORGE) {
                var forgeInstaller = new ForgeServerInstaller();
                forgeInstaller.install(
                    config.mcVersion,
                    config.forgeVersion,
                    config.cacheDir(),
                    config.serverBaseForge(),
                    config.javaExecutable
                );
                // Write the eula + default server.properties for Forge base
                writeCommonServerFiles(config.serverBaseForge());
            }

            // 3. NeoForge server base
            if (config.loaderFilter == HarnessConfig.LoaderFilter.ALL
                    || config.loaderFilter == HarnessConfig.LoaderFilter.NEOFORGE) {
                var neoForgeInstaller = new NeoForgeServerInstaller();
                neoForgeInstaller.install(
                    config.mcVersion,
                    config.neoforgeVersion,
                    config.cacheDir(),
                    config.serverBaseNeoForge(),
                    config.javaExecutable
                );
                writeCommonServerFiles(config.serverBaseNeoForge());
            }

            // 4. Fabric server base
            if (config.loaderFilter == HarnessConfig.LoaderFilter.ALL
                    || config.loaderFilter == HarnessConfig.LoaderFilter.FABRIC) {
                var fabricInstaller = new FabricServerInstaller();
                fabricInstaller.install(
                    config.mcVersion,
                    config.cacheDir(),
                    config.serverBaseFabric(),
                    config.javaExecutable
                );
            }

            System.out.println("\n=== Bootstrap complete ===\n");

        } catch (IOException | InterruptedException e) {
            throw new BootstrapException("Bootstrap failed: " + e.getMessage(), e);
        }
    }

    private void writeCommonServerFiles(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path eula = dir.resolve("eula.txt");
        if (!Files.exists(eula)) {
            Files.writeString(eula, "eula=true\n");
        }
        Path props = dir.resolve("server.properties");
        if (!Files.exists(props)) {
            Files.writeString(props,
                "online-mode=false\n"
                + "level-type=flat\n"
                + "generate-structures=false\n"
                + "spawn-monsters=false\n"
                + "spawn-npcs=false\n"
                + "spawn-animals=false\n"
                + "view-distance=4\n"
                + "simulation-distance=4\n"
            );
        }
    }

    /** Wraps any bootstrap failure for clean error reporting in the CLI. */
    public static final class BootstrapException extends Exception {
        public BootstrapException(String msg, Throwable cause) { super(msg, cause); }
    }
}
