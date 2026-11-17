package com.mineberry.essentials.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TournamentParserTest {
    private static final List<String> FRIENDS = List.of("Steve", "Alex_99", ".BedrockBob");

    private static String chat(String line) {
        return TournamentParser.chatFrom(line, FRIENDS).map(s -> s.friend() + "|" + s.message()).orElse(null);
    }

    private static String death(String line) {
        return TournamentParser.deathOf(line, FRIENDS).orElse(null);
    }

    @Test void friendChatIsRecognisedInCommonFormats() {
        assertEquals("Steve|hello there", chat("<Steve> hello there"));
        assertEquals("Steve|gg", chat("Steve: gg"));
        assertEquals("Steve|rush b", chat("[VIP] Steve \u00bb rush b"));
        assertEquals("Steve|lower", chat("[Clan] [Mod] steve: lower"));
        assertEquals("Alex_99|yo", chat("<Alex_99> yo"));
        assertEquals(".BedrockBob|yo", chat("<.BedrockBob> yo"));
        assertEquals("Steve|hello", chat("[14:40:34] [ASSISTANT] Steve  \u21e8 hello"));
        assertEquals("Steve|hi", chat("[14:40:34] [Clan] [VIP] Steve: hi"));
        assertEquals("Steve|hi", chat("Owner | Steve \u2022 hi"));
    }

    @Test void aNameInsideSomeoneElsesMessageIsNotAFriendTalking() {
        assertEquals(null, chat("Bob: tell Steve: hi"));
        assertEquals(null, chat("Bob whispers to Steve: hi"));
        assertEquals(null, chat("Stevenson: hi"));
        assertEquals(null, chat("Bob: Steve: hi"));
        assertEquals(null, chat("Steve was slain by Bob"));
    }

    @Test void deathLinesMarkTheVictimOnly() {
        assertEquals("Steve", death("Steve was slain by Bob"));
        assertEquals("Steve", death("steve has been eliminated!"));
        assertEquals("Steve", death("[PvP] Steve died"));
        assertEquals("Alex_99", death("\u2620 Alex_99 drowned"));
        assertEquals(null, death("Steve killed Bob"));
        assertEquals(null, death("Bob was slain by Steve"));
        assertEquals(null, death("Steve joined the game"));
    }

    @Test void playerChatNeverCountsAsADeath() {
        assertEquals(null, death("Steve: Steve died"));
        assertEquals(null, death("<Bob> Steve died"));
        assertEquals(null, death("Steve: died"));
        assertEquals(null, death("Stevenson died"));
    }

    @Test void leftWhileInPvpModeAndKilled() {
        String line = "Player Steve has left the game while in PvP mode and was killed";
        assertEquals(Optional.of("Steve"), TournamentParser.leftAndKilled(line, FRIENDS));
        assertEquals("Steve", death(line));
        assertEquals(Optional.empty(), TournamentParser.leftAndKilled("Player Bob has left the game while in PvP mode and was killed", FRIENDS));
        assertEquals(Optional.empty(), TournamentParser.leftAndKilled("Steve left the game", FRIENDS));
    }

    @Test void namesAreValidated() {
        assertEquals(Optional.of("Steve"), TournamentParser.cleanName(" @Steve "));
        assertEquals(Optional.empty(), TournamentParser.cleanName("a b"));
        assertEquals(Optional.empty(), TournamentParser.cleanName("waytoolongusername123"));
    }

    @Test void chatLikeLinesAreSpotted() {
        assertTrue(TournamentParser.looksLikePlayerChat("Bob: round started"));
        assertFalse(TournamentParser.looksLikePlayerChat("The round has started!"));
    }
}
