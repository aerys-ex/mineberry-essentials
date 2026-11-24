package com.mineberry.essentials;

import com.mineberry.essentials.modules.AirdropModule;
import com.mineberry.essentials.modules.AirdropParser;
import com.mineberry.essentials.modules.ServerStats;
import net.minecraft.client.gui.Font;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One conversation with Aerion per server, shared by every place you can talk to it: the Aerion tab, the mini chat
 * on the HUD and {@code !aerion <message>} in normal chat. What you said in one shows up in the others, and Aerion
 * remembers it (follow-ups like "and on anarchy?" work across them). Kept until the game closes.
 */
public final class AerionTalk {

    public record Msg(boolean user, String text) {}

    /** One server's conversation: what was said, and Aerion's memory of it. */
    public record Session(List<Msg> messages, AerionBrain.Chat chat) {}

    private static final int MAX_MESSAGES = 80;

    // ---- typewriter: Aerion's newest answer is revealed letter by letter, wherever it is shown
    private static Msg typingMsg;
    private static long typingStart;

    /** Starts the typewriter on an answer that was just added to a conversation. */
    public static void startTyping(Msg m) {
        typingMsg = m;
        typingStart = System.currentTimeMillis();
    }

    /** Shows the answer in full right away (a new question, closing the chat, clearing it). */
    public static void finishTyping() {
        typingMsg = null;
    }

    /** How many letters of this message are visible: all of them unless it is the one being typed. */
    private static int typedChars(Msg m, int total) {
        if (m != typingMsg) return total;
        float cps = Math.max(55f, total / 2.4f);   // quick, but a long answer never takes more than about 2.4 seconds
        int n = (int) ((System.currentTimeMillis() - typingStart) * cps / 1000f);
        if (n >= total) {
            typingMsg = null;
            return total;
        }
        return n;
    }

    /** The part of one wrapped line to draw: the whole line, a part of it, "" for an empty start, or null when it is not reached yet. */
    public static String typed(Msg m, String line, int start) {
        int total = m.text().length();
        int n = typedChars(m, total);
        if (n >= total) return line;
        int visible = n - start;
        if (visible <= 0) return start == 0 ? "" : null;
        return visible >= line.length() ? line : line.substring(0, visible);
    }

    /** True when the blinking cursor belongs at the end of this line (the line that is being typed right now). */
    public static boolean caret(Msg m, String line, int start) {
        if (m != typingMsg || (System.currentTimeMillis() / 350) % 2 != 0) return false;
        int visible = typedChars(m, m.text().length()) - start;
        if (m != typingMsg) return false;   // it just finished
        return visible > 0 ? visible <= line.length() + 1 : start == 0;
    }
    private static final Map<String, Session> SESSIONS = new HashMap<>();

    private AerionTalk() {}

    /**
     * The guide that answers !aerion and the mini chat: the player's default Aerion (Aerion tab, or "make minepeak my default").
     * MineBerry until they pick another.
     */
    public static String currentServerId() {
        return Config.aerionDefault();
    }

    public static Session session(String serverId) {
        return SESSIONS.computeIfAbsent(serverId, id -> {
            AerionBrain brain = AerionBrain.forServer(id);
            List<Msg> messages = new ArrayList<>();
            messages.add(new Msg(false, brain.greeting()));
            return new Session(messages, brain.newChat());
        });
    }

    /**
     * Asks Aerion and calls back with the answer (on the game thread). Both sides are added to the conversation.
     * An airdrop question runs /airdrop in game, so that answer comes a moment later; "add the airdrop to waypoints"
     * also sets the waypoint (see AirdropModule).
     */
    public static void ask(String serverId, String text, Consumer<String> onAnswer) {
        Session s = session(serverId);
        finishTyping();   // a new question: the last answer is shown in full
        add(s, new Msg(true, text));
        Optional<String> done = AerionActions.tryHandle(text);   // "turn off compass", "remove waypoint home", "undo", yes / no
        if (done.isEmpty()) done = AirdropModule.tryAnswer(text);   // "yes" / "no" to Aerion's airdrop question
        if (done.isPresent()) {
            reply(s, done.get(), onAnswer);
            return;
        }
        if (AirdropParser.asksAboutAirdrop(text)) {
            AirdropModule.requestInfo(answer -> reply(s, answer, onAnswer), AirdropParser.wantsWaypoint(text));
        } else if (ServerStats.asks(text)) {
            ServerStats.requestStats(answer -> reply(s, answer, onAnswer));   // runs /gc in game, so the answer comes a moment later
        } else {
            String answer = s.chat().say(text);
            if (s.chat().wasUnknown()) AerionLog.unanswered(serverId, text);   // so the guide can learn what players ask
            reply(s, answer, onAnswer);
        }
    }

    /** Aerion says something on her own (an airdrop announcement): it goes into the conversation of the default Aerion. */
    public static void post(String text) {
        add(session(currentServerId()), new Msg(false, text));
    }

    private static void reply(Session s, String answer, Consumer<String> onAnswer) {
        Msg msg = new Msg(false, answer);
        add(s, msg);
        startTyping(msg);
        onAnswer.accept(answer);
    }

    private static void add(Session s, Msg m) {
        s.messages().add(m);
        while (s.messages().size() > MAX_MESSAGES) s.messages().remove(0);
    }

    /**
     * The command a wrapped line belongs to: the whole paragraph (not just the wrapped line) when it starts with "/", else null.
     * {@code start} is the line's offset in the message, as the screens keep it.
     */
    public static String commandOf(Msg m, int start) {
        if (m.user()) return null;
        String text = m.text();
        if (text.isEmpty()) return null;
        int at = Math.max(0, Math.min(start, text.length() - 1));
        int from = text.lastIndexOf('\n', at - 1) + 1;
        int to = text.indexOf('\n', at);
        if (to < 0) to = text.length();
        String paragraph = text.substring(from, Math.max(from, to)).strip();
        return paragraph.startsWith("/") ? paragraph : null;
    }

    /** Word wrap by pixel width. Explicit line breaks are kept, a word longer than a line is cut. */
    public static List<String> wrap(Font font, String text, int maxW) {
        List<String> out = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (paragraph.isEmpty()) { out.add(""); continue; }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split(" ")) {
                if (word.isEmpty()) continue;
                while (font.width(word) > maxW) {
                    if (line.length() > 0) { out.add(line.toString()); line.setLength(0); }
                    String cut = font.plainSubstrByWidth(word, maxW);
                    if (cut.isEmpty()) cut = word.substring(0, 1);
                    out.add(cut);
                    word = word.substring(cut.length());
                }
                String candidate = line.length() == 0 ? word : line + " " + word;
                if (font.width(candidate) <= maxW) {
                    line.setLength(0);
                    line.append(candidate);
                } else {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                }
            }
            out.add(line.toString());
        }
        return out;
    }
}
