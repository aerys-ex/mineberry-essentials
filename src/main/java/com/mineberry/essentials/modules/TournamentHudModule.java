package com.mineberry.essentials.modules;

import com.mineberry.essentials.Clock;
import com.mineberry.essentials.Config;
import com.mineberry.essentials.Terms;
import com.mineberry.essentials.MineBerryModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Tournament HUD: a small box on screen with the friends you listed (Control Center > Tournament HUD > gear):
 * who is still alive, who is dead, who left, and the last thing each of them said in chat.
 *
 * How it knows:
 *  - Present / left: the player (tab) list. A friend who was in the match and is no longer in the list has left.
 *  - Dead: a death or elimination line from the server (only system messages count, never player chat),
 *    the "left the game while in PvP mode and was killed" line, or the friend switching to spectator mode.
 *  - Said: chat lines whose sender is a friend (see TournamentParser; a name inside someone else's message does not count).
 *
 * Statuses start fresh when you join a server, when the server says a round/match/game started
 * (config: tournamentStartPattern) and when you press Reset in the friends screen.
 * Off by default. Nothing is sent to the server: it only reads what you already receive.
 */
public class TournamentHudModule extends MineBerryModule {

    public enum Status { ALIVE, DEAD, LEFT }

    /** One chat line of a friend and when it arrived, in Clock.ms() time. */
    public record Msg(String text, long at) {}

    /**
     * One friend on the HUD. died is true when the friend is dead (also when they left afterwards).
     * messages are their last few chat lines, oldest first (empty if none).
     */
    public record Row(String name, Status status, boolean died, List<Msg> messages) {
        /** The text shown for the status: alive, dead (in-game), dead (left) or left. */
        public String label() {
            return switch (status) {
                case ALIVE -> "alive";
                case DEAD -> "dead (in-game)";
                case LEFT -> died ? "dead (left)" : "left";
            };
        }
    }

    public static final int MAX_FRIENDS = 30;
    private static final long DUPLICATE_MS = 300;
    private static final int TICKS_PER_REFRESH = 5;
    private static final int MAX_MESSAGE_CHARS = 256;
    /** How many of a friend's last messages are kept (the HUD shows the ones that have not faded out yet). */
    private static final int MAX_MESSAGES = 3;

    /** Friend name -> state. The key is the lower-case name. Always used while holding LOCK. */
    private static final class State {
        String name;
        boolean seen;            // was in the player list, or was named in a death line, since the last reset
        boolean online;          // in the player list at the last refresh
        boolean spectator;       // in spectator mode at the last refresh
        boolean dead;
        boolean deadFromSpectator;   // dead only because of spectator mode: goes back to alive if they leave it
        boolean left;
        final List<Msg> messages = new ArrayList<>();
        State(String name) { this.name = name; }
    }

    private static TournamentHudModule instance;

    private final Object lock = new Object();
    private final Map<String, State> states = new LinkedHashMap<>();
    private String lastLine;
    private long lastLineAt;
    private int ticks;
    private Pattern startPattern;
    private String startSource;

    public TournamentHudModule() {
        super("tournament_hud", "Tournament HUD", "Shows your friends' status and messages during tournaments");
    }

    /** Unlike most modules this one starts OFF. */
    @Override
    public boolean enabled() {
        return Terms.accepted() && Config.isEnabled(id(), false);
    }

    @Override
    public void init() {
        instance = this;
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) onMessage(message, true); });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> onMessage(message, false));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> clear());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    @Override
    protected void onToggled(boolean nowEnabled) {
        clear();
        Minecraft mc = Minecraft.getInstance();
        boolean noFriends = Config.tournamentFriends().isEmpty();
        mc.execute(() -> {
            if (mc.player == null) return;
            String text = nowEnabled
                    ? (noFriends ? "Tournament HUD ON - add your friends with the gear button on its card" : "Tournament HUD ON")
                    : "Tournament HUD OFF";
            mc.player.sendSystemMessage(Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal(text).withStyle(nowEnabled ? ChatFormatting.GREEN : ChatFormatting.GRAY)));
        });
    }

    // ---------------------------------------------------------------- public API (used by the HUD box and the friends screen)

    /** True while the module is switched on and ready. */
    public static boolean active() {
        TournamentHudModule m = instance;
        return m != null && m.enabled();
    }

    /** The friends that are in the match (or were, and have since died or left), alive first, then dead, then left. */
    public static List<Row> rows() {
        TournamentHudModule m = instance;
        return m == null ? List.of() : m.snapshot();
    }

    /** Forgets every status and message. They are read again from the player list on the next refresh. */
    public static void resetStatuses() {
        TournamentHudModule m = instance;
        if (m != null) m.clear();
    }

    // ---------------------------------------------------------------- state

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private void clear() {
        synchronized (lock) {
            states.clear();
            lastLine = null;
        }
    }

    private List<Row> snapshot() {
        List<Row> out = new ArrayList<>();
        synchronized (lock) {
            for (String friend : Config.tournamentFriends()) {
                State st = states.get(key(friend));
                if (st == null || !(st.seen || st.dead)) continue;
                Status status = st.left ? Status.LEFT : st.dead ? Status.DEAD : Status.ALIVE;
                out.add(new Row(st.name, status, st.dead, List.copyOf(st.messages)));
            }
        }
        out.sort(Comparator.comparingInt((Row r) -> r.status().ordinal()).thenComparing(r -> key(r.name())));
        return out;
    }

    /** The state of a friend, created on first use. The caller holds the lock. */
    private State stateOf(String friend) {
        State st = states.computeIfAbsent(key(friend), k -> new State(friend));
        st.name = friend;
        return st;
    }

    // ---------------------------------------------------------------- messages

    /**
     * system is true for server messages and false for signed player chat. Only system messages can say that someone
     * died or that a round started, so a player typing "Steve died" or "game started" changes nothing.
     */
    private void onMessage(Component message, boolean system) {
        if (!enabled()) return;
        String text = message.getString().strip();
        if (text.isEmpty()) return;
        List<String> friends = List.copyOf(Config.tournamentFriends());
        long now = Clock.ms();
        synchronized (lock) {
            // some servers' lines reach us through both events: handle one
            if (text.equals(lastLine) && now - lastLineAt < DUPLICATE_MS) return;
            lastLine = text;
            lastLineAt = now;

            if (system && !TournamentParser.looksLikePlayerChat(text) && matchesStart(text)) {
                states.clear();                      // a new round: everybody starts again
                return;
            }
            if (friends.isEmpty()) return;
            if (system) {
                var dead = TournamentParser.deathOf(text, friends);
                if (dead.isPresent()) {
                    State st = stateOf(dead.get());
                    st.seen = true;
                    st.dead = true;
                    st.deadFromSpectator = false;
                    return;
                }
            }
            var said = TournamentParser.chatFrom(text, friends);
            if (said.isPresent()) {
                State st = stateOf(said.get().friend());
                String line = said.get().message().replaceAll("\\s+", " ");
                st.messages.add(new Msg(line.length() > MAX_MESSAGE_CHARS ? line.substring(0, MAX_MESSAGE_CHARS) : line, now));
                while (st.messages.size() > MAX_MESSAGES) st.messages.remove(0);
            }
        }
    }

    private boolean matchesStart(String text) {
        String source = Config.tournamentStartPattern();
        if (source == null || source.isBlank()) return false;
        if (startPattern == null || !source.equals(startSource)) {
            try {
                startPattern = Pattern.compile(source, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException e) {
                startPattern = Pattern.compile(Config.DEFAULT_TOURNAMENT_START, Pattern.CASE_INSENSITIVE);
            }
            startSource = source;
        }
        return startPattern.matcher(text).find();
    }

    // ---------------------------------------------------------------- player list

    private void tick(Minecraft mc) {
        if (!enabled() || ++ticks < TICKS_PER_REFRESH) return;
        ticks = 0;
        if (mc.player == null || mc.getConnection() == null) return;

        List<String> friends = Config.tournamentFriends();
        Map<String, GameType> online = new HashMap<>();
        if (!friends.isEmpty()) {
            for (var info : mc.getConnection().getOnlinePlayers()) {
                online.put(key(info.getProfile().name()), info.getGameMode());
            }
        }
        synchronized (lock) {
            Set<String> wanted = new HashSet<>();
            for (String friend : friends) wanted.add(key(friend));
            states.keySet().retainAll(wanted);       // a friend removed from the list is forgotten

            for (String friend : friends) {
                String k = key(friend);
                boolean here = online.containsKey(k);
                State st = states.get(k);
                if (st == null && !here) continue;   // never seen, nothing to track
                if (st == null) st = stateOf(friend);
                st.name = friend;

                if (here) {
                    if (!st.online) {
                        if (st.left) {               // came back after leaving: a fresh start
                            st.left = false;
                            st.dead = false;
                            st.deadFromSpectator = false;
                            st.spectator = false;
                            st.messages.clear();
                        }
                        st.online = true;
                        st.seen = true;
                    }
                    boolean spectating = online.get(k) == GameType.SPECTATOR;
                    if (spectating && !st.spectator) {
                        st.spectator = true;
                        if (!st.dead) {
                            st.dead = true;
                            st.deadFromSpectator = true;
                        }
                    } else if (!spectating && st.spectator) {
                        st.spectator = false;
                        if (st.dead && st.deadFromSpectator) {   // left spectator mode: alive again
                            st.dead = false;
                            st.deadFromSpectator = false;
                        }
                    }
                } else if (st.online) {
                    st.online = false;
                    if (st.seen) st.left = true;
                }
            }
        }
    }
}
