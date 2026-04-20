package org.intermed.core.classloading;

import org.intermed.core.security.SecurityHookTransformer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DagAwareClassWriterIntegrationTest {

    @AfterEach
    void tearDown() {
        ClassHierarchyLcaIndex.install(null);
        DagAwareClassWriter.resetDiagnosticsForTests();
    }

    @Test
    void securityTransformerUsesDagAwareCommonSuperclassResolution() throws Exception {
        LazyInterMedClassLoader loader = new LazyInterMedClassLoader(
            "lca-test", null, Set.of(), getClass().getClassLoader());
        loader.classCache.put(LcaBase.class.getName(), LcaBase.class);
        loader.classCache.put(LcaLeft.class.getName(), LcaLeft.class);
        loader.classCache.put(LcaRight.class.getName(), LcaRight.class);
        loader.classCache.put(FrameMergeFixture.class.getName(), FrameMergeFixture.class);

        ClassHierarchyLcaIndex.install(ClassHierarchyLcaIndex.buildFrom(java.util.List.of(loader)));
        DagAwareClassWriter.resetDiagnosticsForTests();

        byte[] transformed = new SecurityHookTransformer().transform(
            FrameMergeFixture.class.getName(),
            readClassBytes(FrameMergeFixture.class)
        );

        assertTrue(transformed.length > 0);
        assertNotNull(ClassHierarchyLcaIndex.get().rmq());
        assertTrue(DagAwareClassWriter.writerCreationCount() > 0,
            "COMPUTE_FRAMES path should route through the DAG-aware writer");
    }

    private static byte[] readClassBytes(Class<?> type) throws IOException {
        String resourceName = type.getName().replace('.', '/') + ".class";
        try (InputStream stream = type.getClassLoader().getResourceAsStream(resourceName)) {
            if (stream == null) {
                throw new IOException("Missing class bytes for " + type.getName());
            }
            return stream.readAllBytes();
        }
    }

    static class LcaBase {}

    static class LcaLeft extends LcaBase {}

    static class LcaRight extends LcaBase {}

    static class FrameMergeFixture {
        static LcaBase choose(boolean flag) {
            if (flag) {
                return new LcaLeft();
            }
            return new LcaRight();
        }

        static void open(java.nio.file.Path path) throws java.io.IOException {
            java.nio.file.Files.readAllBytes(path);
        }
    }
}
