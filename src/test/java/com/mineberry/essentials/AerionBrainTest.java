package com.mineberry.essentials;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Aerion answers from the JSON files in the jar, so these tests read the real MineBerry and MinePeak guides. */
class AerionBrainTest {

    private final AerionBrain brain = AerionBrain.forServer("mineberry");

    private void assertAnswer(String question, String... parts) {
        String answer = brain.answer(question);
        for (String part : parts) assertTrue(answer.contains(part), "\"" + question + "\" -> " + answer);
    }

    @Test
    void stemmingMakesSpellingsMatch() {
        assertEquals(AerionBrain.tokens("tp killing"), AerionBrain.tokens("TP kills"));
        assertEquals(AerionBrain.tokens("spamming"), AerionBrain.tokens("spam"));
        assertEquals(AerionBrain.tokens("Didn't receive"), AerionBrain.tokens("didnt received"));
    }

    @Test
    void rankPricesAcrossServersAndForOne() {
        assertAnswer("how much is legend rank", "Survival rank: Legend $39.99", "Anarchy rank: Legend $29.99", "OPSurvival rank: Legend $5.99");
        assertAnswer("legend price on anarchy", "Anarchy rank: Legend $29.99");
        assertAnswer("legend price in OP survival", "OPSurvival rank: Legend $5.99");
        assertAnswer("how much are survival ranks", "Silver $2.99", "Ender $69.99");
    }

    @Test
    void needsAGameModeWhenItIsMissing() {
        assertEquals("Which game mode do you mean? Survival, Anarchy, OPSurvival or Minigames.", brain.answer("how much are ranks"));
        assertAnswer("survival prices", "What do you want the price of in Survival");
    }

    @Test
    void upgradesKeysCoinsAndReset() {
        assertAnswer("avenger to legend upgrade", "Avenger -> Legend $10.00", "first named rank");
        assertAnswer("legendary crate key price", "Anarchy crate keys", "10x Legendary Crate Key (+2 bonus) $23.99");
        assertAnswer("1000 coins price", "1000 Coins (+500 bonus) $8.99");
        assertAnswer("grant reset price on anarchy", "Anarchy Grant Reset: $24.99", "does not carry over");
    }

    @Test
    void saysWhenAnItemIsNotSoldOnThatServer() {
        assertAnswer("how much is the vip rank on survival", "couldn't find that in Survival", "OPSurvival rank: Vip $1.99");
    }

    @Test
    void punishmentLookup() {
        assertAnswer("what is the punishment for tp killing", "tp killing: ban 5d");
        assertAnswer("what happens if I use xray", "xray: ban 180d");
        assertAnswer("punishment for hacked account", "hacked account: ban forever");
        assertAnswer("punishment for duping", "possession of duped items", "suspicion of duping", "distribution of duped items");
    }

    @Test
    void rulesAndTopics() {
        assertAnswer("what are the chat rules", "list 1", "list 2");
        assertAnswer("what are the hacking rules", "hack clients");
        assertAnswer("can i share my account", "FOREVER");
        assertAnswer("can I get a refund", "no refund");
        assertAnswer("does my rank carry over in a reset", "carry over");
        assertAnswer("what is the server ip", "mc.mineberry.org");
    }

    @Test
    void unknownQuestionsGetTheFallback() {
        AerionBrain.Chat chat = brain.newChat(1);
        assertTrue(chat.say("banana pancakes").length() > 10);
        assertTrue(chat.wasUnknown());
        chat.say("");
        assertTrue(chat.wasUnknown());
        assertAnswer("banana pancakes", "banana");
    }

    @Test
    void smallTalkSoundsLikeAPersonAndAnswersToo() {
        AerionBrain.Chat chat = brain.newChat(2);
        assertFalse(chat.say("hey").isBlank());
        assertFalse(chat.wasUnknown());
        assertFalse(chat.say("how are you?").isBlank());
        assertFalse(chat.say("thx").isBlank());
        assertFalse(chat.say("tell me a joke").isBlank());
        assertTrue(chat.say("what is the server ip").contains("mc.mineberry.org"));   // a real topic beats the chat
        assertTrue(chat.say("hey, what is the server ip").contains("mc.mineberry.org"));
    }

    @Test
    void aerionAdmitsItIsABotAndNotAnAiModel() {
        for (String q : new String[]{"are you a bot?", "r u human", "are you chatgpt"}) {
            String a = brain.answer(q);
            assertTrue(a.contains("bot") || a.contains("Bot"), q + " -> " + a);
            assertFalse(a.toLowerCase().contains("i am human"));
        }
    }

    @Test
    void doesNotRepeatTheSameWordingTwiceInARow() {
        AerionBrain.Chat chat = brain.newChat(3);
        String previous = chat.say("thanks");
        for (int i = 0; i < 12; i++) {
            String next = chat.say("thanks");
            assertFalse(next.equals(previous), "repeated: " + next);
            previous = next;
        }
    }

    @Test
    void fixesSmallTypos() {
        assertAnswer("how much is lengend rank on survivle", "Survival rank: Legend $39.99");
        assertAnswer("punishmnet for xray", "xray: ban 180d");
        assertAnswer("whats the refnud policy", "no refund");
        assertAnswer("banana pancakes", "banana");   // a typo fix never turns nonsense into an answer
    }

    @Test
    void followUpAfterAPriceQuestion() {
        AerionBrain.Chat chat = brain.newChat(4);
        assertTrue(chat.say("how much is legend rank on survival").contains("Survival rank: Legend $39.99"));
        assertTrue(chat.say("and on anarchy?").contains("Anarchy rank: Legend $29.99"));
        assertTrue(chat.say("what about opsurvival").contains("OPSurvival rank: Legend $5.99"));
    }

    @Test
    void answersWhenAskedWhichGameMode() {
        AerionBrain.Chat chat = brain.newChat(5);
        assertTrue(chat.say("how much are ranks").contains("Which game mode"));
        String answer = chat.say("anarchy");
        assertTrue(answer.contains("Anarchy ranks:"), answer);
        assertTrue(chat.say("and crate keys?").contains("Anarchy crate keys"));
    }

    @Test
    void answersWhenAskedWhichRules() {
        AerionBrain.Chat chat = brain.newChat(6);
        assertTrue(chat.say("rules").contains("chat"));
        assertTrue(chat.say("chat").contains("list 1"));
    }

    @Test
    void cheapestAndMostExpensive() {
        assertAnswer("cheapest rank on survival", "Cheapest Survival rank: Silver $2.99");
        assertAnswer("most expensive rank on survival", "Most expensive Survival rank: Ender $69.99");
        assertEquals("Which game mode do you mean? Survival, Anarchy, OPSurvival or Minigames.", brain.answer("cheapest rank"));
        AerionBrain.Chat chat = brain.newChat(7);
        assertTrue(chat.say("what is the cheapest").contains("cheapest what"));
        assertTrue(chat.say("ranks on anarchy").contains("Cheapest Anarchy rank"));
    }

    @Test
    void canIQuestionsGetAStraightNo() {
        assertAnswer("can I use xray?", "Nope.", "xray: ban 180d");
    }

    @Test
    void offersTheClosestTopicAndAnswersYes() {
        AerionBrain.Chat chat = brain.newChat(8);
        String offer = chat.say("chat");
        assertTrue(chat.wasUnknown());
        assertTrue(offer.contains("chat rule"), offer);
        assertTrue(chat.say("yes").contains("list 1"));
        assertFalse(chat.wasUnknown());
    }

    @Test
    void aStrayThingAfterAPriceQuestionIsNotAPriceQuestion() {
        AerionBrain.Chat chat = brain.newChat(10);
        chat.say("how much is legend rank on survival");
        chat.say("banana");
        assertTrue(chat.wasUnknown());
        assertTrue(chat.say("what is a crate").contains("Crate keys unlock crates"));
    }

    @Test
    void wordsCanBeInAnotherOrderOrSpreadOut() {
        assertAnswer("whats the punishment for killing at spawn", "spawn killing");
        assertAnswer("does the legend rank have perks", "permanent rank on your account");
    }

    @Test
    void asksBackWhenAMessageIsTooShort() {
        AerionBrain.Chat chat = brain.newChat(12);
        assertTrue(chat.say("price").contains("Price of what"));
        assertTrue(chat.say("ranks").contains("Which game mode"));
        assertTrue(chat.say("survival").contains("Survival ranks:"));
        AerionBrain.Chat other = brain.newChat(13);
        assertTrue(other.say("survival").contains("What about Survival"));
        assertTrue(other.say("coins").contains("Survival coins"));
    }

    @Test
    void sendsVictimsAndAppealsToStaff() {
        assertAnswer("i got scammed what do i do", "Discord");
        assertAnswer("how do i appeal", "Discord");
    }

    @Test
    void knowsWhoMadeItAndWhoAerysIs() {
        assertAnswer("who made you", "Aerys (Agio12)", "Nice_EX");
        assertAnswer("whats aerys dc", "ex.aerys");
        assertAnswer("aerys youtube", "Aerys Minecraft", "@AerysMC");
        assertAnswer("who is aerys", "tutorials", "MinePeak", "MineBerry", "chat cop");
        assertAnswer("are there any tutorials", "@AerysMC");
        assertAnswer("who is nice ex", "Nice_EX");
        assertAnswer("who made you", "Aerys");
    }

    @Test
    void knowsTheChatCops() {
        assertAnswer("who are the chat cops", "Agio12", "1055101878502952980", "king1155", "nItR0_", "Squidyshu");
        assertAnswer("is king1155 a chat cop", "762525382586400780");
        assertAnswer("who is nitr0", "1193237733150302242");
        assertAnswer("who is d7uzi", "chat cop", "1411968862903341188");
    }

    @Test
    void knowsTheStaffTeam() {
        assertAnswer("who are the staff", "GhostGirl_", "Sr. mods", "Jr. mods", "endercowww", "_MellowShu", "Chat cops", "king1155");
        assertAnswer("staff hierarchy", "Staff > Sr. mod > Mod > Jr. mod > Chat cop");
        assertAnswer("who are the jr mods", "CID", "Hardmystery", "pixel");
        assertAnswer("who is ghostgirl", "manages almost everything");
        assertAnswer("is crysto a mod", "Sr. mod");
    }

    @Test
    void answersTwoQuestionsInOneMessage() {
        AerionBrain.Chat chat = brain.newChat(14);
        String a = chat.say("what is the server ip? and how much is the legend rank on survival");
        assertTrue(a.contains("mc.mineberry.org") && a.contains("Legend $39.99"), a);
        String b = chat.say("what is the server ip? banana pancakes soup");
        assertTrue(b.contains("mc.mineberry.org") && b.contains("didn't get the other part"), b);
    }

    @Test
    void remembersANameAndCanRepeat() {
        AerionBrain.Chat chat = brain.newChat(15);
        assertTrue(chat.say("what is my name").contains("haven't told me"));
        assertTrue(chat.say("call me Sam").contains("Sam"));
        assertEquals("You're Sam.", chat.say("whats my name"));
        String ip = chat.say("what is the server ip");
        chat.say("thanks");
        assertEquals(chat.say("what is the server ip"), ip);
        String last = chat.say("lol");
        assertEquals(last, chat.say("say that again"));
        assertEquals(last, chat.say("repeat"));
    }

    // ---------------------------------------------------------------- synonyms

    @Test
    void wordsWithTheSameMeaningAreOneWord() {
        assertEquals(AerionBrain.tokens("ban"), AerionBrain.tokens("banned"));
        assertEquals(AerionBrain.tokens("ban"), AerionBrain.tokens("punished"));
        assertEquals(AerionBrain.tokens("price"), AerionBrain.tokens("cost"));
        assertEquals(AerionBrain.tokens("price"), AerionBrain.tokens("how much"));
        assertEquals(AerionBrain.tokens("how much is it"), AerionBrain.tokens("price is it"));
        // each word keeps its own spelling when the matcher needs to know what was typed
        assertFalse(AerionBrain.stemWords("banned").equals(AerionBrain.stemWords("punished")));
    }

    @Test
    void synonymsGiveTheSameAnswer() {
        assertEquals(brain.answer("what is the punishment for xray"), brain.answer("what is the ban for xray"));
        assertEquals(brain.answer("punished for xray"), brain.answer("banned for xray"));
        assertAnswer("what does the legend rank cost on survival", "Survival rank: Legend $39.99");
        assertAnswer("legend pricing on anarchy", "Anarchy rank: Legend $29.99");
        assertAnswer("how much is a legend key on anarchy", "Anarchy crate keys");
    }

    // ---------------------------------------------------------------- punishment commands

    @Test
    void givesTheMuteCommandForARule() {
        assertAnswer("mute command for insulting others", "/mute <player> 3h insulting_others");
        assertFalse(brain.answer("mute command for insulting others").contains("server:"));
        assertAnswer("mute command for insulting others on survival", "/mute <player> 3h insulting_others server:surv");
        assertAnswer("what is the mute command for spamming", "/mute <player> 1h spamming_the_chat");
    }

    @Test
    void commandsFollowTheListedPunishment() {
        assertAnswer("ban command for xray on survival", "/ban <player> 180d xray server:surv");
        assertAnswer("ban command for hacks", "/ban <player> 365d hacks", "Listed: ban 1yr");   // years become days
        assertAnswer("command for impersonating", "/ban <player> 1d impersonating", "warning comes first");
        assertAnswer("ban command for hacked account", "/ban <player> <permanent> hacked_account");
        assertAnswer("mute command for advertising", "/mute <player> 100d advertising");
        assertFalse(brain.answer("mute command for advertising").contains("/ban <player>"));
    }

    @Test
    void doesNotInventACommandItCannotBuild() {
        assertAnswer("command for kill farming", "no fixed command", "ban 1w + 1d per 20 kills");
        assertAnswer("command for irl trading", "no fixed command");
        String wrongKind = brain.answer("ban command for spam");   // spam is a mute
        assertTrue(wrongKind.contains("listed as a mute, not a ban") && wrongKind.contains("/mute <player> 1h"), wrongKind);
        assertAnswer("command for family insults", "/mute <player> 3d family_insults", "a range");
    }

    @Test
    void usesTheUsernameAndOnlyAddsAServerWhenAsked() {
        assertAnswer("mute command eli racism", "/mute eli 1w racism");
        assertFalse(brain.answer("mute command eli racism").contains("server:"));
        assertFalse(brain.answer("mute command eli racism").contains("Replace <player>"));
        assertAnswer("mute command bob racism server:opsurv", "/mute bob 1w racism server:opsurv");
        assertAnswer("mute command bob racism server:*", "/mute bob 1w racism server:*");
        assertAnswer("mute command for racism on survival", "/mute <player> 1w racism server:surv");
        assertAnswer("mute command for racism on op survival", "/mute <player> 1w racism server:opsurv");
        assertAnswer("ban command for xray all servers", "/ban <player> 180d xray server:*");
    }

    @Test
    void doesNotGuessAServerCode() {
        assertAnswer("mute command for spam on anarchy", "don't know the server code for Anarchy");
        assertFalse(brain.answer("mute command for spam on anarchy").contains("spamming_the_chat server:"));
    }

    @Test
    void aBareCommandQuestionAsksWhichPunishment() {
        assertAnswer("mute command", "which punishment");
        assertAnswer("what commands can you help with", "Store prices");   // the help topic is still the help topic
    }

    // ---------------------------------------------------------------- rule numbers

    @Test
    void answersARuleNumber() {
        assertAnswer("what is rule 1.5", "Rule 1.5: Respect others");
        assertAnswer("rule 1.11", "No racism or discrimination");
        assertAnswer("rule 7.1", "Scamming in /ah");
        assertAnswer("what are rules 1.1 and 1.2", "Rule 1.1:", "Rule 1.2:");
        assertAnswer("tell me about rule 1", "Rules in section 1:", "Rule 1.11");
    }

    @Test
    void saysSoWhenItDoesNotHaveTheRuleNumber() {
        assertAnswer("what is rule 5.7", "don't have rule 5.7", "1.1-1.11, 7.1");
        assertAnswer("rule 9", "don't have a section 9");
        assertFalse(brain.answer("how much is the 2.99 rank").contains("don't have rule"));   // a price is not a rule number
    }

    @Test
    void findsWhichRuleCoversSomething() {
        assertAnswer("which rule covers begging", "Rule 1.8");
        assertAnswer("which rule is against racism", "Rule 1.11");
        assertAnswer("which rule covers insulting", "Rule 1.3", "Rule 1.5");
        assertAnswer("which rule covers xray", "don't have a numbered rule", "xray: ban 180d");
        assertAnswer("what are the chat rules", "list 1", "list 2");   // the plain rules question is unchanged
    }

    // ---------------------------------------------------------------- follow-ups

    @Test
    void repeatOffendersFollowAPunishment() {
        AerionBrain.Chat chat = brain.newChat(20);
        assertTrue(chat.say("punishment for insulting others").contains("mute 3h/1w"));
        String next = chat.say("and for repeat offenders?");
        assertTrue(next.contains("mute 1w"), next);
        assertTrue(chat.say("what if they do it again").contains("mute 1w"));
    }

    @Test
    void repeatOffendersFollowARule() {
        AerionBrain.Chat chat = brain.newChat(21);
        assertTrue(chat.say("which rule covers insulting").contains("Rule 1.3"));
        String next = chat.say("and for repeat offenders?");
        assertTrue(next.contains("insulting others") && next.contains("mute 1w"), next);
        AerionBrain.Chat other = brain.newChat(22);
        other.say("what is rule 1.11");
        assertTrue(other.say("and the punishment?").contains("racism: mute 1w"));
    }

    @Test
    void commandFollowUpsKeepTheTopicAndTheServer() {
        AerionBrain.Chat chat = brain.newChat(23);
        chat.say("mute command for insulting others");
        assertTrue(chat.say("and on survival?").contains("3h insulting_others server:surv"));
        String repeat = chat.say("and for repeat offenders?");
        assertTrue(repeat.contains("/mute <player> 1w insulting_others server:surv"), repeat);
        assertTrue(chat.say("what about anarchy?").contains("1w insulting_others"));
        AerionBrain.Chat info = brain.newChat(24);
        info.say("punishment for xray");
        assertTrue(info.say("and the command?").contains("/ban <player> 180d xray"));
    }

    @Test
    void aServerFollowUpAfterARuleIsHonest() {
        AerionBrain.Chat chat = brain.newChat(25);
        chat.say("punishment for xray");
        String a = chat.say("what about anarchy?");
        assertTrue(a.contains("doesn't split") && a.contains("Anarchy"), a);
    }

    @Test
    void theNewestTopicIsTheOneAFollowUpIsAbout() {
        AerionBrain.Chat chat = brain.newChat(26);
        chat.say("how much is legend rank on survival");
        chat.say("punishment for insulting others");
        assertTrue(chat.say("and for repeat offenders?").contains("mute 1w"));   // not a price question any more
        chat.say("how much is legend rank on survival");
        assertTrue(chat.say("and on anarchy?").contains("Anarchy rank: Legend"));   // and back to prices
    }

    @Test
    void aFollowUpWithNothingToFollowAsksWhatItMeans() {
        assertAnswer("and for repeat offenders?", "Repeat offenders of what");
        AerionBrain.Chat chat = brain.newChat(27);
        chat.say("thanks again");
        assertFalse(chat.say("thanks again").contains("Repeat offenders"));
    }

    @Test
    void repeatOffendersWithOnlyOneListedStepSaysSo() {
        AerionBrain.Chat chat = brain.newChat(28);
        chat.say("punishment for xray");
        String a = chat.say("and for repeat offenders?");
        assertTrue(a.contains("only have one listed step") && a.contains("ban 180d"), a);
        chat.say("punishment for begging");
        assertTrue(chat.say("and for repeat offenders?").contains("first offence is a warning"));
    }

    // ---------------------------------------------------------------- [not sure]

    @Test
    void saysNotSureAndShowsTheTwoClosest() {
        AerionBrain.Chat chat = brain.newChat(30);
        String offer = chat.say("chat");
        assertTrue(offer.startsWith("[not sure]"), offer);
        assertTrue(offer.contains("1. chat rule") && offer.contains("2. "), offer);
        assertTrue(chat.wasUnknown());
        assertTrue(chat.say("1").contains("list 1"));
        chat.say("chat");
        String second = chat.say("2");
        assertFalse(second.startsWith("[not sure]") || second.contains("don't know"), second);
        assertFalse(chat.wasUnknown());
    }

    @Test
    void aWeakMatchIsNotAnsweredAsIfItWereSure() {
        AerionBrain.Chat chat = brain.newChat(31);
        String a = chat.say("my cousin plays pvp every weekend sometimes with his friends");
        assertTrue(a.startsWith("[not sure]") && a.contains("1. pvp"), a);
        assertTrue(chat.say("can i fly at spawn").contains("Flying"));   // a clear match is still answered straight
        assertAnswer("is the pvp enabled", "pvp");
    }

    @Test
    void aWeakRuleMatchShowsTheClosestRulesNotAGuess() {
        String weak = brain.answer("which rule covers letters");   // only a word of rule 1.2's text, not one of its keywords
        assertTrue(weak.startsWith("[not sure]") && weak.contains("Rule 1.2"), weak);
        assertTrue(brain.answer("which rule covers server").startsWith("[not sure]"));
        assertTrue(brain.answer("which rule covers banana").contains("don't have a numbered rule"));
    }

    @Test
    void minePeakIsEmptyForNow() {
        AerionBrain peak = AerionBrain.forServer("minepeak");
        assertFalse(peak.isReady());
        assertTrue(brain.isReady());
        assertTrue(peak.answer("how much is a rank").contains("MinePeak"));
        assertFalse(peak.answer("hey").isBlank());
        assertTrue(peak.answer("who are you").contains("Aerion"));   // small talk works even while the guide is empty
    }

    // ---------------------------------------------------------------- smarter Aerion

    @Test
    void doesMath() {
        assertEquals("12 * 7 = 84", brain.answer("what is 12 * 7"));
        assertEquals("(3 + 4) * 2 = 14", brain.answer("(3 + 4) * 2"));
        assertEquals("15% of 200 = 30", brain.answer("15% of 200"));
        assertEquals("20 / 4 = 5", brain.answer("20 divided by 4"));
        assertEquals("2 ^ 10 = 1024", brain.answer("whats 2 ^ 10?"));
        assertEquals("1 / 3 = 0.333333", brain.answer("1/3"));
        assertTrue(brain.answer("10 / 0").contains("divide by zero"));
        assertEquals("12 * 7 = 84", brain.answer("12x7"));
    }

    @Test
    void mathLeavesNormalQuestionsAlone() {
        assertNull(AerionBrain.calc("how much is legend rank"));
        assertNull(AerionBrain.calc("what is rule 1.5"));
        assertNull(AerionBrain.calc("1000 coins price"));
        assertNull(AerionBrain.calc("26.2"));
        assertNull(AerionBrain.calc("5"));
        assertTrue(brain.answer("1000 coins price").contains("1000 Coins"));
        assertAnswer("what is rule 1.5", "Rule 1.5");
    }

    @Test
    void addsUpRanksAndShowsTheCheaperUpgrade() {
        assertAnswer("how much are legend and avenger together", "Survival: Legend $39.99 + Avenger $29.99 = $69.98",
                "Avenger -> Legend upgrade for $10.00");
        assertAnswer("total cost of legend + hero on survival", "Legend $39.99 + Hero $21.99 = $61.98");
        assertAnswer("silver plus supreme", "= $8.98");
        assertFalse(brain.answer("total of legend and hero on survival").contains("Anarchy"));
    }

    @Test
    void comparesTwoRanks() {
        String answer = brain.answer("what is the difference between legend and hero");
        assertTrue(answer.startsWith("Legend vs Hero:"), answer);
        assertTrue(answer.contains("Legend (Survival) perks") && answer.contains("Hero (Survival) perks"), answer);
        assertTrue(answer.contains("Survival: Legend $39.99, Hero $21.99"), answer);
        assertTrue(brain.answer("legend vs avenger").contains("Avenger (Survival) perks"));
        assertTrue(brain.answer("is legend better than hero").startsWith("Legend vs Hero:"));
    }

    @Test
    void countsFromTheStoreLists() {
        assertAnswer("how many ranks are there", "Survival has 9 ranks", "Anarchy has 7 ranks");
        assertAnswer("how many ranks on survival", "Survival has 9 ranks: Silver");
        assertFalse(brain.answer("how many ranks on survival").contains("Anarchy"));
        assertAnswer("how many homes do i get", "/sethome");   // not a store question
    }

    @Test
    void smallTalkDoesNotHijackARealQuestion() {
        AerionBrain.Chat chat = brain.newChat(40);
        assertFalse(chat.say("is it ok if i use a minimap").equals("Cool."));
        assertTrue(chat.wasUnknown());
        assertFalse(chat.say("can you help me find a rule about griefing").startsWith("Of course"));
        assertTrue(chat.say("ok").length() > 2);                    // a plain "ok" is still small talk
        assertFalse(chat.say("hello there my friend how is your day going").contains("don't know"));   // so is a chatty hello
        assertTrue(chat.say("hey, how much is legend rank on survival").contains("Legend $39.99"));
    }

    @Test
    void anOldPriceIsNotRememberedAfterAnotherTopic() {
        AerionBrain.Chat chat = brain.newChat(41);
        assertTrue(chat.say("how much is legend on survival").contains("Legend $39.99"));
        assertTrue(chat.say("thanks").length() > 2);                 // small talk keeps the price in mind
        assertTrue(chat.say("and on anarchy?").contains("Anarchy rank: Legend $29.99"));
        chat.say("how many homes do i get");
        assertFalse(chat.say("and for legend?").contains("Survival rank: Legend"), "a stale price came back");
    }

    @Test
    void oneKeyWordIsEnoughWhenItBelongsToOneTopic() {
        assertAnswer("mineberry ip pls", "mc.mineberry.org");
        assertAnswer("ip?", "mc.mineberry.org");
        assertTrue(brain.answer("chat").startsWith("[not sure]"));   // a word shared by many topics still asks
    }

    @Test
    void helpsTheVictimInsteadOfListingThePunishment() {
        assertAnswer("my friend scammed me what now", "staff");
        assertAnswer("i got scammed", "staff");
        assertAnswer("im being harassed by someone", "staff");
        assertAnswer("what is the punishment for scamming players", "Listed punishment for scamming players");
    }

    @Test
    void saysWhatItKnowsAboutAGameMode() {
        String answer = brain.answer("tell me about survival");
        assertTrue(answer.contains("only know the store"), answer);
        assertTrue(answer.contains("Survival ranks"), answer);
        AerionBrain.Chat chat = brain.newChat(42);
        chat.say("is anarchy hardcore");
        assertTrue(chat.say("ranks").contains("Anarchy ranks:"));   // the follow-up carries the game mode over
    }

    @Test
    void readsTheClockFromTheComputer() {
        AerionBrain.useClock(java.time.Clock.fixed(java.time.Instant.parse("2026-11-23T09:05:00Z"), java.time.ZoneOffset.UTC));
        try {
            assertAnswer("what time is it", "09:05", "Monday 23 November 2026");
            assertAnswer("whats the date", "Monday 23 November 2026");
        } finally {
            AerionBrain.useClock(null);
        }
    }

    @Test
    void knowsTheCouncilLore() {
        assertAnswer("what is the council", "two-tier", "AdiKan");
        assertAnswer("who leads the council", "Main Leader");
        assertAnswer("who is in the higher council", "GhostGirl_", "Squidy");
        assertAnswer("who is in the lower council", "Nice_EX", "Mythic");
        assertAnswer("who founded the council", "DarkDevil");
    }

    @Test
    void littleWordsDoNotBreakAKeyword() {
        assertAnswer("where do i find diamonds", "Y -59");
        assertAnswer("how can i find the end portal", "eyes of ender");
        assertAnswer("how should i make a nether portal", "obsidian");
    }

    @Test
    void knowsGeneralMinecraftBasics() {
        assertAnswer("how do i make a nether portal", "obsidian", "General Minecraft");
        assertAnswer("where can i find diamonds", "Y -59");
        assertAnswer("how to find the end portal", "eyes of ender");
    }
}
