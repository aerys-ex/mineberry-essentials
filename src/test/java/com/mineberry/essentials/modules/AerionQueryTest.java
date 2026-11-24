package com.mineberry.essentials.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mineberry.essentials.modules.AerionQuery.Kind;
import com.mineberry.essentials.modules.AerionQuery.Query;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AerionQueryTest {

    private static Query q(String message) {
        return AerionQuery.parse(message).orElseThrow(() -> new AssertionError("not a query: " + message));
    }

    @Test void whereAmI() {
        assertEquals(Kind.WHERE_AM_I, q("where am i").kind());
        assertEquals(Kind.WHERE_AM_I, q("what are my coords").kind());
        assertEquals(Kind.WHERE_AM_I, q("whats my position?").kind());
        assertEquals(Kind.WHERE_AM_I, q("my coordinates").kind());
    }

    @Test void nearestAndFps() {
        assertEquals(Kind.NEAREST, q("nearest waypoint").kind());
        assertEquals(Kind.NEAREST, q("which waypoint is the closest").kind());
        assertEquals(Kind.NEAREST, q("show me the nearest waypoint").kind());
        assertEquals(Kind.FPS, q("what's my fps").kind());
    }

    @Test void distanceToANamedPlace() {
        assertEquals(new Query(Kind.DISTANCE, "home"), q("how far is home"));
        assertEquals(new Query(Kind.DISTANCE, "home"), q("how far is my home waypoint?"));
        assertEquals(new Query(Kind.DISTANCE, "airdrop"), q("which way is the airdrop"));
        assertEquals(new Query(Kind.DISTANCE, "base"), q("distance to base"));
        assertEquals(new Query(Kind.DISTANCE, "home"), q("where is home"));
        assertEquals(new Query(Kind.DISTANCE, "home"), q("how do i get to home"));
    }

    @Test void commandsAndNumbersAreNotQueries() {
        assertTrue(AerionQuery.parse("remove nearest waypoint").isEmpty());
        assertTrue(AerionQuery.parse("hide the nearest waypoint").isEmpty());
        assertTrue(AerionQuery.parse("turn off fps").isEmpty());
        assertTrue(AerionQuery.parse("convert my coords to nether").isEmpty());
        assertTrue(AerionQuery.parse("how far is 100 200").isEmpty());
        assertTrue(AerionQuery.parse("how much is legend rank").isEmpty());
        assertTrue(AerionQuery.parse("where is").isEmpty());
        assertEquals(Optional.empty(), AerionQuery.parse(null));
    }

    @Test void compassDirections() {
        assertEquals("north", AerionQuery.compass(0, -10));
        assertEquals("east", AerionQuery.compass(10, 0));
        assertEquals("south", AerionQuery.compass(0, 10));
        assertEquals("west", AerionQuery.compass(-10, 0));
        assertEquals("north-east", AerionQuery.compass(10, -10));
        assertEquals("south-west", AerionQuery.compass(-10, 10));
    }

    @Test void relativeToWhereYouLook() {
        // yaw 0 = looking south (+Z)
        assertEquals("straight ahead", AerionQuery.relative(0, 0, 10));
        assertEquals("behind you", AerionQuery.relative(0, 0, -10));
        assertEquals("to your left", AerionQuery.relative(0, 10, 0));     // east is on your left when facing south
        assertEquals("to your right", AerionQuery.relative(0, -10, 0));
        assertEquals("ahead to your right", AerionQuery.relative(0, -10, 10));
    }
}
