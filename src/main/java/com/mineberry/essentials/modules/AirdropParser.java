package com.mineberry.essentials.modules;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the server's airdrop lines for the Airdrop Helper. It has no Minecraft code in it, so it can be tested on its own.
 *
 * The server says it in two ways (one chat line per fact):
 * <pre>
 *   Airdrop + Boss will be spawned in 5 minutes.        (the announcement, then Location and Level lines)
 *   Location: X: -4064, Y: 68, Z: -2852
 *   Level: Easy
 *
 *   Airdrop Information                                 (the answer to /airdrop)
 *   Status: Preparing!   |   Status: Waiting
 *   Level: Easy
 *   Location: X: -4064, Y: 68, Z: -2852
 *   Time to spawn 4 min 58 sec!   |   Next spawn in 28 sec
 *   Last drop taken by: Sonicar2012
 * </pre>
 * Lines like "Level:" or "Status:" are too generic to trust on their own, so the module only accepts them right after
 * an announcement or an "Airdrop Information" header (see {@link Fact#opensContext()}).
 */
public final class AirdropParser {

    public enum Kind { ANNOUNCE, HEADER, LOCATION, LEVEL, STATUS, TIME_TO_SPAWN, NEXT_SPAWN, LAST_TAKEN }

    /**
     * One recognised line. Which fields are filled depends on the kind: ANNOUNCE has seconds + boss, LOCATION has x/y/z,
     * LEVEL / STATUS / LAST_TAKEN have text, TIME_TO_SPAWN and NEXT_SPAWN have seconds.
     */
    public record Fact(Kind kind, String text, long seconds, int x, int y, int z, boolean boss) {
        /** True for the two lines that start an airdrop message; the others only count shortly after one of these. */
        public boolean opensContext() { return kind == Kind.ANNOUNCE || kind == Kind.HEADER; }
    }

    private static final Pattern LEAD = Pattern.compile("^[^A-Za-z0-9]+");
    /** A time in front of the line, like the "[14:40] " that Chat Extras adds with chat timestamps on (also "[14:40:34]", "(14:40)"). */
    private static final Pattern TIME_TAG = Pattern.compile("^(?:\\[\\s*\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:[AaPp][Mm])?\\s*\\]|\\(\\s*\\d{1,2}:\\d{2}(?::\\d{2})?\\s*\\))");
    private static final Pattern ANNOUNCE = Pattern.compile(
            "^air\\s?drop(\\s*\\+\\s*(boss))?\\s+will\\s+(?:be\\s+)?spawn(?:ed)?\\s+in\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEADER = Pattern.compile("^air\\s?drop\\s+information\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCATION = Pattern.compile(
            "^location\\s*:?\\s*x\\s*:?\\s*(-?\\d{1,9})\\s*,?\\s*y\\s*:?\\s*(-?\\d{1,5})\\s*,?\\s*z\\s*:?\\s*(-?\\d{1,9})",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LEVEL = Pattern.compile("^level\\s*:\\s*([A-Za-z][A-Za-z ]{0,15}?)\\s*[.!]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS = Pattern.compile("^status\\s*:\\s*(\\S.{0,78}?)\\s*[.!]?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_TO_SPAWN = Pattern.compile("^time\\s+to\\s+spawn\\s*:?\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEXT_SPAWN = Pattern.compile("^next\\s+spawn\\s+in\\s*:?\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LAST_TAKEN = Pattern.compile("^last\\s+drop\\s+taken\\s+by\\s*:?\\s*(\\.?[A-Za-z0-9_]{1,16})\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION = Pattern.compile(
            "(\\d+)\\s*(h|hr|hrs|hours?|m|mins?|minutes?|s|secs?|seconds?)\\b", Pattern.CASE_INSENSITIVE);

    /** Words the player can use to ask Aerion about the airdrop ("airdrop", "air drop", "airdrops"). */
    private static final Pattern ASKS = Pattern.compile("\\bair\\s?-?drops?\\b", Pattern.CASE_INSENSITIVE);

    /** "add the airdrop coords to waypoint", "set a waypoint for the airdrop", "mark the air drop". */
    private static final Pattern WAYPOINT_WORDS = Pattern.compile("\\b(way\\s?-?points?|mark|save|track|add|set|put)\\b", Pattern.CASE_INSENSITIVE);

    private AirdropParser() {}

    /** True when a question about the airdrop also asks to put it on the waypoints. */
    public static boolean wantsWaypoint(String question) {
        return asksAboutAirdrop(question) && WAYPOINT_WORDS.matcher(question).find();
    }

    // ---------------------------------------------------------------- lines

    /** What the line says, or empty if it is not an airdrop line. Leading decoration (an arrow, a bullet) is skipped. */
    public static Optional<Fact> parse(String line) {
        if (line == null) return Optional.empty();
        String s = clean(line);
        if (s.isEmpty() || s.length() > 120) return Optional.empty();

        Matcher m = ANNOUNCE.matcher(s);
        if (m.find()) {
            long secs = seconds(m.group(3));
            if (secs < 0) return Optional.empty();
            return Optional.of(new Fact(Kind.ANNOUNCE, "", secs, 0, 0, 0, m.group(2) != null));
        }
        if (HEADER.matcher(s).find()) return Optional.of(new Fact(Kind.HEADER, "", 0, 0, 0, 0, false));
        m = LOCATION.matcher(s);
        if (m.find()) {
            try {
                return Optional.of(new Fact(Kind.LOCATION, "", 0,
                        Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), false));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        m = LEVEL.matcher(s);
        if (m.find()) return Optional.of(new Fact(Kind.LEVEL, m.group(1).strip(), 0, 0, 0, 0, false));
        m = STATUS.matcher(s);
        if (m.find()) return Optional.of(new Fact(Kind.STATUS, m.group(1).strip(), 0, 0, 0, 0, false));
        m = TIME_TO_SPAWN.matcher(s);
        if (m.find()) {
            long secs = seconds(m.group(1));
            return secs < 0 ? Optional.empty() : Optional.of(new Fact(Kind.TIME_TO_SPAWN, "", secs, 0, 0, 0, false));
        }
        m = NEXT_SPAWN.matcher(s);
        if (m.find()) {
            long secs = seconds(m.group(1));
            return secs < 0 ? Optional.empty() : Optional.of(new Fact(Kind.NEXT_SPAWN, "", secs, 0, 0, 0, false));
        }
        m = LAST_TAKEN.matcher(s);
        if (m.find()) return Optional.of(new Fact(Kind.LAST_TAKEN, m.group(1), 0, 0, 0, 0, false));
        return Optional.empty();
    }

    /** The line without its decoration: arrows, bullets and a timestamp in front (in any order). */
    static String clean(String line) {
        String s = line.strip();
        for (int i = 0; i < 4; i++) {
            String next = LEAD.matcher(s).replaceFirst("");
            // a lone "[" before the time is not decoration: take the time tag off first
            Matcher t = TIME_TAG.matcher(s);
            if (t.find()) next = s.substring(t.end()).strip();
            else next = next.strip();
            if (next.equals(s)) break;
            s = next;
        }
        return s;
    }

    /** "5 minutes", "4 min 58 sec!", "28 sec", "1 h 2 m" -> seconds. -1 when there is no number with a unit in it. */
    static long seconds(String text) {
        if (text == null) return -1;
        Matcher m = DURATION.matcher(text);
        long total = 0;
        boolean any = false;
        while (m.find()) {
            long n;
            try { n = Long.parseLong(m.group(1)); } catch (NumberFormatException e) { return -1; }
            char unit = Character.toLowerCase(m.group(2).charAt(0));
            total += unit == 'h' ? n * 3600 : unit == 'm' ? n * 60 : n;
            any = true;
        }
        return any ? total : -1;
    }

    /** True when a question to Aerion is about the airdrop ("airdrop info", "when is the next air drop?"). */
    public static boolean asksAboutAirdrop(String question) {
        return question != null && ASKS.matcher(question).find();
    }

    /** "4m 58s", "28s", "1h 02m". */
    public static String formatDuration(long seconds) {
        long s = Math.max(0, seconds);
        if (s >= 3600) return (s / 3600) + "h " + String.format(Locale.ROOT, "%02d", (s % 3600) / 60) + "m";
        if (s >= 60) return (s / 60) + "m" + (s % 60 == 0 ? "" : " " + (s % 60) + "s");
        return s + "s";
    }

    // ---------------------------------------------------------------- state

    /**
     * Everything the server last told us about the airdrop. Times are kept as absolute milliseconds (any steady clock,
     * passed in by the caller), so "spawns in" stays right as time goes by.
     */
    public static final class State {
        public String level = "";
        public String status = "";
        public String lastTaken = "";
        public boolean boss, hasLocation;
        public int x, y, z;
        /** When the airdrop spawns, or -1 if unknown. */
        public long spawnAt = -1;
        /** When the next airdrop spawns (the server says this while it is waiting), or -1. */
        public long nextAt = -1;

        /** Folds one line into the state. An announcement or a header starts a fresh message, so old details are dropped first. */
        public void apply(Fact f, long now) {
            if (f.opensContext()) {
                level = "";
                status = "";
                lastTaken = "";
                boss = false;
                hasLocation = false;
                spawnAt = -1;
                nextAt = -1;
            }
            switch (f.kind()) {
                case ANNOUNCE -> { boss = f.boss(); spawnAt = now + f.seconds() * 1000; }
                case HEADER -> { }
                case LOCATION -> { hasLocation = true; x = f.x(); y = f.y(); z = f.z(); }
                case LEVEL -> level = f.text();
                case STATUS -> status = f.text();
                case TIME_TO_SPAWN -> spawnAt = now + f.seconds() * 1000;
                case NEXT_SPAWN -> nextAt = now + f.seconds() * 1000;
                case LAST_TAKEN -> lastTaken = f.text();
            }
        }

        /** True when the status says the airdrop is already out (for example "Spawned, Boss fight is going!"). */
        public boolean spawned() {
            String t = status.toLowerCase(Locale.ROOT);
            return t.contains("spawned") || t.contains("boss");
        }

        /** True when the status says there is no airdrop at the moment ("Waiting", or one that was already taken). */
        private boolean idle() {
            String t = status.toLowerCase(Locale.ROOT);
            return t.startsWith("waiting") || t.startsWith("taken") || t.startsWith("finished") || t.startsWith("ended")
                    || t.startsWith("none") || t.startsWith("no airdrop");
        }

        /**
         * True when there is an airdrop with a known location that can be marked: coming soon, being prepared or already out.
         * It does not depend on the exact words of the status: any airdrop block with coordinates counts, unless the status
         * says it is waiting for the next one.
         */
        public boolean live(long now) {
            if (!hasLocation) return false;
            if (secondsToSpawn(now) >= 0 || spawned()) return true;
            return !idle() && nextAt < 0;
        }

        public boolean known() {
            return hasLocation || spawnAt >= 0 || nextAt >= 0 || !status.isEmpty() || !lastTaken.isEmpty();
        }

        /** Whole seconds until the airdrop spawns, or -1 if unknown. Never negative. */
        public long secondsToSpawn(long now) {
            return spawnAt < 0 ? -1 : Math.max(0, (spawnAt - now + 999) / 1000);
        }

        public long secondsToNext(long now) {
            return nextAt < 0 ? -1 : Math.max(0, (nextAt - now + 999) / 1000);
        }

        /** The coordinates as the server writes them: "X: -4064, Y: 68, Z: -2852". */
        public String coords() {
            return "X: " + x + ", Y: " + y + ", Z: " + z;
        }

        /** Aerion's answer to "airdrop info?", in plain words. */
        public String summary(long now) {
            if (!known()) return "The server did not say anything about an airdrop.";
            StringBuilder sb = new StringBuilder();
            long toSpawn = secondsToSpawn(now), toNext = secondsToNext(now);
            String kind = (boss ? "Airdrop + Boss" : "Airdrop") + (level.isEmpty() ? "" : " (" + level + ")");
            if (live(now)) {
                sb.append(kind).append(toSpawn > 0 ? " spawns in " + formatDuration(toSpawn)
                        : spawned() ? " is out now" + (boss ? ", the boss fight is going" : "") : " is spawning now").append(".\n");
                sb.append("Location: ").append(coords());
            } else if (toNext >= 0) {
                sb.append("No airdrop right now. The next one spawns in ").append(formatDuration(toNext)).append(".");
            } else if (hasLocation) {
                sb.append(kind).append(" at ").append(coords()).append(".");
            } else {
                sb.append(status.isEmpty() ? "Airdrop status unknown." : "Airdrop status: " + status + ".");
            }
            if (!lastTaken.isEmpty()) sb.append("\nLast drop was taken by ").append(lastTaken).append(".");
            return sb.toString();
        }
    }
}
