package com.mineberry.essentials.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mineberry.essentials.modules.AerionIntents.Intent;
import com.mineberry.essentials.modules.AerionIntents.Item;
import com.mineberry.essentials.modules.AerionIntents.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AerionIntentsTest {

    private static Intent intent(String message) {
        return AerionIntents.parse(message).orElseThrow(() -> new AssertionError("not a command: " + message));
    }

    private static void assertToggle(String message, Boolean on, String target) {
        assertEquals(new Intent(Kind.TOGGLE, on, target), intent(message), message);
    }

    @Test void turningThingsOnAndOff() {
        assertToggle("turn off compass", false, "compass");
        assertToggle("turn the compass off please", false, "compass");
        assertToggle("Can you switch off mini chat?", false, "mini chat");
        assertToggle("aerion enable fps", true, "fps");
        assertToggle("hey aerion, turn on keystrokes", true, "keystrokes");
        assertToggle("disable airdrop helper", false, "airdrop helper");
        assertToggle("toggle keystrokes", null, "keystrokes");
        assertToggle("hide the armor", false, "armor");
        assertToggle("show potions", true, "potions");
    }

    @Test void turnItBackOnHasNoTargetSoAerionUsesTheLastOne() {
        assertToggle("turn it back on", true, "");
        assertToggle("turn it off", false, "");
    }

    @Test void waypointsModuleVersusOneWaypoint() {
        assertToggle("turn on waypoints", true, "waypoints");
        assertToggle("hide all waypoints", false, "waypoints");
        assertEquals(new Intent(Kind.WAYPOINT_VISIBILITY, false, "home"), intent("hide waypoint home"));
        assertEquals(new Intent(Kind.WAYPOINT_VISIBILITY, true, "base"), intent("show waypoint base"));
    }

    @Test void removingWaypoints() {
        assertEquals(new Intent(Kind.WAYPOINT_REMOVE, null, "airdrop"), intent("remove the airdrop waypoint"));
        assertEquals(new Intent(Kind.WAYPOINT_REMOVE, null, "2"), intent("remove waypoint 2"));
        assertEquals(new Intent(Kind.WAYPOINT_REMOVE, null, "nearest"), intent("delete nearest waypoint"));
        assertEquals(new Intent(Kind.WAYPOINT_REMOVE, null, ""), intent("remove waypoint"));
        assertEquals(new Intent(Kind.WAYPOINT_REMOVE, null, "home"), intent("please delete my home waypoint"));
    }

    @Test void removingEveryWaypointIsItsOwnKindSoItCanAskFirst() {
        assertEquals(Kind.WAYPOINT_REMOVE_ALL, intent("remove all waypoints").kind());
        assertEquals(Kind.WAYPOINT_REMOVE_ALL, intent("delete all my waypoints").kind());
        assertEquals(Kind.WAYPOINT_REMOVE_ALL, intent("clear waypoints").kind());
        assertEquals(Kind.WAYPOINT_REMOVE_ALL, intent("clear all waypoints").kind());
    }

    @Test void theWholeHudAndTheRest() {
        assertTrue(AerionIntents.parse("hide the hud").isEmpty());   // the whole HUD is only hidden from the settings
        assertTrue(AerionIntents.parse("show hud").isEmpty());
        assertEquals(Kind.UNDO, intent("undo").kind());
        assertEquals(Kind.UNDO, intent("bring it back").kind());
        assertEquals(Kind.LIST_WAYPOINTS, intent("what waypoints do I have").kind());
        assertEquals(Kind.LIST_WAYPOINTS, intent("list my waypoints").kind());
        assertEquals(Kind.LIST_MODULES, intent("list modules").kind());
        assertEquals(Kind.TEST_ALERT, intent("send a test notification").kind());
    }

    @Test void pickingTheDefaultAerion() {
        assertEquals(new Intent(Kind.SET_DEFAULT, null, "minepeak"), intent("set minepeak as my default"));
        assertEquals(new Intent(Kind.SET_DEFAULT, null, "mineberry"), intent("make mineberry the default aerion"));
        assertEquals(new Intent(Kind.SET_DEFAULT, null, "minepeak"), intent("aerion, use minepeak as default"));
        assertTrue(AerionIntents.parse("what is my default aerion").isEmpty());
    }

    @Test void questionsAndChatterAreNotCommands() {
        assertTrue(AerionIntents.parse("how do I turn off the compass?").isEmpty());
        assertTrue(AerionIntents.parse("what does the radial menu do").isEmpty());
        assertTrue(AerionIntents.parse("is the compass on").isEmpty());
        assertTrue(AerionIntents.parse("clear chat").isEmpty());          // not a waypoint
        assertTrue(AerionIntents.parse("turn left").isEmpty());
        assertTrue(AerionIntents.parse("how much is legend rank").isEmpty());
        assertTrue(AerionIntents.parse("what is rule 5.7").isEmpty());
        assertTrue(AerionIntents.parse("hello").isEmpty());
        assertTrue(AerionIntents.parse("").isEmpty());
        assertEquals(Optional.empty(), AerionIntents.parse(null));
    }

    @Test void yesAndNo() {
        for (String s : List.of("yes", "Yeah", "yep do it", "ok", "sure")) assertTrue(AerionIntents.isYes(s), s);
        for (String s : List.of("no", "nope", "cancel", "never mind", "don't do it")) assertTrue(AerionIntents.isNo(s), s);
        assertFalse(AerionIntents.isYes("no"));
        assertFalse(AerionIntents.isNo("yes"));
        assertFalse(AerionIntents.isYes("what is rule 5"));
        assertFalse(AerionIntents.isYes("it"));
        assertFalse(AerionIntents.isNo("airdrop info"));
    }

    // ---------------------------------------------------------------- finding what they meant

    private static List<Item> items() {
        String[][] all = {{"aerion_mini", "Aerion Mini Chat"}, {"aerion_chat", "Aerion in Chat"}, {"clan_chat", "Clan Chat Toggle"},
                {"waypoints", "Waypoints"}, {"airdrop", "Airdrop Helper"}, {"tournament_mode", "Tournament Mode"},
                {"tournament_hud", "Tournament HUD"}, {"hud_compass", "Compass (HUD)"}, {"hud_tournament", "Tournament Players (HUD)"},
                {"hud_biome", "Biome / Time (HUD)"}, {"aerion_alerts", "Aerion Alerts"}};
        List<Item> out = new ArrayList<>();
        for (String[] m : all) out.add(new Item(m[0], m[1], AerionIntents.aliasesFor(m[0])));
        for (String id : List.of("hud_compass", "hud_biome")) { /* aliases are looked up by id above */ }
        return out;
    }

    private static List<String> ids(String target) {
        return AerionIntents.resolve(target, items()).stream().map(Item::id).toList();
    }

    @Test void aSingleClearNameIsFoundByWordOrAlias() {
        assertEquals(List.of("hud_compass"), ids("compass"));
        assertEquals(List.of("aerion_mini"), ids("mini chat"));
        assertEquals(List.of("airdrop"), ids("airdrop"));
        assertEquals(List.of("airdrop"), ids("airdrop helper"));
        assertEquals(List.of("aerion_alerts"), ids("notifications"));
        assertEquals(List.of("hud_biome"), ids("time"));
        assertEquals(List.of("clan_chat"), ids("clan"));
    }

    @Test void anExactNameBeatsPartialMatches() {
        assertEquals(List.of("waypoints"), ids("waypoints"));
        assertEquals(List.of("aerion_chat"), ids("aerion chat"));
    }

    @Test void anUnclearNameGivesSeveralSoAerionCanAsk() {
        assertEquals(3, ids("tournament").size());
        assertTrue(ids("chat").size() >= 3);
    }

    @Test void unknownNamesFindNothing() {
        assertTrue(ids("nothing at all").isEmpty());
        assertTrue(ids("").isEmpty());
    }

    @Test void waypointNamesMatchByWord() {
        List<Item> wps = List.of(new Item("0", "Home"), new Item("1", "Airdrop (Easy)"), new Item("2", "Waypoint 3"), new Item("3", "Waypoint 4"));
        assertEquals(List.of("1"), AerionIntents.resolve("airdrop", wps).stream().map(Item::id).toList());
        assertEquals(List.of("0"), AerionIntents.resolve("home", wps).stream().map(Item::id).toList());
        assertEquals(List.of("2", "3"), AerionIntents.resolve("waypoint", wps).stream().map(Item::id).toList());
    }
}
