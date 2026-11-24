package com.mineberry.essentials.modules;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads {@code !aerion <message>} typed in normal chat. No Minecraft code in it, so it can be tested on its own. */
public final class AerionChatCommand {

    private static final Pattern PREFIX = Pattern.compile("^!aerion\\b[\\s,:]*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private AerionChatCommand() {}

    /** The message after "!aerion" (empty if there is none), or empty when the line is not for Aerion. "!aerionx" is not. */
    public static Optional<String> parse(String chatLine) {
        if (chatLine == null) return Optional.empty();
        Matcher m = PREFIX.matcher(chatLine.strip());
        return m.find() ? Optional.of(m.group(1).strip()) : Optional.empty();
    }
}
