package org.intermed.core.registry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bijective translation matrix between server and client MPHF registry indices
 * (ТЗ 3.5.3).
 *
 * <h3>Problem</h3>
 * {@link FrozenStringIntHashIndex} generates a minimal-perfect hash function
 * (MPHF) independently on each JVM.  The server builds its MPHF from the
 * server's registry population; the client builds its own from the client-side
 * population.  Because the two populations may differ in iteration order, seed
 * search results, or mod load order, the resulting flat-array indices
 * {@code i_server} and {@code i_client} for the same registry key {@code K}
 * are generally different:
 * <pre>
 *   MPHF_server(K) = 42   ≠   MPHF_client(K) = 17
 * </pre>
 * A packet containing the raw MPHF index 42 from the server is mis-interpreted
 * as key 17 by the client.
 *
 * <h3>Solution — bijective matrix</h3>
 * During the Handshake phase the server sends its <em>full registry snapshot</em>
 * (ordered list of keys and their MPHF-slot assignments).  The client receives
 * the snapshot and computes the bijective translation arrays:
 * <pre>
 *   serverToClient[serverSlot] = clientSlot
 *   clientToServer[clientSlot] = serverSlot
 * </pre>
 * After this, every incoming server packet's raw slot index is translated in
 * O(1) before the client looks it up in its local flat array.
 *
 * <h3>Wire format</h3>
 * The snapshot is serialised as a compact binary blob:
 * <pre>
 *   [4 bytes]  entry count N
 *   for each of N entries:
 *     [4 bytes]  server slot index
 *     [2 bytes]  key UTF-8 length  L
 *     [L bytes]  key UTF-8 bytes
 * </pre>
 *
 * <h3>Thread safety</h3>
 * The active matrix is stored in an {@link AtomicReference}; reads on hot
 * packet-processing threads never block.
 */
public final class RegistryTranslationMatrix {

    /** Sentinel value: slot not present in the remote registry. */
    public static final int UNMAPPED = -1;

    /** No-op matrix installed before a handshake has occurred. */
    private static final RegistryTranslationMatrix IDENTITY = new RegistryTranslationMatrix(
        new int[0], new int[0], 0, 0);

    private static final AtomicReference<RegistryTranslationMatrix> ACTIVE =
        new AtomicReference<>(IDENTITY);

    // ── Translation arrays ────────────────────────────────────────────────────

    /** serverSlot → clientSlot.  Length = server registry size. */
    private final int[] serverToClient;
    /** clientSlot → serverSlot.  Length = client registry size. */
    private final int[] clientToServer;
    private final int serverSize;
    private final int clientSize;

    private RegistryTranslationMatrix(int[] serverToClient, int[] clientToServer,
                                       int serverSize, int clientSize) {
        this.serverToClient = serverToClient;
        this.clientToServer = clientToServer;
        this.serverSize = serverSize;
        this.clientSize = clientSize;
    }

    // ── Active matrix access ──────────────────────────────────────────────────

    /** Returns the currently active translation matrix. */
    public static RegistryTranslationMatrix active() {
        return ACTIVE.get();
    }

    /** Installs a new translation matrix (called on handshake completion). */
    public static void install(RegistryTranslationMatrix matrix) {
        ACTIVE.set(matrix != null ? matrix : IDENTITY);
        System.out.printf("[RegistrySync] Translation matrix installed: server=%d client=%d entries%n",
            matrix != null ? matrix.serverSize : 0,
            matrix != null ? matrix.clientSize : 0);
    }

    /** Resets to identity (no translation). Used on disconnect. */
    public static void reset() {
        ACTIVE.set(IDENTITY);
    }

    /**
     * Builds a wire-format snapshot of the current JVM's frozen virtual registry.
     *
     * <p>This is the canonical runtime entry-point for multiplayer registry-sync.
     * The method intentionally hides the package-private
     * {@link FrozenStringIntHashIndex} implementation from network callers.
     */
    public static byte[] buildLocalSnapshot() {
        return VirtualRegistryService.buildRegistrySyncSnapshot();
    }

    // ── Hot-path translation ──────────────────────────────────────────────────

    /**
     * Translates a server-side MPHF slot to the local (client-side) slot.
     * Returns {@link #UNMAPPED} if the server slot is out of range.
     */
    public int serverToClient(int serverSlot) {
        if (serverSlot < 0 || serverSlot >= serverToClient.length) {
            return UNMAPPED;
        }
        return serverToClient[serverSlot];
    }

    /**
     * Translates a client-side MPHF slot to the remote (server-side) slot.
     * Returns {@link #UNMAPPED} if the client slot is out of range.
     */
    public int clientToServer(int clientSlot) {
        if (clientSlot < 0 || clientSlot >= clientToServer.length) {
            return UNMAPPED;
        }
        return clientToServer[clientSlot];
    }

    public int serverSize() { return serverSize; }
    public int clientSize() { return clientSize; }

    // ── Builder ───────────────────────────────────────────────────────────────

    /**
     * Builds the bijective translation matrix by comparing the server registry
     * snapshot against the client's local frozen registry.
     *
     * @param serverSnapshot  ordered list of {@code (serverSlot, key)} pairs from
     *                        the server's {@link FrozenStringIntHashIndex}
     * @param localRegistry   the client-local frozen registry
     * @return the computed matrix
     */
    public static RegistryTranslationMatrix build(List<SlotEntry> serverSnapshot,
                                                   FrozenStringIntHashIndex localRegistry) {
        if (serverSnapshot == null || serverSnapshot.isEmpty()) {
            return IDENTITY;
        }

        int serverSize = 0;
        for (SlotEntry e : serverSnapshot) {
            serverSize = Math.max(serverSize, e.slot + 1);
        }

        // Build a reverse map: key → clientSlot from the local registry
        // We need to build the local snapshot too.
        Map<String, Integer> localKeyToSlot = localRegistry.toKeySlotMap();
        int clientSize = localKeyToSlot.values().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;

        int[] serverToClient = new int[serverSize];
        int[] clientToServer = new int[clientSize];

        java.util.Arrays.fill(serverToClient, UNMAPPED);
        java.util.Arrays.fill(clientToServer, UNMAPPED);

        int mapped = 0;
        for (SlotEntry entry : serverSnapshot) {
            Integer clientSlot = localKeyToSlot.get(entry.key);
            if (clientSlot != null) {
                serverToClient[entry.slot] = clientSlot;
                if (clientSlot < clientToServer.length) {
                    clientToServer[clientSlot] = entry.slot;
                }
                mapped++;
            }
        }

        System.out.printf("[RegistrySync] Mapped %d / %d server entries to client slots%n",
            mapped, serverSnapshot.size());

        return new RegistryTranslationMatrix(serverToClient, clientToServer, serverSize, clientSize);
    }

    /**
     * Builds the bijective translation matrix from two snapshot lists (server and
     * client), without requiring a live {@link FrozenStringIntHashIndex}.  Used by
     * the network bridge when the server has already serialised its snapshot to
     * a {@code byte[]} and the client has sent its own serialised snapshot back.
     *
     * @param serverSnapshot  (slot, key) pairs from the server registry
     * @param clientSnapshot  (slot, key) pairs from the client registry
     * @return the computed bijective matrix
     */
    public static RegistryTranslationMatrix buildFromSnapshots(List<SlotEntry> serverSnapshot,
                                                                List<SlotEntry> clientSnapshot) {
        if (serverSnapshot == null || serverSnapshot.isEmpty()
                || clientSnapshot == null || clientSnapshot.isEmpty()) {
            return IDENTITY;
        }

        int serverSize = 0;
        for (SlotEntry e : serverSnapshot) serverSize = Math.max(serverSize, e.slot + 1);

        // Build reverse map: key → clientSlot
        Map<String, Integer> clientKeyToSlot = new HashMap<>(clientSnapshot.size() * 2);
        int clientSize = 0;
        for (SlotEntry e : clientSnapshot) {
            clientKeyToSlot.put(e.key, e.slot);
            clientSize = Math.max(clientSize, e.slot + 1);
        }

        int[] serverToClient = new int[serverSize];
        int[] clientToServer = new int[clientSize];
        java.util.Arrays.fill(serverToClient, UNMAPPED);
        java.util.Arrays.fill(clientToServer, UNMAPPED);

        int mapped = 0;
        for (SlotEntry entry : serverSnapshot) {
            Integer clientSlot = clientKeyToSlot.get(entry.key);
            if (clientSlot != null) {
                if (entry.slot < serverSize) serverToClient[entry.slot] = clientSlot;
                if (clientSlot < clientSize) clientToServer[clientSlot] = entry.slot;
                mapped++;
            }
        }

        System.out.printf("[RegistrySync] Mapped %d / %d server entries to client slots (snapshot→snapshot)%n",
            mapped, serverSnapshot.size());
        return new RegistryTranslationMatrix(serverToClient, clientToServer, serverSize, clientSize);
    }

    /**
     * Convenience: serialises the given slot-entry list to the wire format without
     * needing a live {@link FrozenStringIntHashIndex}.
     */
    public static byte[] serialiseSnapshot(List<SlotEntry> entries) {
        if (entries == null) entries = List.of();
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(entries.size() * 32);
            DataOutputStream dos = new DataOutputStream(bos);
            dos.writeInt(entries.size());
            for (SlotEntry e : entries) {
                dos.writeInt(e.slot);
                byte[] keyBytes = e.key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                dos.writeShort(keyBytes.length);
                dos.write(keyBytes);
            }
            dos.flush();
            return bos.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialise registry snapshot", ex);
        }
    }

    // ── Wire serialisation ────────────────────────────────────────────────────

    /**
     * Serialises the server registry snapshot for transmission over the network.
     *
     * @param serverRegistry  the server's frozen registry
     * @return compact binary blob (see wire format in class Javadoc)
     */
    public static byte[] serialiseSnapshot(FrozenStringIntHashIndex serverRegistry) {
        List<SlotEntry> entries = serverRegistry.toSlotEntryList();
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(entries.size() * 32);
            DataOutputStream dos = new DataOutputStream(bos);
            dos.writeInt(entries.size());
            for (SlotEntry e : entries) {
                dos.writeInt(e.slot);
                byte[] keyBytes = e.key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                dos.writeShort(keyBytes.length);
                dos.write(keyBytes);
            }
            dos.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialise registry snapshot", e);
        }
    }

    /**
     * Deserialises a server registry snapshot from the wire format.
     *
     * @param data  binary payload received from the server
     * @return ordered list of {@code (slot, key)} pairs
     */
    public static List<SlotEntry> deserialiseSnapshot(byte[] data) {
        try {
            DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));
            int count = dis.readInt();
            List<SlotEntry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int slot = dis.readInt();
                int keyLen = dis.readUnsignedShort();
                byte[] keyBytes = new byte[keyLen];
                dis.readFully(keyBytes);
                String key = new String(keyBytes, java.nio.charset.StandardCharsets.UTF_8);
                entries.add(new SlotEntry(slot, key));
            }
            return entries;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deserialise registry snapshot", e);
        }
    }

    // ── Types ─────────────────────────────────────────────────────────────────

    /** Immutable (slot, key) pair used during snapshot exchange. */
    public record SlotEntry(int slot, String key) {}
}
