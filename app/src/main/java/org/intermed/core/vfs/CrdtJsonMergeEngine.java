package org.intermed.core.vfs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * CRDT-based JSON array merge engine using {@link LwwElementSet} (ТЗ 3.5.6).
 *
 * <h3>Problem with JSON Patch</h3>
 * {@link JsonPatchMergeEngine} processes sources left-to-right and applies
 * replace/add patches sequentially.  When two sources disagree on the order of
 * array elements (e.g. two mods both appending to {@code "values"} in a tag file
 * at the same millisecond), the last source wins — an ordering-dependent result
 * that breaks determinism across different load orders.
 *
 * <h3>Solution</h3>
 * This engine treats JSON arrays as LWW-Element-Sets.  Each element is keyed by
 * its canonical JSON string representation.  The timestamp is the source's
 * declared priority (mod load order index, higher = more recent).  The merge is
 * commutative: regardless of the order in which sources are processed, the final
 * set is identical.
 *
 * <h3>Scope</h3>
 * Only array fields whose resource path and pointer match the same heuristics as
 * {@link JsonPatchMergeEngine#supportsAppendMerge} use CRDT semantics.  All other
 * fields fall through to standard last-write-wins scalar/object replacement.
 *
 * <h3>Integration</h3>
 * Used by {@link VirtualFileSystemRouter} when {@code crdt.merge} is enabled in
 * {@link VirtualFileSystemOverrides} for a given resource prefix.  The result
 * replaces the output of {@link JsonPatchMergeEngine} for qualifying paths.
 */
public final class CrdtJsonMergeEngine {

    private CrdtJsonMergeEngine() {}

    /**
     * Merges multiple JSON sources for {@code resourcePath} using CRDT semantics
     * for qualifying arrays and last-write-wins for all other fields.
     *
     * @param resourcePath  VFS path (used for heuristics, e.g. {@code "/tags/items/..."})
     * @param sources       ordered list of sources; index 0 = lowest priority,
     *                      {@code sources.size()-1} = highest priority
     * @return merged document and diagnostics
     */
    public static MergeResult merge(String resourcePath,
                                    List<JsonPatchMergeEngine.JsonSource> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("CRDT merge requires at least one source");
        }

        JsonElement base = sources.get(0).document().deepCopy();
        int crdtArrayMerges = 0;
        int lwwScalarReplaces = 0;

        for (int i = 1; i < sources.size(); i++) {
            JsonPatchMergeEngine.JsonSource src = sources.get(i);
            long priority = i; // higher index = higher priority (later mod wins)
            int[] counters = new int[2];
            base = mergeElements(resourcePath, "", base, src.document(), priority, counters);
            crdtArrayMerges    += counters[0];
            lwwScalarReplaces  += counters[1];
        }

        return new MergeResult(base, crdtArrayMerges, lwwScalarReplaces);
    }

    // ── Internal recursive merge ───────────────────────────────────────────────

    private static JsonElement mergeElements(String resourcePath,
                                              String pointer,
                                              JsonElement current,
                                              JsonElement incoming,
                                              long priority,
                                              int[] counters) {
        if (current == null || current.isJsonNull()) {
            return incoming.deepCopy();
        }
        if (incoming == null || incoming.isJsonNull()) {
            return current;
        }

        // Object: recurse per field
        if (current.isJsonObject() && incoming.isJsonObject()) {
            return mergeObjects(resourcePath, pointer, current.getAsJsonObject(),
                                incoming.getAsJsonObject(), priority, counters);
        }

        // Array: use CRDT for qualifying paths, LWW otherwise
        if (current.isJsonArray() && incoming.isJsonArray()) {
            if (supportsCrdtMerge(resourcePath, pointer)) {
                counters[0]++;
                return crdtMergeArrays(current.getAsJsonArray(), incoming.getAsJsonArray(), priority);
            }
            // LWW: higher-priority source wins
            counters[1]++;
            return incoming.deepCopy();
        }

        // Scalar: LWW — incoming (higher priority) wins
        counters[1]++;
        return incoming.deepCopy();
    }

    private static JsonObject mergeObjects(String resourcePath,
                                            String pointer,
                                            JsonObject current,
                                            JsonObject incoming,
                                            long priority,
                                            int[] counters) {
        // Honour the Forge/NeoForge tag "replace": true convention: if the incoming
        // tag object carries replace=true, it wins entirely — do not CRDT-merge its
        // values array (ТЗ 3.5.6 preserves vanilla data-pack semantics).
        if (resourcePath.contains("/tags/")
                && incoming.has("replace")
                && incoming.get("replace").isJsonPrimitive()
                && incoming.get("replace").getAsBoolean()) {
            counters[1]++; // counted as LWW scalar replace
            return incoming.deepCopy();
        }
        JsonObject result = current.deepCopy();
        for (var entry : incoming.entrySet()) {
            String key = entry.getKey();
            String childPointer = childPointer(pointer, key);
            JsonElement incomingVal = entry.getValue();
            if (!result.has(key)) {
                result.add(key, incomingVal.deepCopy());
            } else {
                JsonElement merged = mergeElements(resourcePath, childPointer,
                    result.get(key), incomingVal, priority, counters);
                result.add(key, merged);
            }
        }
        return result;
    }

    /**
     * Merges two JSON arrays using LWW-Element-Set semantics.
     *
     * <p>Each element is represented by its canonical string form (JSON serialization).
     * Elements present in {@code incoming} but not yet in {@code current} are
     * added with the given priority.  Elements in {@code current} are retained
     * unless explicitly removed (remove is not triggered here — only absent-from-
     * incoming items are kept; true deletion requires a separate tombstone convention
     * or an explicit remove signal which is not present in vanilla data packs).
     *
     * <p>The resulting order: base elements first (stable), then new elements from
     * incoming in their original order.
     */
    private static JsonArray crdtMergeArrays(JsonArray current, JsonArray incoming, long priority) {
        // Build LWW set from base (priority = 0 = oldest)
        LwwElementSet<String> set = new LwwElementSet<>();
        List<String> baseOrder = new ArrayList<>(current.size());
        for (JsonElement el : current) {
            String key = canonical(el);
            set.add(key, 0L);
            baseOrder.add(key);
        }

        // Add incoming elements at higher priority
        List<String> incomingOrder = new ArrayList<>(incoming.size());
        for (JsonElement el : incoming) {
            String key = canonical(el);
            set.add(key, priority);
            incomingOrder.add(key);
        }

        // Build result: base order first, then new elements from incoming
        List<String> resultKeys = new ArrayList<>(baseOrder.size() + incomingOrder.size());
        java.util.Set<String> seen = new java.util.LinkedHashSet<>(baseOrder);
        resultKeys.addAll(baseOrder);
        for (String key : incomingOrder) {
            if (seen.add(key)) {
                resultKeys.add(key);
            }
        }

        // Reconstruct JSON array (only include elements present in the LWW set)
        // Build a lookup from canonical key → original JsonElement for reconstruction
        java.util.Map<String, JsonElement> elementByKey = new java.util.LinkedHashMap<>();
        for (JsonElement el : current) {
            elementByKey.putIfAbsent(canonical(el), el);
        }
        for (JsonElement el : incoming) {
            elementByKey.putIfAbsent(canonical(el), el);
        }

        JsonArray result = new JsonArray();
        for (String key : resultKeys) {
            if (set.contains(key)) {
                JsonElement el = elementByKey.get(key);
                if (el != null) result.add(el.deepCopy());
            }
        }
        return result;
    }

    // ── Heuristics (mirrors JsonPatchMergeEngine.supportsAppendMerge) ─────────

    private static boolean supportsCrdtMerge(String resourcePath, String pointer) {
        String leaf = leafToken(pointer);
        if ("values".equals(leaf) && resourcePath.contains("/tags/")) return true;
        if (resourcePath.contains("/loot_tables/")
            && ("pools".equals(leaf) || "entries".equals(leaf)
                || "functions".equals(leaf) || "conditions".equals(leaf)
                || "children".equals(leaf))) return true;
        if (resourcePath.contains("/advancements/")
            && ("requirements".equals(leaf) || "criteria".equals(leaf)
                || "recipes".equals(leaf))) return true;
        if (resourcePath.contains("/predicates/")
            && ("terms".equals(leaf) || "conditions".equals(leaf))) return true;
        if ((resourcePath.contains("/forge/biome_modifier/")
             || resourcePath.contains("/neoforge/biome_modifier/"))
            && ("biomes".equals(leaf) || "features".equals(leaf)
                || "spawners".equals(leaf) || "carvers".equals(leaf)
                || "steps".equals(leaf))) return true;
        if (resourcePath.contains("/worldgen/")
            && ("biomes".equals(leaf) || "features".equals(leaf)
                || "spawners".equals(leaf) || "carvers".equals(leaf)
                || "elements".equals(leaf) || "processors".equals(leaf)
                || "layers".equals(leaf) || "decorators".equals(leaf)
                || "rules".equals(leaf) || "targets".equals(leaf)
                || "templates".equals(leaf) || "placements".equals(leaf)
                || "starts".equals(leaf))) return true;
        return resourcePath.contains("/recipes/")
            && ("ingredients".equals(leaf) || "results".equals(leaf)
                || "conditions".equals(leaf));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String canonical(JsonElement el) {
        return el == null ? "null" : el.toString();
    }

    private static String leafToken(String pointer) {
        if (pointer == null || pointer.isEmpty()) return "";
        int slash = pointer.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= pointer.length()) return pointer;
        return pointer.substring(slash + 1).replace("~1", "/").replace("~0", "~");
    }

    private static String childPointer(String pointer, String token) {
        String escaped = token.replace("~", "~0").replace("/", "~1");
        return pointer.isEmpty() ? "/" + escaped : pointer + "/" + escaped;
    }

    // ── Result type ───────────────────────────────────────────────────────────

    /**
     * Result of a CRDT merge.
     *
     * @param mergedDocument    the final merged JSON element
     * @param crdtArrayMerges   number of arrays merged via CRDT semantics
     * @param lwwScalarReplaces number of scalars/objects merged via LWW
     */
    public record MergeResult(JsonElement mergedDocument,
                               int crdtArrayMerges,
                               int lwwScalarReplaces) {}
}
