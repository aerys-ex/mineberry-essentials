package com.mineberry.essentials.modules;

import com.mojang.blaze3d.platform.InputConstants;
import com.mineberry.essentials.Clock;
import com.mineberry.essentials.Config;
import com.mineberry.essentials.MineBerryEssentials;
import com.mineberry.essentials.MineBerryModule;
import com.mineberry.essentials.AerionNotifier;
import com.mineberry.essentials.AerionTalk;
import com.mineberry.essentials.Terms;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Airdrop Helper: Aerion tells you when the server announces an airdrop, and offers to put it on your waypoints.
 * She says it in her mini chat (the message on the left of the HUD, and in the conversation), not in a box of its own.
 * [ = yes (waypoint added), ] = no. Both keys are rebindable, and typing yes / no in the mini chat works too.
 *
 * Aerion can also be asked about it: "airdrop info" in the Aerion chat runs /airdrop for you and answers with what the
 * server says (see {@link #requestInfo}).
 *
 * Only system messages from the server count (never player chat), so nobody can fake an airdrop by typing one.
 * Nothing is sent to the server except the /airdrop command you ask Aerion for.
 */
public class AirdropModule extends MineBerryModule {

    /** Waypoint names start with this. A new accepted airdrop replaces the old one, so they do not pile up. */
    public static final String WAYPOINT_PREFIX = "Airdrop";
    private static final int WAYPOINT_COLOR = 0xFFFFDD57;

    /** Lines like "Level:" only count this long after an announcement / header line (renewed by each line that fits). */
    private static final long CONTEXT_MS = 6_000;
    /** The prompt goes away by itself after this long. */
    private static final long PROMPT_MS = 45_000;
    private static final long DUPLICATE_MS = 300;
    /** An /airdrop answer is done once no new line arrived for this long, or this long after asking at the latest. */
    private static final long INFO_QUIET_MS = 700, INFO_TIMEOUT_MS = 4_000;

    private static AirdropModule instance;

    private final Object lock = new Object();
    private final AirdropParser.State state = new AirdropParser.State();
    private long contextUntil;
    private String lastLine;
    private long lastLineAt;

    // the prompt (all guarded by lock)
    private boolean promptActive;
    private long promptAt;
    private boolean promptHandled;     // one prompt per announcement: a skip is final until a new airdrop is announced
    private String promptedCoords = "";
    private boolean promptAnnounce;    // the prompt just opened: the tick says it in the mini chat
    private boolean promptPinned;      // the mini chat message is up (game thread only)

    // a pending "airdrop info" request from Aerion (guarded by lock)
    private Consumer<String> infoCallback;
    private long infoAskedAt, infoLastLineAt;
    private boolean infoGotLine, infoWaypoint;

    private KeyMapping yesKey, noKey;

    public AirdropModule() {
        super("airdrop", "Airdrop Helper", "Aerion tells you about airdrops and sets the waypoint");
    }

    @Override
    public void init() {
        instance = this;
        yesKey = Terms.registerKey(new KeyMapping(
                "key.mineberry_essentials.airdrop_yes", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, MineBerryEssentials.CATEGORY));
        noKey = Terms.registerKey(new KeyMapping(
                "key.mineberry_essentials.airdrop_no", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, MineBerryEssentials.CATEGORY));

        // system messages only: a player can type anything in chat, but not a server message
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) onLine(message.getString()); });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> reset());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void reset() {
        synchronized (lock) {
            promptActive = false;
            promptAnnounce = false;
            promptHandled = false;
            promptedCoords = "";
            contextUntil = 0;
            infoCallback = null;
        }
    }

    // ---------------------------------------------------------------- Aerion: "airdrop info"

    /** True while the module is switched on. */
    public static boolean active() {
        AirdropModule m = instance;
        return m != null && m.enabled();
    }

    /**
     * Runs /airdrop and calls back (on the game thread) with a plain-words answer once the server has replied, or with a
     * short explanation if it did not. Used by the Aerion chat. Only one request at a time: a new one replaces the old.
     */
    public static void requestInfo(Consumer<String> callback) {
        requestInfo(callback, false);
    }

    /** Same, and with {@code addWaypoint} it also puts the airdrop on your waypoints when there is one to mark. */
    public static void requestInfo(Consumer<String> callback, boolean addWaypoint) {
        Minecraft mc = Minecraft.getInstance();
        AirdropModule m = instance;
        if (m == null || !m.enabled()) {
            callback.accept("The Airdrop Helper is switched off. Turn it on in the Control Center and ask me again.");
            return;
        }
        if (mc.getConnection() == null || mc.player == null) {
            callback.accept("Join the server first, then ask me again. I run /airdrop for you while you are in game.");
            return;
        }
        synchronized (m.lock) {
            m.infoCallback = callback;
            m.infoAskedAt = Clock.ms();
            m.infoGotLine = false;
            m.infoWaypoint = addWaypoint;
            m.contextUntil = m.infoAskedAt + INFO_TIMEOUT_MS;   // the answer's lines are accepted even without a header
        }
        mc.getConnection().sendCommand("airdrop");   // goes through Tournament Mode / PvP Command Lock like any other command
    }

    // ---------------------------------------------------------------- reading the server

    private void onLine(String raw) {
        long now = Clock.ms();
        AirdropParser.Fact fact = AirdropParser.parse(raw).orElse(null);
        if (fact == null) return;
        synchronized (lock) {
            if (!enabled()) return;
            if (raw.equals(lastLine) && now - lastLineAt < DUPLICATE_MS) return;   // the same line twice
            lastLine = raw;
            lastLineAt = now;
            if (!fact.opensContext() && now > contextUntil) return;   // a stray "Level: ..." from some other plugin
            contextUntil = now + CONTEXT_MS;

            state.apply(fact, now);
            if (infoCallback != null) { infoGotLine = true; infoLastLineAt = now; }

            if (fact.kind() == AirdropParser.Kind.ANNOUNCE) {   // a new airdrop: ask again, even for the same spot
                promptHandled = false;
                promptedCoords = "";
            }
            considerPrompt(now);
        }
    }

    /** Opens the prompt once the coordinates of a new airdrop are known (they arrive after the announcement line). */
    private void considerPrompt(long now) {
        if (promptHandled || !state.hasLocation) return;
        if (!state.live(now)) return;   // a "Waiting" answer has no airdrop to mark
        String coords = state.coords();
        if (coords.equals(promptedCoords) || hasWaypointAt(state.x, state.y, state.z)) {
            promptedCoords = coords;
            promptHandled = true;
            return;
        }
        promptedCoords = coords;
        promptActive = true;
        promptAnnounce = true;
        promptAt = now;
        promptHandled = true;
    }

    private static boolean hasWaypointAt(int x, int y, int z) {
        for (Config.Waypoint wp : Config.waypoints()) {
            if (wp.name.startsWith(WAYPOINT_PREFIX) && wp.x == x && wp.y == y && wp.z == z) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- tick: keys, expiry, answering Aerion

    private void tick(Minecraft client) {
        long now = Clock.ms();
        Consumer<String> done = null;
        String answer = null;
        boolean mark = false, markNow = false;
        boolean accept = false, skip = false, announce = false, showing;
        synchronized (lock) {
            if (promptActive && !enabled()) promptActive = false;
            if (promptActive && now - promptAt > PROMPT_MS) promptActive = false;
            while (yesKey.consumeClick()) { if (enabled() && promptActive) accept = true; }
            while (noKey.consumeClick()) { if (enabled() && promptActive) skip = true; }
            if (accept || skip) promptActive = false;
            announce = promptAnnounce && promptActive;
            promptAnnounce = false;
            showing = promptActive;
            if (infoCallback != null) {
                boolean quiet = infoGotLine && now - infoLastLineAt > INFO_QUIET_MS;
                boolean timedOut = now - infoAskedAt > INFO_TIMEOUT_MS;
                if (quiet || timedOut) {
                    done = infoCallback;
                    infoCallback = null;
                    answer = infoGotLine ? state.summary(now)
                            : "The server did not answer /airdrop. Are you on the right game mode? Tournament Mode also blocks commands.";
                    mark = infoWaypoint && infoGotLine;
                    infoWaypoint = false;
                    markNow = mark && state.live(now);
                }
            }
        }
        if (announce) announce(client);
        if (!showing && promptPinned) {   // answered, skipped, timed out or switched off
            promptPinned = false;
            AerionMiniChatModule.unpin();
        }
        if (accept) {
            addWaypoint(client);
            say(client, "Waypoint set. Good luck!", ChatFormatting.GREEN);
        } else if (skip) {
            say(client, "Okay, no waypoint.", ChatFormatting.GRAY);
        }
        if (done != null) {
            if (markNow) {
                addWaypoint(client);
                answer += "\nDone, the airdrop is on your waypoints."
                        + (Config.isEnabled("waypoints") ? "" : " Switch on the Waypoints module to see it in the world.");
            } else if (mark) {
                answer += "\nThere is no airdrop to put on your waypoints right now.";
            }
            done.accept(answer);
        }
    }

    private void addWaypoint(Minecraft mc) {
        int x, y, z;
        String level, name;
        synchronized (lock) {
            x = state.x; y = state.y; z = state.z;
            level = state.level;
        }
        name = WAYPOINT_PREFIX + (level.isEmpty() ? "" : " (" + level + ")");
        Config.waypoints().removeIf(wp -> wp.name.startsWith(WAYPOINT_PREFIX));   // the old airdrop is gone
        Config.Waypoint wp = WaypointsModule.add(mc, name, x, y, z);
        wp.color = WAYPOINT_COLOR;
        Config.save();
    }

    private static void say(Minecraft mc, String text, ChatFormatting color) {
        com.mineberry.essentials.AerionChat.say(mc, text, color);
    }

    // ---------------------------------------------------------------- Aerion says it in the mini chat

    private static final Pattern YES = Pattern.compile("(?i)^\\s*(yes|yeah|yep|yup|sure|ok|okay|please|do it|set it|yes please)[.! ]*$");
    private static final Pattern NO = Pattern.compile("(?i)^\\s*(no|nope|nah|skip|no thanks|no thank you|dont|don't|not now)[.! ]*$");

    private static String keyName(KeyMapping key) {
        return key.getTranslatedKeyMessage().getString();
    }

    /** What Aerion says for the open question, with the live countdown. Null when there is no question. */
    private String promptText() {
        long now = Clock.ms();
        synchronized (lock) {
            if (!promptActive) return null;
            String countdown = "";
            long toSpawn = state.secondsToSpawn(now);
            if (toSpawn > 0) countdown = "  -  spawns in " + AirdropParser.formatDuration(toSpawn);
            else if (toSpawn == 0) countdown = "  -  spawning now";
            return "Airdrop's here!\n"
                    + (state.boss ? "Airdrop + Boss" : "Airdrop") + (state.level.isEmpty() ? "" : " (" + state.level + ")") + countdown
                    + "\n" + state.coords()
                    + "\nSet up the coords in waypoints?   [" + keyName(yesKey) + "] Yes   [" + keyName(noKey) + "] No";
        }
    }

    /** The prompt just opened: put it in the conversation, and keep it on the HUD like Aerion's other messages. */
    private void announce(Minecraft mc) {
        String text = promptText();
        if (text == null) return;
        String name = mc.getUser() == null ? "" : ", " + mc.getUser().getName();
        AerionTalk.post("Hey" + name + "! " + text);
        if (AerionMiniChatModule.active()) {
            promptPinned = true;
            AerionMiniChatModule.pin(this::promptText);
        } else {
            AerionNotifier.alert(text.replace("   [", "\n[")); // the mini chat module is off: the alert card still tells you
        }
    }

    /** "yes" / "no" typed to Aerion while her airdrop question is open. Empty when it is not an answer to that. */
    public static Optional<String> tryAnswer(String text) {
        AirdropModule m = instance;
        if (m == null || text == null) return Optional.empty();
        boolean yes = YES.matcher(text).matches(), no = NO.matcher(text).matches();
        if (!yes && !no) return Optional.empty();
        synchronized (m.lock) {
            if (!m.promptActive || !m.enabled()) return Optional.empty();
            m.promptActive = false;
        }
        if (yes) {
            m.addWaypoint(Minecraft.getInstance());
            return Optional.of("Waypoint set. Good luck!"
                    + (Config.isEnabled("waypoints") ? "" : " Switch on the Waypoints module to see it in the world."));
        }
        return Optional.of("Okay, no waypoint.");
    }
}
