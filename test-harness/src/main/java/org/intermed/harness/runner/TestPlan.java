package org.intermed.harness.runner;

import org.intermed.harness.HarnessConfig;
import org.intermed.harness.discovery.ModCandidate;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the list of {@link TestCase}s to execute according to the
 * configured {@link HarnessConfig.TestMode}.
 *
 * <p>Modes:
 * <ul>
 *   <li><b>SINGLE</b> — one test per mod, in isolation.</li>
 *   <li><b>PAIRS</b> — SINGLE + every pair of the top-K mods that share a loader.</li>
 *   <li><b>FULL</b> — PAIRS + popular-pack combos (top-20 mods together).</li>
 * </ul>
 *
 * <p>For PAIRS the combinatorial count is kept manageable:
 * {@code k*(k-1)/2} pairs where {@code k = config.pairsTopK} (default 50 → 1225 pairs).
 */
public final class TestPlan {

    private final HarnessConfig config;
    private final List<ModCandidate> mods;

    public TestPlan(HarnessConfig config, List<ModCandidate> mods) {
        this.config = config;
        this.mods   = List.copyOf(mods);
    }

    public List<TestCase> generate() {
        List<TestCase> plan = new ArrayList<>();

        // Phase 1 — single mod tests
        for (ModCandidate mod : mods) {
            plan.addAll(singleCasesFor(mod));
        }

        if (config.mode == HarnessConfig.TestMode.SINGLE) {
            System.out.printf("[Plan] SINGLE mode: %d test cases.%n", plan.size());
            return plan;
        }

        // Phase 2 — pairs (top-K only to keep combinatorial count manageable)
        int k = Math.min(config.pairsTopK, mods.size());
        List<ModCandidate> topK = mods.subList(0, k);
        for (int i = 0; i < topK.size(); i++) {
            for (int j = i + 1; j < topK.size(); j++) {
                ModCandidate a = topK.get(i);
                ModCandidate b = topK.get(j);
                // Only pair mods that share at least one loader
                for (TestCase.Loader loader : sharedLoaders(a, b)) {
                    plan.add(TestCase.pair(a, b, loader));
                }
            }
        }

        if (config.mode == HarnessConfig.TestMode.PAIRS) {
            System.out.printf("[Plan] PAIRS mode: %d test cases.%n", plan.size());
            return plan;
        }

        // Phase 3 (FULL) — popular pack combos
        // Pack A: top 10 Forge mods together
        List<ModCandidate> topForge = topK.stream()
            .filter(m -> m.supportsAnyLoader(List.of("forge")))
            .limit(10)
            .toList();
        if (topForge.size() >= 2) {
            plan.add(TestCase.pack("top10-forge", topForge, TestCase.Loader.FORGE));
        }

        // Pack B: top 10 Fabric mods together
        List<ModCandidate> topFabric = topK.stream()
            .filter(m -> m.supportsAnyLoader(List.of("fabric")))
            .limit(10)
            .toList();
        if (topFabric.size() >= 2) {
            plan.add(TestCase.pack("top10-fabric", topFabric, TestCase.Loader.FABRIC));
        }

        // Pack C: mixed top 20 under InterMed (all loaders)
        List<ModCandidate> top20 = mods.stream().limit(20).toList();
        if (top20.size() >= 5) {
            plan.add(TestCase.pack("top20-mixed", top20, TestCase.Loader.FORGE));
        }

        System.out.printf("[Plan] FULL mode: %d test cases.%n", plan.size());
        return plan;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Generates one TestCase per loader the mod supports (Forge and/or Fabric).
     * Client-only mods (serverSide=false) have already been filtered out in
     * ModRegistry, so we don't need to check here.
     */
    private List<TestCase> singleCasesFor(ModCandidate mod) {
        List<TestCase> cases = new ArrayList<>(2);
        boolean supportsForge  = mod.supportsAnyLoader(List.of("forge", "neoforge"));
        boolean supportsFabric = mod.supportsAnyLoader(List.of("fabric"));

        if (config.loaderFilter != HarnessConfig.LoaderFilter.FABRIC && supportsForge) {
            String id = "single-" + mod.slug() + "-forge";
            cases.add(new TestCase(id, "Single: " + mod.label() + " [Forge]",
                List.of(mod), TestCase.Loader.FORGE));
        }
        if (config.loaderFilter != HarnessConfig.LoaderFilter.FORGE && supportsFabric) {
            String id = "single-" + mod.slug() + "-fabric";
            cases.add(new TestCase(id, "Single: " + mod.label() + " [Fabric]",
                List.of(mod), TestCase.Loader.FABRIC));
        }
        // If neither loader matched (unusual), use FORGE as fallback
        if (cases.isEmpty()) {
            cases.add(TestCase.single(mod));
        }
        return cases;
    }

    /** Returns the set of loaders that both mods support. */
    private List<TestCase.Loader> sharedLoaders(ModCandidate a, ModCandidate b) {
        List<TestCase.Loader> shared = new ArrayList<>(2);
        boolean bothForge  = a.supportsAnyLoader(List.of("forge","neoforge"))
                          && b.supportsAnyLoader(List.of("forge","neoforge"));
        boolean bothFabric = a.supportsAnyLoader(List.of("fabric"))
                          && b.supportsAnyLoader(List.of("fabric"));

        if (bothForge  && config.loaderFilter != HarnessConfig.LoaderFilter.FABRIC) {
            shared.add(TestCase.Loader.FORGE);
        }
        if (bothFabric && config.loaderFilter != HarnessConfig.LoaderFilter.FORGE) {
            shared.add(TestCase.Loader.FABRIC);
        }
        return shared;
    }
}
