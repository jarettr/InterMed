package org.intermed.core.sandbox;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Objects;

/**
 * Structured outcome of a sandboxed mod entrypoint execution.
 */
public record SandboxExecutionResult(String modId,
                                     SandboxMode requestedMode,
                                     SandboxMode effectiveMode,
                                     String key,
                                     String target,
                                     boolean success,
                                     boolean nativeFallbackRecommended,
                                     String message,
                                     String planReason,
                                     String runtimeDiagnostics,
                                     String stdout,
                                     String stderr,
                                     List<Long> numericResults) {

    public SandboxExecutionResult {
        modId = sanitize(modId, "unknown");
        requestedMode = Objects.requireNonNullElse(requestedMode, SandboxMode.NATIVE);
        effectiveMode = Objects.requireNonNullElse(effectiveMode, SandboxMode.NATIVE);
        key = sanitize(key, "main");
        target = sanitize(target, "");
        message = sanitize(message, "");
        planReason = sanitize(planReason, "");
        runtimeDiagnostics = sanitize(runtimeDiagnostics, "");
        stdout = sanitize(stdout, "");
        stderr = sanitize(stderr, "");
        numericResults = numericResults == null ? List.of() : List.copyOf(numericResults);
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("modId", modId);
        json.addProperty("requestedMode", requestedMode.externalName());
        json.addProperty("effectiveMode", effectiveMode.externalName());
        json.addProperty("key", key);
        json.addProperty("target", target);
        json.addProperty("success", success);
        json.addProperty("nativeFallbackRecommended", nativeFallbackRecommended);
        json.addProperty("message", message);
        if (!planReason.isBlank()) {
            json.addProperty("planReason", planReason);
        }
        if (!runtimeDiagnostics.isBlank()) {
            json.addProperty("runtimeDiagnostics", runtimeDiagnostics);
        }
        if (!stdout.isBlank()) {
            json.addProperty("stdout", stdout);
        }
        if (!stderr.isBlank()) {
            json.addProperty("stderr", stderr);
        }
        if (!numericResults.isEmpty()) {
            JsonArray results = new JsonArray();
            for (Long result : numericResults) {
                results.add(result);
            }
            json.add("numericResults", results);
        }
        return json;
    }

    private static String sanitize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
