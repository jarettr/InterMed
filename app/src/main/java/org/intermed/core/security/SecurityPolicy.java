package org.intermed.core.security;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores the capability profile granted to each mod.
 *
 * <h3>Profile sources (in priority order)</h3>
 * <ol>
 *   <li><b>Mod manifest</b> — {@code intermed:permissions} / {@code intermed:security}
 *       fields inside the mod's {@code fabric.mod.json} or {@code mods.toml}.</li>
 *   <li><b>External override file</b> — {@code <configDir>/intermed-security-profiles.json},
 *       an array of per-mod profile objects loaded at boot via
 *       {@link #loadExternalProfiles(Path)}.  Profiles here override manifest defaults,
 *       allowing server admins to tighten or loosen permissions without modifying JARs.</li>
 * </ol>
 *
     * <h3>Manifest form examples</h3>
     * <pre>
     *   "intermed:permissions": ["FILE_READ", "NETWORK_CONNECT"]
 *
 *   "intermed:security": {
 *     "capabilities": ["FILE_READ", "NETWORK_CONNECT"],
 *     "fileReadPaths": ["config/**", "/tmp/cache"],
 *     "fileWritePaths": ["config/my-mod/**"],
 *     "networkHosts": ["api.modrinth.com", "*.example.org"],
 *     "unsafeMembers": ["allocateInstance", "putObject"],
 *     "memoryMembers": ["VarHandle.setVolatile", "MemorySegment.reinterpret"]
 *   }
 * </pre>
 *
 * <h3>External override file format ({@code intermed-security-profiles.json})</h3>
 * <pre>
 *   [
 *     {
 *       "modId": "some-mod",
 *       "capabilities": ["FILE_READ"],
 *       "fileReadPaths": ["config/some-mod/**"],
 *       "fileWritePaths": [],
 *       "networkHosts": [],
 *       "unsafeMembers": [],
 *       "memoryMembers": []
 *     }
     *   ]
     * </pre>
     *
     * <p>Production default: simple capability declarations no longer widen into
     * catch-all file or network access. A mod can declare intent with
     * {@code intermed:permissions}, but scoped resources must be granted through
     * {@code intermed:security} or an external profile override. Legacy wildcard
     * widening remains available only behind
     * {@code security.legacy.broad.permissions.enabled=true}.
     */
public final class SecurityPolicy {

    /** Name of the external per-mod profile override file, resolved under configDir. */
    public static final String EXTERNAL_PROFILES_FILE = "intermed-security-profiles.json";

    private static final Map<String, ModSecurityProfile> MOD_PROFILES = new ConcurrentHashMap<>();

    static {
        installBuiltinProfiles();
    }

    private SecurityPolicy() {}

    public static void registerModCapabilities(String modId, JsonObject modJson) {
        ModSecurityProfile profile = parseProfile(modId, modJson, true);
        MOD_PROFILES.put(modId, profile);
        CapabilityManager.invalidateMod(modId);
        System.out.println("[SecurityPolicy] Registered profile for '" + modId + "': " + profile.describe());
    }

    public static void unregisterMod(String modId) {
        if (modId == null) {
            return;
        }
        MOD_PROFILES.remove(modId);
        CapabilityManager.invalidateMod(modId);
    }

    public static boolean hasPermission(String modId, Capability capability) {
        return hasPermission(modId, SecurityRequest.capabilityOnly(capability));
    }

    public static boolean hasCapabilityGrant(String modId, Capability capability) {
        if (!BootstrapRuntimeConfig.isSecurityStrictMode()) {
            return true;
        }
        if (capability == null) {
            return false;
        }
        if (modId == null) {
            return KernelContext.isActive();
        }
        ModSecurityProfile profile = MOD_PROFILES.get(modId);
        return profile != null && profile.hasCapability(capability);
    }

    public static boolean hasPermission(String modId, SecurityRequest request) {
        if (!BootstrapRuntimeConfig.isSecurityStrictMode()) {
            return true;
        }
        return allowsInStrictMode(modId, request);
    }

    static boolean allowsInStrictMode(String modId, SecurityRequest request) {
        if (modId == null) {
            return KernelContext.isActive();
        }
        ModSecurityProfile profile = MOD_PROFILES.get(modId);
        return profile != null && profile.allows(request);
    }

    static String denialReason(String modId, SecurityRequest request) {
        if (modId == null) {
            return "unattributed caller outside trusted host stack";
        }
        ModSecurityProfile profile = MOD_PROFILES.get(modId);
        if (profile == null) {
            return "no security profile registered for this mod; strict mode defaults to deny";
        }
        if (request == null || request.capability() == null) {
            return "security request is incomplete";
        }
        if (!profile.hasCapability(request.capability())) {
            return "profile does not grant " + request.capability();
        }
        return switch (request.capability()) {
            case FILE_READ -> "FILE_READ is granted, but the path is outside fileReadPaths";
            case FILE_WRITE -> "FILE_WRITE is granted, but the path is outside fileWritePaths";
            case NETWORK_CONNECT -> "NETWORK_CONNECT is granted, but the host is outside networkHosts";
            case MEMORY_ACCESS, UNSAFE_ACCESS ->
                "memory access is granted, but the member is outside memoryMembers/unsafeMembers";
            case REFLECTION_ACCESS, PROCESS_SPAWN, NATIVE_LIBRARY ->
                "profile grant did not allow this operation";
        };
    }

    /**
     * Loads per-mod security profiles from the external override file
     * ({@value #EXTERNAL_PROFILES_FILE}) located inside {@code configDir}.
     *
     * <p>This method is idempotent — calling it multiple times replaces previously
     * loaded external profiles with the current file content.  Profiles for
     * {@code intermed_core} supplied via this file are silently ignored to prevent
     * privilege escalation.
     *
     * <p>If the file does not exist this method returns without error — external
     * overrides are optional.
     *
     * @param configDir the directory that contains {@value #EXTERNAL_PROFILES_FILE}
     */
    public static void loadExternalProfiles(Path configDir) {
        if (configDir == null) {
            return;
        }
        Path profilesFile = configDir.resolve(EXTERNAL_PROFILES_FILE);
        if (!Files.exists(profilesFile)) {
            System.out.printf("[SecurityPolicy] External profiles file not found (%s) — using manifest-only mode.%n",
                profilesFile.getFileName());
            return;
        }
        try (Reader reader = Files.newBufferedReader(profilesFile)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
            int loaded = 0;
            for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                if (!entry.has("modId") || !entry.get("modId").isJsonPrimitive()) {
                    System.err.println("[SecurityPolicy] Skipping entry without 'modId' in " + profilesFile.getFileName());
                    continue;
                }
                String modId = entry.get("modId").getAsString().trim();
                if (modId.isBlank()) continue;
                if ("intermed_core".equals(modId)) {
                    System.err.println("[SecurityPolicy] Ignoring attempt to override 'intermed_core' profile via external file.");
                    continue;
                }
                ModSecurityProfile profile = parseProfile(modId, entry, false);
                MOD_PROFILES.put(modId, profile);
                CapabilityManager.invalidateMod(modId);
                System.out.printf("[SecurityPolicy] External profile loaded for '%s': %s%n",
                    modId, profile.describe());
                loaded++;
            }
            System.out.printf("[SecurityPolicy] Loaded %d external profile(s) from %s.%n",
                loaded, profilesFile.getFileName());
        } catch (IOException e) {
            System.err.printf("[SecurityPolicy] Failed to read %s: %s%n",
                profilesFile.getFileName(), e.getMessage());
        } catch (Exception e) {
            System.err.printf("[SecurityPolicy] Malformed %s: %s%n",
                profilesFile.getFileName(), e.getMessage());
        }
    }

    /**
     * Grants a single capability to a mod at runtime, merging it into the mod's
     * existing profile.  If the mod has no profile yet an empty-baseline profile
     * is created first.
     *
     * <p>This is the hook used by the CLI {@code grant} command and by
     * {@link org.intermed.core.db.DatabaseManager#loadAllCapabilities()} during
     * boot to restore persisted grants.
     *
     * @param modId      mod id
     * @param capability capability to grant
     */
    public static void grantCapability(String modId, Capability capability) {
        if (modId == null || capability == null) return;
        MOD_PROFILES.compute(modId, (id, existing) -> {
            ModSecurityProfile.Builder builder = existing != null
                ? existing.toBuilder()
                : ModSecurityProfile.builder();
            builder.capability(capability);
            if (shouldGrantBroadDefaults(existing, capability)) {
                grantBroadDefaults(builder, capability);
            }
            ModSecurityProfile updated = builder.build();
            System.out.printf("[SecurityPolicy] Granted %s to '%s': %s%n", capability, id, updated.describe());
            return updated;
        });
        CapabilityManager.invalidateMod(modId);
    }

    /**
     * Revokes a single capability from a mod's profile.
     * If the mod has no profile, or already lacks the capability, this is a no-op.
     */
    public static void revokeCapability(String modId, Capability capability) {
        if (modId == null || capability == null) return;
        MOD_PROFILES.computeIfPresent(modId, (id, existing) -> {
            ModSecurityProfile updated = existing.without(capability);
            System.out.printf("[SecurityPolicy] Revoked %s from '%s': %s%n", capability, id, updated.describe());
            return updated;
        });
        CapabilityManager.invalidateMod(modId);
    }

    public static void resetForTests() {
        MOD_PROFILES.clear();
        installBuiltinProfiles();
        CapabilityManager.invalidateDecisionCache();
        // External profiles are NOT restored here — tests must call loadExternalProfiles()
        // explicitly if they need per-mod overrides.
    }

    private static void installBuiltinProfiles() {
        // Only the platform kernel gets an unrestricted profile out of the box.
        // A few well-known utility mods receive narrow bootstrap profiles so
        // existing packs remain usable before administrators add explicit rules.
        MOD_PROFILES.put("intermed_core", ModSecurityProfile.allowAllProfile());
        MOD_PROFILES.put("appleskin", ModSecurityProfile.builder()
            .capability(Capability.FILE_READ)
            .fileReadPath("config/**")
            .build());
    }

    private static ModSecurityProfile parseProfile(String modId, JsonObject modJson, boolean includeManifestDefaults) {
        ModSecurityProfile.Builder builder = ModSecurityProfile.builder();
        boolean hasGranularSecurity = modJson != null
            && modJson.has("intermed:security")
            && modJson.get("intermed:security").isJsonObject();
        boolean hasLegacyPermissions = modJson != null && modJson.has("intermed:permissions");

        if (hasLegacyPermissions) {
            readCapabilities(
                builder,
                modJson.getAsJsonArray("intermed:permissions"),
                !hasGranularSecurity && BootstrapRuntimeConfig.isLegacyBroadPermissionDefaultsEnabled()
            );
        }

        if (hasGranularSecurity) {
            JsonObject security = modJson.getAsJsonObject("intermed:security");
            if (security.has("capabilities")) {
                readCapabilities(builder, security.getAsJsonArray("capabilities"), false);
            }
            readStringArray(security, "fileReadPaths", builder::fileReadPath);
            readStringArray(security, "fileWritePaths", builder::fileWritePath);
            readStringArray(security, "networkHosts", builder::networkHost);
            readStringArray(security, "unsafeMembers", builder::unsafeMember);
            readStringArray(security, "memoryMembers", builder::unsafeMember);
        }

        boolean hasExternalShape = modJson != null
            && (modJson.has("capabilities")
                || modJson.has("fileReadPaths")
                || modJson.has("fileWritePaths")
                || modJson.has("networkHosts")
                || modJson.has("unsafeMembers")
                || modJson.has("memoryMembers"));
        if (hasExternalShape) {
            if (modJson.has("capabilities") && modJson.get("capabilities").isJsonArray()) {
                readCapabilities(builder, modJson.getAsJsonArray("capabilities"), false);
            }
            readStringArray(modJson, "fileReadPaths", builder::fileReadPath);
            readStringArray(modJson, "fileWritePaths", builder::fileWritePath);
            readStringArray(modJson, "networkHosts", builder::networkHost);
            readStringArray(modJson, "unsafeMembers", builder::unsafeMember);
            readStringArray(modJson, "memoryMembers", builder::unsafeMember);
        }

        if (includeManifestDefaults && !hasGranularSecurity && !hasLegacyPermissions && !hasExternalShape) {
            grantDefaultConfigAccess(builder, modId);
        }

        return builder.build();
    }

    private static void grantDefaultConfigAccess(ModSecurityProfile.Builder builder, String modId) {
        if (modId == null || modId.isBlank()) {
            return;
        }
        builder.capability(Capability.FILE_READ);
        builder.capability(Capability.FILE_WRITE);

        Path configDir = BootstrapRuntimeConfig.getGameDir()
            .resolve("config")
            .toAbsolutePath()
            .normalize();
        builder.fileReadPath(configDir.toString());
        builder.fileReadPath(configDir.resolve("**").toString());
        builder.fileWritePath(configDir.toString());
        builder.fileWritePath(configDir.resolve("**").toString());
    }

    private static void readCapabilities(ModSecurityProfile.Builder builder,
                                         JsonArray permissions,
                                         boolean grantBroadDefaults) {
        for (JsonElement element : permissions) {
            try {
                Capability capability = Capability.valueOf(element.getAsString());
                builder.capability(capability);
                if (grantBroadDefaults) {
                    grantBroadDefaults(builder, capability);
                }
            } catch (IllegalArgumentException ignored) {
                System.err.println("[SecurityPolicy] Unknown capability '" + element.getAsString() + "'");
            }
        }
    }

    private static void grantBroadDefaults(ModSecurityProfile.Builder builder, Capability capability) {
        switch (capability) {
            case FILE_READ -> builder.fileReadPath("**");
            case FILE_WRITE -> builder.fileWritePath("**");
            case NETWORK_CONNECT -> builder.networkHost("*");
            case MEMORY_ACCESS, UNSAFE_ACCESS -> builder.unsafeMember("*");
            default -> {
                // Non-scoped capabilities do not need extra wildcard grants.
            }
        }
    }

    private static boolean shouldGrantBroadDefaults(ModSecurityProfile existing, Capability capability) {
        if (!BootstrapRuntimeConfig.isLegacyBroadPermissionDefaultsEnabled()) {
            return false;
        }
        if (existing == null) {
            return true;
        }
        return switch (capability) {
            case FILE_READ -> existing.fileReadPaths().isEmpty();
            case FILE_WRITE -> existing.fileWritePaths().isEmpty();
            case NETWORK_CONNECT -> existing.networkHosts().isEmpty();
            case MEMORY_ACCESS, UNSAFE_ACCESS -> existing.unsafeMembers().isEmpty();
            default -> false;
        };
    }

    private static void readStringArray(JsonObject json, String key, java.util.function.Consumer<String> consumer) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return;
        }
        for (JsonElement element : json.getAsJsonArray(key)) {
            String value = element.getAsString();
            if (!value.isBlank()) {
                consumer.accept(value.trim());
            }
        }
    }

    record ModSecurityProfile(EnumSet<Capability> capabilities,
                              List<PathPattern> fileReadPaths,
                              List<PathPattern> fileWritePaths,
                              List<HostPattern> networkHosts,
                              Set<String> unsafeMembers,
                              boolean allowAll) {

        static Builder builder() {
            return new Builder();
        }

        /**
         * Returns a new {@link Builder} pre-populated with this profile's current state.
         * Used by {@link SecurityPolicy#grantCapability} and {@link SecurityPolicy#revokeCapability}
         * to produce a modified copy without touching the original.
         */
        Builder toBuilder() {
            Builder b = new Builder();
            capabilities.forEach(b::capability);
            fileReadPaths.forEach(p -> b.fileReadPath(p.original()));
            fileWritePaths.forEach(p -> b.fileWritePath(p.original()));
            networkHosts.forEach(h -> b.networkHost(h.original()));
            unsafeMembers.forEach(b::unsafeMember);
            return b;
        }

        /**
         * Returns a copy of this profile with {@code capability} removed from the
         * granted set.  All scoped constraints (paths, hosts) are preserved.
         */
        ModSecurityProfile without(Capability capability) {
            if (!capabilities.contains(capability)) {
                return this; // nothing to remove
            }
            EnumSet<Capability> reduced = EnumSet.copyOf(capabilities);
            reduced.remove(capability);
            return new ModSecurityProfile(
                reduced,
                fileReadPaths,
                fileWritePaths,
                networkHosts,
                unsafeMembers,
                false
            );
        }

        static ModSecurityProfile allowAllProfile() {
            return new ModSecurityProfile(
                EnumSet.allOf(Capability.class),
                List.of(PathPattern.matchAny()),
                List.of(PathPattern.matchAny()),
                List.of(HostPattern.matchAny()),
                Set.of("*"),
                true
            );
        }

        boolean allows(SecurityRequest request) {
            if (allowAll) {
                return true;
            }
            if (!hasCapability(request.capability())) {
                return false;
            }
            if (request.path() == null && request.host() == null && request.detail() == null) {
                return allowsWithoutScope(request.capability());
            }
            return switch (request.capability()) {
                case FILE_READ -> matchesAnyPath(fileReadPaths, request.path());
                case FILE_WRITE -> matchesAnyPath(fileWritePaths, request.path());
                case NETWORK_CONNECT -> matchesAnyHost(networkHosts, request.host());
                case MEMORY_ACCESS, UNSAFE_ACCESS -> matchesUnsafeMember(request.detail());
                case REFLECTION_ACCESS -> true;
                case PROCESS_SPAWN, NATIVE_LIBRARY -> true;
            };
        }

        private boolean allowsWithoutScope(Capability capability) {
            return switch (capability) {
                case FILE_READ -> fileReadPaths.stream().anyMatch(PathPattern::any);
                case FILE_WRITE -> fileWritePaths.stream().anyMatch(PathPattern::any);
                case NETWORK_CONNECT -> networkHosts.stream().anyMatch(HostPattern::any);
                case MEMORY_ACCESS, UNSAFE_ACCESS -> unsafeMembers.contains("*");
                case REFLECTION_ACCESS, PROCESS_SPAWN, NATIVE_LIBRARY -> true;
            };
        }

        private boolean hasCapability(Capability capability) {
            if (capability == null) {
                return false;
            }
            if (capabilities.contains(capability)) {
                return true;
            }
            return switch (capability) {
                case MEMORY_ACCESS -> capabilities.contains(Capability.UNSAFE_ACCESS);
                case UNSAFE_ACCESS -> capabilities.contains(Capability.MEMORY_ACCESS);
                default -> false;
            };
        }

        String describe() {
            return "capabilities=" + capabilities
                + ", readPaths=" + fileReadPaths
                + ", writePaths=" + fileWritePaths
                + ", hosts=" + networkHosts
                + ", unsafeMembers=" + unsafeMembers;
        }

        private boolean matchesAnyPath(List<PathPattern> patterns, Path path) {
            if (path == null) {
                return false;
            }
            return patterns.stream().anyMatch(pattern -> pattern.matches(path));
        }

        private boolean matchesAnyHost(List<HostPattern> patterns, String host) {
            if (host == null || host.isBlank()) {
                return false;
            }
            return patterns.stream().anyMatch(pattern -> pattern.matches(host));
        }

        private boolean matchesUnsafeMember(String memberName) {
            if (unsafeMembers.contains("*")) {
                return true;
            }
            return memberName != null && unsafeMembers.contains(memberName);
        }

        static final class Builder {
            private final EnumSet<Capability> capabilities = EnumSet.noneOf(Capability.class);
            private final List<PathPattern> fileReadPaths = new ArrayList<>();
            private final List<PathPattern> fileWritePaths = new ArrayList<>();
            private final List<HostPattern> networkHosts = new ArrayList<>();
            private final Set<String> unsafeMembers = ConcurrentHashMap.newKeySet();

            Builder capability(Capability capability) {
                capabilities.add(capability);
                return this;
            }

            Builder fileReadPath(String pattern) {
                fileReadPaths.add(PathPattern.of(pattern));
                return this;
            }

            Builder fileWritePath(String pattern) {
                fileWritePaths.add(PathPattern.of(pattern));
                return this;
            }

            Builder networkHost(String pattern) {
                networkHosts.add(HostPattern.of(pattern));
                return this;
            }

            Builder unsafeMember(String memberName) {
                unsafeMembers.add(memberName);
                return this;
            }

            ModSecurityProfile build() {
                return new ModSecurityProfile(
                    EnumSet.copyOf(capabilities.isEmpty() ? EnumSet.noneOf(Capability.class) : capabilities),
                    List.copyOf(fileReadPaths),
                    List.copyOf(fileWritePaths),
                    List.copyOf(networkHosts),
                    Set.copyOf(unsafeMembers),
                    false
                );
            }
        }
    }

    public static record SecurityRequest(Capability capability, Path path, String host, String detail) {
        static SecurityRequest capabilityOnly(Capability capability) {
            return new SecurityRequest(capability, null, null, null);
        }

        static SecurityRequest fileRead(Path path) {
            return new SecurityRequest(Capability.FILE_READ, normalize(path), null, null);
        }

        static SecurityRequest fileWrite(Path path) {
            return new SecurityRequest(Capability.FILE_WRITE, normalize(path), null, null);
        }

        static SecurityRequest network(String host) {
            return new SecurityRequest(Capability.NETWORK_CONNECT, null, host, null);
        }

        static SecurityRequest unsafe(String memberName) {
            return new SecurityRequest(Capability.UNSAFE_ACCESS, null, null, memberName);
        }

        static SecurityRequest memory(String memberName) {
            return new SecurityRequest(Capability.MEMORY_ACCESS, null, null, memberName);
        }

        static SecurityRequest reflection(String detail) {
            return new SecurityRequest(Capability.REFLECTION_ACCESS, null, null, detail);
        }

        static SecurityRequest nativeLibrary(String detail) {
            return new SecurityRequest(Capability.NATIVE_LIBRARY, null, null, detail);
        }

        private static Path normalize(Path path) {
            return path == null ? null : path.toAbsolutePath().normalize();
        }
    }

    record PathPattern(String original, Path absoluteBase, boolean any, boolean recursive) {
        static PathPattern matchAny() {
            return new PathPattern("**", null, true, true);
        }

        static PathPattern of(String rawPattern) {
            if ("**".equals(rawPattern) || "*".equals(rawPattern)) {
                return matchAny();
            }
            String normalized = rawPattern.replace('\\', '/');
            boolean recursive = normalized.endsWith("/**");
            String base = recursive ? normalized.substring(0, normalized.length() - 3) : normalized;
            Path path = Paths.get(base).toAbsolutePath().normalize();
            return new PathPattern(rawPattern, path, false, recursive);
        }

        boolean matches(Path candidate) {
            if (candidate == null) {
                return false;
            }
            if (any) {
                return true;
            }
            Path normalized = candidate.toAbsolutePath().normalize();
            return recursive ? normalized.startsWith(absoluteBase) : normalized.equals(absoluteBase);
        }

        @Override
        public String toString() {
            return original;
        }
    }

    record HostPattern(String original, String normalized, boolean any, boolean wildcardSuffix) {
        static HostPattern matchAny() {
            return new HostPattern("*", "*", true, true);
        }

        static HostPattern of(String rawPattern) {
            if ("*".equals(rawPattern)) {
                return matchAny();
            }
            String lower = rawPattern.toLowerCase(Locale.ROOT);
            boolean wildcardSuffix = lower.startsWith("*.");
            String normalized = wildcardSuffix ? lower.substring(2) : lower;
            return new HostPattern(rawPattern, normalized, false, wildcardSuffix);
        }

        boolean matches(String host) {
            if (host == null) {
                return false;
            }
            String lower = host.toLowerCase(Locale.ROOT);
            if (any) {
                return true;
            }
            if (wildcardSuffix) {
                return lower.equals(normalized) || lower.endsWith("." + normalized);
            }
            return lower.equals(normalized);
        }

        @Override
        public String toString() {
            return original;
        }
    }
}
