package org.intermed.core.vfs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Strategic JSON merge engine expressed as a small JSON-Patch pipeline.
 *
 * <p>The engine emits add/replace operations and applies them back onto a deep
 * copy of the accumulated document. This keeps the implementation close to JSON
 * Patch semantics while still allowing InterMed-specific merge heuristics for
 * data-driven hot spots such as tags and loot tables.
 */
final class JsonPatchMergeEngine {

    private JsonPatchMergeEngine() {}

    static MergeOutcome merge(String resourcePath, List<JsonSource> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("JSON merge requires at least one source");
        }

        JsonElement merged = sources.get(0).document().deepCopy();
        int operationCount = 0;
        boolean structuralConflict = false;

        for (int index = 1; index < sources.size(); index++) {
            JsonSource source = sources.get(index);
            List<PatchOperation> operations = new ArrayList<>();
            StructuralConflictTracker tracker = new StructuralConflictTracker();
            buildPatch(
                resourcePath,
                "",
                merged,
                source.document(),
                operations,
                tracker
            );
            merged = applyPatch(merged, operations);
            operationCount += operations.size();
            structuralConflict |= tracker.structuralConflict();
        }

        return new MergeOutcome(merged, operationCount, structuralConflict);
    }

    private static void buildPatch(String resourcePath,
                                   String pointer,
                                   JsonElement current,
                                   JsonElement incoming,
                                   List<PatchOperation> operations,
                                   StructuralConflictTracker tracker) {
        if (current == null || current.isJsonNull()) {
            operations.add(PatchOperation.add(pointer, incoming.deepCopy()));
            return;
        }
        if (incoming == null || incoming.isJsonNull()) {
            return;
        }

        if (current.isJsonObject() && incoming.isJsonObject()) {
            JsonObject currentObject = current.getAsJsonObject();
            JsonObject incomingObject = incoming.getAsJsonObject();
            if (resourcePath.contains("/tags/")
                && incomingObject.has("replace")
                && incomingObject.get("replace").isJsonPrimitive()
                && incomingObject.get("replace").getAsBoolean()) {
                tracker.markStructuralConflict();
                operations.add(PatchOperation.replace(pointer, incoming.deepCopy()));
                return;
            }
            for (var entry : incomingObject.entrySet()) {
                String key = entry.getKey();
                String childPointer = childPointer(pointer, key);
                if (!currentObject.has(key)) {
                    operations.add(PatchOperation.add(childPointer, entry.getValue().deepCopy()));
                    continue;
                }
                buildPatch(
                    resourcePath,
                    childPointer,
                    currentObject.get(key),
                    entry.getValue(),
                    operations,
                    tracker
                );
            }
            return;
        }

        if (current.isJsonArray() && incoming.isJsonArray()) {
            if (supportsAppendMerge(resourcePath, pointer)) {
                JsonArray mergedArray = appendUnique(current.getAsJsonArray(), incoming.getAsJsonArray());
                if (!jsonEquals(current, mergedArray)) {
                    operations.add(PatchOperation.replace(pointer, mergedArray));
                }
                return;
            }
            if (!jsonEquals(current, incoming)) {
                tracker.markStructuralConflict();
                operations.add(PatchOperation.replace(pointer, incoming.deepCopy()));
            }
            return;
        }

        if (!jsonEquals(current, incoming)) {
            tracker.markStructuralConflict();
            operations.add(PatchOperation.replace(pointer, incoming.deepCopy()));
        }
    }

    private static JsonArray appendUnique(JsonArray base, JsonArray incoming) {
        JsonArray merged = base.deepCopy();
        Set<String> seen = new LinkedHashSet<>();
        for (JsonElement element : merged) {
            seen.add(canonical(element));
        }
        for (JsonElement element : incoming) {
            String key = canonical(element);
            if (seen.add(key)) {
                merged.add(element.deepCopy());
            }
        }
        return merged;
    }

    private static JsonElement applyPatch(JsonElement root, List<PatchOperation> operations) {
        JsonElement working = root.deepCopy();
        for (PatchOperation operation : operations) {
            working = applyOperation(working, operation);
        }
        return working;
    }

    private static JsonElement applyOperation(JsonElement root, PatchOperation operation) {
        if (operation.pointer().isEmpty()) {
            return operation.value().deepCopy();
        }

        List<String> tokens = parsePointer(operation.pointer());
        JsonElement parent = root;
        for (int index = 0; index < tokens.size() - 1; index++) {
            String token = tokens.get(index);
            if (parent.isJsonObject()) {
                parent = parent.getAsJsonObject().get(token);
            } else if (parent.isJsonArray()) {
                parent = parent.getAsJsonArray().get(Integer.parseInt(token));
            } else {
                throw new IllegalStateException("Invalid JSON pointer navigation: " + operation.pointer());
            }
        }

        String leaf = tokens.get(tokens.size() - 1);
        if (parent.isJsonObject()) {
            parent.getAsJsonObject().add(leaf, operation.value().deepCopy());
            return root;
        }
        if (!parent.isJsonArray()) {
            throw new IllegalStateException("Invalid JSON pointer target: " + operation.pointer());
        }

        JsonArray array = parent.getAsJsonArray();
        if ("-".equals(leaf) || Integer.toString(array.size()).equals(leaf)) {
            array.add(operation.value().deepCopy());
            return root;
        }
        array.set(Integer.parseInt(leaf), operation.value().deepCopy());
        return root;
    }

    private static List<String> parsePointer(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        for (String raw : pointer.substring(1).split("/")) {
            tokens.add(raw.replace("~1", "/").replace("~0", "~"));
        }
        return tokens;
    }

    private static boolean supportsAppendMerge(String resourcePath, String pointer) {
        String leaf = leafToken(pointer);
        if ("values".equals(leaf) && resourcePath.contains("/tags/")) {
            return true;
        }
        if (resourcePath.contains("/loot_tables/")
            && ("pools".equals(leaf)
            || "entries".equals(leaf)
            || "functions".equals(leaf)
            || "conditions".equals(leaf)
            || "children".equals(leaf))) {
            return true;
        }
        if (resourcePath.contains("/advancements/")
            && ("requirements".equals(leaf)
            || "criteria".equals(leaf)
            || "recipes".equals(leaf))) {
            return true;
        }
        if (resourcePath.contains("/predicates/")
            && ("terms".equals(leaf) || "conditions".equals(leaf))) {
            return true;
        }
        if ((resourcePath.contains("/forge/biome_modifier/") || resourcePath.contains("/neoforge/biome_modifier/"))
            && ("biomes".equals(leaf)
            || "features".equals(leaf)
            || "spawners".equals(leaf)
            || "carvers".equals(leaf)
            || "steps".equals(leaf))) {
            return true;
        }
        if (resourcePath.contains("/worldgen/")
            && ("biomes".equals(leaf)
            || "features".equals(leaf)
            || "spawners".equals(leaf)
            || "carvers".equals(leaf)
            || "elements".equals(leaf)
            || "processors".equals(leaf)
            || "layers".equals(leaf)
            || "decorators".equals(leaf)
            || "rules".equals(leaf)
            || "targets".equals(leaf)
            || "templates".equals(leaf)
            || "placements".equals(leaf)
            || "starts".equals(leaf))) {
            return true;
        }
        return resourcePath.contains("/recipes/")
            && ("ingredients".equals(leaf) || "results".equals(leaf) || "conditions".equals(leaf));
    }

    private static String leafToken(String pointer) {
        if (pointer == null || pointer.isEmpty()) {
            return "";
        }
        int slash = pointer.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= pointer.length()) {
            return pointer;
        }
        return pointer.substring(slash + 1).replace("~1", "/").replace("~0", "~");
    }

    private static String childPointer(String pointer, String token) {
        String escaped = token.replace("~", "~0").replace("/", "~1");
        return pointer.isEmpty() ? "/" + escaped : pointer + "/" + escaped;
    }

    private static boolean jsonEquals(JsonElement left, JsonElement right) {
        return Objects.equals(canonical(left), canonical(right));
    }

    private static String canonical(JsonElement element) {
        return element == null ? "null" : element.toString();
    }

    record JsonSource(String modId, JsonElement document) {}

    record MergeOutcome(JsonElement mergedDocument, int operationCount, boolean structuralConflict) {}

    private record PatchOperation(String op, String pointer, JsonElement value) {
        private PatchOperation {
            Objects.requireNonNull(op, "op");
            Objects.requireNonNull(pointer, "pointer");
            Objects.requireNonNull(value, "value");
        }

        private static PatchOperation add(String pointer, JsonElement value) {
            return new PatchOperation("add", pointer, value);
        }

        private static PatchOperation replace(String pointer, JsonElement value) {
            return new PatchOperation("replace", pointer, value);
        }
    }

    private static final class StructuralConflictTracker {
        private boolean structuralConflict;

        private void markStructuralConflict() {
            structuralConflict = true;
        }

        private boolean structuralConflict() {
            return structuralConflict;
        }
    }
}
