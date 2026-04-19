package org.intermed.core.vfs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.intermed.core.cache.AOTCacheManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * External user overrides for VFS conflict resolution.
 *
 * <p>Overrides are resolved from {@code <configDir>/intermed-vfs-overrides.json}
 * and allow users to pin path-specific priority and merge policy without
 * rebuilding mods or the platform.
 */
final class VirtualFileSystemOverrides {

    static final String EXTERNAL_OVERRIDES_FILE = "intermed-vfs-overrides.json";
    private static final VirtualFileSystemOverrides EMPTY =
        new VirtualFileSystemOverrides(List.of(), AOTCacheManager.sha256("intermed-vfs-overrides:none"));

    private final List<Rule> rules;
    private final String fingerprint;

    private VirtualFileSystemOverrides(List<Rule> rules, String fingerprint) {
        this.rules = List.copyOf(rules);
        this.fingerprint = fingerprint;
    }

    static VirtualFileSystemOverrides load(Path configDir) {
        if (configDir == null) {
            return EMPTY;
        }
        Path file = configDir.resolve(EXTERNAL_OVERRIDES_FILE).toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            return EMPTY;
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            JsonObject root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            List<Rule> parsedRules = new ArrayList<>();
            if (root.has("resources") && root.get("resources").isJsonArray()) {
                JsonArray resources = root.getAsJsonArray("resources");
                for (JsonElement element : resources) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    Rule rule = Rule.parse(element.getAsJsonObject());
                    if (rule != null) {
                        parsedRules.add(rule);
                    }
                }
            }
            return new VirtualFileSystemOverrides(parsedRules, AOTCacheManager.sha256(bytes));
        } catch (Exception e) {
            System.err.println("[InterMed VFS] Failed to load overrides: " + e.getMessage());
            return new VirtualFileSystemOverrides(List.of(), AOTCacheManager.sha256("intermed-vfs-overrides:error"));
        }
    }

    String fingerprint() {
        return fingerprint;
    }

    Rule match(String resourcePath) {
        return rules.stream()
            .filter(rule -> rule.matches(resourcePath))
            .max(Comparator.comparingInt(Rule::specificity))
            .orElse(null);
    }

    record Rule(String pattern,
                Pattern regex,
                int specificity,
                String policy,
                String winnerModId,
                List<String> priorityMods) {

        private static Rule parse(JsonObject json) {
            if (json == null || !json.has("path") || !json.get("path").isJsonPrimitive()) {
                return null;
            }
            String pattern = json.get("path").getAsString();
            if (pattern == null || pattern.isBlank()) {
                return null;
            }
            String policy = json.has("policy") && json.get("policy").isJsonPrimitive()
                ? json.get("policy").getAsString().trim()
                : null;
            String winnerModId = json.has("winner") && json.get("winner").isJsonPrimitive()
                ? json.get("winner").getAsString().trim()
                : null;
            ArrayList<String> priorityMods = new ArrayList<>();
            if (json.has("priorityMods") && json.get("priorityMods").isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray("priorityMods")) {
                    if (element.isJsonPrimitive()) {
                        String modId = element.getAsString();
                        if (modId != null && !modId.isBlank() && !priorityMods.contains(modId.trim())) {
                            priorityMods.add(modId.trim());
                        }
                    }
                }
            }
            return new Rule(
                pattern.trim(),
                compileGlob(pattern.trim()),
                literalLength(pattern.trim()),
                policy == null || policy.isBlank() ? null : policy,
                winnerModId == null || winnerModId.isBlank() ? null : winnerModId,
                List.copyOf(priorityMods)
            );
        }

        boolean matches(String resourcePath) {
            return resourcePath != null && regex.matcher(resourcePath).matches();
        }

        String policySource() {
            return "override:" + pattern;
        }

        private static Pattern compileGlob(String glob) {
            StringBuilder regex = new StringBuilder("^");
            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                if (c == '*') {
                    boolean doubleStar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                    if (doubleStar) {
                        regex.append(".*");
                        i++;
                    } else {
                        regex.append("[^/]*");
                    }
                    continue;
                }
                if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                    regex.append('\\');
                }
                regex.append(c);
            }
            regex.append('$');
            return Pattern.compile(regex.toString());
        }

        private static int literalLength(String pattern) {
            int score = 0;
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c != '*') {
                    score++;
                }
            }
            return score;
        }
    }
}
