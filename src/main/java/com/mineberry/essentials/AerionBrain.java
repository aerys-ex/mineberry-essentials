package com.mineberry.essentials;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The knowledge behind Aerion, the info guide in the Control Center. One brain per server (MineBerry, MinePeak).
 * It works fully offline: the knowledge is a JSON file in the jar (assets/mineberry_essentials/aerion/&lt;id&gt;.json)
 * plus {@code common.json}, the small talk every server shares (hello, thanks, jokes, "are you a bot"...).
 * A question is matched against it by keywords. No Minecraft classes are used here, so it is unit-testable.
 *
 * The JSON files have these parts (all optional):
 * <ul>
 *   <li>{@code greeting}, {@code fallback}, {@code clarify}, {@code storeNote}: fixed lines of text</li>
 *   <li>{@code fallbacks}: more ways to say "I don't know", picked at random so Aerion does not repeat itself</li>
 *   <li>{@code ready}: false shows the server as "coming soon" in the picker</li>
 *   <li>{@code suggestions}: quick-question buttons, each {@code {"label": "...", "ask": "..."}}</li>
 *   <li>{@code entries}: {@code {"keywords": [...], "answer": "..."}}, plain topics (rules, links, FAQ).
 *       Use {@code "answers": [...]} for several ways to say it (one is picked at random, never the same twice in a
 *       row). {@code {server}} in an answer becomes the server name. {@code "title"} is the name shown in "did you mean".
 *       {@code "then": "rule"} means: if the next message is a short unknown one, retry it with that word added
 *       (so "which rules?" followed by "chat" works like "chat rule")</li>
 *   <li>{@code punishments}: {@code {"name", "value", "aliases"}}, looked up by name</li>
 *   <li>{@code store}: price lists per game mode and type (rank, upgrade, key, coins, reset)</li>
 *   <li>{@code rules}: the numbered rules, {@code {"id": "1.5", "text": "...", "keywords": [...], "punishes": "<punishment name>"}}.
 *       They answer "what is rule 1.5" and "which rule covers insulting". {@code punishes} is optional and links a rule to a
 *       punishment, so a follow-up like "and for repeat offenders?" knows what is meant</li>
 *   <li>{@code commands}: for the punishment commands, {@code {"servers": {"Survival": "surv"}, "permanent": "perm"}}.
 *       Both are optional. {@code servers} gives the {@code server:} code of a game mode, {@code permanent} the duration a
 *       permanent ban takes. Unknown codes are left as a placeholder instead of guessed</li>
 * </ul>
 * To teach Aerion something new, edit the JSON file. No code change is needed.
 *
 * Matching works on "stems": words are lower-cased and lightly shortened (killing, kills and kill all become kill),
 * so the keywords in the JSON file do not need every spelling. Small typos are fixed first ("lengend" becomes "legend").
 * Words that mean the same thing are one word to the matcher (see {@link #SYNONYM_GROUPS}): "ban", "banned" and "punished",
 * or "price", "cost" and "how much", so the keyword lists only need one of them.
 *
 * {@link Chat} is one conversation. It remembers what was just talked about, so "and on anarchy?" or a plain "survival"
 * after "how much are ranks?" work, and so does "and for repeat offenders?" after a punishment or a rule. It says things
 * in different words each time. When a match is weak it says "[not sure]" and shows the two closest instead of guessing.
 */
public final class AerionBrain {

    /** A quick-question button: the short label on it and the full question it asks. */
    public record Suggestion(String label, String ask) {}

    private record Entry(String title, List<List<String>> phrases, List<String> answers, String then, boolean chatty, boolean victim) {}
    private record Punishment(String name, String value, List<List<String>> phrases) {}
    private record Item(String server, String type, String name, String price, Set<String> keys) {}
    private record Hit(Punishment punishment, int start, int len) {}
    private record Closest(String label, String ask, int score) {}
    /** A numbered rule. {@code words} are the words a question can match, {@code punishes} names a punishment or is null. */
    private record Rule(String id, String text, List<List<String>> phrases, Set<String> words, String punishes) {}
    /** A punishment value read as steps: {@code types} are mute/ban, {@code durations} like 3h, 1w. See {@link #plan(String)}. */
    private record Plan(List<String> types, List<String> durations, boolean range, boolean forever, boolean warns, boolean fixed) {}
    /** What a punishment or rule answer was about, so a follow-up knows what "repeat offenders" or "the command" means. */
    private record Focus(List<Punishment> punishments, boolean command, boolean repeat, Set<String> servers) {
        Focus(List<Punishment> punishments, boolean command, Set<String> servers) { this(punishments, command, false, servers); }
    }
    private record Synonym(List<String> words, String to) {}

    private enum Kind { ENTRY, SMALL, PUNISH, RULE, STORE, STORE_ASK, UNKNOWN }

    /** What Aerion says, what kind of answer it is, and what to remember for the next message. */
    private record Reply(String text, Kind kind, String then, List<String> suggest, Focus focus) {
        Reply(String text, Kind kind) { this(text, kind, null, null, null); }
        Reply(String text, Kind kind, String then) { this(text, kind, then, null, null); }
    }

    private static final Pattern NAME = Pattern.compile("(?i)\\b(?:my name is|my names|call me)\\s+([\\p{L}0-9_]{2,16})\\b");

    private static final Map<String, AerionBrain> CACHE = new HashMap<>();

    /**
     * Words that mean the same thing. Each group becomes one word for the matcher, the first one in the group, in the
     * questions and in the JSON files alike. A group can hold phrases ("how much"). Keep groups small and sure: two words
     * only go in one group if either could be used in the other's place. Words that already share a stem ("ban" and
     * "banned") are fine to list, they just make the group easier to read.
     */
    static final List<List<String>> SYNONYM_GROUPS = List.of(
            List.of("punish", "ban", "banned", "punished", "punishment", "penalty", "penalties"),
            List.of("price", "cost", "how much", "pricing"),
            List.of("rule", "law", "guideline"),
            List.of("buy", "purchase"),
            List.of("swear", "curse", "cuss", "profanity"),
            List.of("advertise", "promote", "advertising", "promotion"),
            List.of("cheap", "affordable", "budget"),
            List.of("admin", "administrator", "moderator"));
    private static final Map<String, String> SYNONYM_WORD = new HashMap<>();
    private static final List<Synonym> SYNONYM_PHRASE = new ArrayList<>();
    static {
        for (List<String> group : SYNONYM_GROUPS) {
            String to = stem(group.get(0));
            for (String member : group) {
                List<String> words = stemWords(member);
                if (words.size() == 1) SYNONYM_WORD.put(words.get(0), to);
                else if (words.size() > 1) SYNONYM_PHRASE.add(new Synonym(words, to));
            }
        }
        SYNONYM_PHRASE.sort((a, b) -> b.words().size() - a.words().size());   // the longest phrase first
    }

    /** "1.5" or "5.7" in a question. Not part of a longer number or a price. */
    private static final Pattern RULE_ID = Pattern.compile("(?<![\\d.$])(\\d{1,2})\\.(\\d{1,2})(?![\\d.])");
    private static final Pattern RULE_SECTION = Pattern.compile("(?i)\\brules?\\s*#?\\s*(\\d{1,2})(?!\\d|\\.\\d)");
    private static final Pattern DURATION = Pattern.compile("(?i)\\b(\\d+)\\s*(min|m|h|d|w|yr|y)\\b");

    // words with a meaning for the matcher (stemmed the same way as the questions)
    private static final Set<String> PRICE_WORDS = stems("price", "cost", "much", "buy", "purchase", "usd", "dollar",
            "cheap", "expensive", "pay", "worth");
    private static final Set<String> PUNISH_WORDS = stems("punish", "punishment", "penalty", "penalties", "ban", "mute",
            "kick", "happen", "consequence", "sentence", "long", "duration");
    private static final Set<String> LOW = stems("cheap", "cheapest", "cheaper", "lowest", "affordable", "budget");
    private static final Set<String> HIGH = stems("expensive", "priciest", "pricey", "highest", "costliest");
    /** Words that ask what something gives, not what it costs ("does legend have perks?"). */
    private static final Set<String> INFO = stems("perk", "benefit", "include", "kit", "feature", "advantage");
    private static final Set<String> ALLOWED = stems("can", "allow", "bannable", "legal", "permit");
    /** "give me the mute command for spam". */
    private static final Set<String> COMMAND_WORDS = stems("command", "cmd", "syntax");
    /** "and for repeat offenders?", "what if they do it again". */
    private static final Set<String> REPEAT_WORDS = stems("repeat", "second", "twice", "third", "offender",
            "reoffend", "recidivist", "habitual", "persistent");
    /** Words that turn "which rule ..." into a search of the numbered rules ("which rule covers insulting"). */
    private static final Set<String> RULE_SEARCH = stems("cover", "against", "number", "break", "violate", "govern", "forbid", "apply");
    /** "how much are legend and avenger together", "total of legend + hero". */
    private static final Set<String> TOTAL_WORDS = stems("total", "together", "combined", "altogether", "sum", "both", "plus");
    /** "legend vs hero", "what is the difference between legend and avenger". */
    private static final Set<String> COMPARE_WORDS = stems("compare", "comparison", "difference", "differ", "different", "versus", "vs", "better");
    private static final Set<String> MANY = stems("many");
    /** Words that say nothing about the topic even though they are not little words: politeness, filler, Aerion's own name. */
    private static final Set<String> FILLER = stems("pls", "plz", "please", "aerion", "server", "minecraft", "bro", "mate", "guys", "yo", "hey", "hi", "hello", "thanks", "thx");
    private static final String RULE = stem("rule"), MUTE = stem("mute"), BAN = stem("ban");
    private static final Set<String> FIRST = Set.of("1", "1st", "first", "one"), SECOND = Set.of("2", "2nd", "second", "two");
    private static final Set<String> YES = stems("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "please", "y");
    /** Little words that say nothing about the topic, ignored when looking for the closest topic. */
    private static final Set<String> STOP = stems("the", "is", "are", "was", "what", "whats", "how", "do", "does", "did",
            "i", "my", "me", "can", "could", "to", "of", "in", "on", "for", "it", "you", "your", "and", "or", "with",
            "about", "this", "that", "there", "any", "get", "have", "has", "a", "an", "at", "be", "if", "so", "im", "tell",
            "know", "want", "need", "please", "which", "who", "when", "where", "why", "will", "would", "should", "use",
            "help", "ask", "much", "many", "some", "just", "like");
    /** Real words that are close to a guide word but must never be "corrected" into it. */
    private static final Set<String> PROTECTED = stems("create", "claim", "build", "money", "player", "clan", "chest");

    private static final String UPGRADE = stem("upgrade"), CRATE = stem("crate"), KEY = stem("key"), COIN = stem("coin"),
            RESET = stem("reset"), GRANT = stem("grant"), RANK = stem("rank"), CARRY = stem("carry"),
            SURVIVAL = stem("survival"), ANARCHY = stem("anarchy"), OPSURVIVAL = stem("opsurvival"),
            MINIGAME = stem("minigames"), GAME = stem("game");
    private static final Set<String> SERVER_WORDS = Set.of(SURVIVAL, ANARCHY, OPSURVIVAL, MINIGAME, GAME, "op", "mini");
    private static final Set<String> TYPE_WORDS = Set.of(UPGRADE, CRATE, KEY, COIN, RESET, GRANT, RANK);

    /** Openers for a price answer. The first is empty, so the plain answer is the default. */
    private static final List<String> LEADS = List.of("", "Here you go:\n", "Sure, this is what I have:\n", "Got it. Prices right now:\n");
    private static final List<String> NOPES = List.of("Nope. ", "Not allowed. ", "Short answer: no. ");
    private static final List<String> NO_IDEA = List.of("I don't have anything on {topic} yet.");

    private final String server, greeting, clarify, storeNote;
    private final List<String> fallbacks = new ArrayList<>();
    private final boolean ready;
    private final List<Suggestion> suggestions = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private final List<Punishment> punishments = new ArrayList<>();
    private final List<Item> items = new ArrayList<>();
    private final List<Rule> rules = new ArrayList<>();
    private final Map<String, String> commandServers = new HashMap<>();   // "survival" -> "surv"
    private String permanentDuration = "";
    private final Set<String> vocab = new HashSet<>();

    // ---------------------------------------------------------------- loading

    /** The brain for "mineberry" or "minepeak", loaded once from the jar. A missing or broken file gives an empty brain. */
    public static synchronized AerionBrain forServer(String id) {
        return CACHE.computeIfAbsent(id, AerionBrain::load);
    }

    private static AerionBrain load(String id) {
        AerionBrain brain = null;
        try {
            Reader reader = open("assets/mineberry_essentials/aerion/" + id + ".json");
            if (reader != null) brain = parse(reader);
        } catch (Exception ignored) {
            // fall through to the empty brain
        }
        if (brain == null) {
            brain = parse(new StringReader("{\"server\":\"" + id + "\",\"greeting\":\"Hi, I'm Aerion. My guide could not be loaded.\"}"));
        }
        try {
            Reader common = open("assets/mineberry_essentials/aerion/common.json");
            if (common != null) brain.addCommon(JsonParser.parseReader(common).getAsJsonObject());
        } catch (Exception ignored) {
            // no small talk, the guide still works
        }
        brain.buildVocabulary();
        return brain;
    }

    private static Reader open(String path) throws java.io.IOException {
        InputStream in = AerionBrain.class.getClassLoader().getResourceAsStream(path);
        return in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8);
    }

    /** Builds a brain from JSON text (also used by the tests). */
    public static AerionBrain parse(Reader reader) {
        AerionBrain brain = new AerionBrain(JsonParser.parseReader(reader).getAsJsonObject());
        brain.buildVocabulary();
        return brain;
    }

    /** Builds a brain from a server file and the shared small talk file (also used by the tests). */
    public static AerionBrain parse(Reader serverFile, Reader commonFile) {
        AerionBrain brain = new AerionBrain(JsonParser.parseReader(serverFile).getAsJsonObject());
        brain.addCommon(JsonParser.parseReader(commonFile).getAsJsonObject());
        brain.buildVocabulary();
        return brain;
    }

    private AerionBrain(JsonObject root) {
        server = string(root, "server", "Server");
        greeting = string(root, "greeting", "Hi, I'm Aerion. Ask me a question.");
        clarify = string(root, "clarify", "Which game mode do you mean?");
        storeNote = string(root, "storeNote", "");
        ready = root.has("ready") && root.get("ready").getAsBoolean();

        fallbacks.add(string(root, "fallback", "I don't know that one yet."));
        for (JsonElement e : array(root, "fallbacks")) fallbacks.add(e.getAsString());

        for (JsonElement e : array(root, "suggestions")) {
            JsonObject o = e.getAsJsonObject();
            suggestions.add(new Suggestion(string(o, "label", ""), string(o, "ask", "")));
        }
        addEntries(root, false);
        for (JsonElement e : array(root, "punishments")) {
            JsonObject o = e.getAsJsonObject();
            String name = string(o, "name", "");
            List<String> all = new ArrayList<>();
            for (String alternative : name.split("/")) all.add(alternative);   // "a/b" can be asked as either
            for (JsonElement alias : array(o, "aliases")) all.add(alias.getAsString());
            punishments.add(new Punishment(name, string(o, "value", ""), phrases(all)));
        }
        for (JsonElement e : array(root, "store")) {
            JsonObject group = e.getAsJsonObject();
            String serverName = string(group, "server", ""), type = string(group, "type", "");
            for (JsonElement row : array(group, "items")) {
                JsonArray a = row.getAsJsonArray();
                items.add(makeItem(serverName, type, a.get(0).getAsString(), a.get(1).getAsString(),
                        a.size() > 2 ? a.get(2).getAsString() : ""));
            }
        }
        for (JsonElement e : array(root, "rules")) {
            JsonObject o = e.getAsJsonObject();
            String id = string(o, "id", "").trim(), text = string(o, "text", ""), punishes = string(o, "punishes", null);
            if (id.isEmpty() || text.isEmpty()) continue;
            Set<String> words = new LinkedHashSet<>(tokens(text));
            if (punishes != null) words.addAll(tokens(punishes));
            List<List<String>> keywords = phrases(array(o, "keywords"));
            for (List<String> k : keywords) words.addAll(k);
            words.removeIf(w -> w.length() < 3 || STOP.contains(w) || w.equals(RULE));
            rules.add(new Rule(id, text, keywords, words, punishes));
        }
        if (root.has("commands") && root.get("commands").isJsonObject()) {
            JsonObject commands = root.getAsJsonObject("commands");
            permanentDuration = string(commands, "permanent", "");
            if (commands.has("servers") && commands.get("servers").isJsonObject()) {
                for (Map.Entry<String, JsonElement> s : commands.getAsJsonObject("servers").entrySet()) {
                    commandServers.put(s.getKey().toLowerCase(Locale.ROOT), s.getValue().getAsString());
                }
            }
        }
    }

    /** The small talk shared by every server. Added after the server's own entries, so those win a tie. */
    private void addCommon(JsonObject root) {
        addEntries(root, true);
    }

    private void addEntries(JsonObject root, boolean chatty) {
        for (JsonElement e : array(root, "entries")) {
            JsonObject o = e.getAsJsonObject();
            JsonArray keywords = array(o, "keywords");
            List<String> answers = new ArrayList<>();
            if (o.has("answer")) answers.add(o.get("answer").getAsString());
            for (JsonElement a : array(o, "answers")) answers.add(a.getAsString());
            if (answers.isEmpty()) answers.add("");
            String title = string(o, "title", keywords.size() > 0 ? keywords.get(0).getAsString() : "");
            boolean victim = o.has("victim") && o.get("victim").getAsBoolean();
            entries.add(new Entry(title, phrases(keywords), answers, string(o, "then", null), chatty, victim));
        }
    }

    private void buildVocabulary() {
        vocab.clear();
        for (Entry e : entries) for (List<String> p : e.phrases()) vocab.addAll(p);
        for (Punishment p : punishments) for (List<String> ph : p.phrases()) vocab.addAll(ph);
        for (Item i : items) { vocab.addAll(i.keys()); vocab.addAll(tokens(i.name())); }
        vocab.addAll(PRICE_WORDS);
        vocab.addAll(PUNISH_WORDS);
        vocab.addAll(SERVER_WORDS);
        vocab.addAll(TYPE_WORDS);
        vocab.addAll(LOW);
        vocab.addAll(HIGH);
        vocab.removeIf(w -> w.isEmpty() || STOP.contains(w));
    }

    private static Item makeItem(String server, String type, String name, String price, String extra) {
        Set<String> keys = new LinkedHashSet<>();
        String shown = name;
        switch (type) {
            case "rank" -> { keys.addAll(tokens(name)); shown = titleCase(name); }
            case "upgrade" -> {
                keys.addAll(tokens(name.replace(">", " ")));
                shown = titleCase(name.split(">")[0]) + " -> " + titleCase(name.split(">")[1]);
            }
            case "key" -> keys.addAll(tokens(extra));
            case "coins" -> {
                List<String> first = tokens(name);
                if (!first.isEmpty()) keys.add(first.get(0));   // "1000 Coins ..." is found by its number
            }
            case "reset" -> { keys.add(GRANT); keys.add(RESET); }
            default -> { }
        }
        return new Item(server, type, shown, price, keys);
    }

    private static String string(JsonObject o, String key, String fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : fallback;
    }

    private static JsonArray array(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static List<List<String>> phrases(Iterable<?> words) {
        List<List<String>> out = new ArrayList<>();
        for (Object w : words) {
            String text = w instanceof JsonElement je ? je.getAsString() : w.toString();
            List<String> t = tokens(text);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ---------------------------------------------------------------- info for the screens

    public String serverName() { return server; }

    public String greeting() { return greeting; }

    public List<Suggestion> suggestions() { return suggestions; }

    /** False while a server's guide is still empty (the picker shows "Info coming soon"). */
    public boolean isReady() { return ready; }

    // ---------------------------------------------------------------- conversation

    /** A new conversation with answers that vary from message to message. */
    public Chat newChat() { return new Chat(new Random()); }

    /** A conversation with a fixed seed, so a test can repeat it exactly. */
    public Chat newChat(long seed) { return new Chat(new Random(seed)); }

    /**
     * One conversation with Aerion. It remembers the last price question (so "and on anarchy?" works), the last
     * punishment or rule (so "and for repeat offenders?" or "give me the command" works), a question Aerion asked back
     * ("which rules?" then "chat"), and the "[not sure]" matches it offered (then "yes", "1" or "2").
     * Without a random source (see {@link #answer(String)}) it always gives the first wording, which keeps tests stable.
     */
    public final class Chat {
        private final Random random;
        private final Map<String, Integer> lastPick = new HashMap<>();
        private List<String> lastStore;
        private Focus focus;   // the punishment or rule that was just talked about
        private boolean resolving;   // answering the closest topic, so that answer cannot start another guess
        private List<String> pendingSuggest;
        private Set<String> plain = Set.of();   // the words of the message as typed (before synonyms), for "mute" or "ban"
        private String curPlayer;               // the username in this message ("mute command eli racism"), or null
        private String curCode = "";            // an explicit server code in this message ("server:opsurv", "all servers" = "*")
        private String lastPlayer;              // what the last command was built for, so a follow-up keeps it
        private String lastCode = "";
        private String pendingThen, name, lastReply;
        private boolean unknown, askedStore;

        private Chat(Random random) { this.random = random; }

        /** Forget the conversation (the random wording stays). */
        public void reset() {
            lastStore = null;
            focus = null;
            pendingThen = null;
            pendingSuggest = null;
            unknown = false;
            askedStore = false;
            lastReply = null;
            lastPlayer = null;
            lastCode = "";
        }

        /** True when the last answer was "I don't know". */
        public boolean wasUnknown() { return unknown; }

        /**
         * What Aerion says to a message. Never null, never throws. A message with two or three questions in it
         * ("how much is legend on survival? and what is the ip") gets an answer for each.
         */
        public String say(String question) {
            if (question != null) {
                List<String> parts = new ArrayList<>();
                for (String part : question.split("[?!;]+\\s*|\\.\\s+")) if (tokens(part).size() >= 2) parts.add(part);
                if (parts.size() >= 2 && parts.size() <= 3) {
                    List<String> answers = new ArrayList<>();
                    int missed = 0;
                    for (String part : parts) {
                        String a = one(part);
                        if (unknown) missed++; else answers.add(a);
                    }
                    if (!answers.isEmpty()) {
                        String joined = String.join("\n\n", answers);
                        if (missed > 0) joined += "\n\nI didn't get the other part, though.";
                        unknown = false;
                        lastReply = joined;
                        return joined;
                    }
                }
            }
            return one(question);
        }

        /** One question or message. */
        private String one(String question) {
            String reply = answerOne(question);
            if (!reply.isEmpty() && !repeating) lastReply = reply;
            return reply;
        }

        private boolean repeating;

        private String answerOne(String question) {
            repeating = false;
            List<String> asks = pendingSuggest;
            pendingSuggest = null;
            String spoken = question == null ? null : question.replace("+", " plus ");
            List<String> raw = spoken == null ? List.of() : tokens(spoken);
            plain = spoken == null ? Set.of() : new HashSet<>(stemWords(spoken));
            curPlayer = playerIn(spoken);
            curCode = codeIn(spoken);
            if (raw.isEmpty()) {
                unknown = true;
                pendingThen = null;
                return pick("fb", fallbacks);
            }
            if (asks != null && raw.size() <= 3) {   // "yes", "1" or "2" to a "[not sure]"
                if (YES.contains(raw.get(0)) || FIRST.contains(raw.get(0))) return answerOne(asks.get(0));
                if (SECOND.contains(raw.get(0)) && asks.size() > 1) return answerOne(asks.get(1));
            }

            // small things a person would do: say a name, ask it back, ask to hear it again
            Matcher who = NAME.matcher(question);
            if (who.find()) {
                name = who.group(1);
                return pick("name", List.of("Nice to meet you, " + name + ".", "Hey " + name + ", good to meet you."));
            }
            if (raw.contains(stem("name")) && raw.contains("my") && (raw.contains("what") || raw.contains("who") || raw.contains("remember"))
                    || indexOf(raw, List.of("who", "am", "i")) >= 0) {
                return name != null ? "You're " + name + "." : "You haven't told me. Say \"call me\" and a name, and I'll remember it until the game closes.";
            }
            if (raw.equals(List.of("again")) || raw.equals(List.of("repeat")) || indexOf(raw, List.of("say", "that", "again")) >= 0
                    || indexOf(raw, List.of("repeat", "that")) >= 0 || indexOf(raw, List.of("what", "did", "you", "say")) >= 0
                    || indexOf(raw, List.of("come", "again")) >= 0) {
                repeating = true;
                return lastReply != null ? lastReply : "I haven't said anything yet.";
            }

            String sum = calc(question);   // "what is 12 * 7"
            if (sum != null) {
                pendingThen = null;
                unknown = false;
                return sum;
            }

            List<String> t = correct(raw);
            // a follow-up about the last punishment or rule, a rule number, or "which rule covers ..."
            Reply r = followUp(t);
            if (r == null) r = ruleReply(question, t);
            if (r == null) r = respond(this, t, question.indexOf('$') >= 0);

            boolean again = r.kind() == Kind.UNKNOWN || r.kind() == Kind.STORE_ASK;
            if (again && pendingThen != null && t.size() <= 3) {   // an answer to a question Aerion just asked
                List<String> withThen = new ArrayList<>(t);
                withThen.addAll(tokens(pendingThen));
                Reply r2 = respond(this, withThen, false);
                if (r2.kind() == Kind.ENTRY || r2.kind() == Kind.PUNISH) { r = r2; again = false; }
            }
            // "and on anarchy?", "what about crate keys", or the answer to "which game mode?"
            boolean follow = again || askedStore || continues(t);
            if (follow && lastStore != null && t.size() <= 6 && r.kind() != Kind.PUNISH && r.kind() != Kind.RULE && storeSignal(t)) {
                List<String> merged = merge(lastStore, t);
                Reply r2 = respond(this, merged, false);
                // a price answer, or a better question back ("which game mode?") instead of a shrug
                if (r2.kind() == Kind.STORE || (r2.kind() == Kind.STORE_ASK && r.kind() == Kind.UNKNOWN)) { r = r2; t = merged; }
            }

            askedStore = r.kind() == Kind.STORE_ASK;
            if (r.kind() == Kind.ENTRY) {   // small talk ("thanks") does not count: a price follow-up still works after it
                lastStore = null;
                focus = null;
            }
            if (r.kind() == Kind.STORE || r.kind() == Kind.STORE_ASK) {
                lastStore = t;
                focus = null;
            } else if (r.kind() == Kind.PUNISH || r.kind() == Kind.RULE) {   // the newest topic is the one a follow-up is about
                focus = r.focus();
                lastStore = null;
            }
            pendingThen = r.then();
            pendingSuggest = r.suggest();
            unknown = r.kind() == Kind.UNKNOWN;
            return r.text();
        }

        /**
         * "and for repeat offenders?", "give me the command", "and on anarchy?" after a punishment or a rule.
         * Null when the message is not about what was just said (or nothing was).
         */
        private Reply followUp(List<String> t) {
            if (focus == null || focus.punishments().isEmpty() || t.size() > 7) return null;
            if (!punishmentHits(t).isEmpty()) return null;   // it names something new, so it is a new question
            List<Punishment> about = focus.punishments();
            Set<String> servers = serversIn(t);
            boolean command = commandIntent(t), repeat = repeatIntent(t);
            boolean short_ = continues(t) || t.size() <= 3;

            if (command || repeat) {
                boolean asCommand = command || focus.command();
                Set<String> where = servers.isEmpty() ? focus.servers() : servers;
                String text = asCommand ? commandAnswer(about, where, plain, repeat, this, true) : repeatAnswer(about);
                return new Reply(text, Kind.PUNISH, null, null, new Focus(about, asCommand, repeat, where));
            }
            if (!servers.isEmpty() && short_) {   // "and on anarchy?"
                if (focus.command()) {
                    return new Reply(commandAnswer(about, servers, plain, focus.repeat(), this, true), Kind.PUNISH, null, null, new Focus(about, true, focus.repeat(), servers));
                }
                return new Reply("My guide doesn't split rules or punishments by game mode, so what I just said is all I have for "
                        + String.join(" and ", servers) + " too.", Kind.PUNISH, null, null, focus);
            }
            if (t.stream().anyMatch(PUNISH_WORDS::contains) && short_) {   // "and the punishment?"
                return new Reply(punishmentText(this, about, t), Kind.PUNISH, null, null, new Focus(about, false, focus.servers()));
            }
            return null;
        }

        /** "what is rule 5.7", "rule 5", "which rule covers insulting". */
        private Reply ruleReply(String question, List<String> t) {
            if (rules.isEmpty()) return null;
            Reply byNumber = ruleByNumber(question, t);
            return byNumber != null ? byNumber : ruleSearch(t);
        }

        /** One of the options. Never the same one twice in a row for the same key. */
        private String pick(String key, List<String> options) {
            if (options.size() == 1 || random == null) return options.get(0);
            int i = random.nextInt(options.size());
            Integer previous = lastPick.get(key);
            if (previous != null && previous == i) i = (i + 1) % options.size();
            lastPick.put(key, i);
            return options.get(i);
        }
    }

    /** Does the message start like a follow-up ("and ...", "also ...", "what about ...", "how about ...")? */
    private static boolean continues(List<String> t) {
        if (t.isEmpty()) return false;
        String first = t.get(0);
        if (first.equals("and") || first.equals("also") || first.equals("or") || first.equals("then")) return true;
        return t.size() > 1 && (first.equals("what") || first.equals("how")) && t.get(1).equals("about");
    }

    /** The answer to a single question, without any memory of earlier ones. Never null, never throws. */
    public String answer(String question) {
        return new Chat(null).say(question);
    }

    // ---------------------------------------------------------------- answering

    private Reply respond(Chat c, List<String> t, boolean dollar) {
        boolean price = dollar || t.stream().anyMatch(PRICE_WORDS::contains);

        Reply store = storeAnswer(c, t, price);
        if (store != null) return store;

        // a punishment name, or a topic: the longer match wins, and asking "what happens if..." prefers the punishment
        List<Hit> hits = punishmentHits(t);
        int punishScore = hits.stream().mapToInt(h -> h.len() * 2).max().orElse(0);
        boolean intent = t.stream().anyMatch(PUNISH_WORDS::contains) || commandIntent(t);

        // "my friend scammed me, what now?" is someone asking for help, not asking what the scammer gets
        if (!hits.isEmpty() && victimIntent(hits, t)) {
            for (Entry v : entries) {
                if (v.victim()) return new Reply(fill(c.pick("victim", v.answers())), Kind.ENTRY, null);
            }
        }

        int best = -1, bestScore = 0;
        for (int i = 0; i < entries.size(); i++) {
            int s = score(entries.get(i), t);
            if (s > bestScore) { best = i; bestScore = s; }
        }
        boolean askedForRule = false;
        if (best >= 0 && entries.get(best).chatty()) {
            // small talk only wins when the message really is small talk. "is it ok if i use a minimap" is not "ok",
            // and "can you help me find a rule about griefing" is not "can you help me"
            Entry talk = entries.get(best);
            // a one-word opener ("hello there, how is your day") is a greeting; the same word later on is not ("is it ok if ...")
            boolean single = matchedSize(talk, t) <= 1 && matchedAt(talk, t) > 0;
            if (strayWords(talk, t) >= (single ? 1 : 2)) {
                askedForRule = t.contains(RULE);
                int alt = -1, altScore = 0;
                for (int i = 0; i < entries.size(); i++) {
                    if (entries.get(i).chatty()) continue;
                    int s = score(entries.get(i), t);
                    if (s > altScore) { alt = i; altScore = s; }
                }
                if (alt >= 0) { best = alt; bestScore = altScore; }
                else if (single || askedForRule) { best = -1; bestScore = 0; }
            }
        }
        if (hits.isEmpty() && commandIntent(t) && t.size() <= 4 && (c.plain.contains(MUTE) || c.plain.contains(BAN))) {
            return new Reply("The command for which punishment? Say it like \"mute command for spam\".", Kind.UNKNOWN);   // not the help topic
        }
        if (!hits.isEmpty() && (intent || punishScore >= bestScore)) {
            List<Punishment> found = hits.stream().map(Hit::punishment).toList();
            boolean command = commandIntent(t);
            Set<String> servers = serversIn(t);
            String text = command ? commandAnswer(found, servers, c.plain, repeatIntent(t), c, false) : punishmentText(c, found, t);
            return new Reply(text, Kind.PUNISH, null, null, new Focus(found, command, servers));
        }
        // "rule about griefing": only a weak "rules" topic matched, so look through the numbered rules for the word
        if (!rules.isEmpty() && t.contains(RULE) && (best < 0 || (bestScore <= 2 && !entries.get(best).chatty() && strayWords(entries.get(best), t) >= 1))) {
            Reply byRule = ruleSearch(t, true);
            if (byRule != null) return byRule;
        }
        if (best >= 0) {
            Entry e = entries.get(best);
            // weak: one word matched and either another topic matches just as well, or the rest of a long question is about
            // something else. Say so and show the two closest instead of guessing.
            if (!e.chatty() && bestScore <= 2 && (strayWords(e, t) >= 5 || tiedTopics(t, bestScore) > 1)) {
                List<Closest> near = closest(t, 2);
                if (!near.isEmpty()) return notSure(near);
            }
            return new Reply(fill(c.pick("e" + best, e.answers())), e.chatty() ? Kind.SMALL : Kind.ENTRY, e.then());
        }
        return unknown(c, t);
    }

    /** "scammed me", "i got hacked": the person is the one it happened to. */
    private static boolean victimIntent(List<Hit> hits, List<String> t) {
        Set<String> before = Set.of("got", "was", "been");
        for (Hit h : hits) {
            int end = h.start() + h.len();
            if (end < t.size() && t.get(end).equals("me")) return true;
            if (h.start() > 0 && before.contains(t.get(h.start() - 1))) return true;
        }
        return false;
    }

    /** The size of the longest keyword of the entry that is in the question (0 when none is). */
    private static int matchedSize(Entry e, List<String> t) {
        int n = 0;
        for (List<String> phrase : e.phrases()) {
            if (indexOf(t, phrase) >= 0 || looseIndexOf(t, phrase) >= 0) n = Math.max(n, phrase.size());
        }
        return n;
    }

    /** Where the longest keyword of the entry starts in the question (0 for the first word), or -1. */
    private static int matchedAt(Entry e, List<String> t) {
        int size = 0, at = -1;
        for (List<String> phrase : e.phrases()) {
            int found = indexOf(t, phrase);
            if (found < 0) found = looseIndexOf(t, phrase);
            if (found >= 0 && phrase.size() > size) { size = phrase.size(); at = found; }
        }
        return at;
    }

    /** Fills in {server}, {time} and {date} in an answer. */
    private String fill(String text) {
        String out = text.replace("{server}", server);
        if (out.contains("{time}") || out.contains("{date}")) {
            ZonedDateTime now = ZonedDateTime.now(clock);
            out = out.replace("{time}", now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)))
                    .replace("{date}", now.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)));
        }
        return out;
    }

    /** The clock behind {time} and {date}. A test can set a fixed one. */
    private static volatile Clock clock = Clock.systemDefaultZone();

    static void useClock(Clock replacement) {
        clock = replacement == null ? Clock.systemDefaultZone() : replacement;
    }

    /** Is this word one that carries no topic (politeness, Aerion's name, the server's own name)? */
    private boolean filler(String w) {
        return FILLER.contains(w) || tokens(server).contains(w);
    }

    private static int score(Entry entry, List<String> tokens) {
        int best = 0;
        List<String> core = null;   // the question without its little words, built only if needed
        for (List<String> phrase : entry.phrases()) {
            if (indexOf(tokens, phrase) >= 0) best = Math.max(best, phrase.size() * 2);
            else if (looseIndexOf(tokens, phrase) >= 0) best = Math.max(best, phrase.size() * 2 - 1);   // a close second
            else {
                // "where can i find diamonds" is "where to find diamonds" in other words: compare without little words.
                // Needs two real words left, so a keyword that is mostly filler cannot match on one common word.
                List<String> key = withoutStops(phrase);
                if (key.size() >= 2) {
                    if (core == null) core = withoutStops(tokens);
                    if (indexOf(core, key) >= 0) best = Math.max(best, key.size() * 2 - 1);
                }
            }
        }
        return best;
    }

    private static List<String> withoutStops(List<String> words) {
        List<String> out = new ArrayList<>();
        for (String w : words) if (!STOP.contains(w)) out.add(w);
        return out;
    }

    // ---------------------------------------------------------------- not understood

    /** "I don't know", or "[not sure]" and the two closest topics when part of the question matches. */
    private Reply unknown(Chat c, List<String> t) {
        if (repeatIntent(t) && t.size() <= 6) {   // "and for repeat offenders?" with nothing to follow
            return new Reply("Repeat offenders of what? Ask me about a punishment or a rule first, like \"punishment for spam\".", Kind.UNKNOWN);
        }
        if (commandIntent(t) && t.size() <= 6) {
            return new Reply("The command for which punishment? Say it like \"mute command for spam\".", Kind.UNKNOWN);
        }
        Entry sure = decisive(t);
        if (sure != null) return new Reply(fill(c.pick("d" + entries.indexOf(sure), sure.answers())), Kind.ENTRY, sure.then());
        Set<String> servers = serversIn(t);
        if (!servers.isEmpty() && !items.isEmpty()) return serverInfo(servers);
        List<Closest> near = closest(t, 2);
        if (!near.isEmpty()) {
            Closest top = near.get(0);
            boolean clear = !c.resolving && top.score() >= 8 && !top.label().startsWith("rule ")
                    && (near.size() < 2 || top.score() >= near.get(1).score() + 5);
            if (clear) {   // one topic is clearly the best: answer it, and say which one was understood
                c.resolving = true;
                try {
                    Reply r = respond(c, tokens(top.ask()), false);
                    if (r.kind() != Kind.UNKNOWN) {
                        return new Reply("Sounds like you mean \"" + top.label() + "\":\n" + r.text(), r.kind(), r.then(), r.suggest(), r.focus());
                    }
                } finally {
                    c.resolving = false;
                }
            }
            return notSure(near);
        }
        return new Reply(fill(c.pick("fb", fallbacks.isEmpty() ? NO_IDEA : fallbacks)).replace("{topic}", topicOf(t)), Kind.UNKNOWN);
    }

    /** The question's own words for a fallback line: {@code "grief claims"}, or "that" when nothing useful is left. */
    private static String topicOf(List<String> t) {
        List<String> words = new ArrayList<>();
        for (String w : t) if (w.length() >= 3 && !STOP.contains(w) && !words.contains(w)) words.add(w);
        if (words.isEmpty()) return "that";
        return "\"" + String.join(" ", words.subList(0, Math.min(3, words.size()))) + "\"";
    }

    /**
     * A short question with one word that belongs to a single topic ("mineberry ip pls") gets that topic, instead of a
     * "[not sure]". A word is decisive when one topic lists it in at least two of its keywords and no other topic comes
     * close. Null when the question has more than two such words, or the words point to different topics.
     */
    private Entry decisive(List<String> t) {
        Entry found = null;
        int words = 0;
        for (String w : new LinkedHashSet<>(t)) {
            if (w.length() < 2 || STOP.contains(w) || filler(w)) continue;
            words++;
            Entry owner = null;
            int top = 0, second = 0;
            for (Entry e : entries) {
                if (e.chatty()) continue;
                int n = 0;
                for (List<String> phrase : e.phrases()) if (phrase.contains(w)) n++;
                if (n > top) { second = top; top = n; owner = e; }
                else if (n > second) second = n;
            }
            // a word that also runs through the punishments and the numbered rules ("chat") is a theme, not one topic
            int elsewhere = 0;
            for (Punishment p : punishments) for (List<String> phrase : p.phrases()) if (phrase.contains(w)) { elsewhere++; break; }
            for (Rule r : rules) if (r.words().contains(w)) elsewhere++;
            if (owner != null && top >= 2 && second * 2 <= top && elsewhere < 2) {
                if (found != null && found != owner) return null;
                found = owner;
            }
        }
        return words <= 2 ? found : null;
    }

    /** "tell me about survival": all Aerion knows about a game mode is its store, so it says that and does not make things up. */
    private Reply serverInfo(Set<String> servers) {
        List<String> lines = new ArrayList<>();
        for (String s : servers) {
            Set<String> kinds = new LinkedHashSet<>();
            for (Item i : items) if (i.server().equals(s)) kinds.add(label(i.type(), 2));
            if (kinds.isEmpty()) continue;
            lines.add("For " + s + " I only know the store: " + String.join(", ", kinds) + ". How the mode plays isn't in my guide, staff can tell you.");
        }
        if (lines.isEmpty()) return new Reply((fallbacks.isEmpty() ? NO_IDEA.get(0) : fallbacks.get(0)).replace("{topic}", "that"), Kind.UNKNOWN);
        return new Reply(String.join("\n", lines) + "\nAsk me for example \"" + servers.iterator().next() + " ranks\".", Kind.STORE_ASK);
    }

    /** The match is weak: say so and show the best two. "yes" or "1" takes the first, "2" the second. */
    private static Reply notSure(List<Closest> near) {
        List<String> asks = new ArrayList<>();
        for (Closest c : near) asks.add(c.ask());
        String text = near.size() == 1
                ? "[not sure] The closest thing I have is \"" + near.get(0).label() + "\". Say yes and I'll answer that."
                : "[not sure] The two closest things I have:\n1. " + near.get(0).label() + "\n2. " + near.get(1).label()
                        + "\nSay 1 or 2 and I'll answer that.";
        return new Reply(text, Kind.UNKNOWN, null, asks, null);
    }

    /** The topics and punishments that share the most words with the question, best first, at most {@code n}. */
    private List<Closest> closest(List<String> t, int n) {
        Set<String> words = new LinkedHashSet<>();
        for (String w : t) {
            boolean short_ = w.length() == 2 && vocab.contains(w);   // "ip", "tp", "ah"
            if ((w.length() >= 3 || short_) && !STOP.contains(w) && !filler(w)) words.add(w);
        }
        if (words.size() > 1) words.remove(RULE);   // "rule about griefing": the word "rule" is in half the topics, "griefing" is the clue
        if (words.isEmpty()) return List.of();

        Map<String, Closest> best = new LinkedHashMap<>();   // one per label, so the two are different topics
        for (Entry e : entries) {
            if (e.chatty() || e.title().isEmpty()) continue;
            for (List<String> p : e.phrases()) offer(best, e.title(), e.title(), overlap(p, words));
        }
        for (Punishment p : punishments) {
            for (List<String> ph : p.phrases()) offer(best, "punishment for " + p.name(), "punishment for " + p.name(), overlap(ph, words));
        }
        for (Rule r : rules) {
            int hits = 0;
            for (String w : words) if (r.words().contains(w)) hits++;
            offer(best, "rule " + r.id(), "rule " + r.id(), hits == 0 ? 0 : hits * 10 - 4);   // a whole topic is a better guess than one rule
        }
        List<Closest> sorted = new ArrayList<>(best.values());
        sorted.sort((a, b) -> b.score() - a.score());   // stable: on a tie the one listed first in the guide wins
        return sorted.subList(0, Math.min(n, sorted.size()));
    }

    private static void offer(Map<String, Closest> best, String label, String ask, int score) {
        if (score <= 0) return;
        Closest old = best.get(label);
        if (old == null || score > old.score()) best.put(label, new Closest(label, ask, score));
    }

    /** How many topics (not small talk) match the question with exactly this score. */
    private int tiedTopics(List<String> t, int score) {
        int n = 0;
        for (Entry e : entries) if (!e.chatty() && score(e, t) == score) n++;
        return n;
    }

    /** How many words of the question say something but are not part of any phrase of the entry. */
    private int strayWords(Entry e, List<String> t) {
        Set<String> known = new HashSet<>();
        for (List<String> p : e.phrases()) known.addAll(p);
        int n = 0;
        for (String w : t) if (w.length() >= 3 && !STOP.contains(w) && !filler(w) && !known.contains(w)) n++;
        return n;
    }

    /** How many words of the phrase are in the question, scored so that a short phrase fully covered beats a long one barely touched. */
    private static int overlap(List<String> phrase, Set<String> words) {
        int n = 0;
        for (String w : phrase) if (words.contains(w)) n++;
        return n == 0 ? 0 : n * 10 - phrase.size();
    }

    // ---------------------------------------------------------------- typos

    /** Fixes small typos by looking for a guide word one or two letters away ("lengend" gives "legend"). */
    private List<String> correct(List<String> t) {
        List<String> out = new ArrayList<>(t.size());
        for (String w : t) out.add(fix(w));
        return out;
    }

    private String fix(String w) {
        if (w.length() < 5 || vocab.contains(w) || PROTECTED.contains(w) || STOP.contains(w)) return w;
        for (int i = 0; i < w.length(); i++) if (Character.isDigit(w.charAt(i))) return w;
        int max = w.length() >= 9 ? 2 : 1;
        String best = null;
        int bestDistance = max + 1;
        boolean tie = false;
        for (String v : vocab) {
            if (v.length() < 4 || v.charAt(0) != w.charAt(0) || Math.abs(v.length() - w.length()) > max) continue;
            int d = distance(w, v);
            if (d < bestDistance) { best = v; bestDistance = d; tie = false; }
            else if (d == bestDistance && best != null && !best.equals(v)) tie = true;
        }
        return best != null && !tie ? best : w;
    }

    /** Edit distance where swapping two neighbouring letters counts as one change. */
    private static int distance(String a, String b) {
        int n = a.length(), m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[n][m];
    }

    // ---------------------------------------------------------------- punishments

    private List<Hit> punishmentHits(List<String> t) {
        List<Hit> hits = new ArrayList<>();
        for (Punishment p : punishments) {
            Hit best = null;
            for (List<String> phrase : p.phrases()) {
                int at = indexOf(t, phrase);
                if (at < 0) at = looseIndexOf(t, phrase);   // "killing at spawn" is spawn killing
                if (at >= 0 && (best == null || phrase.size() > best.len())) best = new Hit(p, at, phrase.size());
            }
            if (best != null) hits.add(best);
        }
        // "hacked account" also contains "hack": keep only the most specific match for the same words
        List<Hit> kept = new ArrayList<>();
        for (Hit h : hits) {
            boolean inside = false;
            for (Hit g : hits) {
                if (g != h && g.len() > h.len() && g.start() <= h.start() && g.start() + g.len() >= h.start() + h.len()) inside = true;
            }
            if (!inside) kept.add(h);
        }
        return kept;
    }

    private static String punishmentText(Chat c, List<Punishment> list, List<String> t) {
        // "can I use xray?" and "is spamming allowed?" get a straight "no" first
        String lead = t.stream().anyMatch(ALLOWED::contains) ? c.pick("nope", NOPES) : "";
        if (list.size() == 1) {
            Punishment p = list.get(0);
            return lead + "Listed punishment for " + p.name() + ": " + p.value();
        }
        StringBuilder sb = new StringBuilder(lead).append("Listed punishments:");
        for (int i = 0; i < list.size() && i < 8; i++) {
            sb.append("\n- ").append(list.get(i).name()).append(": ").append(list.get(i).value());
        }
        return sb.toString();
    }

    private static boolean commandIntent(List<String> t) {
        return t.stream().anyMatch(COMMAND_WORDS::contains);
    }

    /** "and for repeat offenders?", "what if they do it again". */
    private static boolean repeatIntent(List<String> t) {
        return t.stream().anyMatch(REPEAT_WORDS::contains) || (t.contains("again") && t.contains("if"));
    }

    // ---------------------------------------------------------------- punishment commands

    /**
     * Reads a listed punishment like "mute 3h/1w", "warn/ban 1d" or "ban forever" as steps. {@code fixed} says a command can
     * be built from it: it has a mute or ban and either a duration or "forever". Something like "ban 1w + 1d per 20 kills" or
     * "ban for original mute time" cannot, so the caller shows the listed text instead. Years become days (1yr = 365d),
     * because "yr" is not a unit the listed commands are known to take.
     */
    private static Plan plan(String value) {
        String v = value.toLowerCase(Locale.ROOT);
        List<String> types = new ArrayList<>();
        Matcher word = Pattern.compile("\\b(warn|mute|ban|kick)\\b").matcher(v);
        boolean warns = false;
        while (word.find()) {
            String w = word.group(1);
            if (w.equals("warn")) warns = true;
            else if (!types.contains(w)) types.add(w);
        }
        List<String> durations = new ArrayList<>();
        Matcher d = DURATION.matcher(v);
        while (d.find()) {
            String unit = d.group(2);
            if (unit.equals("yr") || unit.equals("y")) durations.add(Integer.parseInt(d.group(1)) * 365 + "d");
            else durations.add(d.group(1) + (unit.equals("min") ? "m" : unit));
        }
        boolean range = v.matches(".*\\d+\\s*[a-z]+\\s*-\\s*\\d+.*");   // "3d-30d": by severity, not a next step
        boolean forever = (v.contains("forever") || v.contains("permanent")) && !v.contains("temporary");
        boolean formula = v.contains(" per ") || v.contains("+");
        boolean fixed = !types.isEmpty() && !formula && (forever || !durations.isEmpty());
        return new Plan(types, durations, range, forever, warns, fixed);
    }

    /** "insulting others" gives "insulting_others"; "trespassing/naughty player" gives "trespassing". */
    private static String reasonId(String name) {
        String first = name.split("/")[0].toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return first.replaceAll("^_+|_+$", "");
    }

    /**
     * The mute or ban command for each punishment, in the format {@code /mute bob 3h insulting_others}. The username is the
     * one in the question (or {@code <player>}), and {@code server:} is only added when the question asks for one.
     * {@code repeat} gives the next step for a repeat offender instead of the first one. {@code plain} are the words as typed,
     * so "ban command for ..." picks the ban when a punishment can be either.
     */
    private String commandAnswer(List<Punishment> list, Set<String> servers, Collection<String> plain, boolean repeat,
                                 Chat c, boolean followUp) {
        String who = c.curPlayer != null ? c.curPlayer : followUp ? c.lastPlayer : null;
        String code = !c.curCode.isEmpty() ? c.curCode : followUp ? c.lastCode : "";
        c.lastPlayer = who;
        c.lastCode = code;
        String target = who == null ? "<player>" : who;
        String note = "";
        if (code.isEmpty() && servers.size() == 1) {
            String name = servers.iterator().next();
            String known = commandServers.get(name.toLowerCase(Locale.ROOT));
            if (known != null) code = known;
            else note = "\nI don't know the server code for " + name + " yet, so add server:<code> yourself if you need it.";
        }
        String suffix = code.isEmpty() ? "" : " server:" + code;
        boolean wantMute = plain.contains(MUTE), wantBan = plain.contains(BAN);

        List<String> blocks = new ArrayList<>();
        boolean built = false;
        for (int i = 0; i < list.size() && i < 4; i++) {
            Punishment p = list.get(i);
            Plan plan = plan(p.value());
            String label = list.size() > 1 ? p.name() + ": " : "";
            if (!plan.fixed()) {
                blocks.add(label + "no fixed command, the listed punishment is \"" + p.value() + "\". Pick the duration yourself.");
                continue;
            }
            String duration;
            if (repeat) {
                duration = plan.durations().size() > 1 ? plan.durations().get(1) : plan.warns() && !plan.durations().isEmpty() ? plan.durations().get(0) : null;
                if (duration == null) {
                    blocks.add(label + "I only have one listed step (" + p.value() + "). Anything beyond that is up to staff.");
                    continue;
                }
            } else {
                duration = plan.forever() ? (permanentDuration.isEmpty() ? "<permanent>" : permanentDuration) : plan.durations().get(0);
            }
            List<String> types = new ArrayList<>(plan.types());
            StringBuilder block = new StringBuilder(label);
            if (wantMute != wantBan) {
                String wanted = wantMute ? "mute" : "ban";
                if (types.contains(wanted)) types = List.of(wanted);
                else block.append("This one is listed as a ").append(String.join("/", types)).append(", not a ").append(wanted).append(": ");
            }
            for (int k = 0; k < types.size(); k++) {
                if (k > 0) block.append('\n');
                block.append('/').append(types.get(k)).append(' ').append(target).append(' ').append(duration).append(' ')
                        .append(reasonId(p.name())).append(suffix);
            }
            block.append("\nListed: ").append(p.value());
            if (plan.warns() && !repeat) block.append(" (a warning comes first)");
            if (plan.range()) block.append(" (a range: pick by severity and how often)");
            built = true;
            if (!repeat && plan.durations().size() > 1 && !plan.range()) block.append(". Ask \"and for repeat offenders?\" for the next step");
            if (plan.forever() && permanentDuration.isEmpty()) block.append(". Use the permanent duration your ban command takes");
            blocks.add(block.toString());
        }
        StringBuilder out = new StringBuilder(String.join("\n\n", blocks)).append(note);
        if (!built) return out.toString();
        if (who == null) out.append("\n\nReplace <player> with the username (or add it to your question: \"mute command bob racism\").");
        return out.toString();
    }

    // words that are never a username, and the words that can come right before one
    private static final Set<String> NAME_STOP = Set.of("the", "for", "and", "what", "whats", "give", "get", "tell", "show", "how",
            "can", "you", "please", "pls", "with", "from", "that", "this", "are", "not", "its", "his", "her", "them", "they", "then",
            "about", "when", "where", "who", "some", "any", "one", "again", "also", "too", "now", "use", "using", "need", "want",
            "does", "did", "has", "have", "had", "was", "were", "been", "being", "mute", "ban", "muted", "banned", "command",
            "commands", "cmd", "punishment", "punishments", "punish", "reason", "server", "servers", "surv", "opsurv", "opsurvival",
            "survival", "anarchy", "minigames", "minigame", "mini", "game", "games", "repeat", "repeats", "offender", "offenders",
            "player", "user", "username", "name", "plus", "rule", "rules", "warn", "warning", "long", "time", "length", "duration",
            "day", "days", "hour", "hours", "week", "weeks", "month", "months", "year", "years", "permanent", "forever", "perm",
            "all", "everywhere", "global", "aerion", "okay", "yes", "tempmute", "tempban");
    private static final Set<String> NAME_BEFORE = Set.of("mute", "ban", "command", "cmd", "for", "on", "against", "punish",
            "tempmute", "tempban");
    private static final Pattern SERVER_ARG = Pattern.compile("(?i)\\bserver:([A-Za-z0-9_*,]+)");
    private static final Pattern ALL_SERVERS = Pattern.compile("\\ball (?:the )?(?:servers?|game ?modes?)\\b|\\beverywhere\\b|\\bserver ?wide\\b");
    private static final Pattern OPSURV_CODE = Pattern.compile("\\bopsurv\\b");
    private static final Pattern SURV_CODE = Pattern.compile("\\bsurv\\b");

    /**
     * The username in "mute command eli racism" or "ban eli for xray": the first word right after mute / ban / command / for
     * that is not a word of the guide (a punishment, a server, a duration). Null when there is none.
     */
    private String playerIn(String text) {
        if (text == null) return null;
        Set<String> vocab = new HashSet<>();
        for (Punishment p : punishments) {
            vocab.addAll(stemWords(p.name()));
            vocab.addAll(tokens(p.name()));
            for (List<String> phrase : p.phrases()) vocab.addAll(phrase);
        }
        Matcher m = Pattern.compile("[A-Za-z0-9_]+").matcher(text);
        String prev = "";
        while (m.find()) {
            String w = m.group(), lw = w.toLowerCase(Locale.ROOT);
            if (NAME_BEFORE.contains(prev) && w.length() >= 3 && w.length() <= 16 && !NAME_STOP.contains(lw)
                    && !lw.matches("\\d+[a-z]*") && !knownWord(lw, vocab)) {
                return w;
            }
            prev = lw;
        }
        return null;
    }

    private static boolean knownWord(String lw, Set<String> vocab) {
        String stem = stem(lw);
        if (vocab.contains(lw) || vocab.contains(stem) || PUNISH_WORDS.contains(stem) || COMMAND_WORDS.contains(stem)) return true;
        for (String t : tokens(lw)) if (vocab.contains(t)) return true;
        return false;
    }

    /** "server:opsurv" gives "opsurv", "server:*" or "all servers" gives "*", a bare "surv" / "opsurv" gives that code. Else "". */
    private static String codeIn(String text) {
        if (text == null) return "";
        Matcher m = SERVER_ARG.matcher(text);
        if (m.find()) return m.group(1).toLowerCase(Locale.ROOT);
        String lower = text.toLowerCase(Locale.ROOT);
        if (ALL_SERVERS.matcher(lower).find()) return "*";
        if (OPSURV_CODE.matcher(lower).find()) return "opsurv";
        if (SURV_CODE.matcher(lower).find()) return "surv";
        return "";
    }

    /** What happens to a repeat offender, from the steps in the listed punishment. */
    private static String repeatAnswer(List<Punishment> list) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < list.size() && i < 4; i++) {
            Punishment p = list.get(i);
            Plan plan = plan(p.value());
            String v = p.value();
            String line;
            if (p.value().toLowerCase(Locale.ROOT).contains("repeat")) line = "Listed punishment for " + p.name() + ": " + v;
            else if (plan.fixed() && plan.durations().size() > 1 && !plan.range()) {
                line = p.name() + ": listed as " + v + ", so a repeat offender gets the next step, " + plan.types().get(plan.types().size() - 1)
                        + " " + plan.durations().get(1) + ".";
            } else if (plan.fixed() && plan.warns() && !plan.durations().isEmpty()) {
                line = p.name() + ": listed as " + v + ". The first offence is a warning, so repeating it means " + plan.types().get(plan.types().size() - 1)
                        + " " + plan.durations().get(0) + ".";
            } else if (plan.range()) {
                line = p.name() + ": listed as " + v + ". That is a range by severity and how often, so a repeat offender is at the higher end.";
            } else {
                line = p.name() + ": I only have one listed step (" + v + "). Anything beyond that is up to staff.";
            }
            lines.add(line);
        }
        return String.join("\n", lines);
    }

    // ---------------------------------------------------------------- numbered rules

    /** "what is rule 5.7", "rule 1.5 and 1.6", "rule 5" (a whole section). Null when the message names no rule number. */
    private Reply ruleByNumber(String question, List<String> t) {
        if (question.indexOf('$') >= 0) return null;
        boolean ruleWord = t.contains(RULE);
        List<String> ids = new ArrayList<>();
        Matcher m = RULE_ID.matcher(question);
        while (m.find()) ids.add(m.group(1) + "." + m.group(2));
        if (ids.isEmpty() && ruleWord) {
            Matcher section = RULE_SECTION.matcher(question);
            if (section.find()) {
                String prefix = section.group(1) + ".";
                List<Rule> inside = rules.stream().filter(r -> r.id().startsWith(prefix)).toList();
                if (!inside.isEmpty()) return ruleList("Rules in section " + section.group(1) + ":", inside);
                return new Reply("I don't have a section " + section.group(1) + " in my rule list yet. " + knownRules(), Kind.RULE);
            }
            return null;
        }
        if (ids.isEmpty()) return null;

        List<Rule> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            Rule r = rules.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
            if (r != null) { if (!found.contains(r)) found.add(r); }
            else if (ruleWord) missing.add(id);
        }
        if (found.isEmpty() && missing.isEmpty()) return null;   // a number like 2.5 that is not about rules
        StringBuilder sb = new StringBuilder();
        for (Rule r : found) sb.append(sb.length() > 0 ? "\n" : "").append(ruleLine(r));
        if (!missing.isEmpty()) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("I don't have rule ").append(String.join(" or ", missing)).append(" in my rule list yet. ").append(knownRules());
        }
        return new Reply(sb.toString(), Kind.RULE, null, null, ruleFocus(found));
    }

    /** "which rule covers insulting": the rule or rules that match best, or a "[not sure]" with the closest two. */
    private Reply ruleSearch(List<String> t) {
        return ruleSearch(t, false);
    }

    /** {@code relaxed} also searches for "rule about griefing", but then says nothing at all when no numbered rule matches. */
    private Reply ruleSearch(List<String> t, boolean relaxed) {
        boolean search = t.contains(RULE) && (relaxed || t.stream().anyMatch(RULE_SEARCH::contains)
                || indexOf(t, List.of("which", RULE)) >= 0 || indexOf(t, List.of("what", RULE)) >= 0);
        if (!search) return null;
        Set<String> words = new LinkedHashSet<>();
        for (String w : t) if (w.length() >= 3 && !STOP.contains(w) && !w.equals(RULE) && !RULE_SEARCH.contains(w) && !PUNISH_WORDS.contains(w)) words.add(w);
        if (words.isEmpty()) return null;   // "which rules?" is answered by the rules topic

        List<Rule> ranked = new ArrayList<>(rules);
        Map<Rule, Integer> score = new HashMap<>();
        for (Rule r : rules) {
            int s = 0;
            for (List<String> k : r.phrases()) if (words.containsAll(k) ) s = Math.max(s, 20 + k.size());
            for (String w : words) if (r.words().contains(w)) s += 5;
            score.put(r, s);
        }
        ranked.sort((a, b) -> score.get(b) - score.get(a));   // stable: on a tie the lower number comes first
        int top = ranked.isEmpty() ? 0 : score.get(ranked.get(0));

        if (top >= 20) {   // a keyword of the rule is in the question: show every rule that matches as well as the best
            List<Rule> best = ranked.stream().filter(r -> score.get(r) == top).limit(3).toList();
            return ruleList(best.size() == 1 ? "That's covered by:" : "A few rules touch that:", best);
        }
        if (top > 0) {   // only a loose word in common
            List<Rule> two = ranked.stream().filter(r -> score.get(r) > 0).limit(2).toList();
            Reply r = ruleList("[not sure] The closest " + (two.size() == 1 ? "rule I have:" : "two rules I have:"), two);
            return new Reply(r.text(), Kind.RULE, null, null, null);
        }
        // no numbered rule, but there may be a listed punishment for it
        List<Hit> hits = punishmentHits(t);
        if (!hits.isEmpty()) {
            List<Punishment> found = hits.stream().map(Hit::punishment).toList();
            return new Reply("I don't have a numbered rule for that yet. " + punishmentText(new Chat(null), found, t), Kind.PUNISH, null, null, new Focus(found, false, Set.of()));
        }
        if (relaxed) return null;
        return new Reply("I don't have a numbered rule for that yet. " + knownRules(), Kind.RULE);
    }

    private Reply ruleList(String intro, List<Rule> list) {
        StringBuilder sb = new StringBuilder(intro);
        for (Rule r : list) sb.append('\n').append(ruleLine(r));
        return new Reply(sb.toString(), Kind.RULE, null, null, ruleFocus(list));
    }

    private static String ruleLine(Rule r) {
        return "Rule " + r.id() + ": " + r.text();
    }

    /** A follow-up like "and for repeat offenders?" after a rule uses the punishments the shown rules are linked to, if any. */
    private Focus ruleFocus(List<Rule> shown) {
        List<Punishment> linked = new ArrayList<>();
        for (Rule r : shown) {
            if (r.punishes() == null) continue;
            for (Punishment p : punishments) {
                if (p.name().equalsIgnoreCase(r.punishes()) && !linked.contains(p)) linked.add(p);
            }
        }
        return linked.isEmpty() ? null : new Focus(linked, false, Set.of());
    }

    /** Which numbered rules the guide has, like "1.1-1.11, 7.1". */
    private String knownRules() {
        if (rules.isEmpty()) return "";
        Map<String, List<Integer>> sections = new LinkedHashMap<>();
        for (Rule r : rules) {
            int dot = r.id().indexOf('.');
            String section = dot < 0 ? r.id() : r.id().substring(0, dot);
            try {
                sections.computeIfAbsent(section, k -> new ArrayList<>()).add(dot < 0 ? 0 : Integer.parseInt(r.id().substring(dot + 1)));
            } catch (NumberFormatException e) {
                sections.computeIfAbsent(section, k -> new ArrayList<>());
            }
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : sections.entrySet()) {
            List<Integer> n = e.getValue();
            if (n.isEmpty()) continue;
            int min = n.stream().min(Integer::compare).get(), max = n.stream().max(Integer::compare).get();
            parts.add(n.size() == 1 ? e.getKey() + "." + min
                    : max - min + 1 == n.size() ? e.getKey() + "." + min + "-" + e.getKey() + "." + max
                    : e.getKey() + "." + n.stream().sorted().map(String::valueOf).reduce((x, y) -> x + "/" + y).get());
        }
        return "The numbered rules I have: " + String.join(", ", parts) + ".";
    }

    // ---------------------------------------------------------------- store prices

    /** The price answer, or null when the question is not about the store. */
    private Reply storeAnswer(Chat c, List<String> t, boolean price) {
        if (items.isEmpty() || t.contains(CARRY)) return null;   // "does my rank carry over" is a rules question
        Set<String> servers = serversIn(t);
        String type = typeOf(t);

        // two or more ranks named: add them up ("legend and avenger together") or set them side by side ("legend vs hero")
        List<String> ranks = rankNames(t);
        if (ranks.size() >= 2) {
            if (t.stream().anyMatch(COMPARE_WORDS::contains)) {
                Reply compare = compareReply(ranks, servers);
                if (compare != null) return compare;
            }
            if (t.stream().anyMatch(TOTAL_WORDS::contains)) {
                Reply total = totalReply(ranks, servers);
                if (total != null) return total;
            }
        }
        // "how many ranks are there"
        if (!price && t.contains("many") && (UPGRADE.equals(type) || "rank".equals(type) || "key".equals(type))) {
            Reply count = countReply(type, servers);
            if (count != null) return count;
        }
        if (!price && t.stream().anyMatch(INFO::contains)) return null;   // "does legend have perks" is not a price question

        List<Item> pool = new ArrayList<>();
        for (Item i : items) if (type == null || i.type().equals(type)) pool.add(i);

        // items whose names appear in the question; the best-scoring ones only ("avenger to legend" beats "legend")
        int max = 0;
        for (Item i : pool) max = Math.max(max, hitCount(i, t));

        // "cheapest rank on survival"
        boolean low = t.stream().anyMatch(LOW::contains), high = t.stream().anyMatch(HIGH::contains);
        if ((low || high) && max == 0) return extreme(t, servers, type, pool, low);

        List<Item> matches = new ArrayList<>();
        if (max > 0) {
            for (Item i : pool) if (hitCount(i, t) == max) matches.add(i);
            if (type == null) {   // "legend" on its own means the rank
                List<Item> rankItems = matches.stream().filter(i -> i.type().equals("rank")).toList();
                if (!rankItems.isEmpty()) matches = new ArrayList<>(rankItems);
            }
        }

        if (type == null && max == 0 && servers.isEmpty() && price && t.size() <= 3) {   // "price?", "how much?"
            return new Reply("Price of what? Ranks, rank upgrades, crate keys, coins or Grant Reset.", Kind.STORE_ASK);
        }
        if (type == null && max == 0 && !servers.isEmpty() && t.stream().allMatch(SERVER_WORDS::contains)) {   // just "survival"
            return new Reply("What about " + String.join(" and ", servers) + "? I know the prices of ranks, rank upgrades, crate keys, coins and Grant Reset there.", Kind.STORE_ASK);
        }
        if (type == null && max == 0 && price && !servers.isEmpty()) {   // "survival prices": which item?
            return new Reply("What do you want the price of in " + String.join(" and ", servers)
                    + "? Ranks, rank upgrades, crate keys, coins or Grant Reset.", Kind.STORE_ASK);
        }

        boolean about = type != null ? (price || !servers.isEmpty() || max > 0)
                : (max > 0 && (price || !servers.isEmpty()));
        if (!about) return null;

        String body;
        if (max == 0) {
            if (servers.isEmpty()) return new Reply(clarify, Kind.STORE_ASK);   // "how much are ranks?" needs a game mode
            List<Item> listing = pool.stream().filter(i -> servers.contains(i.server())).toList();
            if (listing.isEmpty()) return null;
            body = c.pick("lead", LEADS) + format(listing);
        } else {
            List<Item> inServers = servers.isEmpty() ? matches : matches.stream().filter(i -> servers.contains(i.server())).toList();
            if (inServers.isEmpty()) {
                body = "I couldn't find that in " + String.join(" or ", servers) + ". It is sold in:\n" + format(matches);
            } else {
                body = c.pick("lead", LEADS) + format(inServers);
            }
        }
        return new Reply(storeNote.isEmpty() ? body : body + "\n\n" + storeNote, Kind.STORE);
    }

    /** The cheapest or most expensive item of a type, per game mode. */
    private Reply extreme(List<String> t, Set<String> servers, String type, List<Item> pool, boolean low) {
        if (type == null) {
            return new Reply("The " + (low ? "cheapest" : "most expensive") + " what? Ranks, rank upgrades, crate keys or coins.", Kind.STORE_ASK);
        }
        if (servers.isEmpty()) return new Reply(clarify, Kind.STORE_ASK);
        List<String> lines = new ArrayList<>();
        for (String s : servers) {
            Item best = null;
            for (Item i : pool) {
                if (!i.server().equals(s) || cents(i) < 0) continue;
                if (best == null || (low ? cents(i) < cents(best) : cents(i) > cents(best))) best = i;
            }
            if (best == null) continue;
            lines.add((low ? "Cheapest " : "Most expensive ") + s + " " + label(type, 1) + ": " + best.name() + " $" + best.price());
        }
        return lines.isEmpty() ? null : new Reply(String.join("\n", lines), Kind.STORE);
    }

    private static long cents(Item i) {
        try {
            return Math.round(Double.parseDouble(i.price().replace(",", "")) * 100);
        } catch (NumberFormatException e) {
            return -1;
        }
    }


    // ---------------------------------------------------------------- totals, comparisons, counts

    /** The ranks named in the question, in the order they were named ("legend and hero" gives legend, hero). */
    private List<String> rankNames(List<String> t) {
        List<String> out = new ArrayList<>();
        for (String w : t) {
            if (out.contains(w)) continue;
            for (Item i : items) {
                if (i.type().equals("rank") && i.keys().contains(w)) { out.add(w); break; }
            }
        }
        return out;
    }

    private List<String> serverNames() {
        Set<String> out = new LinkedHashSet<>();
        for (Item i : items) out.add(i.server());
        return new ArrayList<>(out);
    }

    private Item rankItem(String serverName, String key) {
        for (Item i : items) if (i.server().equals(serverName) && i.type().equals("rank") && i.keys().contains(key)) return i;
        return null;
    }

    private static BigDecimal money(Item i) {
        try {
            return new BigDecimal(i.price().replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The upgrade between two named ranks, if the store sells one on that server (either way round). */
    private Item upgradeBetween(String serverName, List<String> ranks) {
        for (Item i : items) {
            if (i.server().equals(serverName) && i.type().equals("upgrade") && i.keys().containsAll(ranks)) return i;
        }
        return null;
    }

    /** "how much are legend and avenger together": each price, the total, and the upgrade if that is cheaper. */
    private Reply totalReply(List<String> ranks, Set<String> servers) {
        List<String> lines = new ArrayList<>();
        for (String s : serverNames()) {
            if (!servers.isEmpty() && !servers.contains(s)) continue;
            List<Item> parts = new ArrayList<>();
            BigDecimal sum = BigDecimal.ZERO;
            for (String r : ranks) {
                Item i = rankItem(s, r);
                BigDecimal m = i == null ? null : money(i);
                if (m == null) { parts = null; break; }
                parts.add(i);
                sum = sum.add(m);
            }
            if (parts == null) continue;
            StringBuilder sb = new StringBuilder(s).append(": ");
            for (int k = 0; k < parts.size(); k++) {
                if (k > 0) sb.append(" + ");
                sb.append(parts.get(k).name()).append(" $").append(parts.get(k).price());
            }
            sb.append(" = $").append(sum.setScale(2, RoundingMode.HALF_UP).toPlainString());
            Item up = ranks.size() == 2 ? upgradeBetween(s, ranks) : null;
            if (up != null) sb.append("\n  or the ").append(up.name()).append(" upgrade for $").append(up.price()).append(" if you already own the first rank");
            lines.add(sb.toString());
        }
        if (lines.isEmpty()) return null;
        String body = String.join("\n", lines);
        return new Reply(storeNote.isEmpty() ? body : body + "\n\n" + storeNote, Kind.STORE);
    }

    /** "legend vs hero": what each rank gives (from its perks topic) and what each costs. */
    private Reply compareReply(List<String> ranks, Set<String> servers) {
        StringBuilder sb = new StringBuilder();
        List<String> shown = new ArrayList<>();
        for (String r : ranks) shown.add(titleCase(r));
        sb.append(String.join(" vs ", shown)).append(":");
        for (String r : ranks) {
            List<String> perk = tokens(r + " perks");
            String text = null;
            for (Entry e : entries) {
                if (e.chatty()) continue;
                for (List<String> phrase : e.phrases()) if (phrase.equals(perk)) text = e.answers().get(0);
                if (text != null) break;
            }
            if (text == null) { sb.append("\n\nI don't have the perks of ").append(titleCase(r)).append(" in my guide."); continue; }
            String[] lines = text.split("\n");
            sb.append("\n\n").append(lines[0]);
            if (lines.length > 1) sb.append('\n').append(lines[1]);
        }
        List<String> prices = new ArrayList<>();
        for (String s : serverNames()) {
            if (!servers.isEmpty() && !servers.contains(s)) continue;
            List<String> parts = new ArrayList<>();
            for (String r : ranks) {
                Item i = rankItem(s, r);
                if (i != null) parts.add(i.name() + " $" + i.price());
            }
            if (parts.size() == ranks.size()) prices.add(s + ": " + String.join(", ", parts));
        }
        if (!prices.isEmpty()) sb.append("\n\nPrices:\n").append(String.join("\n", prices));
        if (storeNote.length() > 0 && !prices.isEmpty()) sb.append("\n\n").append(storeNote);
        return new Reply(sb.toString(), Kind.ENTRY);
    }

    /** "how many ranks are there": counted from the store lists, so it is never out of date with them. */
    private Reply countReply(String type, Set<String> servers) {
        List<String> lines = new ArrayList<>();
        for (String s : serverNames()) {
            if (!servers.isEmpty() && !servers.contains(s)) continue;
            List<Item> list = items.stream().filter(i -> i.server().equals(s) && i.type().equals(type)).toList();
            if (list.isEmpty()) continue;
            StringBuilder sb = new StringBuilder(s).append(" has ").append(list.size()).append(' ').append(label(type, list.size()));
            if (!type.equals("key")) {
                sb.append(": ");
                for (int k = 0; k < list.size(); k++) sb.append(k > 0 ? ", " : "").append(list.get(k).name());
            }
            lines.add(sb.toString());
        }
        return lines.isEmpty() ? null : new Reply(String.join("\n", lines), Kind.STORE);
    }

    // ---------------------------------------------------------------- follow-up questions

    /** Does this short message say anything about the store (a game mode, a kind of item, an item, a price word)? */
    private boolean storeSignal(List<String> t) {
        if (!serversIn(t).isEmpty() || typeOf(t) != null || hasItem(t)) return true;
        return t.stream().anyMatch(w -> PRICE_WORDS.contains(w) || LOW.contains(w) || HIGH.contains(w));
    }

    private boolean hasItem(List<String> t) {
        for (Item i : items) if (hitCount(i, t) > 0) return true;
        return false;
    }

    /** The last price question with the new message laid over it: a new game mode, kind or item replaces the old one. */
    private List<String> merge(List<String> last, List<String> now) {
        List<String> base = new ArrayList<>(last);
        if (!serversIn(now).isEmpty()) base.removeIf(SERVER_WORDS::contains);
        if (typeOf(now) != null) base.removeIf(TYPE_WORDS::contains);
        if (hasItem(now)) {
            Set<String> names = new HashSet<>();
            for (Item i : items) names.addAll(i.keys());
            base.removeIf(w -> names.contains(w) && !TYPE_WORDS.contains(w));
        }
        base.addAll(now);
        return base;
    }

    private static int hitCount(Item item, List<String> t) {
        int n = 0;
        for (String key : item.keys()) if (t.contains(key)) n++;
        return n;
    }

    private static String format(List<Item> list) {
        Map<String, List<Item>> groups = new LinkedHashMap<>();
        for (Item i : list) groups.computeIfAbsent(i.server() + "|" + i.type(), k -> new ArrayList<>()).add(i);

        StringBuilder sb = new StringBuilder();
        Set<String> types = new LinkedHashSet<>();
        for (List<Item> group : groups.values()) {
            Item first = group.get(0);
            types.add(first.type());
            if (sb.length() > 0) sb.append('\n');
            sb.append(first.server()).append(' ').append(label(first.type(), group.size())).append(": ");
            if (first.type().equals("reset")) {
                sb.append('$').append(first.price());
                continue;
            }
            String separator = first.type().equals("key") ? "; " : ", ";
            for (int i = 0; i < group.size(); i++) {
                if (i > 0) sb.append(separator);
                sb.append(group.get(i).name()).append(" $").append(group.get(i).price());
            }
        }
        for (String type : types) {
            switch (type) {
                case "upgrade" -> sb.append("\n\nAn upgrade only works if you already own the first named rank.");
                case "key" -> sb.append("\n\nKeys open crates in game at /crates.");
                case "coins" -> sb.append("\n\nSpend coins in the Coin Shop with /coinshop.");
                case "reset" -> sb.append("\n\nGrant Reset does not carry over in a reset.");
                default -> { }
            }
        }
        return sb.toString();
    }

    private static String label(String type, int count) {
        return switch (type) {
            case "rank" -> count == 1 ? "rank" : "ranks";
            case "upgrade" -> count == 1 ? "rank upgrade" : "rank upgrades";
            case "key" -> count == 1 ? "crate key" : "crate keys";
            case "coins" -> "coins";
            default -> "Grant Reset";
        };
    }

    private static String typeOf(List<String> t) {
        if (t.contains(UPGRADE)) return "upgrade";
        if (t.contains(CRATE) || t.contains(KEY)) return "key";
        if (t.contains(COIN)) return "coins";
        if (t.contains(RESET) || t.contains(GRANT)) return "reset";
        if (t.contains(RANK)) return "rank";
        return null;
    }

    private static Set<String> serversIn(List<String> t) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < t.size(); i++) {
            String w = t.get(i);
            boolean afterOp = i > 0 && t.get(i - 1).equals("op");
            if (w.equals(OPSURVIVAL) || w.equals("op") || (w.equals(SURVIVAL) && afterOp)) out.add("OPSurvival");
            else if (w.equals(SURVIVAL)) out.add("Survival");
            else if (w.equals(ANARCHY)) out.add("Anarchy");
            else if (w.equals(MINIGAME) || (w.equals("mini") && i + 1 < t.size() && t.get(i + 1).equals(GAME))) out.add("Minigames");
        }
        return out;
    }


    // ---------------------------------------------------------------- calculator

    private static final Pattern PERCENT_OF = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%\\s*of\\s*");
    private static final Pattern MATH_LEAD = Pattern.compile("^(?:(?:what|whats|is|are|the|calculate|calc|compute|solve|how|much|tell|me|can|you|answer|result|of|equals|equal|to|please|pls)\\b\\s*)+");

    /**
     * A small, safe calculator, so "what is 12 * 7", "(3 + 4) * 2", "15% of 200" or "20 divided by 4" get an answer.
     * It only reacts to a message that is nothing but an expression (with the usual lead-in words), so a normal question
     * that happens to hold a number is left alone. Null when the message is not an expression.
     */
    static String calc(String question) {
        if (question == null || question.length() > 80) return null;
        String q = question.toLowerCase(Locale.ROOT).replace("'", "").replace("\u2019", "").replace("$", "")
                .replace("\u00d7", "*").replace("\u00f7", "/").replace("?", " ").replace("=", " ").replace("!", " ");
        q = q.replaceAll("(?<=\\d),(?=\\d{3})", "")                       // 1,000
                .replaceAll("\\b(?:multiplied\\s+by|times)\\b", " * ")
                .replaceAll("\\b(?:divided\\s+by|over)\\b", " / ")
                .replaceAll("\\bplus\\b", " + ")
                .replaceAll("\\bminus\\b", " - ")
                .replaceAll("(?<=\\d)\\s*x\\s*(?=\\d)", " * ");           // 12x7
        q = MATH_LEAD.matcher(q.trim()).replaceFirst("").trim();
        if (q.isEmpty() || !q.matches("[0-9+\\-*/().%^\\s]+|[0-9.\\s]+%\\s*of\\s*[0-9.\\s+\\-*/()]+")) return null;

        String shown = q.replaceAll("\\s+", " ");
        String expr = PERCENT_OF.matcher(shown).replaceAll("($1/100)*");
        if (expr.indexOf('%') >= 0) return null;
        int numbers = 0;
        Matcher n = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(expr);
        while (n.find()) numbers++;
        boolean operator = expr.matches(".*[+*/^].*") || expr.matches(".*\\d\\s*-\\s*[\\d(].*");
        if (numbers < 2 || !operator) return null;
        try {
            BigDecimal value = new MathParser(expr).parse();
            if (value.scale() > 6) value = value.setScale(6, RoundingMode.HALF_UP);
            String result = value.round(new MathContext(15)).stripTrailingZeros().toPlainString();
            String pretty = shown.replaceAll("(?<=[\\d)])\\s*([+*/^-])\\s*(?=[\\d(-])", " $1 ");
            return pretty + " = " + result;
        } catch (ArithmeticException e) {
            return e.getMessage() != null && e.getMessage().equals("zero") ? "I can't divide by zero." : "That number is too big for me.";
        } catch (RuntimeException e) {
            return null;   // not a sum after all
        }
    }

    /** Numbers, + - * / ^ and brackets. Division keeps 16 digits, a power needs a whole exponent from 0 to 64. */
    private static final class MathParser {
        private final String s;
        private int i;

        MathParser(String s) { this.s = s; }

        BigDecimal parse() {
            BigDecimal v = sum();
            skip();
            if (i < s.length()) throw new IllegalArgumentException("trailing");
            return v;
        }

        private BigDecimal sum() {
            BigDecimal v = product();
            while (true) {
                skip();
                if (eat('+')) v = v.add(product());
                else if (eat('-')) v = v.subtract(product());
                else return v;
            }
        }

        private BigDecimal product() {
            BigDecimal v = unary();
            while (true) {
                skip();
                if (eat('*')) v = v.multiply(unary());
                else if (eat('/')) {
                    BigDecimal d = unary();
                    if (d.signum() == 0) throw new ArithmeticException("zero");
                    v = v.divide(d, MathContext.DECIMAL64);
                } else return v;
            }
        }

        private BigDecimal unary() {
            skip();
            if (eat('-')) return unary().negate();
            if (eat('+')) return unary();
            BigDecimal base = atom();
            skip();
            if (eat('^')) {
                int power = unary().intValueExact();
                if (power < 0 || power > 64) throw new ArithmeticException("big");
                return base.pow(power);
            }
            return base;
        }

        private BigDecimal atom() {
            skip();
            if (eat('(')) {
                BigDecimal v = sum();
                skip();
                if (!eat(')')) throw new IllegalArgumentException("bracket");
                return v;
            }
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (start == i) throw new IllegalArgumentException("number");
            return new BigDecimal(s.substring(start, i));
        }

        private void skip() { while (i < s.length() && s.charAt(i) == ' ') i++; }

        private boolean eat(char c) {
            if (i < s.length() && s.charAt(i) == c) { i++; return true; }
            return false;
        }
    }

    // ---------------------------------------------------------------- words

    /** Lower-case words, punctuation removed, each one stemmed, and words with the same meaning made one word. */
    static List<String> tokens(String text) {
        return unify(stemWords(text));
    }

    /** Same as {@link #tokens(String)} but every word keeps its own meaning ("ban" is not turned into "punish"). */
    static List<String> stemWords(String text) {
        String lower = text.toLowerCase(Locale.ROOT).replace("'", "").replace("’", "");
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= lower.length(); i++) {
            char c = i < lower.length() ? lower.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                word.append(c);
            } else if (word.length() > 0) {
                out.add(stem(word.toString()));
                word.setLength(0);
            }
        }
        return out;
    }

    /** Replaces every word or phrase of a synonym group with the first word of its group. */
    private static List<String> unify(List<String> words) {
        List<String> out = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            String replaced = null;
            int skip = 0;
            for (Synonym s : SYNONYM_PHRASE) {
                int n = s.words().size();
                if (i + n <= words.size() && words.subList(i, i + n).equals(s.words())) { replaced = s.to(); skip = n - 1; break; }
            }
            if (replaced == null) replaced = SYNONYM_WORD.getOrDefault(words.get(i), words.get(i));
            out.add(replaced);
            i += skip;
        }
        return out;
    }

    /** Light stemming: killing, kills and kill all give "kill"; rule and rules both give "rul". Same rules for data and questions. */
    static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ing")) w = undouble(w.substring(0, w.length() - 3));
        else if (w.length() > 4 && w.endsWith("ed")) w = undouble(w.substring(0, w.length() - 2));
        if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us")) w = w.substring(0, w.length() - 1);
        if (w.length() > 3 && w.endsWith("e")) w = w.substring(0, w.length() - 1);
        return w;
    }

    private static String undouble(String w) {
        int n = w.length();
        if (n >= 2 && w.charAt(n - 1) == w.charAt(n - 2) && "lsz".indexOf(w.charAt(n - 1)) < 0) return w.substring(0, n - 1);
        return w;
    }

    private static Set<String> stems(String... words) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : words) out.add(stem(w));
        return out;
    }

    private static String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (String part : s.trim().split("\\s+")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    /**
     * Where the words of a phrase of two or more words are all in {@code tokens}, in any order, close together
     * (at most two other words in between), or -1. This is how "killing at spawn" finds "spawn killing".
     */
    private static int looseIndexOf(List<String> tokens, List<String> phrase) {
        if (phrase.size() < 2) return -1;
        int low = Integer.MAX_VALUE, high = -1;
        Set<Integer> used = new HashSet<>();
        for (String word : phrase) {
            int at = -1;
            for (int i = 0; i < tokens.size(); i++) {
                if (tokens.get(i).equals(word) && !used.contains(i)) { at = i; break; }
            }
            if (at < 0) return -1;
            used.add(at);
            low = Math.min(low, at);
            high = Math.max(high, at);
        }
        return high - low + 1 <= phrase.size() + 2 ? low : -1;
    }

    /** Where {@code phrase} appears as consecutive words in {@code tokens}, or -1. */
    private static int indexOf(List<String> tokens, List<String> phrase) {
        for (int i = 0; i + phrase.size() <= tokens.size(); i++) {
            boolean same = true;
            for (int j = 0; j < phrase.size(); j++) {
                if (!tokens.get(i + j).equals(phrase.get(j))) { same = false; break; }
            }
            if (same) return i;
        }
        return -1;
    }
}
