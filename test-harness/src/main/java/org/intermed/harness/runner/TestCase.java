package org.intermed.harness.runner;

import org.intermed.harness.discovery.ModCandidate;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Describes a single test scenario: which mods to load together, under which
 * loader baseline (Forge / Fabric / NeoForge).
 */
public record TestCase(
    /** Unique identifier used for directory names and report keys. */
    String id,
    /** Human-readable description shown in the report. */
    String description,
    /** The mods included in this test. */
    List<ModCandidate> mods,
    /** Server baseline to use for this test. */
    Loader loader
) {
    public enum Loader { FORGE, FABRIC, NEOFORGE }

    /** Convenience factory: single mod, auto-selects loader. */
    public static TestCase single(ModCandidate mod) {
        Loader loader = mod.supportsAnyLoader(List.of("forge"))
            ? Loader.FORGE
            : mod.supportsAnyLoader(List.of("neoforge"))
                ? Loader.NEOFORGE
                : Loader.FABRIC;
        String id = "single-" + mod.slug() + "-" + loader.name().toLowerCase();
        return new TestCase(id, "Single: " + mod.label(), List.of(mod), loader);
    }

    /** Convenience factory: two mods, loader must match. */
    public static TestCase pair(ModCandidate a, ModCandidate b, Loader loader) {
        String id = "pair-" + a.slug() + "-" + b.slug() + "-" + loader.name().toLowerCase();
        String desc = "Pair: " + a.label() + " + " + b.label();
        return new TestCase(id, desc, List.of(a, b), loader);
    }

    /** Convenience factory: arbitrary mod list. */
    public static TestCase pack(String packName, List<ModCandidate> mods, Loader loader) {
        String id = "pack-" + packName.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase();
        String slugList = mods.stream().map(ModCandidate::slug).collect(Collectors.joining("+"));
        return new TestCase(id, "Pack[" + packName + "]: " + slugList, List.copyOf(mods), loader);
    }

    /** Convenience factory: curated alpha proof slice, separate from FULL pack mode. */
    public static TestCase slice(String sliceName, List<ModCandidate> mods, Loader loader) {
        String id = "slice-" + sliceName.replaceAll("[^a-zA-Z0-9]", "-").toLowerCase();
        String slugList = mods.stream().map(ModCandidate::slug).collect(Collectors.joining("+"));
        return new TestCase(id, "Slice[" + sliceName + "]: " + slugList, List.copyOf(mods), loader);
    }

    /** Number of mods in this test. */
    public int modCount() { return mods.size(); }
}
