package org.intermed.harness.runner;

import org.intermed.harness.HarnessConfig;
import org.intermed.harness.discovery.ModCandidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPlanNeoForgeTest {

    @Test
    void generatesDedicatedNeoForgeSingleCase() {
        ModCandidate neoOnly = new ModCandidate(
            "proj-neo",
            "neo-only",
            "Neo Only",
            100,
            List.of("neoforge"),
            "ver-1",
            "1.0.0",
            "https://example.invalid/neo-only.jar",
            "neo-only.jar",
            true
        );

        HarnessConfig config = HarnessConfig.builder()
            .loaderFilter(HarnessConfig.LoaderFilter.ALL)
            .mode(HarnessConfig.TestMode.SINGLE)
            .build();

        List<TestCase> cases = new TestPlan(config, List.of(neoOnly)).generate();

        assertEquals(1, cases.size());
        assertEquals(TestCase.Loader.NEOFORGE, cases.get(0).loader());
        assertTrue(cases.get(0).id().endsWith("-neoforge"));
    }

    @Test
    void loaderFilterNarrowsToNeoForgeCases() {
        ModCandidate shared = new ModCandidate(
            "proj-shared",
            "shared-mod",
            "Shared Mod",
            200,
            List.of("forge", "neoforge", "fabric"),
            "ver-2",
            "2.0.0",
            "https://example.invalid/shared-mod.jar",
            "shared-mod.jar",
            true
        );

        HarnessConfig config = HarnessConfig.builder()
            .loaderFilter(HarnessConfig.LoaderFilter.NEOFORGE)
            .mode(HarnessConfig.TestMode.SINGLE)
            .build();

        List<TestCase> cases = new TestPlan(config, List.of(shared)).generate();

        assertEquals(1, cases.size());
        assertEquals(TestCase.Loader.NEOFORGE, cases.get(0).loader());
    }

    @Test
    void slicesModeGeneratesCuratedAlphaSlicesWithoutFullPackCombos() {
        HarnessConfig config = HarnessConfig.builder()
            .loaderFilter(HarnessConfig.LoaderFilter.ALL)
            .mode(HarnessConfig.TestMode.SLICES)
            .build();

        List<TestCase> cases = new TestPlan(config, List.of(
            mod("fabric-api", "Fabric API", List.of("fabric")),
            mod("lithium", "Lithium", List.of("fabric")),
            mod("fabric-language-kotlin", "Fabric Language Kotlin", List.of("fabric")),
            mod("appleskin", "AppleSkin", List.of("fabric")),
            mod("xaeros-minimap", "Xaero's Minimap", List.of("fabric")),
            mod("xaeros-world-map", "Xaero's World Map", List.of("fabric")),
            mod("architectury-api", "Architectury API", List.of("forge")),
            mod("cloth-config", "Cloth Config API", List.of("forge")),
            mod("jei", "JEI", List.of("forge")),
            mod("jade", "Jade", List.of("forge", "neoforge")),
            mod("geckolib", "Geckolib", List.of("forge")),
            mod("modernfix", "ModernFix", List.of("forge")),
            mod("collective", "Collective", List.of("fabric", "forge", "neoforge")),
            mod("ferrite-core", "FerriteCore", List.of("forge", "neoforge")),
            mod("packet-fixer", "Packet Fixer", List.of("fabric", "forge", "neoforge")),
            mod("starter-kit", "Starter Kit", List.of("fabric", "forge", "neoforge")),
            mod("terralith", "Terralith", List.of("datapack")),
            mod("incendium", "Incendium", List.of("datapack")),
            mod("nullscape", "Nullscape", List.of("datapack")),
            mod("yungs-better-nether-fortresses", "YUNG's Better Nether Fortresses", List.of("fabric")),
            mod("yungs-better-dungeons", "YUNG's Better Dungeons", List.of("fabric")),
            mod("yungs-better-ocean-monuments", "YUNG's Better Ocean Monuments", List.of("fabric")),
            mod("c2me-fabric", "C2ME", List.of("fabric")),
            mod("debugify", "Debugify", List.of("fabric")),
            mod("krypton", "Krypton", List.of("fabric")),
            mod("starlight", "Starlight", List.of("fabric")),
            mod("vmp-fabric", "VMP", List.of("fabric")),
            mod("mixintrace", "MixinTrace", List.of("fabric")),
            mod("open-parties-and-claims", "Open Parties and Claims", List.of("fabric")),
            mod("e4mc", "e4mc", List.of("fabric")),
            mod("viafabric", "ViaFabric", List.of("fabric")),
            mod("voice-chat-interaction", "Voice Chat Interaction", List.of("fabric"))
        )).generate();

        List<String> ids = cases.stream().map(TestCase::id).toList();
        assertTrue(ids.contains("slice-fabric-foundation"));
        assertTrue(ids.contains("slice-forge-foundation"));
        assertTrue(ids.contains("slice-neoforge-minimal"));
        assertTrue(ids.contains("slice-data-resource-heavy"));
        assertTrue(ids.contains("slice-mixin-heavy-fabric"));
        assertTrue(ids.contains("slice-network-heavy-fabric"));
        assertTrue(ids.stream().noneMatch(id -> id.startsWith("pack-")));
    }

    private static ModCandidate mod(String slug, String name, List<String> loaders) {
        return new ModCandidate(
            "proj-" + slug,
            slug,
            name,
            100,
            loaders,
            "ver-" + slug,
            "1.0.0",
            "https://example.invalid/" + slug + ".jar",
            slug + ".jar",
            true
        );
    }
}
