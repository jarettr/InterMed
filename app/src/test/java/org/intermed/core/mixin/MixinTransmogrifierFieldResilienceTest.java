package org.intermed.core.mixin;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link MixinTransmogrifier}'s reflective field-lookup helper
 * is resilient to upstream field renames — it must try each candidate name in
 * order and walk the full class hierarchy before failing.
 */
class MixinTransmogrifierFieldResilienceTest {

    // ── Fixtures ──────────────────────────────────────────────────────────────

    @SuppressWarnings("unused")
    private static class Parent {
        private String inheritedField = "parent-value";
    }

    @SuppressWarnings("unused")
    private static class Child extends Parent {
        private String childField = "child-value";
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void findsFieldByExactNameInDirectClass() throws NoSuchFieldException {
        Field f = MixinTransmogrifier.findFieldForTests(Child.class, "childField");
        assertNotNull(f);
        assertEquals("childField", f.getName());
    }

    @Test
    void findsFieldInheritedFromSuperclass() throws NoSuchFieldException {
        Field f = MixinTransmogrifier.findFieldForTests(Child.class, "inheritedField");
        assertNotNull(f);
        assertEquals("inheritedField", f.getName());
    }

    @Test
    void fallsBackToSecondCandidateWhenFirstMissing() throws NoSuchFieldException {
        Field f = MixinTransmogrifier.findFieldForTests(Child.class,
            "nonExistentName", "childField");
        assertNotNull(f);
        assertEquals("childField", f.getName());
    }

    @Test
    void fallsBackToThirdCandidateAcrossInheritanceBoundary() throws NoSuchFieldException {
        Field f = MixinTransmogrifier.findFieldForTests(Child.class,
            "nope1", "nope2", "inheritedField");
        assertNotNull(f);
        assertEquals("inheritedField", f.getName());
    }

    @Test
    void throwsDescriptiveExceptionWhenAllCandidatesFail() {
        NoSuchFieldException ex = assertThrows(NoSuchFieldException.class, () ->
            MixinTransmogrifier.findFieldForTests(Child.class,
                "missing1", "missing2", "missing3")
        );
        String msg = ex.getMessage();
        assertTrue(msg.contains("missing1"), "message should list tried names: " + msg);
        assertTrue(msg.contains("missing2"), "message should list tried names: " + msg);
        assertTrue(msg.contains("childField"), "message should show declared fields: " + msg);
        assertTrue(msg.contains("inheritedField"), "message should show inherited fields: " + msg);
    }

    @Test
    void returnedFieldIsAccessible() throws Exception {
        Field f = MixinTransmogrifier.findFieldForTests(Child.class, "childField");
        assertTrue(f.canAccess(new Child()) || f.isAccessible(),
            "findField must call setAccessible(true)");
    }
}
