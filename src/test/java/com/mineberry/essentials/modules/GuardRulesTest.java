package com.mineberry.essentials.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class GuardRulesTest {
    // Same values as the defaults in Config. Not read from Config on purpose: loading that class needs
    // Fabric's config folder, which a plain unit test does not have.
    private static final List<String> WORDS = List.of("nigga", "nigger", "niger", "faggot");
    private static final List<String> COMMANDS = List.of("suicide");

    @Test void guardedWordsAreFoundInAnyCaseWithPluralAndPunctuation() {
        assertTrue(GuardRules.containsGuardedWord("nigga", WORDS));
        assertTrue(GuardRules.containsGuardedWord("NIGGER", WORDS));
        assertTrue(GuardRules.containsGuardedWord("you are a faggot", WORDS));
        assertTrue(GuardRules.containsGuardedWord("faggots", WORDS));
        assertTrue(GuardRules.containsGuardedWord("niggas", WORDS));
        assertTrue(GuardRules.containsGuardedWord("niggaz", WORDS));
        assertTrue(GuardRules.containsGuardedWord("what a   faggot!", WORDS));
        assertTrue(GuardRules.containsGuardedWord("(nigga)", WORDS));
        assertTrue(GuardRules.containsGuardedWord("niger", WORDS));
    }

    @Test void longerWordsAndStuckOnCharactersAreNotMatched() {
        assertFalse(GuardRules.containsGuardedWord("Nigeria", WORDS));
        assertFalse(GuardRules.containsGuardedWord("Nigerian", WORDS));
        assertFalse(GuardRules.containsGuardedWord("snigger", WORDS));
        assertFalse(GuardRules.containsGuardedWord("bigger", WORDS));
        assertFalse(GuardRules.containsGuardedWord("nigga123", WORDS));
        assertFalse(GuardRules.containsGuardedWord("hello world", WORDS));
        assertFalse(GuardRules.containsGuardedWord("", WORDS));
        assertFalse(GuardRules.containsGuardedWord(null, WORDS));
    }

    @Test void invisibleCharactersCannotHideAWord() {
        assertTrue(GuardRules.containsGuardedWord("fag\u200Bgot", WORDS));
        assertTrue(GuardRules.containsGuardedWord("fag\u00ADgot", WORDS));
    }

    @Test void emptyOrMissingListsGuardNothing() {
        assertFalse(GuardRules.containsGuardedWord("faggot", List.of()));
        assertFalse(GuardRules.containsGuardedWord("faggot", null));
        assertFalse(GuardRules.containsGuardedWord("faggot", java.util.Arrays.asList("", "  ", null)));
    }

    @Test void guardedCommandsIgnoreSlashCaseNamespaceAndArguments() {
        assertTrue(GuardRules.isGuardedCommand("suicide", COMMANDS));
        assertTrue(GuardRules.isGuardedCommand("/Suicide", COMMANDS));
        assertTrue(GuardRules.isGuardedCommand("essentials:suicide", COMMANDS));
        assertTrue(GuardRules.isGuardedCommand("suicide now", COMMANDS));
        assertFalse(GuardRules.isGuardedCommand("suicidex", COMMANDS));
        assertFalse(GuardRules.isGuardedCommand("msg Steve hi", COMMANDS));
        assertFalse(GuardRules.isGuardedCommand("suicide", List.of()));
        assertFalse(GuardRules.isGuardedCommand(null, COMMANDS));
    }

    @Test void secondSendWithinTheWindowGoesThroughAndThenResets() {
        GuardRules.Confirmation c = new GuardRules.Confirmation();
        assertFalse(c.confirmed("cmd:suicide", 1_000, 5_000));     // first send: held back
        assertTrue(c.confirmed("cmd:suicide", 3_000, 5_000));      // same again in time: allowed
        assertFalse(c.confirmed("cmd:suicide", 3_500, 5_000));     // a third send must be confirmed again
        assertTrue(c.confirmed("cmd:suicide", 4_000, 5_000));
    }

    @Test void tooLateOrDifferentTextDoesNotConfirm() {
        GuardRules.Confirmation c = new GuardRules.Confirmation();
        assertFalse(c.confirmed("chat:a", 0, 5_000));
        assertFalse(c.confirmed("chat:a", 5_001, 5_000));          // window ran out: arms again instead
        assertTrue(c.confirmed("chat:a", 6_000, 5_000));           // ...and that second try is in time
        assertFalse(c.confirmed("chat:b", 7_000, 5_000));
        assertFalse(c.confirmed("chat:c", 7_500, 5_000));          // a different message replaces the armed one
        assertFalse(c.confirmed("chat:b", 8_000, 5_000));
        assertTrue(c.confirmed("chat:b", 8_500, 5_000));
        assertFalse(c.confirmed("cmd:x", 9_000, 5_000));           // a command and a chat line never confirm each other
        assertFalse(c.confirmed("chat:x", 9_500, 5_000));
    }

    @Test void theSecondSendMustBeTheSameText() {
        assertEquals("hello world", GuardRules.key("  Hello   World "));
        assertEquals(GuardRules.key("Suicide"), GuardRules.key(" suicide "));
        assertFalse(GuardRules.key("hello").equals(GuardRules.key("hello!")));
        assertEquals("", GuardRules.key(null));
    }
}
