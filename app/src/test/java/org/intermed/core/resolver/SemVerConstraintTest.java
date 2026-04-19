package org.intermed.core.resolver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SemVerConstraintTest {

    @Test
    void parsesMavenStyleClosedOpenRange() {
        SemVerConstraint constraint = SemVerConstraint.parse("[1.0,2.0)");

        assertTrue(constraint.matches("1.0.0"));
        assertTrue(constraint.matches("1.5.4"));
        assertFalse(constraint.matches("2.0.0"));
        assertFalse(constraint.matches("0.9.9"));
    }

    @Test
    void parsesMavenStyleLowerBoundOnlyRange() {
        SemVerConstraint constraint = SemVerConstraint.parse("[47,)");

        assertTrue(constraint.matches("47.0.0"));
        assertTrue(constraint.matches("48.2.1"));
        assertFalse(constraint.matches("46.9.9"));
    }

    @Test
    void parsesMavenStyleUpperBoundOnlyRange() {
        SemVerConstraint constraint = SemVerConstraint.parse("(,2.0]");

        assertTrue(constraint.matches("0.0.1"));
        assertTrue(constraint.matches("2.0.0"));
        assertFalse(constraint.matches("2.0.1"));
    }
}
