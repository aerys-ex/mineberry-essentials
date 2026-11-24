package com.mineberry.essentials.modules;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads what you ask Aerion to DO ("turn off compass", "remove the airdrop waypoint", "undo") and finds which module,
 * HUD element or waypoint you mean. No Minecraft code in it, so it can be tested on its own; AerionActions carries it out.
 *
 * Questions are never commands: "how do I turn off the compass?" goes to the normal guide, "turn off the compass" acts.
 */
public final class AerionIntents {

    public enum Kind {
        /** Switch a module or HUD element on / off (on == null means flip it). */
        TOGGLE,
        WAYPOINT_REMOVE,
        WAYPOINT_REMOVE_ALL,
        /** Hide or show one waypoint without deleting it. */
        WAYPOINT_VISIBILITY,
        LIST_WAYPOINTS,
        LIST_MODULES,
        /** Pick the default Aerion (target "mineberry" or "minepeak"). */
        SET_DEFAULT,
        UNDO,
        TEST_ALERT
    }

    /** {@code on} is null when the player did not say (toggle). {@code target} is what they named, may be empty. */
    public record Intent(Kind kind, Boolean on, String target) {}

    /** Something that can be named: a module, a HUD element or a waypoint. aliases are extra names, separated by commas. */
    public record Item(String id, String name, String aliases) {
        public Item(String id, String name) { this(id, name, ""); }
    }

    private static final Set<String> FILLER = setOf("please", "pls", "plz", "can", "could", "would", "will", "you", "hey", "hi",
            "yo", "the", "a", "an", "my", "me", "for", "to", "module", "mod", "feature", "option", "thing", "it", "back", "again", "now",
            "just", "mineberry", "also", "and", "that", "this", "one", "of", "on", "off");
    /** Dropped when comparing names. Not "aerion": it is part of the names "Aerion in Chat" and "Aerion Mini Chat". */
    private static final Set<String> NORM_DROP = setOf("the", "a", "an", "my", "me", "for", "to", "of", "and", "it", "that", "this", "please");
    private static final Set<String> QUESTION_START = setOf("how", "what", "whats", "why", "where", "when", "which", "who", "is", "are",
            "does", "do", "did", "should", "if");
    private static final Set<String> WAYPOINT_WORDS = setOf("waypoint", "waypoints", "wp", "wps", "marker", "markers", "pin", "pins");
    private static final Set<String> REMOVE = setOf("remove", "delete", "clear", "erase", "forget", "unmark", "wipe");
    private static final Set<String> ON_VERBS = setOf("enable", "activate", "unhide");
    private static final Set<String> OFF_VERBS = setOf("disable", "deactivate");
    private static final Set<String> SET_VERBS = setOf("set", "make", "use", "change", "switch", "choose", "pick", "should", "want", "select");
    private static final Set<String> ALERT_WORDS = setOf("alert", "alerts", "notification", "notifications", "notif", "notifs");
    private static final Set<String> YES = setOf("yes", "y", "yeah", "yep", "yup", "sure", "ok", "okay", "confirm", "do", "it", "that", "please");
    private static final Set<String> NO = setOf("no", "n", "nope", "nah", "cancel", "stop", "dont", "do", "never", "mind", "nevermind", "it", "that");

    /** Extra names for modules and HUD elements, by id, so "pie menu" or "cps" work. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("aerion_mini", "mini chat,aerion mini"),
            Map.entry("aerion_chat", "bang aerion,aerion command"),
            Map.entry("aerion_alerts", "alerts,notifications,notifs,aerion notifications"),
            Map.entry("clan_chat", "clan,cc"),
            Map.entry("admin_chat", "admin,ac"),
            Map.entry("party_chat", "party,pc"),
            Map.entry("chat_extras", "mentions,timestamps,chat extras"),
            Map.entry("chat_search", "search chat,chat search"),
            Map.entry("quick_reply", "reply,quick reply"),
            Map.entry("safe_logout", "logout,safe logout"),
            Map.entry("pvp_command_lock", "pvp lock,command lock,pvp"),
            Map.entry("send_guard", "guard,send guard"),
            Map.entry("evidence", "recorder,evidence recorder"),
            Map.entry("screenshot", "screenshots,f2"),
            Map.entry("radial_menu", "radial,pie menu,pie,wheel"),
            Map.entry("airdrop", "airdrops,airdrop helper"),
            Map.entry("hud_fps", "fps,frame rate,framerate"),
            Map.entry("hud_ping", "ping,latency"),
            Map.entry("hud_coords", "coords,coordinates,xyz,position"),
            Map.entry("hud_sprint", "sprint"),
            Map.entry("hud_clock", "clock"),
            Map.entry("hud_keystrokes", "keystrokes,cps,keys,wasd"),
            Map.entry("hud_armor", "armor,armour"),
            Map.entry("hud_potions", "potions,effects,potion effects"),
            Map.entry("hud_compass", "compass"),
            Map.entry("hud_biome", "biome,time,biome time"),
            Map.entry("hud_watermark", "watermark,logo"),
            Map.entry("hud_scoreboard", "scoreboard,sidebar"),
            Map.entry("hud_tournament", "tournament players,players"));

    private AerionIntents() {}

    /** The extra names of a module / HUD element (empty if none). */
    public static String aliasesFor(String id) {
        return ALIASES.getOrDefault(id, "");
    }

    // ---------------------------------------------------------------- reading a message

    /** What the message asks Aerion to do, or empty when it is not a command (a question for the guide, small talk, ...). */
    public static Optional<Intent> parse(String message) {
        List<String> t = tokens(message);
        while (!t.isEmpty() && (t.get(0).equals("aerion") || t.get(0).equals("hey") || t.get(0).equals("hi") || t.get(0).equals("yo"))) t.remove(0);   // talking to him
        if (t.isEmpty() || t.size() > 14) return Optional.empty();
        boolean waypointWord = t.stream().anyMatch(WAYPOINT_WORDS::contains);

        if (t.contains("undo") || (t.size() <= 6 && t.contains("back") && (t.contains("bring") || t.contains("restore") || t.contains("put")))) {
            return Optional.of(new Intent(Kind.UNDO, null, ""));
        }
        if (t.contains("test") && t.stream().anyMatch(ALERT_WORDS::contains)) {
            return Optional.of(new Intent(Kind.TEST_ALERT, null, ""));
        }
        if (waypointWord && (t.contains("list") || t.contains("have") || t.contains("saved") || t.contains("got"))
                && t.stream().noneMatch(REMOVE::contains)) {
            return Optional.of(new Intent(Kind.LIST_WAYPOINTS, null, ""));
        }
        if ((t.contains("modules") || t.contains("features")) && (t.contains("list") || t.contains("what") || t.contains("which") || t.contains("show"))) {
            return Optional.of(new Intent(Kind.LIST_MODULES, null, ""));
        }
        if (QUESTION_START.contains(t.get(0)) || (t.size() > 1 && t.get(0).equals("can") && t.get(1).equals("i"))) return Optional.empty();

        if (t.contains("default") && t.stream().anyMatch(SET_VERBS::contains)) {
            for (String w : t) {
                if (w.equals("minepeak") || w.equals("mineberry")) return Optional.of(new Intent(Kind.SET_DEFAULT, null, w));
            }
        }

        List<String> u = new ArrayList<>();
        for (String w : t) if (!FILLER.contains(w) || w.equals("on") || w.equals("off")) u.add(w);

        // the verb: the first word that says what to do
        int at = -1;
        String verb = null;
        for (int i = 0; i < u.size(); i++) {
            String w = u.get(i);
            if (REMOVE.contains(w) || ON_VERBS.contains(w) || OFF_VERBS.contains(w) || w.equals("toggle") || w.equals("flip")
                    || w.equals("hide") || w.equals("show") || w.equals("turn") || w.equals("switch")) {
                at = i;
                verb = w;
                break;
            }
        }
        if (verb == null) return Optional.empty();

        Boolean on = null;
        if (ON_VERBS.contains(verb) || verb.equals("show")) on = Boolean.TRUE;
        else if (OFF_VERBS.contains(verb) || verb.equals("hide")) on = Boolean.FALSE;
        else if (verb.equals("turn") || verb.equals("switch")) {
            if (u.contains("on")) on = Boolean.TRUE;
            else if (u.contains("off")) on = Boolean.FALSE;
            else return Optional.empty();   // "turn left"
        }

        List<String> rest = new ArrayList<>();
        for (int i = 0; i < u.size(); i++) {
            String w = u.get(i);
            if (i == at || w.equals("on") || w.equals("off")) continue;
            rest.add(w);
        }

        if (REMOVE.contains(verb)) {
            boolean airdrop = rest.contains("airdrop");
            if (!waypointWord && !airdrop) return Optional.empty();   // "clear chat" is not for Aerion
            List<String> name = new ArrayList<>();
            for (String w : rest) if (!WAYPOINT_WORDS.contains(w)) name.add(w);
            boolean every = name.isEmpty() ? verb.equals("clear") || verb.equals("wipe") : name.size() == 1 && Set.of("all", "every", "everything").contains(name.get(0));
            if (every) {
                return Optional.of(new Intent(Kind.WAYPOINT_REMOVE_ALL, null, ""));
            }
            name.remove("all");
            return Optional.of(new Intent(Kind.WAYPOINT_REMOVE, null, String.join(" ", name)));
        }

        // hide / show / turn on / turn off
        if (waypointWord) {
            List<String> name = new ArrayList<>();
            for (String w : rest) if (!WAYPOINT_WORDS.contains(w) && !w.equals("all")) name.add(w);
            if (!name.isEmpty()) return Optional.of(new Intent(Kind.WAYPOINT_VISIBILITY, on, String.join(" ", name)));
            return Optional.of(new Intent(Kind.TOGGLE, on, "waypoints"));   // "turn off waypoints" is the module
        }
        String target = String.join(" ", rest);
        if (Set.of("hud", "all hud", "whole hud", "the hud", "hud overlay", "overlay").contains(target)) {
            return Optional.empty();   // the whole HUD is only hidden from the MineBerry Client settings, never by Aerion
        }
        return Optional.of(new Intent(Kind.TOGGLE, on, target));
    }

    /** A short yes ("yes", "yeah do it", "ok"). */
    public static boolean isYes(String message) {
        return shortAnswer(message, YES, Set.of("it", "that", "please"));
    }

    /** A short no ("no", "cancel", "never mind", "dont do it"). */
    public static boolean isNo(String message) {
        return shortAnswer(message, NO, Set.of("it", "that", "do"));
    }

    /** At most three words, all of them from {@code words}, and at least one that is not just filler. */
    private static boolean shortAnswer(String message, Set<String> words, Set<String> weak) {
        List<String> t = tokens(message);
        if (t.isEmpty() || t.size() > 3) return false;
        boolean strong = false;
        for (String w : t) {
            if (!words.contains(w)) return false;
            if (!weak.contains(w)) strong = true;
        }
        return strong;
    }

    // ---------------------------------------------------------------- finding what they meant

    /**
     * The items that best match what the player named. One item = sure. Several = ask which one. None = no such thing.
     * An exact name wins over partial matches ("waypoints" is the Waypoints module, not "Waypoint 1").
     */
    public static List<Item> resolve(String target, List<Item> items) {
        List<String> want = norm(target);
        if (want.isEmpty()) return List.of();
        String wantPhrase = String.join(" ", want);
        int best = 0;
        List<Item> out = new ArrayList<>();
        for (Item item : items) {
            int score = score(item, want, wantPhrase);
            if (score == 0) continue;
            if (score > best) { best = score; out.clear(); }
            if (score == best) out.add(item);
        }
        return out;
    }

    private static int score(Item item, List<String> want, String wantPhrase) {
        List<String> phrases = new ArrayList<>();
        phrases.add(String.join(" ", norm(item.name())));
        phrases.add(String.join(" ", norm(item.id().replace('_', ' '))));
        if (!item.aliases().isBlank()) for (String a : item.aliases().split(",")) phrases.add(String.join(" ", norm(a)));
        int best = 0;
        for (String p : phrases) {
            if (p.isEmpty()) continue;
            if (p.equals(wantPhrase)) return 100;
            List<String> pt = Arrays.asList(p.split(" "));
            if (pt.containsAll(want)) best = Math.max(best, 60);
            else if (phrases.indexOf(p) >= 2 && want.containsAll(pt)) best = Math.max(best, 50);   // an alias inside a longer request
        }
        return best;
    }

    // ---------------------------------------------------------------- words

    /** Lower-case words of a message: letters and digits only, apostrophes dropped ("don't" is "dont"). */
    static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String w : s.toLowerCase(java.util.Locale.ROOT).replace("'", "").split("[^a-z0-9]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** Words with a plural "s" removed, so "waypoints" and "waypoint" are the same. */
    static List<String> norm(String s) {
        List<String> out = new ArrayList<>();
        for (String w : tokens(s)) {
            if (NORM_DROP.contains(w)) continue;
            out.add(w.length() > 3 && w.endsWith("s") && !w.endsWith("ss") ? w.substring(0, w.length() - 1) : w);
        }
        return out;
    }

    private static Set<String> setOf(String... words) {
        return new LinkedHashSet<>(Arrays.asList(words));
    }
}
