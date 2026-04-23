package org.intermed.core.bridge.forge;

import com.google.gson.JsonObject;
import org.intermed.core.metadata.ModPlatform;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.metadata.RuntimeModIndex;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeModListMirrorTest {

    @AfterEach
    void tearDown() {
        RuntimeModIndex.clear();
        System.clearProperty("intermed.forge.modlist.mirror");
    }

    @Test
    void mirrorsDiscoveredInterMedModsIntoForgeUiList() throws Exception {
        RuntimeModIndex.registerDiscovered(metadata("spectrum", "Spectrum"));
        RuntimeModIndex.markLoadFailure("spectrum", "Missing dependency 'revelationary' required by spectrum@1.8.13");

        FakeForgeMod existing = new FakeForgeMod("forge");
        List<?> augmented = ForgeModListMirror.augmentModInfos(List.of(existing));

        assertEquals(2, augmented.size());
        Object mirrored = augmented.stream()
            .filter(candidate -> candidate != existing)
            .findFirst()
            .orElseThrow();

        Method getModId = mirrored.getClass().getMethod("getModId");
        Method getDisplayName = mirrored.getClass().getMethod("getDisplayName");
        Method getDescription = mirrored.getClass().getMethod("getDescription");
        Method getModProperties = mirrored.getClass().getMethod("getModProperties");

        assertEquals("spectrum", getModId.invoke(mirrored));
        assertEquals("Spectrum [I?]", getDisplayName.invoke(mirrored));
        assertTrue(String.valueOf(getDescription.invoke(mirrored)).contains("Missing dependency 'revelationary'"));
        Map<?, ?> properties = (Map<?, ?>) getModProperties.invoke(mirrored);
        assertEquals("discovered", properties.get("intermed:state"));
        assertEquals(
            "Missing dependency 'revelationary' required by spectrum@1.8.13",
            properties.get("intermed:failure")
        );
    }

    @Test
    void lateDiscoveryRebuildsOpenForgeModScreenOnlyOnce() throws Exception {
        FakeForgeMod existing = new FakeForgeMod("forge");
        FakeModListScreen screen = new FakeModListScreen(List.of(existing));

        RuntimeModIndex.registerDiscovered(metadata("spectrum", "Spectrum"));

        ForgeModListMirror.syncScreenLists(screen);

        assertEquals(List.of("forge", "spectrum"), modIds(readListField(screen, "mods")));
        assertEquals(List.of("forge", "spectrum"), modIds(readListField(screen, "unsortedMods")));
        assertEquals(1, screen.reloadCount);
        assertEquals(2, screen.modList.refreshCount);
        assertEquals(1, screen.updateCacheCount);
        assertTrue(screen.sorted);
        assertNotNull(screen.selected);
        assertEquals("forge", modIdOf(screen.selected.getInfo()));

        ForgeModListMirror.syncScreenLists(screen);

        assertEquals(1, screen.reloadCount);
        assertEquals(2, screen.modList.refreshCount);
        assertEquals(1, screen.updateCacheCount);
    }

    private static NormalizedModMetadata metadata(String id, String name) {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("id", id);
        manifest.addProperty("name", name);
        manifest.addProperty("version", "1.0.0");
        return new NormalizedModMetadata(
            id,
            "1.0.0",
            new File(id + ".jar"),
            ModPlatform.FABRIC,
            manifest,
            Map.of()
        );
    }

    public static final class FakeForgeMod {
        private final String modId;

        public FakeForgeMod(String modId) {
            this.modId = modId;
        }

        public String getModId() {
            return modId;
        }

        public Object getOwningFile() {
            return null;
        }
    }

    private static List<String> modIds(List<?> mods) {
        return mods.stream()
            .map(ForgeModListMirrorTest::modIdOf)
            .toList();
    }

    private static String modIdOf(Object mod) {
        try {
            Method method = mod.getClass().getMethod("getModId");
            return String.valueOf(method.invoke(mod));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<?> readListField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (List<?>) field.get(target);
    }

    private static final class FakeModListScreen {
        private List<Object> mods;
        private final List<Object> unsortedMods;
        private final FakeModListWidget modList;
        private FakeModEntry selected;
        private boolean sorted;
        private final Comparator<Object> sortType = Comparator.comparing(ForgeModListMirrorTest::modIdOf);
        private int reloadCount;
        private int updateCacheCount;

        private FakeModListScreen(List<?> baseMods) {
            this.mods = new ArrayList<>(baseMods);
            this.unsortedMods = List.copyOf(baseMods);
            this.modList = new FakeModListWidget(this);
            this.modList.refreshList();
            this.selected = this.modList.entries.get(0);
        }

        @SuppressWarnings("unused")
        private void reloadMods() {
            reloadCount++;
            this.mods = new ArrayList<>(unsortedMods);
        }

        @SuppressWarnings("unused")
        private void updateCache() {
            updateCacheCount++;
        }
    }

    private static final class FakeModListWidget {
        private final FakeModListScreen screen;
        private final List<FakeModEntry> entries = new ArrayList<>();
        private int refreshCount;
        private FakeModEntry widgetSelection;

        private FakeModListWidget(FakeModListScreen screen) {
            this.screen = screen;
        }

        @SuppressWarnings("unused")
        public void refreshList() {
            refreshCount++;
            entries.clear();
            for (Object mod : screen.mods) {
                entries.add(new FakeModEntry(mod));
            }
        }

        @SuppressWarnings("unused")
        public List<FakeModEntry> m_6702_() {
            return entries;
        }

        @SuppressWarnings("unused")
        public void m_6987_(FakeModEntry entry) {
            widgetSelection = entry;
        }
    }

    private static final class FakeModEntry {
        private final Object info;

        private FakeModEntry(Object info) {
            this.info = info;
        }

        @SuppressWarnings("unused")
        public Object getInfo() {
            return info;
        }
    }
}
