package com.mineberry.essentials.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class CommandRulesTest {
    @Test void rootDropsSlashNamespaceAndArguments() {
        assertEquals("msg", CommandRules.root("/Essentials:MSG Steve hi"));
        assertEquals("tp", CommandRules.root("minecraft:tp 1 2 3"));
        assertEquals("spawn", CommandRules.root("  /spawn "));
        assertEquals("", CommandRules.root(null));
    }

    @Test void normalizeLowercasesAndStripsSlashes() {
        Set<String> s = CommandRules.normalize(List.of(" /MSG ", "r", "", "/"));
        assertEquals(Set.of("msg", "r"), s);
    }

    @Test void serverNoticeIsAcceptedButPlayerChatIsNot() {
        Pattern p = Pattern.compile("entered pvp mode", Pattern.CASE_INSENSITIVE);
        assertTrue(SafeLogoutModule.matchesServerNotice(p, "You've entered PvP mode!"));
        assertFalse(SafeLogoutModule.matchesServerNotice(p, "<Steve> entered pvp mode"));
        assertFalse(SafeLogoutModule.matchesServerNotice(p, "Steve: entered pvp mode"));
        assertFalse(SafeLogoutModule.matchesServerNotice(p, "[Member] Steve entered pvp mode"));
        assertFalse(SafeLogoutModule.matchesServerNotice(p, "Nick \u2022 entered pvp mode"));
        assertTrue(SafeLogoutModule.matchesServerNotice(p, "[PvP] You\u2019ve entered PvP mode!"));
        assertFalse(SafeLogoutModule.matchesServerNotice(p, "Steve - entered pvp mode"));
        assertFalse(SafeLogoutModule.matchesServerNotice(p, "Steve \u27a4 entered pvp mode"));
    }

    @Test void noticesAboutOtherPlayersAreRecognised() {
        assertTrue(SafeLogoutModule.isOtherPlayerNotice("Steve has left the game while in PvP mode"));
        assertFalse(SafeLogoutModule.isOtherPlayerNotice("You've entered PvP mode!"));
    }
}
