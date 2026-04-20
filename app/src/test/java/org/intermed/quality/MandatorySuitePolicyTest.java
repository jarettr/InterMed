package org.intermed.quality;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MandatorySuitePolicyTest {

    private static final List<String> MANDATORY_SUITE_CLASSFILES = List.of(
        "org/intermed/core/sandbox/PolyglotSandboxManagerTest.class",
        "org/intermed/launcher/InterMedLauncherTest.class"
    );

    private static final List<String> BANNED_SKIP_MARKERS = List.of(
        "org/junit/jupiter/api/Assumptions",
        "Lorg/junit/jupiter/api/Disabled;",
        "org/junit/jupiter/api/condition/",
        "org/junit/Ignore"
    );

    @Test
    void mandatorySuitesDoNotContainSkipControls() throws Exception {
        for (String classFile : MANDATORY_SUITE_CLASSFILES) {
            String classContents = readClassFile(classFile);
            for (String bannedMarker : BANNED_SKIP_MARKERS) {
                assertFalse(
                    classContents.contains(bannedMarker),
                    classFile + " must not contain skip control marker: " + bannedMarker
                );
            }
        }
    }

    private static String readClassFile(String classFile) throws Exception {
        try (var input = MandatorySuitePolicyTest.class.getClassLoader().getResourceAsStream(classFile)) {
            assertNotNull(input, "Missing compiled test class resource: " + classFile);
            return new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }
}
