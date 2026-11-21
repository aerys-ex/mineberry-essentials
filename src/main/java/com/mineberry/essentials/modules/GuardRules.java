package com.mineberry.essentials.modules;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** The matching rules of Send Guard. No Minecraft classes in here, so they can be unit tested. */
final class GuardRules {

    /** Zero-width characters and soft hyphens: stripped first, so a word can't hide from the check with them in the middle. */
    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF\\u00AD]");

    private GuardRules() {}

    /** True when the command's first word (slash, namespace and case ignored) is in the guarded list. */
    static boolean isGuardedCommand(String command, List<String> guarded) {
        String root = CommandRules.root(command);
        return !root.isEmpty() && CommandRules.normalize(guarded).contains(root);
    }

    /**
     * True when the text contains one of the words as a WHOLE word, in any case, with an optional plural "s" or "z"
     * ("words", "WORDS", "wordz"). A longer word that merely starts with it does not count (a country name that begins
     * with a guarded word is fine), and neither does a letter or digit stuck to either side.
     */
    static boolean containsGuardedWord(String text, List<String> words) {
        if (text == null || words == null || words.isEmpty()) return false;
        StringBuilder alternatives = new StringBuilder();
        for (String word : words) {
            if (word == null) continue;
            String w = word.trim();
            if (w.isEmpty()) continue;
            if (alternatives.length() > 0) alternatives.append('|');
            alternatives.append(Pattern.quote(w));
        }
        if (alternatives.length() == 0) return false;
        Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + alternatives + ")(?:s|z)?(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return p.matcher(INVISIBLE.matcher(text).replaceAll("")).find();
    }

    /**
     * The "send it again to confirm" state. ask() is called for every guarded send: the first call arms it and returns
     * false (do not send), the same key again inside the window returns true (send) and disarms it, so a third send
     * has to be confirmed again. A different key replaces the armed one.
     */
    static final class Confirmation {
        private String key = "";
        private long until;

        boolean confirmed(String newKey, long now, long windowMs) {
            if (newKey.equals(key) && now <= until) {
                key = "";
                return true;
            }
            key = newKey;
            until = now + windowMs;
            return false;
        }
    }

    /** What counts as "the same message" for the second send: trimmed, lower case, spaces collapsed. */
    static String key(String text) {
        return text == null ? "" : text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
