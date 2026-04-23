package org.intermed.core.vfs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.metadata.ModPlatform;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.metadata.RuntimeModIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualFileSystemRouterTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("runtime.game.dir");
        System.clearProperty("vfs.enabled");
        System.clearProperty("vfs.conflict.policy");
        System.clearProperty("vfs.priority.mods");
        System.clearProperty("vfs.cache.dir");
        RuntimeModIndex.clear();
        RuntimeConfig.resetForTests();
        VirtualFileSystemRouter.invalidateCache();
    }

    @Test
    void buildsOverlayPackThatMergesTagValuesAcrossMods() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-vfs-merge");
        System.setProperty("runtime.game.dir", tempDir.toString());
        System.setProperty("vfs.priority.mods", "high_priority,low_priority");
        RuntimeConfig.reload();

        File lowJar = createJar(tempDir.resolve("low.jar"), Map.of(
            "data/minecraft/tags/items/intermed_tools.json",
            """
                {"replace":false,"values":["minecraft:stick"]}
                """
        ));
        File highJar = createJar(tempDir.resolve("high.jar"), Map.of(
            "data/minecraft/tags/items/intermed_tools.json",
            """
                {"replace":false,"values":["minecraft:planks","minecraft:stick"]}
                """
        ));

        VirtualFileSystemRouter.MountPlan plan = VirtualFileSystemRouter.buildPlan(
            List.of(
                metadata("low_priority", lowJar, false, true),
                metadata("high_priority", highJar, false, true)
            ),
            RuntimeConfig.get()
        );

        assertTrue(plan.hasOverlayPack());
        assertTrue(plan.hasDiagnosticsReport());
        assertEquals(plan.overlayPack(), plan.mountablePacks().getFirst());
        assertEquals(1, plan.conflicts().size());
        assertEquals(1, plan.mergedResourceCount());
        assertEquals("crdt_lww_merge", plan.conflicts().getFirst().resolution());
        assertTrue(plan.conflicts().getFirst().jsonMerged());
        assertEquals("tag", plan.conflicts().getFirst().resourceKind());
        assertEquals("high_priority", plan.conflicts().getFirst().winnerModId());

        JsonObject merged = readJson(plan.overlayPack(), "data/minecraft/tags/items/intermed_tools.json");
        JsonArray values = merged.getAsJsonArray("values");
        assertEquals(2, values.size());
        assertEquals("minecraft:stick", values.get(0).getAsString());
        assertEquals("minecraft:planks", values.get(1).getAsString());
    }

    @Test
    void structuralJsonConflictsFallBackToConfiguredPriority() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-vfs-priority");
        System.setProperty("runtime.game.dir", tempDir.toString());
        System.setProperty("vfs.priority.mods", "priority_mod,baseline_mod");
        RuntimeConfig.reload();

        File baselineJar = createJar(tempDir.resolve("baseline.jar"), Map.of(
            "data/minecraft/loot_tables/chests/start.json",
            """
                {"type":"minecraft:generic","pools":[{"rolls":1}]}
                """
        ));
        File priorityJar = createJar(tempDir.resolve("priority.jar"), Map.of(
            "data/minecraft/loot_tables/chests/start.json",
            """
                {"type":"minecraft:generic","pools":"priority_override"}
                """
        ));

        VirtualFileSystemRouter.MountPlan plan = VirtualFileSystemRouter.buildPlan(
            List.of(
                metadata("baseline_mod", baselineJar, false, true),
                metadata("priority_mod", priorityJar, false, true)
            ),
            RuntimeConfig.get()
        );

        assertTrue(plan.hasOverlayPack());
        assertEquals(1, plan.conflicts().size());
        assertEquals("priority_mod", plan.conflicts().getFirst().winnerModId());
        assertEquals("crdt_lww_merge", plan.conflicts().getFirst().resolution());
        assertTrue(plan.conflicts().getFirst().jsonMerged());

        JsonObject merged = readJson(plan.overlayPack(), "data/minecraft/loot_tables/chests/start.json");
        assertEquals("priority_override", merged.get("pools").getAsString());

        JsonObject manifest = readJson(plan.overlayPack(), "intermed-vfs-manifest.json");
        assertEquals(1, manifest.getAsJsonArray("conflicts").size());
        assertEquals(1, manifest.getAsJsonObject("summary").getAsJsonObject("byKind").get("loot_table").getAsInt());
    }

    @Test
    void disabledVfsReturnsRawPacksWithoutOverlay() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-vfs-disabled");
        System.setProperty("runtime.game.dir", tempDir.toString());
        System.setProperty("vfs.enabled", "false");
        RuntimeConfig.reload();

        File rawJar = createJar(tempDir.resolve("raw.jar"), Map.of(
            "assets/example/lang/en_us.json",
            """
                {"message":"hello"}
                """
        ));

        VirtualFileSystemRouter.MountPlan plan = VirtualFileSystemRouter.buildPlan(
            List.of(metadata("raw_mod", rawJar, true, false)),
            RuntimeConfig.get()
        );

        assertFalse(plan.hasOverlayPack());
        assertFalse(plan.hasDiagnosticsReport());
        assertEquals(1, plan.mountablePacks().size());
        File mountablePack = plan.mountablePacks().getFirst();
        assertTrue(!rawJar.getAbsoluteFile().equals(mountablePack));
        assertTrue(mountablePack.getName().startsWith("intermed-synth-pack-raw_mod-"));
        try (ZipFile zip = new ZipFile(mountablePack)) {
            assertNotNull(zip.getEntry("pack.mcmeta"));
            assertNotNull(zip.getEntry("assets/example/lang/en_us.json"));
        }
        assertTrue(plan.conflicts().isEmpty());
    }

    @Test
    void recipeIngredientsMergeWhenRecipeTypeMatches() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-vfs-recipes");
        System.setProperty("runtime.game.dir", tempDir.toString());
        System.setProperty("vfs.priority.mods", "recipe_priority,recipe_base");
        RuntimeConfig.reload();

        File baseJar = createJar(tempDir.resolve("recipe-base.jar"), Map.of(
            "data/example/recipes/torch.json",
            """
                {"type":"minecraft:crafting_shapeless","ingredients":[{"item":"minecraft:stick"}],"result":{"item":"minecraft:torch"}}
                """
        ));
        File priorityJar = createJar(tempDir.resolve("recipe-priority.jar"), Map.of(
            "data/example/recipes/torch.json",
            """
                {"type":"minecraft:crafting_shapeless","ingredients":[{"item":"minecraft:coal"}],"result":{"item":"minecraft:torch"}}
                """
        ));

        VirtualFileSystemRouter.MountPlan plan = VirtualFileSystemRouter.buildPlan(
            List.of(
                metadata("recipe_base", baseJar, false, true),
                metadata("recipe_priority", priorityJar, false, true)
            ),
            RuntimeConfig.get()
        );

        JsonObject merged = readJson(plan.overlayPack(), "data/example/recipes/torch.json");
        JsonArray ingredients = merged.getAsJsonArray("ingredients");
        assertEquals(2, ingredients.size());
        assertEquals("minecraft:stick", ingredients.get(0).getAsJsonObject().get("item").getAsString());
        assertEquals("minecraft:coal", ingredients.get(1).getAsJsonObject().get("item").getAsString());
        assertEquals("json_patch_merge", plan.conflicts().getFirst().resolution());
    }

    @Test
    void advancementsMergeCriteriaAndRequirements() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-vfs-advancements");
        System.setProperty("runtime.game.dir", tempDir.toString());
        System.setProperty("vfs.priority.mods", "advancement_priority,advancement_base");
        RuntimeConfig.reload();

        File baseJar = createJar(tempDir.resolve("advancement-base.jar"), Map.of(
            "data/example/advancements/root.json",
            """
                {"criteria":{"stick":{"trigger":"minecraft:inventory_changed"}},"requirements":[["stick"]]}
                """
        ));
        File priorityJar = createJar(tempDir.resolve("advancement-priority.jar"), Map.of(
            "data/example/advancements/root.json",
            """
                {"criteria":{"coal":{"trigger":"minecraft:inventory_changed"}},"requirements":[["coal"]]}
                """
        ));

        VirtualFileSystemRouter.MountPlan plan = VirtualFileSystemRouter.buildPlan(
            List.of(
                metadata("advancement_base", baseJar, false, true),
                metadata("advancement_priority", priorityJar, false, true)
            ),
            RuntimeConfig.get()
        );

        JsonObject merged = readJson(plan.overlayPack(), "data/example/advancements/root.json");
        assertTrue(merged.getAsJsonObject("criteria").has("stick"));
        assertTrue(merged.getAsJsonObject("criteria").has("coal"));
        JsonArray requirements = merged.getAsJsonArray("requirements");
        assertEquals(2, requirements.size());
        assertEquals("crdt_lww_merge", plan.conflicts().getFirst().resolution());
    }

    @Test
    void advancementRewardRecipesMergeIntoSingleOverlay() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-vfs-advancement-rewards");
        System.setProperty("runtime.game.dir", tempDir.toString());
        System.setProperty("vfs.priority.mods", "advancement_priority,advancement_base");
        RuntimeConfig.reload();

        File baseJar = createJar(tempDir.resolve("advancement-reward-base.jar"), Map.of(
            "data/example/advancements/root.json",
            """
                {"criteria":{"stick":{"trigger":"minecraft:inventory_changed"}},"requirements":[["stick"]],"rewards":{"recipes":["example:torch"]}}
                """
        ));
        File priorityJar = createJar(tempDir.resolve("advancement-reward-priority.jar"), Map.of(
            "data/example/advancements/root.json",
            """
                {"criteria":{"coal":{"trigger":"minecraft:inventory_changed"}},"requirements":[["coal"]],"rewards":{"recipes":["example:lantern"]}}
                """
        ));

        VirtualFileSystemRouter.MountPlan plan = VirtualFileSystemRouter.buildPlan(
            List.of(
                metadata("advancement_base", baseJar, false, true),
                metadata("advancement_priority", priorityJar, false, true)
            ),
            RuntimeConfig.get()
        );

        JsonObject merged = readJson(plan.overlayPack(), "data/example/advancements/root.json");
        JsonArray recipes = merged.getAsJsonObject("rewards").getAsJsonArray("recipes");
        assertEquals(2, recipes.size());
        assertEquals("example:torch", recipes.get(0).getAsString());
        assertEquals("example:lantern", recipes.get(1).getAsString());
        assertEquals("advancement", plan.conflicts().getFirst().resourceKind());
        assertEquals("crdt_lww_merge", plan.conflicts().getFirst().resolution());
    }

    @Test
    void tagReplaceTrueEscalatesToPriorityOverride() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-vfs-tag-replace");
        System.setProperty("runtime.game.dir", tempDir.toString());
        System.setProperty("vfs.priority.mods", "override_mod,base_mod");
        RuntimeConfig.reload();

        File baseJar = createJar(tempDir.resolve("base-tag.jar"), Map.of(
            "data/minecraft/tags/blocks/intermed_stones.json",
            """
                {"replace":false,"values":["minecraft:stone"]}
                """
        ));
        File overrideJar = createJar(tempDir.resolve("override-tag.jar"), Map.of(
            "data/minecraft/tags/blocks/intermed_stones.json",
            """
                {"replace":true,"values":["minecraft:deepslate"]}
                """
        ));

        VirtualFileSystemRouter.MountPlan plan = VirtualFileSystemRouter.buildPlan(
            List.of(
                metadata("base_mod", baseJar, false, true),
                metadata("override_mod", overrideJar, false, true)
            ),
            RuntimeConfig.get()
        );

        JsonObject merged = readJson(plan.overlayPack(), "data/minecraft/tags/blocks/intermed_stones.json");
        JsonArray values = merged.getAsJsonArray("values");
        assertEquals(1, values.size());
        assertEquals("minecraft:deepslate", values.get(0).getAsString());
        assertEquals("crdt_lww_merge", plan.conflicts().getFirst().resolution());
    }

    @Test
    void externalOverrideFileCanForcePathSpecificPriority() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-vfs-overrides");
        System.setProperty("runtime.game.dir", tempDir.toString());
        System.setProperty("vfs.priority.mods", "preferred_mod,base_mod");
        RuntimeConfig.reload();

        writeOverrides("""
            {
              "resources": [
                {
                  "path": "data/example/recipes/*.json",
                  "policy": "priority",
                  "winner": "base_mod"
                }
              ]
            }
            """
        );

        File baseJar = createJar(tempDir.resolve("override-base.jar"), Map.of(
            "data/example/recipes/torch.json",
            """
                {"type":"minecraft:crafting_shapeless","ingredients":[{"item":"minecraft:stick"}],"result":{"item":"minecraft:torch"}}
                """
        ));
        File preferredJar = createJar(tempDir.resolve("override-preferred.jar"), Map.of(
            "data/example/recipes/torch.json",
            """
                {"type":"minecraft:crafting_shapeless","ingredients":[{"item":"minecraft:coal"}],"result":{"item":"minecraft:soul_torch"}}
                """
        ));

        VirtualFileSystemRouter.invalidateCache();
        VirtualFileSystemRouter.MountPlan plan = VirtualFileSystemRouter.buildPlan(
            List.of(
                metadata("base_mod", baseJar, false, true),
                metadata("preferred_mod", preferredJar, false, true)
            ),
            RuntimeConfig.get()
        );

        JsonObject merged = readJson(plan.overlayPack(), "data/example/recipes/torch.json");
        assertEquals("minecraft:torch", merged.getAsJsonObject("result").get("item").getAsString());
        assertEquals("base_mod", plan.conflicts().getFirst().winnerModId());
        assertEquals("priority_override", plan.conflicts().getFirst().resolution());
        assertEquals("override:data/example/recipes/*.json", plan.conflicts().getFirst().policySource());

        JsonObject manifest = readJson(plan.overlayPack(), "intermed-vfs-manifest.json");
        JsonObject firstConflict = manifest.getAsJsonArray("conflicts").get(0).getAsJsonObject();
        assertEquals("override:data/example/recipes/*.json", firstConflict.get("policySource").getAsString());
    }

    @Test
    void assetLanguageConflictsProduceOverlayAndDiagnosticsReport() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-vfs-lang");
        System.setProperty("runtime.game.dir", tempDir.toString());
        System.setProperty("vfs.priority.mods", "lang_priority,lang_base");
        RuntimeConfig.reload();

        File baseJar = createJar(tempDir.resolve("lang-base.jar"), Map.of(
            "assets/example/lang/en_us.json",
            """
                {"item.example.hammer":"Hammer","item.example.shared":"Shared"}
                """
        ));
        File priorityJar = createJar(tempDir.resolve("lang-priority.jar"), Map.of(
            "assets/example/lang/en_us.json",
            """
                {"item.example.shared":"Shared Override","item.example.wrench":"Wrench"}
                """
        ));

        VirtualFileSystemRouter.MountPlan plan = VirtualFileSystemRouter.buildPlan(
            List.of(
                metadata("lang_base", baseJar, true, false),
                metadata("lang_priority", priorityJar, true, false)
            ),
            RuntimeConfig.get()
        );

        assertTrue(plan.hasOverlayPack());
        assertTrue(plan.hasDiagnosticsReport());
        assertEquals("lang", plan.conflicts().getFirst().resourceKind());
        assertEquals("json_patch_merge_with_priority", plan.conflicts().getFirst().resolution());

        JsonObject merged = readJson(plan.overlayPack(), "assets/example/lang/en_us.json");
        assertEquals("Hammer", merged.get("item.example.hammer").getAsString());
        assertEquals("Shared Override", merged.get("item.example.shared").getAsString());
        assertEquals("Wrench", merged.get("item.example.wrench").getAsString());

        JsonObject report = readJson(plan.diagnosticsReport());
        assertEquals(1, report.get("conflictCount").getAsInt());
        assertEquals(1, report.getAsJsonObject("summary").getAsJsonObject("byKind").get("lang").getAsInt());
        assertEquals(1, report.getAsJsonArray("resources").size());
        JsonObject conflict = report.getAsJsonArray("conflicts").get(0).getAsJsonObject();
        assertEquals("assets/example/lang/en_us.json", conflict.get("path").getAsString());
        assertEquals("lang", conflict.get("resourceKind").getAsString());
        assertEquals("lang_priority", conflict.getAsJsonObject("suggestedOverride").get("winner").getAsString());
    }

    @Test
    void biomeModifierArraysMergeForCrossLoaderDataDrivenResources() throws Exception {
        Path tempDir = Files.createTempDirectory("intermed-vfs-biome-modifier");
        System.setProperty("runtime.game.dir", tempDir.toString());
        System.setProperty("vfs.priority.mods", "biome_priority,biome_base");
        RuntimeConfig.reload();

        File baseJar = createJar(tempDir.resolve("biome-base.jar"), Map.of(
            "data/example/neoforge/biome_modifier/ores.json",
            """
                {"type":"neoforge:add_features","biomes":["minecraft:plains"],"features":["example:tin_ore"],"steps":["underground_ores"]}
                """
        ));
        File priorityJar = createJar(tempDir.resolve("biome-priority.jar"), Map.of(
            "data/example/neoforge/biome_modifier/ores.json",
            """
                {"type":"neoforge:add_features","biomes":["minecraft:forest"],"features":["example:silver_ore"],"steps":["underground_ores"]}
                """
        ));

        VirtualFileSystemRouter.MountPlan plan = VirtualFileSystemRouter.buildPlan(
            List.of(
                metadata("biome_base", baseJar, false, true),
                metadata("biome_priority", priorityJar, false, true)
            ),
            RuntimeConfig.get()
        );

        JsonObject merged = readJson(plan.overlayPack(), "data/example/neoforge/biome_modifier/ores.json");
        JsonArray biomes = merged.getAsJsonArray("biomes");
        JsonArray features = merged.getAsJsonArray("features");
        JsonArray steps = merged.getAsJsonArray("steps");
        assertEquals(2, biomes.size());
        assertEquals(2, features.size());
        assertEquals(1, steps.size());
        assertEquals("biome_modifier", plan.conflicts().getFirst().resourceKind());
        assertEquals("crdt_lww_merge", plan.conflicts().getFirst().resolution());
    }

    private static NormalizedModMetadata metadata(String modId, File jar, boolean clientResources, boolean serverData) {
        JsonObject manifest = new JsonObject();
        if (clientResources) {
            manifest.addProperty("intermed:has_client_resources", true);
        }
        if (serverData) {
            manifest.addProperty("intermed:has_server_data", true);
        }
        return new NormalizedModMetadata(modId, "1.0.0", jar, ModPlatform.FABRIC, manifest, Map.of());
    }

    private static File createJar(Path path, Map<String, String> entries) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return path.toFile();
    }

    private static void writeOverrides(String json) throws IOException {
        Path configDir = RuntimeConfig.get().getConfigDir();
        Files.createDirectories(configDir);
        Files.writeString(
            configDir.resolve(VirtualFileSystemOverrides.EXTERNAL_OVERRIDES_FILE),
            json,
            StandardCharsets.UTF_8
        );
    }

    private static JsonObject readJson(File zip, String path) throws IOException {
        assertNotNull(zip);
        try (ZipFile file = new ZipFile(zip)) {
            var entry = file.getEntry(path);
            assertNotNull(entry, "Missing overlay entry: " + path);
            try (var input = file.getInputStream(entry)) {
                return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        }
    }

    private static JsonObject readJson(File file) throws IOException {
        assertNotNull(file);
        return JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
