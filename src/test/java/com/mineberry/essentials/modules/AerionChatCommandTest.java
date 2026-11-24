package com.mineberry.essentials.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class AerionChatCommandTest {
    @Test void messageAfterThePrefixIsRead() {
        assertEquals(Optional.of("airdrop info"), AerionChatCommand.parse("!aerion airdrop info"));
        assertEquals(Optional.of("hi"), AerionChatCommand.parse("  !AERION   hi  "));
        assertEquals(Optional.of("how much is legend"), AerionChatCommand.parse("!aerion, how much is legend"));
    }

    @Test void barePrefixGivesAnEmptyMessage() {
        assertEquals(Optional.of(""), AerionChatCommand.parse("!aerion"));
    }

    @Test void otherLinesAreNotForAerion() {
        assertTrue(AerionChatCommand.parse("hello !aerion").isEmpty());
        assertTrue(AerionChatCommand.parse("!aerionx hi").isEmpty());
        assertTrue(AerionChatCommand.parse("aerion hi").isEmpty());
        assertTrue(AerionChatCommand.parse(null).isEmpty());
    }
}
