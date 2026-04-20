package org.intermed.harness;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * All runtime settings for the test harness. Built by {@link HarnessMain} from
 * CLI flags and an optional {@code harness.properties} file; immutable once
 * constructed.
 */
public final class HarnessConfig {

    public enum TestMode {
        /** Test every mod in isolation (fastest). */
        SINGLE,
        /** Test every pair of the top-N mods (n*(n-1)/2 pairs). */
        PAIRS,
        /** SINGLE + PAIRS + popular-pack combos. */
        FULL
    }

    public enum LoaderFilter {
        ALL, FORGE, FABRIC
    }

    // ── Minecraft / loader versions ────────────────────────────────────────────
    public final String mcVersion;
    /** Forge build number for mcVersion, e.g. "47.3.0". */
    public final String forgeVersion;

    // ── Discovery ──────────────────────────────────────────────────────────────
    /** How many top-downloaded mods to fetch from Modrinth. */
    public final int topN;
    /** Filter mods by loader; default ALL means any compatible loader is included. */
    public final LoaderFilter loaderFilter;

    // ── Execution ──────────────────────────────────────────────────────────────
    public final TestMode mode;
    /** Seconds to wait for the server to print "Done (" before declaring TIMEOUT. */
    public final int timeoutSeconds;
    /** Maximum number of server processes to run in parallel. */
    public final int concurrency;
    /** JVM heap for each spawned test server, in megabytes. */
    public final int heapMb;

    // ── Paths ──────────────────────────────────────────────────────────────────
    /** Root directory for all harness artefacts (cache, runs, report). */
    public final Path outputDir;
    /** Path to the InterMed fat JAR (used as -javaagent). */
    public final Path intermedJar;
    /** Java executable to use when launching test servers. */
    public final String javaExecutable;

    // ── Skip flags (allow resuming an interrupted run) ─────────────────────────
    public final boolean skipBootstrap;
    public final boolean skipDiscover;
    public final boolean skipRun;

    // ── Mod exclusions ─────────────────────────────────────────────────────────
    /** Modrinth project slugs to skip (known incompatible, client-only, etc.). */
    public final List<String> excludeSlugs;

    // ── Pairs phase settings ───────────────────────────────────────────────────
    /** Limit for pair-testing: only the top-K mods from SINGLE pass are paired. */
    public final int pairsTopK;

    private HarnessConfig(Builder b) {
        this.mcVersion       = b.mcVersion;
        this.forgeVersion    = b.forgeVersion;
        this.topN            = b.topN;
        this.loaderFilter    = b.loaderFilter;
        this.mode            = b.mode;
        this.timeoutSeconds  = b.timeoutSeconds;
        this.concurrency     = b.concurrency;
        this.heapMb          = b.heapMb;
        this.outputDir       = b.outputDir.toAbsolutePath().normalize();
        this.intermedJar     = b.intermedJar.toAbsolutePath().normalize();
        this.javaExecutable  = b.javaExecutable;
        this.skipBootstrap   = b.skipBootstrap;
        this.skipDiscover    = b.skipDiscover;
        this.skipRun         = b.skipRun;
        this.excludeSlugs    = List.copyOf(b.excludeSlugs);
        this.pairsTopK       = b.pairsTopK;
    }

    // ── Derived paths ──────────────────────────────────────────────────────────

    public Path cacheDir()        { return outputDir.resolve("cache"); }
    public Path modsCache()       { return cacheDir().resolve("mods"); }
    public Path serverBaseForge() { return cacheDir().resolve("server-base-forge"); }
    public Path serverBaseFabric(){ return cacheDir().resolve("server-base-fabric"); }
    public Path runsDir()         { return outputDir.resolve("runs"); }
    public Path reportDir()       { return outputDir.resolve("report"); }

    // ── Builder ────────────────────────────────────────────────────────────────

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        String mcVersion      = "1.20.1";
        String forgeVersion   = "47.3.0";
        int topN              = 1000;
        LoaderFilter loaderFilter = LoaderFilter.ALL;
        TestMode mode         = TestMode.SINGLE;
        int timeoutSeconds    = 120;
        int concurrency       = 4;
        int heapMb            = 2048;
        Path outputDir        = Paths.get("harness-output");
        Path intermedJar      = Paths.get("intermed.jar");
        String javaExecutable = "java";
        boolean skipBootstrap = false;
        boolean skipDiscover  = false;
        boolean skipRun       = false;
        List<String> excludeSlugs = new ArrayList<>();
        int pairsTopK         = 50;

        public Builder mcVersion(String v)          { mcVersion = v;        return this; }
        public Builder forgeVersion(String v)       { forgeVersion = v;     return this; }
        public Builder topN(int n)                  { topN = n;             return this; }
        public Builder loaderFilter(LoaderFilter f) { loaderFilter = f;     return this; }
        public Builder mode(TestMode m)             { mode = m;             return this; }
        public Builder timeoutSeconds(int t)        { timeoutSeconds = t;   return this; }
        public Builder concurrency(int c)           { concurrency = c;      return this; }
        public Builder heapMb(int m)                { heapMb = m;           return this; }
        public Builder outputDir(Path p)            { outputDir = p;        return this; }
        public Builder intermedJar(Path p)          { intermedJar = p;      return this; }
        public Builder javaExecutable(String e)     { javaExecutable = e;   return this; }
        public Builder skipBootstrap(boolean v)     { skipBootstrap = v;    return this; }
        public Builder skipDiscover(boolean v)      { skipDiscover = v;     return this; }
        public Builder skipRun(boolean v)           { skipRun = v;          return this; }
        public Builder exclude(String slug)         { excludeSlugs.add(slug); return this; }
        public Builder pairsTopK(int k)             { pairsTopK = k;        return this; }

        public HarnessConfig build() {
            if (!intermedJar.toFile().exists()) {
                System.err.println("[WARN] InterMed JAR not found at: " + intermedJar +
                    " — set --intermed-jar=/path/to/intermed.jar");
            }
            return new HarnessConfig(this);
        }
    }
}
