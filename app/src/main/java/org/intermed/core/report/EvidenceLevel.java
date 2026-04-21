package org.intermed.core.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * Canonical evidence ladder used by launcher reports and diagnostics bundles.
 *
 * <p>The levels are intentionally conservative.  A report may attach only the
 * subset it can justify from machine-readable artifacts; missing levels must
 * not be implied by adjacent stronger evidence.</p>
 */
public enum EvidenceLevel {
    PARSED(
        "parsed",
        "Manifest or data-driven artifact parsed successfully. This does not prove boot, gameplay, security, or performance compatibility."
    ),
    BOOTED(
        "booted",
        "Automated runtime evidence shows the target reached its boot-complete marker. This is still not gameplay, multiplayer, or security proof."
    ),
    SESSION_OK(
        "session-ok",
        "Automated short-session probes completed successfully. This is stronger than boot-only evidence but still not long-run stability proof."
    ),
    SOAK_OK(
        "soak-ok",
        "Long-running automated soak evidence completed successfully for the scoped workload."
    ),
    STRICT_OK(
        "strict-ok",
        "Strict fail-closed capability and sandbox verification passed for the scoped workload."
    ),
    BASELINE_OK(
        "baseline-ok",
        "Native-loader baseline comparison artifacts are attached for the scoped workload."
    );

    private final String slug;
    private final String description;

    EvidenceLevel(String slug, String description) {
        this.slug = slug;
        this.description = description;
    }

    public String slug() {
        return slug;
    }

    public String description() {
        return description;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("level", name());
        json.addProperty("slug", slug);
        json.addProperty("ordinal", ordinal());
        json.addProperty("description", description);
        return json;
    }

    public static JsonArray canonicalLevels() {
        JsonArray array = new JsonArray();
        for (EvidenceLevel level : values()) {
            array.add(level.toJson());
        }
        return array;
    }

    public static JsonArray names(Collection<EvidenceLevel> levels) {
        JsonArray array = new JsonArray();
        if (levels == null) {
            return array;
        }
        LinkedHashSet<EvidenceLevel> deduped = new LinkedHashSet<>(levels);
        for (EvidenceLevel level : values()) {
            if (deduped.contains(level)) {
                array.add(level.name());
            }
        }
        return array;
    }

    public static EvidenceLevel highest(Collection<EvidenceLevel> levels) {
        EvidenceLevel highest = PARSED;
        if (levels == null || levels.isEmpty()) {
            return highest;
        }
        for (EvidenceLevel level : levels) {
            if (level != null && level.ordinal() > highest.ordinal()) {
                highest = level;
            }
        }
        return highest;
    }

    public static JsonObject truthModel(Collection<EvidenceLevel> achievedLevels, String note) {
        JsonObject json = new JsonObject();
        json.add("canonicalLevels", canonicalLevels());
        json.add("achievedLevels", names(achievedLevels));
        json.addProperty("highestLevel", highest(achievedLevels).name());
        if (note != null && !note.isBlank()) {
            json.addProperty("note", note);
        }
        return json;
    }
}
