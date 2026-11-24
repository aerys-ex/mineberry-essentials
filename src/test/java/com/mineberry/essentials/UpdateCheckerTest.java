package com.mineberry.essentials;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UpdateCheckerTest {
    @Test void higherNumbersAreNewer() {
        assertTrue(UpdateChecker.isNewer("1.7.0", "1.6.9"));
        assertTrue(UpdateChecker.isNewer("v2.0", "1.9.9"));
        assertTrue(UpdateChecker.isNewer("1.6.1", "1.6"));
    }

    @Test void sameOrLowerIsNotNewer() {
        assertFalse(UpdateChecker.isNewer("1.6.0", "1.6.0"));
        assertFalse(UpdateChecker.isNewer("1.6", "1.6.0"));
        assertFalse(UpdateChecker.isNewer("1.5.9", "1.6.0"));
    }

    @Test void releaseBeatsItsOwnPreRelease() {
        assertTrue(UpdateChecker.isNewer("1.6.0", "1.6.0-beta"));
        assertFalse(UpdateChecker.isNewer("1.6.0-beta", "1.6.0"));
        assertFalse(UpdateChecker.isNewer("1.6.0+build5", "1.6.0"));
    }

    @Test void laterPreReleaseBeatsEarlierOne() {
        assertTrue(UpdateChecker.isNewer("1.8.0-beta.2", "1.8.0-beta.1"));
        assertTrue(UpdateChecker.isNewer("1.8.0-beta.10", "1.8.0-beta.9"));
        assertFalse(UpdateChecker.isNewer("1.8.0-beta.1", "1.8.0-beta.2"));
        assertFalse(UpdateChecker.isNewer("1.8.0-beta", "1.8.0-beta"));
    }
}
