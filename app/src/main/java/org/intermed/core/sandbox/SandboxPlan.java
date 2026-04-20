package org.intermed.core.sandbox;

import com.google.gson.JsonObject;

/**
 * Immutable sandbox routing decision for a mod.
 */
public record SandboxPlan(String modId,
                          SandboxMode requestedMode,
                          SandboxMode effectiveMode,
                          boolean risky,
                          boolean hotPath,
                          boolean fallbackApplied,
                          String reason,
                          String modulePath,
                          String entrypoint) {

    public boolean isSandboxed() {
        return effectiveMode != SandboxMode.NATIVE;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("modId", modId);
        json.addProperty("requested", requestedMode.externalName());
        json.addProperty("effective", effectiveMode.externalName());
        json.addProperty("risky", risky);
        json.addProperty("hotPath", hotPath);
        json.addProperty("fallbackApplied", fallbackApplied);
        json.addProperty("reason", reason == null ? "" : reason);
        if (modulePath != null && !modulePath.isBlank()) {
            json.addProperty("modulePath", modulePath);
        }
        if (entrypoint != null && !entrypoint.isBlank()) {
            json.addProperty("entrypoint", entrypoint);
        }
        return json;
    }
}
