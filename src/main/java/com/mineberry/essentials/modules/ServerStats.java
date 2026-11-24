package com.mineberry.essentials.modules;

import com.mineberry.essentials.Terms;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "server stats" in the Aerion chat: Aerion runs /gc for you and reads the lines the server answers with (uptime, TPS,
 * memory, entities, chunks). /gc needs a rank on the server, so when it does not answer, or says no, Aerion tells you
 * that you need a higher rank in-game.
 *
 * Nothing is sent to the server except the /gc command you ask for, and it goes through Tournament Mode / PvP Command
 * Lock like any other command. Only one request at a time: a new one replaces the old.
 */
public final class ServerStats {

    private static final long QUIET_MS = 900, TIMEOUT_MS = 4000;

    /** "server stats", "gc", "tps", "is the server lagging", "how is the server". Not "server ip" or "server info". */
    private static final Pattern ASKS = Pattern.compile("(?i)\\b(?:server|srv)\\s+(?:stats?|statistics|status|performance|health|lag|tps|uptime|memory|ram)\\b"
            + "|\\b(?:stats?|status|tps|uptime|ram|memory|performance|lag)\\s+(?:of|for|on)\\s+(?:the\\s+)?server\\b"
            + "|^\\s*/?(?:gc|tps)\\s*[?!.]*\\s*$"   // just \"gc\" or \"tps\", not \"what does tps mean\"
            + "|\\b(?:check|show|get|run|what'?s)\\s+(?:the\\s+)?(?:server\\s+)?(?:gc|tps)\\b"
            + "|\\bis\\s+(?:the\\s+)?server\\s+(?:lagging|laggy|ok|fine|down|slow)\\b"
            + "|\\bhow(?:'?s|\\s+is)\\s+the\\s+server\\b"
            + "|\\bserver\\s+is\\s+(?:lagging|laggy|slow)\\b");

    /** A line of the /gc answer: it starts with one of these words. */
    private static final Pattern STAT = Pattern.compile("(?i)^(?:uptime|tps|mspt|used memory|memory|ram|entities(?: on server)?|loaded chunks|chunks|players(?: online)?|online|worlds?|threads|cpu)\\b.*");
    private static final Pattern DENIED = Pattern.compile("(?i)no permission|don't have permission|do not have permission|not allowed|unknown(?: or incomplete)? command|insufficient permission|not authori[sz]ed|higher rank|requires? (?:the )?rank");
    private static final Pattern TPS = Pattern.compile("(?i)^tps\\W*(\\d+(?:[.,]\\d+)?)");

    /** \"Steve: ...\" or \"<Steve> ...\": a player talking, never the server answering /gc. */
    private static final Pattern PLAYER_CHAT = Pattern.compile("^<?[A-Za-z0-9_.]{1,16}>?\\s*[:>\u00bb]\\s");

    private static final Object LOCK = new Object();
    private static final List<String> lines = new ArrayList<>();
    private static Consumer<String> callback;
    private static long askedAt, lastAt;
    private static boolean denied;

    private ServerStats() {}

    /** Call once from the client initializer. */
    public static void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) onLine(message.getString()); });
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
    }

    /** True when the message asks for the server's stats. */
    public static boolean asks(String text) {
        return text != null && ASKS.matcher(text).find();
    }

    /** Runs /gc and calls back (on the game thread) with the stats in plain words, or with a short explanation. */
    public static void requestStats(Consumer<String> done) {
        Minecraft mc = Minecraft.getInstance();
        if (!Terms.accepted()) {
            done.accept("Accept the Terms first, then ask me again.");
            return;
        }
        if (mc.getConnection() == null || mc.player == null) {
            done.accept("Join the server first, then ask me again. I run /gc for you while you are in game.");
            return;
        }
        Consumer<String> replaced;
        synchronized (LOCK) {
            replaced = callback;     // an unanswered earlier request must still be completed, or its chat shows "..." forever
            callback = done;
            askedAt = System.currentTimeMillis();
            lastAt = 0;
            denied = false;
            lines.clear();
        }
        if (replaced != null) replaced.accept("Cancelled: you asked for the server stats again.");
        mc.getConnection().sendCommand("gc");   // goes through Tournament Mode / PvP Command Lock like any other command
    }

    private static String clean(String raw) {
        return raw == null ? "" : raw.replaceAll("^[^A-Za-z0-9]+", "").strip();
    }

    private static void onLine(String raw) {
        synchronized (LOCK) {
            if (callback == null) return;
            String s = clean(raw);
            if (s.isEmpty() || s.length() > 160) return;
            long now = System.currentTimeMillis();
            if (s.length() <= 90 && STAT.matcher(s).matches()) {
                if (!lines.contains(s)) lines.add(s);
                lastAt = now;
            } else if (s.length() <= 100 && lines.isEmpty() && !PLAYER_CHAT.matcher(s).find() && DENIED.matcher(s).find()) {
                denied = true;
                lastAt = now;
            }
        }
    }

    private static void tick() {
        Consumer<String> done;
        String answer;
        synchronized (LOCK) {
            if (callback == null) return;
            long now = System.currentTimeMillis();
            boolean got = !lines.isEmpty() || denied;
            boolean quiet = got && now - lastAt > QUIET_MS;
            boolean timedOut = now - askedAt > TIMEOUT_MS;
            if (!quiet && !timedOut) return;
            done = callback;
            callback = null;
            answer = build();
        }
        done.accept(answer);
    }

    private static String build() {
        if (lines.isEmpty()) {
            return denied
                    ? "The server won't let you run /gc. You need a higher rank in-game to see server stats."
                    : "The server didn't answer /gc. It needs a higher rank in-game, so you may not have access to it yet. "
                    + "(Tournament Mode and PvP Command Lock also block commands.)";
        }
        StringBuilder sb = new StringBuilder("Server stats (from /gc):");
        double tps = -1;
        for (String l : lines) {
            sb.append('\n').append(l);
            Matcher m = TPS.matcher(l);
            if (tps < 0 && m.find()) {
                try { tps = Double.parseDouble(m.group(1).replace(',', '.')); } catch (NumberFormatException ignored) {}
            }
        }
        if (tps >= 0) {
            sb.append("\n\n").append(tps >= 19.0 ? "The server is running smoothly."
                    : tps >= 15.0 ? "TPS is a bit low, expect some lag."
                    : "TPS is low, the server is lagging.");
        }
        return sb.toString();
    }
}
