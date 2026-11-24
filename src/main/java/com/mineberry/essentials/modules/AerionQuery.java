package com.mineberry.essentials.modules;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Questions about the game right now that Aerion can answer from what the mod knows: "where am I", "how far is home",
 * "which way is the airdrop", "nearest waypoint", "what's my fps". No Minecraft code in it, so it can be tested on its own;
 * AerionActions reads the game and fills in the answer.
 *
 * A question about a place Aerion has no waypoint for ("where is the shop") is NOT taken here: AerionActions hands it
 * back so the normal guide can answer it.
 */
public final class AerionQuery {

    public enum Kind { WHERE_AM_I, NEAREST, DISTANCE, FPS }

    /** {@code target} is what the player named for DISTANCE (a waypoint), empty otherwise. */
    public record Query(Kind kind, String target) {}

    private static final Set<String> NOT_A_QUESTION = Set.of("remove", "delete", "clear", "erase", "hide", "toggle", "turn", "switch",
            "disable", "enable", "convert", "add", "set", "save");
    private static final Set<String> WAYPOINT_WORDS = Set.of("waypoint", "waypoints", "wp", "marker", "markers");
    private static final Set<String> PLACE_WORDS = Set.of("coords", "coord", "coordinates", "position", "location", "xyz", "pos");
    private static final Set<String> STOP = Set.of("how", "far", "is", "are", "to", "the", "a", "my", "me", "from", "away", "of", "i", "get",
            "do", "way", "which", "direction", "distance", "where", "navigate", "go", "can", "you", "tell", "show", "please", "aerion",
            "waypoint", "waypoints", "wp", "marker", "whats", "what", "currently", "right", "now", "at", "in", "and", "us", "find", "give");

    private AerionQuery() {}

    public static Optional<Query> parse(String message) {
        if (message == null || message.chars().anyMatch(Character::isDigit)) return Optional.empty();
        List<String> t = AerionIntents.tokens(message);
        if (t.isEmpty() || t.size() > 12 || t.stream().anyMatch(NOT_A_QUESTION::contains)) return Optional.empty();
        boolean asks = t.contains("what") || t.contains("whats") || t.contains("where") || t.contains("how") || t.contains("which")
                || t.contains("show") || t.contains("tell") || t.contains("give");

        if (t.contains("fps") && (asks || t.contains("my") || t.contains("current"))) return Optional.of(new Query(Kind.FPS, ""));

        if (t.contains("where") && t.contains("am") && t.contains("i")) return Optional.of(new Query(Kind.WHERE_AM_I, ""));
        if ((t.contains("my") || t.contains("current") || t.contains("whats") || t.contains("what")) && t.stream().anyMatch(PLACE_WORDS::contains)
                && t.stream().noneMatch(WAYPOINT_WORDS::contains)) {
            return Optional.of(new Query(Kind.WHERE_AM_I, ""));
        }

        if ((t.contains("nearest") || t.contains("closest")) && t.stream().anyMatch(WAYPOINT_WORDS::contains)) {
            return Optional.of(new Query(Kind.NEAREST, ""));
        }

        boolean trigger = (t.contains("how") && t.contains("far")) || t.contains("distance") || (t.contains("where") && t.contains("is"))
                || (t.contains("which") && t.contains("way")) || t.contains("direction") || (t.contains("get") && t.contains("to"))
                || t.contains("navigate");
        if (!trigger) return Optional.empty();
        StringBuilder target = new StringBuilder();
        for (String w : t) {
            if (STOP.contains(w)) continue;
            if (target.length() > 0) target.append(' ');
            target.append(w);
        }
        return target.length() == 0 ? Optional.empty() : Optional.of(new Query(Kind.DISTANCE, target.toString()));
    }

    /** The compass direction of an offset in the world: -Z is north, +X is east. */
    public static String compass(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz));   // 0 = north, 90 = east
        angle = (angle % 360 + 360) % 360;
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return names[(int) Math.round(angle / 45.0) % 8];
    }

    /** Where an offset is compared with the way the player looks (Minecraft yaw: 0 = south, 90 = west). */
    public static String relative(double yaw, double dx, double dz) {
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((target - yaw) % 360 + 540) % 360 - 180;   // [-180, 180), positive = to the right
        double a = Math.abs(rel);
        String side = rel > 0 ? "right" : "left";
        if (a < 22.5) return "straight ahead";
        if (a < 67.5) return "ahead to your " + side;
        if (a < 112.5) return "to your " + side;
        if (a < 157.5) return "behind you to the " + side;
        return "behind you";
    }
}
