package org.intermed.core.registry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for {@link RegistryTranslationMatrix} (ТЗ 3.5.3).
 *
 * <p>Covers:
 * <ul>
 *   <li>Bijective serverToClient / clientToServer mapping correctness</li>
 *   <li>UNMAPPED sentinel (-1) for out-of-range and absent slots</li>
 *   <li>Binary serialise / deserialise round-trip</li>
 *   <li>{@code build(snapshot, localRegistry)} builder</li>
 *   <li>{@code buildFromSnapshots(server, client)} builder</li>
 *   <li>Identity (empty) matrix behaviour</li>
 *   <li>Partial overlap — keys present only on one side stay UNMAPPED</li>
 *   <li>{@code install()} + {@code active()} + {@code reset()} lifecycle</li>
 * </ul>
 */
class RegistryTranslationMatrixTest {

    @AfterEach
    void resetActiveMatrix() {
        RegistryTranslationMatrix.reset();
    }

    // ── Identity / empty matrix ──────────────────────────────────────────────

    @Test
    void activeIsIdentityBeforeInstall() {
        RegistryTranslationMatrix m = RegistryTranslationMatrix.active();
        assertEquals(RegistryTranslationMatrix.UNMAPPED, m.serverToClient(0));
        assertEquals(RegistryTranslationMatrix.UNMAPPED, m.clientToServer(0));
        assertEquals(0, m.serverSize());
        assertEquals(0, m.clientSize());
    }

    @Test
    void resetRestoresIdentity() {
        List<RegistryTranslationMatrix.SlotEntry> snap = List.of(
            new RegistryTranslationMatrix.SlotEntry(0, "minecraft:stone")
        );
        RegistryTranslationMatrix m = RegistryTranslationMatrix.buildFromSnapshots(snap, snap);
        RegistryTranslationMatrix.install(m);
        assertNotEquals(0, RegistryTranslationMatrix.active().serverSize());

        RegistryTranslationMatrix.reset();
        assertEquals(0, RegistryTranslationMatrix.active().serverSize());
    }

    @Test
    void installNullRestoresIdentity() {
        RegistryTranslationMatrix.install(null);
        RegistryTranslationMatrix m = RegistryTranslationMatrix.active();
        assertEquals(RegistryTranslationMatrix.UNMAPPED, m.serverToClient(0));
    }

    // ── buildFromSnapshots — symmetric (same registry both sides) ────────────

    @Test
    void symmetricSnapshotMapsIdentically() {
        List<RegistryTranslationMatrix.SlotEntry> snap = buildSnapshot(
            "minecraft:air", "minecraft:stone", "minecraft:grass_block"
        );

        RegistryTranslationMatrix m = RegistryTranslationMatrix.buildFromSnapshots(snap, snap);

        for (RegistryTranslationMatrix.SlotEntry e : snap) {
            int slot = e.slot();
            // With identical snapshots every server slot must map to itself
            assertEquals(slot, m.serverToClient(slot),
                "symmetric: serverToClient(" + slot + ") should equal " + slot);
            assertEquals(slot, m.clientToServer(slot),
                "symmetric: clientToServer(" + slot + ") should equal " + slot);
        }
    }

    @Test
    void symmetricMappingIsBijective() {
        List<RegistryTranslationMatrix.SlotEntry> snap = buildSnapshot(
            "mod_a:item1", "mod_a:item2", "mod_a:item3", "mod_b:block1"
        );
        RegistryTranslationMatrix m = RegistryTranslationMatrix.buildFromSnapshots(snap, snap);

        // serverToClient then clientToServer must be identity for all valid slots
        for (RegistryTranslationMatrix.SlotEntry e : snap) {
            int s = e.slot();
            int c = m.serverToClient(s);
            assertNotEquals(RegistryTranslationMatrix.UNMAPPED, c, "slot " + s + " should be mapped");
            assertEquals(s, m.clientToServer(c),
                "round-trip serverToClient(" + s + ")=" + c + " then clientToServer should return " + s);
        }
    }

    // ── buildFromSnapshots — different slot assignments ───────────────────────

    @Test
    void differentSlotAssignmentsAreMappedCorrectly() {
        // Server assigned slots 0,1,2 to keys A,B,C
        List<RegistryTranslationMatrix.SlotEntry> serverSnap = List.of(
            new RegistryTranslationMatrix.SlotEntry(0, "mod:key_a"),
            new RegistryTranslationMatrix.SlotEntry(1, "mod:key_b"),
            new RegistryTranslationMatrix.SlotEntry(2, "mod:key_c")
        );
        // Client assigned the same keys to different slots: C=0, A=1, B=2
        List<RegistryTranslationMatrix.SlotEntry> clientSnap = List.of(
            new RegistryTranslationMatrix.SlotEntry(0, "mod:key_c"),
            new RegistryTranslationMatrix.SlotEntry(1, "mod:key_a"),
            new RegistryTranslationMatrix.SlotEntry(2, "mod:key_b")
        );

        RegistryTranslationMatrix m = RegistryTranslationMatrix.buildFromSnapshots(serverSnap, clientSnap);

        // server slot 0 = key_a → client slot 1
        assertEquals(1, m.serverToClient(0), "key_a: server=0 → client=1");
        // server slot 1 = key_b → client slot 2
        assertEquals(2, m.serverToClient(1), "key_b: server=1 → client=2");
        // server slot 2 = key_c → client slot 0
        assertEquals(0, m.serverToClient(2), "key_c: server=2 → client=0");

        // Inverse direction
        assertEquals(0, m.clientToServer(1), "key_a: client=1 → server=0");
        assertEquals(1, m.clientToServer(2), "key_b: client=2 → server=1");
        assertEquals(2, m.clientToServer(0), "key_c: client=0 → server=2");
    }

    // ── UNMAPPED sentinel ────────────────────────────────────────────────────

    @Test
    void outOfRangeServerSlotReturnsUnmapped() {
        List<RegistryTranslationMatrix.SlotEntry> snap = buildSnapshot("minecraft:stone");
        RegistryTranslationMatrix m = RegistryTranslationMatrix.buildFromSnapshots(snap, snap);

        assertEquals(RegistryTranslationMatrix.UNMAPPED, m.serverToClient(-1));
        assertEquals(RegistryTranslationMatrix.UNMAPPED, m.serverToClient(999));
    }

    @Test
    void outOfRangeClientSlotReturnsUnmapped() {
        List<RegistryTranslationMatrix.SlotEntry> snap = buildSnapshot("minecraft:stone");
        RegistryTranslationMatrix m = RegistryTranslationMatrix.buildFromSnapshots(snap, snap);

        assertEquals(RegistryTranslationMatrix.UNMAPPED, m.clientToServer(-1));
        assertEquals(RegistryTranslationMatrix.UNMAPPED, m.clientToServer(999));
    }

    @Test
    void keyPresentOnlyOnServerReturnsUnmapped() {
        // Server has an extra key that the client doesn't know about
        List<RegistryTranslationMatrix.SlotEntry> serverSnap = List.of(
            new RegistryTranslationMatrix.SlotEntry(0, "mod:shared"),
            new RegistryTranslationMatrix.SlotEntry(1, "mod:server_only")
        );
        List<RegistryTranslationMatrix.SlotEntry> clientSnap = List.of(
            new RegistryTranslationMatrix.SlotEntry(0, "mod:shared")
        );

        RegistryTranslationMatrix m = RegistryTranslationMatrix.buildFromSnapshots(serverSnap, clientSnap);

        assertEquals(0, m.serverToClient(0), "shared key should map to client slot 0");
        assertEquals(RegistryTranslationMatrix.UNMAPPED, m.serverToClient(1),
            "server-only key should be UNMAPPED on client");
    }

    @Test
    void keyPresentOnlyOnClientReturnsUnmapped() {
        List<RegistryTranslationMatrix.SlotEntry> serverSnap = List.of(
            new RegistryTranslationMatrix.SlotEntry(0, "mod:shared")
        );
        List<RegistryTranslationMatrix.SlotEntry> clientSnap = List.of(
            new RegistryTranslationMatrix.SlotEntry(0, "mod:shared"),
            new RegistryTranslationMatrix.SlotEntry(1, "mod:client_only")
        );

        RegistryTranslationMatrix m = RegistryTranslationMatrix.buildFromSnapshots(serverSnap, clientSnap);

        assertEquals(0, m.serverToClient(0), "shared key maps to client slot 0");
        // client slot 1 (client_only) has no server counterpart
        assertEquals(RegistryTranslationMatrix.UNMAPPED, m.clientToServer(1),
            "client-only slot should be UNMAPPED");
    }

    // ── build(snapshot, localRegistry) ──────────────────────────────────────

    @Test
    void buildFromSnapshotAndLocalRegistryMapsCorrectly() {
        // Build a local registry (client-side FrozenStringIntHashIndex)
        Map<String, Integer> localEntries = new LinkedHashMap<>();
        localEntries.put("minecraft:air",   0);
        localEntries.put("minecraft:stone", 1);
        localEntries.put("minecraft:grass", 2);
        FrozenStringIntHashIndex localRegistry = FrozenStringIntHashIndex.build(localEntries);

        // Server assigned the same keys to different slots
        List<RegistryTranslationMatrix.SlotEntry> serverSnapshot = List.of(
            new RegistryTranslationMatrix.SlotEntry(5, "minecraft:grass"),
            new RegistryTranslationMatrix.SlotEntry(6, "minecraft:air"),
            new RegistryTranslationMatrix.SlotEntry(7, "minecraft:stone")
        );

        RegistryTranslationMatrix m = RegistryTranslationMatrix.build(serverSnapshot, localRegistry);

        // localRegistry.toKeySlotMap() returns slot index in the slotKeys array (i.e. 0,1,2)
        // These are the MPHF-assigned slot positions in the local registry.
        // We validate round-trip: translate server slot → client slot, then look up key.
        Map<String, Integer> localSlots = localRegistry.toKeySlotMap();
        for (RegistryTranslationMatrix.SlotEntry e : serverSnapshot) {
            Integer expectedClientSlot = localSlots.get(e.key());
            assertNotNull(expectedClientSlot, "Key " + e.key() + " should exist in local registry");
            assertEquals(expectedClientSlot.intValue(), m.serverToClient(e.slot()),
                "Server slot " + e.slot() + " (" + e.key() + ") should map to client slot " + expectedClientSlot);
        }
    }

    @Test
    void buildReturnsIdentityForEmptySnapshot() {
        Map<String, Integer> localEntries = Map.of("minecraft:stone", 0);
        FrozenStringIntHashIndex localRegistry = FrozenStringIntHashIndex.build(localEntries);

        RegistryTranslationMatrix m = RegistryTranslationMatrix.build(List.of(), localRegistry);
        assertEquals(0, m.serverSize());
        assertEquals(RegistryTranslationMatrix.UNMAPPED, m.serverToClient(0));
    }

    // ── Serialise / deserialise round-trip ───────────────────────────────────

    @Test
    void serialiseDeserialiseRoundTrip() {
        List<RegistryTranslationMatrix.SlotEntry> original = List.of(
            new RegistryTranslationMatrix.SlotEntry(0,  "minecraft:air"),
            new RegistryTranslationMatrix.SlotEntry(1,  "minecraft:stone"),
            new RegistryTranslationMatrix.SlotEntry(42, "modid:special_block"),
            new RegistryTranslationMatrix.SlotEntry(99, "other:item_with_long_name_to_test_utf8")
        );

        byte[] wire = RegistryTranslationMatrix.serialiseSnapshot(original);
        assertNotNull(wire);
        assertTrue(wire.length > 0);

        List<RegistryTranslationMatrix.SlotEntry> decoded = RegistryTranslationMatrix.deserialiseSnapshot(wire);

        assertEquals(original.size(), decoded.size());
        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.get(i).slot(), decoded.get(i).slot(),
                "slot mismatch at index " + i);
            assertEquals(original.get(i).key(), decoded.get(i).key(),
                "key mismatch at index " + i);
        }
    }

    @Test
    void serialiseViaListOverloadMatchesRegistryOverload() {
        Map<String, Integer> entries = new LinkedHashMap<>();
        entries.put("alpha:a", 0);
        entries.put("beta:b",  1);
        entries.put("gamma:c", 2);
        FrozenStringIntHashIndex registry = FrozenStringIntHashIndex.build(entries);

        byte[] viaRegistry = RegistryTranslationMatrix.serialiseSnapshot(registry);
        List<RegistryTranslationMatrix.SlotEntry> entryList = registry.toSlotEntryList();
        byte[] viaList    = RegistryTranslationMatrix.serialiseSnapshot(entryList);

        // Both overloads must produce identical bytes
        assertArrayEquals(viaRegistry, viaList,
            "serialiseSnapshot(registry) and serialiseSnapshot(list) must produce identical wire bytes");
    }

    @Test
    void roundTripPreservesUtf8Keys() {
        // Keys with non-ASCII characters (cyrillic, emoji, multi-byte)
        List<RegistryTranslationMatrix.SlotEntry> original = List.of(
            new RegistryTranslationMatrix.SlotEntry(0, "мод:предмет"),
            new RegistryTranslationMatrix.SlotEntry(1, "mod:\u00e9l\u00e9ment"),  // élément
            new RegistryTranslationMatrix.SlotEntry(2, "mod:item\uD83D\uDE00")    // emoji
        );

        List<RegistryTranslationMatrix.SlotEntry> decoded =
            RegistryTranslationMatrix.deserialiseSnapshot(
                RegistryTranslationMatrix.serialiseSnapshot(original));

        for (int i = 0; i < original.size(); i++) {
            assertEquals(original.get(i).key(), decoded.get(i).key(),
                "UTF-8 key must survive round-trip at index " + i);
        }
    }

    @Test
    void emptySnapshotSerialiseDeserialise() {
        byte[] wire = RegistryTranslationMatrix.serialiseSnapshot(List.of());
        List<RegistryTranslationMatrix.SlotEntry> decoded =
            RegistryTranslationMatrix.deserialiseSnapshot(wire);
        assertTrue(decoded.isEmpty());
    }

    // ── install / active / reset lifecycle ──────────────────────────────────

    @Test
    void installedMatrixIsReturnedByActive() {
        List<RegistryTranslationMatrix.SlotEntry> snap = List.of(
            new RegistryTranslationMatrix.SlotEntry(0, "mc:stone"),
            new RegistryTranslationMatrix.SlotEntry(1, "mc:dirt")
        );
        RegistryTranslationMatrix m = RegistryTranslationMatrix.buildFromSnapshots(snap, snap);
        RegistryTranslationMatrix.install(m);

        assertSame(m, RegistryTranslationMatrix.active());
    }

    // ── Large registry stress ────────────────────────────────────────────────

    @Test
    void largeRegistryMapsAllKeysCorrectly() {
        int N = 1_000;
        List<RegistryTranslationMatrix.SlotEntry> serverSnap = new ArrayList<>(N);
        List<RegistryTranslationMatrix.SlotEntry> clientSnap = new ArrayList<>(N);

        for (int i = 0; i < N; i++) {
            serverSnap.add(new RegistryTranslationMatrix.SlotEntry(i, "mod:key_" + i));
            // Client stores keys in reverse order
            clientSnap.add(new RegistryTranslationMatrix.SlotEntry(N - 1 - i, "mod:key_" + i));
        }

        RegistryTranslationMatrix m = RegistryTranslationMatrix.buildFromSnapshots(serverSnap, clientSnap);

        assertEquals(N, m.serverSize());
        assertEquals(N, m.clientSize());

        for (int i = 0; i < N; i++) {
            int expectedClient = N - 1 - i;
            assertEquals(expectedClient, m.serverToClient(i),
                "key_" + i + ": server=" + i + " → client=" + expectedClient);
            assertEquals(i, m.clientToServer(expectedClient),
                "key_" + i + ": client=" + expectedClient + " → server=" + i);
        }
    }

    @Test
    void bijectivePropertyHoldsForLargeRegistry() {
        int N = 500;
        List<RegistryTranslationMatrix.SlotEntry> serverSnap = new ArrayList<>(N);
        List<RegistryTranslationMatrix.SlotEntry> clientSnap = new ArrayList<>(N);

        // Shuffle assignment: client slot = (server slot * 7) % N  (coprime → bijective)
        for (int i = 0; i < N; i++) {
            serverSnap.add(new RegistryTranslationMatrix.SlotEntry(i, "ns:k" + i));
            clientSnap.add(new RegistryTranslationMatrix.SlotEntry((i * 7) % N, "ns:k" + i));
        }

        RegistryTranslationMatrix m = RegistryTranslationMatrix.buildFromSnapshots(serverSnap, clientSnap);

        for (int s = 0; s < N; s++) {
            int c = m.serverToClient(s);
            assertNotEquals(RegistryTranslationMatrix.UNMAPPED, c, "slot " + s + " must be mapped");
            assertEquals(s, m.clientToServer(c),
                "bijective round-trip failed at server slot " + s);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static List<RegistryTranslationMatrix.SlotEntry> buildSnapshot(String... keys) {
        List<RegistryTranslationMatrix.SlotEntry> list = new ArrayList<>(keys.length);
        for (int i = 0; i < keys.length; i++) {
            list.add(new RegistryTranslationMatrix.SlotEntry(i, keys[i]));
        }
        return list;
    }
}
