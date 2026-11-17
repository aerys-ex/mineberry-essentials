package com.mineberry.essentials.modules;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads chat lines for the Tournament HUD. It has no Minecraft code in it, so it can be tested on its own.
 *
 * Three jobs:
 *  - who said a chat line (only friends are looked for, and only when the name is where a sender's name goes)
 *  - which friend a death line is about ("Steve was slain by Alex", "Steve has been eliminated")
 *  - which friend a "left the game while in PvP mode and was killed" line is about
 *
 * It is careful on purpose: a player typing "Steve died lol" must not mark Steve as dead, and a player typing
 * "tell Steve: hi" must not be shown as Steve talking.
 */
public final class TournamentParser {

    /** A valid player name: 1-16 letters, digits or underscores, with an optional leading dot (Bedrock players). */
    private static final Pattern VALID_NAME = Pattern.compile("\\.?[A-Za-z0-9_]{1,16}");

    /** Characters that separate a sender from the message: "name: msg", "<name> msg", "name » msg", "name ⇨ msg"... */
    private static final String SEPARATORS = ":>\u00bb\u203a\u27a4\u2192\u27eb\u21e8\u21d2\u279c\u2794\u27a1\u25b6\u25ba\u226b\u2022";

    private static final String TAG = "(?:\\[[^\\]]{1,24}\\]|\\([^)]{1,24}\\))";
    private static final String WORD = "[^\\s" + SEPARATORS + "\\[\\]()]{1,16}";

    /** In front of the sender's name: up to three [tags] (a time, a rank, a clan), up to two short words, up to two more [tags]. */
    private static final String PREFIX = "(?:" + TAG + "\\s*){0,3}(?:" + WORD + "\\s+){0,2}(?:" + TAG + "\\s*){0,2}";

    /** Optional [tags] between the name and the separator, e.g. "Steve [Clan]: hi". */
    private static final String SUFFIX = "(?:\\s*(?:\\[[^\\]]{1,24}\\]|\\([^)]{1,24}\\)))*";

    /** What follows a name in a death line. Covers the vanilla messages and common "eliminated" wording of tournament plugins. */
    private static final Pattern DEATH_REST = Pattern.compile("^(?:"
            + "died|has died|has been (?:eliminated|killed|slain|defeated)|is (?:out|eliminated|dead)|"
            + "was (?:slain|killed|shot|blown|fireballed|pummeled|pummelled|squashed|impaled|skewered|stung|poked|pricked|"
            + "doomed|struck|roasted|squished|obliterated|burned|burnt|eliminated|finished|knocked|bitten|speared|zapped|frozen|"
            + "crushed|smashed|defeated|pushed|fried|blasted|executed)|"
            + "got (?:eliminated|killed)|fell|hit the ground|drowned|burned to|burnt to|blew up|tried to|walked into|starved|"
            + "suffocated|withered|froze|experienced kinetic energy|discovered the floor|went up in flames|went off with a bang|"
            + "left the confines|didn't want to live|bit the dust|perished"
            + ")\\b", Pattern.CASE_INSENSITIVE);

    /** "Player X has left the game while in PvP mode and was killed" (the MineBerry / MinePeak wording). */
    private static final Pattern LEFT_AND_KILLED = Pattern.compile(
            "^(?:player\\s+)?(\\.?[A-Za-z0-9_]{1,16})\\s+(?:has\\s+)?(?:left|quit|disconnected)(?:\\s+the\\s+game)?"
                    + "(?:\\s+while\\s+(?:in\\s+)?(?:pvp|combat)(?:\\s+mode)?)?\\s+and\\s+(?:was|got|has\\s+been)\\s+(?:killed|slain|eliminated)",
            Pattern.CASE_INSENSITIVE);

    /** Leading decoration of a server line: "[PvP] ", "(!) ", a skull... All of it is skipped before the name. */
    private static final Pattern LEAD = Pattern.compile("^(?:\\s|\\[[^\\]]{0,24}\\]|\\([^)]{0,24}\\)|[^A-Za-z0-9_.\\s:>\u00bb])*");

    private TournamentParser() {}

    // ---------------------------------------------------------------- names

    /** The name without spaces or a slash if it is a valid player name, otherwise empty. */
    public static Optional<String> cleanName(String raw) {
        if (raw == null) return Optional.empty();
        String n = raw.strip();
        if (n.startsWith("@")) n = n.substring(1);
        return VALID_NAME.matcher(n).matches() ? Optional.of(n) : Optional.empty();
    }

    // ---------------------------------------------------------------- chat

    /** A friend's chat message: the friend's name (as the player wrote it in the list) and what they said. */
    public record Said(String friend, String message) {}

    /**
     * If this line is a chat message sent by one of the friends, who and what. The name must sit in the sender
     * position: at most two prefix words or tags before it, then an optional tag, then a separator (":", ">", "»"...).
     */
    public static Optional<Said> chatFrom(String line, List<String> friends) {
        if (line == null || friends.isEmpty()) return Optional.empty();
        String text = line.strip();
        if (text.isEmpty()) return Optional.empty();
        for (String friend : friends) {
            Pattern p = senderPattern(friend);
            Matcher m = p.matcher(text);
            if (m.find()) {
                String said = m.group(1).strip();
                if (!said.isEmpty()) return Optional.of(new Said(friend, said));
            }
        }
        return Optional.empty();
    }

    private static final java.util.Map<String, Pattern> SENDER_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static Pattern senderPattern(String friend) {
        if (SENDER_CACHE.size() > 200) SENDER_CACHE.clear();
        return SENDER_CACHE.computeIfAbsent(friend.toLowerCase(Locale.ROOT), key -> build(friend));
    }

    private static Pattern build(String friend) {
        // "<Steve> hi" has the name inside angle brackets: allow one opening bracket in front of it
        return Pattern.compile("^<?\\s*" + PREFIX + "<?(?<![A-Za-z0-9_])" + Pattern.quote(friend) + "(?![A-Za-z0-9_])" + SUFFIX
                + "\\s*[" + SEPARATORS + "]\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    }

    // ---------------------------------------------------------------- deaths

    /** The friend a death or elimination line is about, if the line is one. Lines that look like player chat never match. */
    public static Optional<String> deathOf(String line, List<String> friends) {
        if (line == null || friends.isEmpty()) return Optional.empty();
        String text = stripLead(line.strip());
        if (text.isEmpty()) return Optional.empty();
        // a death line is a server notice that starts with the name; a chat line has a separator after the name and never matches
        Matcher left = LEFT_AND_KILLED.matcher(text);
        if (left.find()) return friendNamed(left.group(1), friends);
        for (String friend : friends) {
            if (!startsWithName(text, friend)) continue;
            String rest = text.substring(friend.length()).stripLeading();
            if (DEATH_REST.matcher(rest).find()) return Optional.of(friend);
        }
        return Optional.empty();
    }

    /** The friend named in a "left the game while in PvP mode and was killed" line (they are dead and gone), if any. */
    public static Optional<String> leftAndKilled(String line, List<String> friends) {
        if (line == null || friends.isEmpty()) return Optional.empty();
        Matcher m = LEFT_AND_KILLED.matcher(stripLead(line.strip()));
        return m.find() ? friendNamed(m.group(1), friends) : Optional.empty();
    }

    private static String stripLead(String text) {
        Matcher m = LEAD.matcher(text);
        return m.find() ? text.substring(m.end()) : text;
    }

    private static boolean startsWithName(String text, String name) {
        if (text.length() < name.length() || !text.regionMatches(true, 0, name, 0, name.length())) return false;
        if (text.length() == name.length()) return true;
        char next = text.charAt(name.length());
        return !(Character.isLetterOrDigit(next) || next == '_');
    }

    private static Optional<String> friendNamed(String name, List<String> friends) {
        for (String friend : friends) {
            if (friend.equalsIgnoreCase(name)) return Optional.of(friend);
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- misc

    /** True if the line looks like something a player typed (a separator such as ":" or ">" within the first 40 characters). */
    public static boolean looksLikePlayerChat(String line) {
        if (line == null) return false;
        return CHATLIKE.matcher(line.strip()).find();
    }

    private static final Pattern CHATLIKE = Pattern.compile("^.{0,40}?[" + SEPARATORS + "]\\s");
}
