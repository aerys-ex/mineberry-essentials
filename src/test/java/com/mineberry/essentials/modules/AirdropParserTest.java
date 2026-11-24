package com.mineberry.essentials.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AirdropParserTest {
    private static AirdropParser.Fact fact(String line) {
        return AirdropParser.parse(line).orElse(null);
    }

    @Test void announcementIsRead() {
        AirdropParser.Fact f = fact("\u27a4 Airdrop + Boss will be spawned in 5 minutes.");
        assertEquals(AirdropParser.Kind.ANNOUNCE, f.kind());
        assertEquals(300, f.seconds());
        assertTrue(f.boss());
        assertTrue(f.opensContext());
        assertFalse(fact("Airdrop will be spawned in 30 seconds").boss());
    }

    @Test void chatTimestampsInFrontAreSkipped() {
        assertEquals(AirdropParser.Kind.HEADER, fact("[14:40] Airdrop Information").kind());
        assertEquals(298, fact("[14:40] \u27a4 Time to spawn 4 min 58 sec!").seconds());
        assertEquals(-4064, fact("[14:40:34] \u27a4 Location: X: -4064, Y: 68, Z: -2852").x());
        assertEquals(300, fact("[09:05] \u27a4 Airdrop + Boss will be spawned in 5 minutes.").seconds());
        assertEquals("Easy", fact("(14:40) Level: Easy").text());
    }

    @Test void locationAndLevelAreRead() {
        AirdropParser.Fact loc = fact("\u27a4 Location: X: -4064, Y: 68, Z: -2852");
        assertEquals(AirdropParser.Kind.LOCATION, loc.kind());
        assertEquals(-4064, loc.x());
        assertEquals(68, loc.y());
        assertEquals(-2852, loc.z());
        assertEquals("Easy", fact("\u27a4 Level: Easy").text());
    }

    @Test void infoBlockIsRead() {
        assertEquals(AirdropParser.Kind.HEADER, fact("Airdrop Information").kind());
        assertEquals("Preparing", fact("\u27a4 Status: Preparing!").text());
        assertEquals("Waiting", fact("\u27a4 Status: Waiting").text());
        assertEquals(298, fact("\u27a4 Time to spawn 4 min 58 sec!").seconds());
        assertEquals(28, fact("\u27a4 Next spawn in 28 sec").seconds());
        assertEquals("Sonicar2012", fact("\u27a4 Last drop taken by: Sonicar2012").text());
    }

    @Test void otherLinesAreIgnored() {
        assertEquals(null, fact("Steve: hello"));
        assertEquals(null, fact("Level up!"));
        assertEquals(null, fact(""));
        assertEquals(null, fact(null));
    }

    @Test void askingAboutAirdrop() {
        assertTrue(AirdropParser.asksAboutAirdrop("airdrop info"));
        assertTrue(AirdropParser.asksAboutAirdrop("when is the next air drop?"));
        assertFalse(AirdropParser.asksAboutAirdrop("how much is legend rank"));
    }

    @Test void askingForTheWaypoint() {
        assertTrue(AirdropParser.wantsWaypoint("add the airdrop cords to waypoint"));
        assertTrue(AirdropParser.wantsWaypoint("set a waypoint for the air drop"));
        assertFalse(AirdropParser.wantsWaypoint("airdrop info"));
        assertFalse(AirdropParser.wantsWaypoint("add a waypoint"));
    }

    @Test void stateAnswersFromTheInfoBlock() {
        AirdropParser.State s = new AirdropParser.State();
        long t = 1_000_000;
        for (String line : new String[] {"Airdrop Information", "Status: Preparing!", "Level: Easy",
                "Location: X: -4064, Y: 68, Z: -2852", "Time to spawn 4 min 58 sec!"}) {
            s.apply(fact(line), t);
        }
        assertEquals("Airdrop (Easy) spawns in 4m 58s.\nLocation: X: -4064, Y: 68, Z: -2852", s.summary(t));
        assertEquals(238, s.secondsToSpawn(t + 60_000));
    }

    @Test void stateAnswersWhileWaiting() {
        AirdropParser.State s = new AirdropParser.State();
        long t = 5_000;
        s.apply(fact("Airdrop Information"), t);
        s.apply(fact("Status: Waiting"), t);
        s.apply(fact("Next spawn in 28 sec"), t);
        s.apply(fact("Last drop taken by: Sonicar2012"), t);
        assertEquals("No airdrop right now. The next one spawns in 28s.\nLast drop was taken by Sonicar2012.", s.summary(t));
    }

    @Test void spawnedAirdropWithBossFightCanBeMarked() {
        AirdropParser.State s = new AirdropParser.State();
        long t = 1_000;
        for (String line : new String[] {"Airdrop Information", "\u27a4 Status: Spawned, Boss fight is going!", "\u27a4 Level: Easy",
                "\u27a4 Location: X: -1579, Y: 63, Z: 2782"}) {
            s.apply(fact(line), t);
        }
        assertEquals("Spawned, Boss fight is going", s.status);
        assertTrue(s.live(t));
        assertEquals("Airdrop (Easy) is out now.\nLocation: X: -1579, Y: 63, Z: 2782", s.summary(t));
    }

    @Test void anyStatusWithCoordinatesCountsAsLiveExceptWaiting() {
        AirdropParser.State s = new AirdropParser.State();
        s.apply(fact("Airdrop Information"), 0);
        s.apply(fact("Status: Something new we never saw"), 0);
        s.apply(fact("Location: X: 1, Y: 2, Z: 3"), 0);
        assertTrue(s.live(0));
        AirdropParser.State w = new AirdropParser.State();
        w.apply(fact("Airdrop Information"), 0);
        w.apply(fact("Status: Waiting"), 0);
        w.apply(fact("Location: X: 1, Y: 2, Z: 3"), 0);
        assertFalse(w.live(0));
    }

    @Test void newAnnouncementDropsOldDetails() {
        AirdropParser.State s = new AirdropParser.State();
        s.apply(fact("Airdrop Information"), 0);
        s.apply(fact("Last drop taken by: Sonicar2012"), 0);
        s.apply(fact("Airdrop + Boss will be spawned in 5 minutes."), 0);
        assertEquals("", s.lastTaken);
        assertTrue(s.boss);
        assertFalse(s.hasLocation);
    }
}
