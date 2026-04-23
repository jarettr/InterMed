package org.intermed.core.security;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.intermed.core.config.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Tag("strict-security")
class SecurityPolicyTest {

    @BeforeEach
    void setUp() {
        SecurityPolicy.resetForTests();
        CapabilityManager.resetForTests();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("security.strict.mode");
        System.clearProperty("security.legacy.broad.permissions.enabled");
        System.clearProperty("runtime.game.dir");
        RuntimeConfig.resetForTests();
        SecurityPolicy.resetForTests();
        CapabilityManager.resetForTests();
    }

    @Test
    void testNullModIdIsDeniedInStrictMode() {
        assertFalse(SecurityPolicy.hasPermission(null, Capability.FILE_READ));
        assertFalse(SecurityPolicy.hasPermission(null, Capability.NETWORK_CONNECT));
    }

    @Test
    void testKernelContextRetainsPrivilegedHostAccess() {
        assertTrue(KernelContext.execute(() -> SecurityPolicy.hasPermission(null, Capability.FILE_READ)));
        assertTrue(KernelContext.execute(() -> SecurityPolicy.hasPermission(
            null,
            SecurityPolicy.SecurityRequest.network("localhost")
        )));
    }

    @Test
    void testAppleskinHasFileReadPermission() {
        assertFalse(SecurityPolicy.hasPermission("appleskin", Capability.FILE_READ));
        assertTrue(SecurityPolicy.hasPermission("appleskin", SecurityPolicy.SecurityRequest.fileRead(Path.of("config/appleskin.json"))));
    }

    @Test
    void testAppleskinDoesNotHaveNetworkAccess() {
        assertFalse(SecurityPolicy.hasPermission("appleskin", Capability.NETWORK_CONNECT));
    }

    @Test
    void testUnknownModHasNoPermissions() {
        assertFalse(SecurityPolicy.hasPermission("unknown_mod", Capability.FILE_READ));
        assertFalse(SecurityPolicy.hasPermission("unknown_mod", Capability.NETWORK_CONNECT));
    }

    @Test
    void testRegisterSimpleCapabilitiesFromJson() {
        JsonObject json = new JsonObject();
        JsonArray perms = new JsonArray();
        perms.add("FILE_READ");
        perms.add("NETWORK_CONNECT");
        json.add("intermed:permissions", perms);

        SecurityPolicy.registerModCapabilities("test_registration_mod", json);

        assertTrue(SecurityPolicy.hasCapabilityGrant("test_registration_mod", Capability.FILE_READ));
        assertTrue(SecurityPolicy.hasCapabilityGrant("test_registration_mod", Capability.NETWORK_CONNECT));
        assertFalse(SecurityPolicy.hasCapabilityGrant("test_registration_mod", Capability.UNSAFE_ACCESS));
        assertFalse(SecurityPolicy.hasPermission(
            "test_registration_mod",
            SecurityPolicy.SecurityRequest.fileRead(Path.of("config/test.txt"))
        ));
        assertFalse(SecurityPolicy.hasPermission(
            "test_registration_mod",
            SecurityPolicy.SecurityRequest.network("cdn.example.org")
        ));
    }

    @Test
    void testGranularFileAndNetworkRules() {
        JsonObject json = new JsonObject();
        JsonObject security = new JsonObject();

        JsonArray capabilities = new JsonArray();
        capabilities.add("FILE_READ");
        capabilities.add("FILE_WRITE");
        capabilities.add("NETWORK_CONNECT");
        capabilities.add("UNSAFE_ACCESS");
        security.add("capabilities", capabilities);

        JsonArray readPaths = new JsonArray();
        readPaths.add("config/**");
        security.add("fileReadPaths", readPaths);

        JsonArray writePaths = new JsonArray();
        writePaths.add("config/my-mod/**");
        security.add("fileWritePaths", writePaths);

        JsonArray hosts = new JsonArray();
        hosts.add("api.modrinth.com");
        hosts.add("*.example.org");
        security.add("networkHosts", hosts);

        JsonArray unsafeMembers = new JsonArray();
        unsafeMembers.add("allocateInstance");
        security.add("unsafeMembers", unsafeMembers);

        json.add("intermed:security", security);
        SecurityPolicy.registerModCapabilities("granular_mod", json);

        assertFalse(SecurityPolicy.hasPermission("granular_mod", Capability.FILE_READ));
        assertFalse(SecurityPolicy.hasPermission("granular_mod", Capability.FILE_WRITE));
        assertFalse(SecurityPolicy.hasPermission("granular_mod", Capability.NETWORK_CONNECT));
        assertFalse(SecurityPolicy.hasPermission("granular_mod", Capability.UNSAFE_ACCESS));
        assertTrue(SecurityPolicy.hasPermission(
            "granular_mod",
            SecurityPolicy.SecurityRequest.fileRead(Path.of("config/settings/options.toml"))
        ));
        assertFalse(SecurityPolicy.hasPermission(
            "granular_mod",
            SecurityPolicy.SecurityRequest.fileRead(Path.of("saves/world/level.dat"))
        ));
        assertTrue(SecurityPolicy.hasPermission(
            "granular_mod",
            SecurityPolicy.SecurityRequest.fileWrite(Path.of("config/my-mod/cache/data.json"))
        ));
        assertFalse(SecurityPolicy.hasPermission(
            "granular_mod",
            SecurityPolicy.SecurityRequest.fileWrite(Path.of("config/other-mod/data.json"))
        ));
        assertTrue(SecurityPolicy.hasPermission(
            "granular_mod",
            SecurityPolicy.SecurityRequest.network("cdn.example.org")
        ));
        assertFalse(SecurityPolicy.hasPermission(
            "granular_mod",
            SecurityPolicy.SecurityRequest.network("evil.example.net")
        ));
        assertTrue(SecurityPolicy.hasPermission(
            "granular_mod",
            SecurityPolicy.SecurityRequest.unsafe("allocateInstance")
        ));
        assertFalse(SecurityPolicy.hasPermission(
            "granular_mod",
            SecurityPolicy.SecurityRequest.unsafe("putObject")
        ));
    }

    @Test
    void memoryMembersHonorLegacyUnsafeCapabilityAlias() {
        JsonObject json = new JsonObject();
        JsonObject security = new JsonObject();

        JsonArray capabilities = new JsonArray();
        capabilities.add("UNSAFE_ACCESS");
        security.add("capabilities", capabilities);

        JsonArray memoryMembers = new JsonArray();
        memoryMembers.add("VarHandle.setVolatile");
        memoryMembers.add("java.lang.foreign.MemorySegment.reinterpret");
        security.add("memoryMembers", memoryMembers);

        json.add("intermed:security", security);
        SecurityPolicy.registerModCapabilities("memory_alias_mod", json);

        assertTrue(SecurityPolicy.hasCapabilityGrant("memory_alias_mod", Capability.MEMORY_ACCESS));
        assertTrue(SecurityPolicy.hasPermission(
            "memory_alias_mod",
            SecurityPolicy.SecurityRequest.memory("VarHandle.setVolatile")
        ));
        assertTrue(SecurityPolicy.hasPermission(
            "memory_alias_mod",
            SecurityPolicy.SecurityRequest.unsafe("VarHandle.setVolatile")
        ));
        assertTrue(SecurityPolicy.hasPermission(
            "memory_alias_mod",
            SecurityPolicy.SecurityRequest.memory("java.lang.foreign.MemorySegment.reinterpret")
        ));
        assertFalse(SecurityPolicy.hasPermission(
            "memory_alias_mod",
            SecurityPolicy.SecurityRequest.memory("java.lang.foreign.MemorySegment.copyFrom")
        ));
    }

    @Test
    void testRegisterModWithNoPermissionsBlock() {
        SecurityPolicy.registerModCapabilities("no_perms_mod", new JsonObject());
        assertFalse(SecurityPolicy.hasPermission("no_perms_mod", Capability.FILE_READ));
    }

    @Test
    void ordinaryManifestReceivesScopedGameConfigAccess() throws Exception {
        Path gameDir = Files.createTempDirectory("intermed-game-dir");
        System.setProperty("runtime.game.dir", gameDir.toString());

        SecurityPolicy.registerModCapabilities("plain_fabric_mod", new JsonObject());

        assertFalse(SecurityPolicy.hasPermission("plain_fabric_mod", Capability.FILE_WRITE));
        assertTrue(SecurityPolicy.hasPermission(
            "plain_fabric_mod",
            SecurityPolicy.SecurityRequest.fileWrite(gameDir.resolve("config"))
        ));
        assertTrue(SecurityPolicy.hasPermission(
            "plain_fabric_mod",
            SecurityPolicy.SecurityRequest.fileWrite(gameDir.resolve("config/plain_fabric_mod.toml"))
        ));
        assertTrue(SecurityPolicy.hasPermission(
            "plain_fabric_mod",
            SecurityPolicy.SecurityRequest.fileRead(gameDir.resolve("config/plain_fabric_mod.toml"))
        ));
        assertFalse(SecurityPolicy.hasPermission(
            "plain_fabric_mod",
            SecurityPolicy.SecurityRequest.fileWrite(gameDir.resolve("saves/world/level.dat"))
        ));
    }

    @Test
    void testUnregisterModDropsProfile() {
        JsonObject json = new JsonObject();
        JsonArray perms = new JsonArray();
        perms.add("FILE_READ");
        json.add("intermed:permissions", perms);

        SecurityPolicy.registerModCapabilities("to_remove", json);
        assertTrue(SecurityPolicy.hasCapabilityGrant("to_remove", Capability.FILE_READ));

        SecurityPolicy.unregisterMod("to_remove");
        assertFalse(SecurityPolicy.hasCapabilityGrant("to_remove", Capability.FILE_READ));
    }

    @Test
    void testSecurityStrictModeCanBeDisabledForBootstrapScenarios() {
        System.setProperty("security.strict.mode", "false");
        RuntimeConfig.reload();
        assertTrue(SecurityPolicy.hasPermission("unknown_mod", Capability.FILE_READ));
        assertTrue(SecurityPolicy.hasPermission("unknown_mod", Capability.NETWORK_CONNECT));
        assertTrue(SecurityPolicy.hasPermission(
            "unknown_mod",
            SecurityPolicy.SecurityRequest.network("untrusted.example")
        ));
    }

    @Test
    void runtimeCapabilityGrantDoesNotImplyBroadScopeByDefault() {
        SecurityPolicy.registerModCapabilities("granted_mod", new JsonObject());
        SecurityPolicy.grantCapability("granted_mod", Capability.NETWORK_CONNECT);

        assertTrue(SecurityPolicy.hasCapabilityGrant("granted_mod", Capability.NETWORK_CONNECT));
        assertFalse(SecurityPolicy.hasPermission(
            "granted_mod",
            SecurityPolicy.SecurityRequest.network("cdn.example.org")
        ));
    }

    @Test
    void legacyBroadPermissionDefaultsCanBeReenabledExplicitly() {
        System.setProperty("security.legacy.broad.permissions.enabled", "true");
        RuntimeConfig.reload();

        JsonObject json = new JsonObject();
        JsonArray perms = new JsonArray();
        perms.add("FILE_READ");
        perms.add("NETWORK_CONNECT");
        json.add("intermed:permissions", perms);

        SecurityPolicy.registerModCapabilities("legacy_scope_mod", json);

        assertTrue(SecurityPolicy.hasPermission(
            "legacy_scope_mod",
            SecurityPolicy.SecurityRequest.fileRead(Path.of("config/test.txt"))
        ));
        assertTrue(SecurityPolicy.hasPermission(
            "legacy_scope_mod",
            SecurityPolicy.SecurityRequest.network("cdn.example.org")
        ));
    }

    @Test
    void externalProfilesOverrideManifestAndPersistedStyleGrants() throws Exception {
        Path configDir = Files.createTempDirectory("intermed-security-profiles");
        Path allowedPath = Files.createTempFile("intermed-security-override", ".cfg");

        JsonObject manifest = new JsonObject();
        JsonObject security = new JsonObject();
        JsonArray capabilities = new JsonArray();
        capabilities.add("FILE_READ");
        security.add("capabilities", capabilities);
        JsonArray readPaths = new JsonArray();
        readPaths.add(allowedPath.toAbsolutePath().normalize().toString());
        security.add("fileReadPaths", readPaths);
        manifest.add("intermed:security", security);
        SecurityPolicy.registerModCapabilities("override_mod", manifest);
        SecurityPolicy.grantCapability("override_mod", Capability.REFLECTION_ACCESS);

        assertTrue(SecurityPolicy.hasPermission(
            "override_mod",
            SecurityPolicy.SecurityRequest.fileRead(allowedPath)
        ));
        assertTrue(SecurityPolicy.hasPermission("override_mod", Capability.REFLECTION_ACCESS));

        Files.writeString(
            configDir.resolve(SecurityPolicy.EXTERNAL_PROFILES_FILE),
            """
            [
              {
                "modId": "override_mod",
                "capabilities": [],
                "fileReadPaths": [],
                "fileWritePaths": [],
                "networkHosts": [],
                "unsafeMembers": []
              }
            ]
            """,
            StandardCharsets.UTF_8
        );

        SecurityPolicy.loadExternalProfiles(configDir);

        assertFalse(SecurityPolicy.hasPermission("override_mod", Capability.FILE_READ));
        assertFalse(SecurityPolicy.hasPermission("override_mod", Capability.REFLECTION_ACCESS));
        assertFalse(SecurityPolicy.hasPermission(
            "override_mod",
            SecurityPolicy.SecurityRequest.fileRead(allowedPath)
        ));
    }

    @Test
    void externalProfilesHonorDocumentedTopLevelScopedFormat() throws Exception {
        Path configDir = Files.createTempDirectory("intermed-security-doc-profile");
        Path configRoot = Files.createTempDirectory("intermed-security-doc-profile-config");

        Files.writeString(
            configDir.resolve(SecurityPolicy.EXTERNAL_PROFILES_FILE),
            """
            [
              {
                "modId": "documented_profile_mod",
                "capabilities": ["FILE_READ", "FILE_WRITE", "NETWORK_CONNECT"],
                "fileReadPaths": ["%s/**"],
                "fileWritePaths": ["%s/cache/**"],
                "networkHosts": ["api.example.org"],
                "unsafeMembers": []
              }
            ]
            """.formatted(
                configRoot.toAbsolutePath().normalize().toString().replace("\\", "\\\\"),
                configRoot.toAbsolutePath().normalize().toString().replace("\\", "\\\\")
            ),
            StandardCharsets.UTF_8
        );

        SecurityPolicy.loadExternalProfiles(configDir);

        assertTrue(SecurityPolicy.hasPermission(
            "documented_profile_mod",
            SecurityPolicy.SecurityRequest.fileRead(configRoot.resolve("settings.toml"))
        ));
        assertTrue(SecurityPolicy.hasPermission(
            "documented_profile_mod",
            SecurityPolicy.SecurityRequest.fileWrite(configRoot.resolve("cache/state.json"))
        ));
        assertTrue(SecurityPolicy.hasPermission(
            "documented_profile_mod",
            SecurityPolicy.SecurityRequest.network("api.example.org")
        ));
        assertFalse(SecurityPolicy.hasPermission(
            "documented_profile_mod",
            SecurityPolicy.SecurityRequest.fileWrite(configRoot.resolve("settings.toml"))
        ));
        assertFalse(SecurityPolicy.hasPermission(
            "documented_profile_mod",
            SecurityPolicy.SecurityRequest.network("telemetry.example.org")
        ));
    }

    @Test
    void externalProfilesInvalidateCachedCapabilityDecisions() throws Exception {
        Path configDir = Files.createTempDirectory("intermed-security-cache-profiles");
        Path allowedPath = Files.createTempFile("intermed-security-cache-allowed", ".txt");

        JsonObject manifest = new JsonObject();
        JsonObject security = new JsonObject();
        JsonArray capabilities = new JsonArray();
        capabilities.add("FILE_READ");
        security.add("capabilities", capabilities);
        JsonArray readPaths = new JsonArray();
        readPaths.add(allowedPath.toAbsolutePath().normalize().toString());
        security.add("fileReadPaths", readPaths);
        manifest.add("intermed:security", security);
        SecurityPolicy.registerModCapabilities("cached_override_mod", manifest);

        assertDoesNotThrow(() -> CapabilityManager.executeAsMod("cached_override_mod", () ->
            CapabilityManager.checkFileRead(allowedPath)));

        Files.writeString(
            configDir.resolve(SecurityPolicy.EXTERNAL_PROFILES_FILE),
            """
            [
              {
                "modId": "cached_override_mod",
                "capabilities": [],
                "fileReadPaths": [],
                "fileWritePaths": [],
                "networkHosts": [],
                "unsafeMembers": []
              }
            ]
            """,
            StandardCharsets.UTF_8
        );

        SecurityPolicy.loadExternalProfiles(configDir);

        assertThrows(SecurityException.class, () -> CapabilityManager.executeAsMod("cached_override_mod", () ->
            CapabilityManager.checkFileRead(allowedPath)));
    }
}
