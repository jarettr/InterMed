package org.intermed.core.metadata;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeModIndexTest {
    private static final String SNAPSHOT_PROPERTY = "intermed.runtimeModIndex.snapshot.v1";

    @AfterEach
    void tearDown() {
        RuntimeModIndex.clear();
        System.clearProperty(SNAPSHOT_PROPERTY);
    }

    @Test
    void uiFallsBackToDiscoveredModsWhenNothingLoaded() {
        RuntimeModIndex.registerDiscoveredAll(List.of(metadata("spectrum"), metadata("cloth-config")));

        List<String> visible = RuntimeModIndex.visibleModsForUi().stream()
            .map(NormalizedModMetadata::id)
            .sorted()
            .toList();

        assertEquals(List.of("cloth-config", "spectrum"), visible);
        assertTrue(RuntimeModIndex.allMods().isEmpty());
    }

    @Test
    void uiPrefersLoadedModsOnceDagIsResolved() {
        RuntimeModIndex.registerDiscoveredAll(List.of(metadata("spectrum"), metadata("cloth-config")));
        RuntimeModIndex.registerAll(List.of(metadata("cloth-config")));

        List<String> visible = RuntimeModIndex.visibleModsForUi().stream()
            .map(NormalizedModMetadata::id)
            .toList();

        assertEquals(List.of("cloth-config"), visible);
    }

    @Test
    void uiKeepsDiscoveredFailuresVisibleAlongsideLoadedSubset() {
        String failure = "Missing dependency 'trinkets' required by spectrum@1.8.13";
        RuntimeModIndex.registerDiscoveredAll(List.of(metadata("spectrum"), metadata("cloth-config")));
        RuntimeModIndex.markLoadFailure("spectrum", failure);

        RuntimeModIndex.registerAll(List.of(metadata("cloth-config")));

        List<String> visible = RuntimeModIndex.visibleModsForUi().stream()
            .map(NormalizedModMetadata::id)
            .sorted()
            .toList();

        assertEquals(List.of("cloth-config", "spectrum"), visible);
        assertEquals(failure, RuntimeModIndex.loadFailure("spectrum").orElseThrow());
    }

    @Test
    void uiFallsBackToSnapshotWhenStaticStateIsEmpty() {
        System.setProperty(SNAPSHOT_PROPERTY,
            "[{\"id\":\"spectrum\",\"name\":\"Spectrum\",\"version\":\"1.0.0\",\"platform\":\"FABRIC\",\"loaded\":false}]");

        List<String> visible = RuntimeModIndex.visibleModsForUi().stream()
            .map(NormalizedModMetadata::id)
            .toList();

        assertEquals(List.of("spectrum"), visible);
        assertTrue(RuntimeModIndex.isDiscovered("spectrum"));
    }

    @Test
    void loadedStateFallsBackToSnapshotWhenStaticStateIsEmpty() {
        System.setProperty(SNAPSHOT_PROPERTY,
            "[{\"id\":\"cloth-config\",\"name\":\"Cloth Config\",\"version\":\"1.0.0\",\"platform\":\"FABRIC\",\"loaded\":true}]");

        assertTrue(RuntimeModIndex.isLoaded("cloth-config"));
        assertEquals("cloth-config", RuntimeModIndex.get("cloth-config").map(NormalizedModMetadata::id).orElseThrow());
    }

    @Test
    void loadFailureFallsBackToSnapshotWhenStaticStateIsEmpty() {
        System.setProperty(SNAPSHOT_PROPERTY,
            "[{\"id\":\"spectrum\",\"name\":\"Spectrum\",\"version\":\"1.0.0\",\"platform\":\"FABRIC\",\"loaded\":false,\"failure\":\"Missing dependency 'revelationary' required by spectrum@1.8.13\"}]");

        assertEquals(
            "Missing dependency 'revelationary' required by spectrum@1.8.13",
            RuntimeModIndex.loadFailure("spectrum").orElseThrow()
        );
    }

    @Test
    void loadingAModClearsItsRecordedFailureReason() {
        RuntimeModIndex.registerDiscovered(metadata("spectrum"));
        RuntimeModIndex.markLoadFailure("spectrum", "Missing dependency 'revelationary' required by spectrum@1.8.13");

        assertTrue(RuntimeModIndex.loadFailure("spectrum").isPresent());

        RuntimeModIndex.register(metadata("spectrum"));

        assertTrue(RuntimeModIndex.loadFailure("spectrum").isEmpty());
    }

    private static NormalizedModMetadata metadata(String id) {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("id", id);
        manifest.addProperty("name", id);
        manifest.addProperty("version", "1.0.0");
        return new NormalizedModMetadata(
            id,
            "1.0.0",
            new File(id + ".jar"),
            ModPlatform.FABRIC,
            manifest,
            java.util.Map.of()
        );
    }
}
