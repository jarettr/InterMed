package org.intermed.harness.runner;

import org.intermed.harness.HarnessConfig;
import org.intermed.harness.discovery.ModCandidate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Generates the list of {@link TestCase}s to execute according to the
 * configured {@link HarnessConfig.TestMode}.
 *
 * <p>Modes:
 * <ul>
 *   <li><b>SINGLE</b> — one test per mod, in isolation.</li>
 *   <li><b>PAIRS</b> — SINGLE + every pair of the top-K mods that share a loader.</li>
 *   <li><b>SLICES</b> — fixed curated alpha slices, separate from Phase 3.</li>
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
            plan.sort(Comparator.comparing(TestCase::id));
            System.out.printf("[Plan] SINGLE mode: %d test cases.%n", plan.size());
            return plan;
        }

        if (config.mode == HarnessConfig.TestMode.SLICES) {
            List<TestCase> slices = curatedSliceCases();
            slices.sort(Comparator.comparing(TestCase::id));
            System.out.printf("[Plan] SLICES mode: %d curated test cases.%n", slices.size());
            return slices;
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
            plan.sort(Comparator.comparing(TestCase::id));
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

        // Pack C: top 10 NeoForge mods together
        List<ModCandidate> topNeoForge = topK.stream()
            .filter(m -> m.supportsAnyLoader(List.of("neoforge")))
            .limit(10)
            .toList();
        if (topNeoForge.size() >= 2) {
            plan.add(TestCase.pack("top10-neoforge", topNeoForge, TestCase.Loader.NEOFORGE));
        }

        // Pack D: mixed top 20 under InterMed (legacy Forge baseline)
        List<ModCandidate> top20 = mods.stream().limit(20).toList();
        if (top20.size() >= 5
                && (config.loaderFilter == HarnessConfig.LoaderFilter.ALL
                || config.loaderFilter == HarnessConfig.LoaderFilter.FORGE)) {
            plan.add(TestCase.pack("top20-mixed", top20, TestCase.Loader.FORGE));
        }

        plan.sort(Comparator.comparing(TestCase::id));
        System.out.printf("[Plan] FULL mode: %d test cases.%n", plan.size());
        return plan;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Generates one TestCase per loader the mod supports.
     * The caller is responsible for pre-filtering the corpus to the active
     * server-compatible lane.
     */
    private List<TestCase> singleCasesFor(ModCandidate mod) {
        List<TestCase> cases = new ArrayList<>(3);
        boolean supportsForge  = mod.supportsAnyLoader(List.of("forge"));
        boolean supportsNeoForge = mod.supportsAnyLoader(List.of("neoforge"));
        boolean supportsFabric = mod.supportsAnyLoader(List.of("fabric"));

        if ((config.loaderFilter == HarnessConfig.LoaderFilter.ALL
                || config.loaderFilter == HarnessConfig.LoaderFilter.FORGE) && supportsForge) {
            String id = "single-" + mod.slug() + "-forge";
            cases.add(new TestCase(id, "Single: " + mod.label() + " [Forge]",
                List.of(mod), TestCase.Loader.FORGE));
        }
        if ((config.loaderFilter == HarnessConfig.LoaderFilter.ALL
                || config.loaderFilter == HarnessConfig.LoaderFilter.NEOFORGE) && supportsNeoForge) {
            String id = "single-" + mod.slug() + "-neoforge";
            cases.add(new TestCase(id, "Single: " + mod.label() + " [NeoForge]",
                List.of(mod), TestCase.Loader.NEOFORGE));
        }
        if ((config.loaderFilter == HarnessConfig.LoaderFilter.ALL
                || config.loaderFilter == HarnessConfig.LoaderFilter.FABRIC) && supportsFabric) {
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

    private List<TestCase> curatedSliceCases() {
        List<TestCase> cases = new ArrayList<>();
        addSlice(cases, "fabric-foundation", TestCase.Loader.FABRIC, List.of(
            "fabric-api",
            "lithium",
            "fabric-language-kotlin",
            "appleskin",
            "xaeros-minimap",
            "xaeros-world-map"
        ));
        addSlice(cases, "forge-foundation", TestCase.Loader.FORGE, List.of(
            "architectury-api",
            "cloth-config",
            "jei",
            "jade",
            "geckolib",
            "modernfix"
        ));
        addSlice(cases, "neoforge-minimal", TestCase.Loader.NEOFORGE, List.of(
            "collective",
            "ferrite-core",
            "jade",
            "packet-fixer",
            "starter-kit"
        ));
        addSlice(cases, "data-resource-heavy", TestCase.Loader.FABRIC, List.of(
            "terralith",
            "incendium",
            "nullscape",
            "yungs-better-nether-fortresses",
            "yungs-better-dungeons",
            "yungs-better-ocean-monuments"
        ));
        addSlice(cases, "mixin-heavy-fabric", TestCase.Loader.FABRIC, List.of(
            "c2me-fabric",
            "lithium",
            "debugify",
            "krypton",
            "starlight",
            "vmp-fabric",
            "mixintrace"
        ));
        addSlice(cases, "network-heavy-fabric", TestCase.Loader.FABRIC, List.of(
            "packet-fixer",
            "krypton",
            "vmp-fabric",
            "open-parties-and-claims",
            "e4mc",
            "viafabric",
            "voice-chat-interaction"
        ));
        return cases;
    }

    private void addSlice(List<TestCase> cases,
                          String sliceName,
                          TestCase.Loader loader,
                          List<String> slugs) {
        if (!loaderAllowed(loader)) {
            return;
        }
        List<ModCandidate> selected = slugs.stream()
            .map(this::firstBySlug)
            .filter(java.util.Objects::nonNull)
            .filter(mod -> mod.serverSide() && (supportsLoader(mod, loader) || isDataDriven(mod)))
            .toList();
        if (selected.size() >= 2) {
            cases.add(TestCase.slice(sliceName, selected, loader));
        } else {
            System.out.printf("[Plan] SLICES mode: skipping %s, only %d eligible mod(s) found.%n",
                sliceName, selected.size());
        }
    }

    private ModCandidate firstBySlug(String slug) {
        for (ModCandidate mod : mods) {
            if (mod.slug().equals(slug)) {
                return mod;
            }
        }
        return null;
    }

    private boolean loaderAllowed(TestCase.Loader loader) {
        return config.loaderFilter == HarnessConfig.LoaderFilter.ALL
            || switch (loader) {
                case FORGE -> config.loaderFilter == HarnessConfig.LoaderFilter.FORGE;
                case FABRIC -> config.loaderFilter == HarnessConfig.LoaderFilter.FABRIC;
                case NEOFORGE -> config.loaderFilter == HarnessConfig.LoaderFilter.NEOFORGE;
            };
    }

    private boolean supportsLoader(ModCandidate mod, TestCase.Loader loader) {
        return switch (loader) {
            case FORGE -> mod.supportsAnyLoader(List.of("forge"));
            case FABRIC -> mod.supportsAnyLoader(List.of("fabric"));
            case NEOFORGE -> mod.supportsAnyLoader(List.of("neoforge"));
        };
    }

    private boolean isDataDriven(ModCandidate mod) {
        return mod.supportsAnyLoader(List.of("datapack", "resourcepack", "dataresourcepack"));
    }

    /** Returns the set of loaders that both mods support. */
    private List<TestCase.Loader> sharedLoaders(ModCandidate a, ModCandidate b) {
        List<TestCase.Loader> shared = new ArrayList<>(3);
        boolean bothForge  = a.supportsAnyLoader(List.of("forge"))
                          && b.supportsAnyLoader(List.of("forge"));
        boolean bothNeoForge = a.supportsAnyLoader(List.of("neoforge"))
                          && b.supportsAnyLoader(List.of("neoforge"));
        boolean bothFabric = a.supportsAnyLoader(List.of("fabric"))
                          && b.supportsAnyLoader(List.of("fabric"));

        if (bothForge
                && (config.loaderFilter == HarnessConfig.LoaderFilter.ALL
                || config.loaderFilter == HarnessConfig.LoaderFilter.FORGE)) {
            shared.add(TestCase.Loader.FORGE);
        }
        if (bothNeoForge
                && (config.loaderFilter == HarnessConfig.LoaderFilter.ALL
                || config.loaderFilter == HarnessConfig.LoaderFilter.NEOFORGE)) {
            shared.add(TestCase.Loader.NEOFORGE);
        }
        if (bothFabric
                && (config.loaderFilter == HarnessConfig.LoaderFilter.ALL
                || config.loaderFilter == HarnessConfig.LoaderFilter.FABRIC)) {
            shared.add(TestCase.Loader.FABRIC);
        }
        return shared;
    }
}
