package com.mineberry.essentials.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Overworld <-> Nether coordinates for Aerion: "convert 800 64 -1600 to nether", "nether coords for x: 1000 z: -400",
 * "convert my coords to the nether and add it to waypoints". The Nether is 8 times smaller, so Overworld to Nether divides
 * X and Z by 8 (rounded down) and Nether to Overworld multiplies by 8. Y does not change.
 * No Minecraft code in it, so it can be tested on its own.
 */
public final class CoordConvert {

    public enum Dim {
        OVERWORLD("minecraft:overworld", "Overworld"), NETHER("minecraft:the_nether", "Nether");

        public final String id, label;

        Dim(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public Dim other() { return this == OVERWORLD ? NETHER : OVERWORLD; }

        /** The dimension for a Minecraft dimension id ("minecraft:the_nether"), Overworld for anything else. */
        public static Dim of(String id) {
            return NETHER.id.equals(id) ? NETHER : OVERWORLD;
        }
    }

    /**
     * What was asked. {@code to} is null when no dimension was named. x, y, z are the numbers typed (y may be null),
     * or all null with {@code useMine} when the player means their own position.
     */
    public record Request(Dim to, Integer x, Integer y, Integer z, boolean useMine, boolean addWaypoint) {}

    /** The answer. y is null when none was known. */
    public record Result(Dim from, Dim to, int fromX, Integer y, int fromZ, int toX, int toZ) {
        /** "Overworld 800, 64, -1600 -> Nether 100, 64, -200" */
        public String text() {
            return from.label + " " + fmt(fromX, y, fromZ) + " → " + to.label + " " + fmt(toX, y, toZ);
        }

        private static String fmt(int x, Integer y, int z) {
            return y == null ? x + ", " + z : x + ", " + y + ", " + z;
        }

        public String how() {
            return from == Dim.OVERWORLD ? "(divided by 8, rounded down)" : "(times 8, so it can be off by up to 7 blocks)";
        }
    }

    private static final Pattern NUMBER = Pattern.compile("(?<![\\w.])-?\\d+(?:\\.\\d+)?(?![\\w])");
    private static final Set<String> WAYPOINT_WORDS = Set.of("waypoint", "waypoints", "wp", "marker", "markers");
    private static final Set<String> ADD_VERBS = Set.of("add", "set", "save", "mark", "put", "make", "create", "place", "store");
    private static final Set<String> MINE = Set.of("my", "me", "here", "current", "mine", "own");
    private static final Set<String> REFERS_BACK = Set.of("that", "those", "it", "them", "there", "coords", "coordinates", "result", "converted");

    private CoordConvert() {}

    /** A conversion request, or empty when the message is not one (questions about portals without numbers, other talk). */
    public static Optional<Request> parse(String message) {
        if (message == null) return Optional.empty();
        List<String> t = AerionIntents.tokens(message);
        if (t.isEmpty() || t.contains("airdrop")) return Optional.empty();

        List<Dim> dims = new ArrayList<>();
        List<Boolean> fromFlags = new ArrayList<>();
        for (int i = 0; i < t.size(); i++) {
            String w = t.get(i);
            Dim d = w.equals("nether") ? Dim.NETHER : w.equals("overworld") || w.equals("ow") ? Dim.OVERWORLD : null;
            if (d == null) continue;
            int p = i - 1;
            if (p >= 0 && t.get(p).equals("the")) p--;
            dims.add(d);
            fromFlags.add(p >= 0 && t.get(p).equals("from"));
        }
        boolean coordsWord = t.contains("coords") || t.contains("coord") || t.contains("coordinates") || t.contains("xyz");
        boolean convert = t.contains("convert") || t.contains("conversion") || t.contains("convertion");
        if (dims.isEmpty() && !(convert && coordsWord)) return Optional.empty();

        Dim to = null;
        if (dims.size() >= 2) to = dims.get(dims.size() - 1);                       // "overworld to nether"
        else if (dims.size() == 1) to = fromFlags.get(0) ? dims.get(0).other() : dims.get(0);   // "from nether" = to the overworld

        List<Integer> nums = new ArrayList<>();
        Matcher m = NUMBER.matcher(message.toLowerCase(java.util.Locale.ROOT));
        while (m.find()) nums.add((int) Math.floor(Double.parseDouble(m.group())));

        boolean mine = nums.isEmpty() && t.stream().anyMatch(MINE::contains);
        boolean waypoint = t.stream().anyMatch(WAYPOINT_WORDS::contains) && t.stream().anyMatch(ADD_VERBS::contains);
        if (mine) return Optional.of(new Request(to, null, null, null, true, waypoint));
        if (nums.size() == 2) return Optional.of(new Request(to, nums.get(0), null, nums.get(1), false, waypoint));
        if (nums.size() == 3) return Optional.of(new Request(to, nums.get(0), nums.get(1), nums.get(2), false, waypoint));
        return Optional.empty();
    }

    /** "add that to waypoints", "save those coords as a waypoint": no numbers, it points back at the last conversion. */
    public static boolean wantsAddLast(String message) {
        if (message == null) return false;
        List<String> t = AerionIntents.tokens(message);
        if (t.isEmpty() || t.size() > 10 || t.contains("airdrop") || NUMBER.matcher(message).find()) return false;
        return t.stream().anyMatch(WAYPOINT_WORDS::contains) && t.stream().anyMatch(ADD_VERBS::contains)
                && t.stream().anyMatch(REFERS_BACK::contains);
    }

    /**
     * Does the conversion. {@code current} and the player's position are used for "my coords" and when no dimension was named
     * (then the numbers are taken to be from the dimension the player is in).
     */
    public static Result convert(Request r, Dim current, int playerX, int playerY, int playerZ) {
        int x, z;
        Integer y;
        Dim from, to;
        if (r.useMine()) {
            x = playerX; y = playerY; z = playerZ;
            from = current;
            to = r.to() != null ? r.to() : current.other();
        } else {
            x = r.x(); y = r.y(); z = r.z();
            to = r.to() != null ? r.to() : current.other();
            from = r.to() != null ? to.other() : current;
        }
        if (from == to) return new Result(from, to, x, y, z, x, z);
        if (from == Dim.OVERWORLD) return new Result(from, to, x, y, z, Math.floorDiv(x, 8), Math.floorDiv(z, 8));
        return new Result(from, to, x, y, z, x * 8, z * 8);
    }
}
