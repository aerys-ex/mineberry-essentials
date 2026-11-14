package com.mineberry.essentials;

import java.util.Locale;

/** Pure formatting and math for the HUD widgets (no Minecraft classes, so it is easy to test). */
final class HudFormat {

    private static final String[] ROMAN = {"", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
    private static final String[] POINTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final String[] AXES = {"-Z", "", "+X", "", "+Z", "", "-X", ""};

    private HudFormat() {}

    /** Effect level suffix from the amplifier: level I shows nothing (like vanilla), II..X in roman, then plain numbers. */
    static String roman(int amplifier) {
        if (amplifier <= 0) return "";
        return amplifier < ROMAN.length ? ROMAN[amplifier] : String.valueOf(amplifier + 1);
    }

    /** Remaining effect time: m:ss, or h:mm:ss from one hour on. Rounds up so it never shows 0:00 while the effect is active. */
    static String duration(int ticks) {
        long s = (Math.max(ticks, 0) + 19L) / 20L;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec) : String.format(Locale.ROOT, "%d:%02d", m, sec);
    }

    /** Compass bearing 0..359 (0 = north, 90 = east) from Minecraft's yaw (0 = south, 90 = west). */
    static int bearing(float yaw) {
        float b = ((yaw + 180f) % 360f + 360f) % 360f;
        return Math.round(b) % 360;
    }

    /** Index 0..7 into N, NE, E, SE, S, SW, W, NW. */
    static int point(int bearing) {
        return (Math.floorMod(bearing, 360) + 22) / 45 % 8;
    }

    static String pointName(int point) {
        return POINTS[Math.floorMod(point, 8)];
    }

    /** The world axis a main direction faces (north is -Z, east is +X...), empty for the diagonals. */
    static String axis(int point) {
        return AXES[Math.floorMod(point, 8)];
    }

    /** Minecraft day ticks as a 24h clock. Tick 0 is 06:00, like the sun-up of a fresh day. */
    static String clock(long dayTicks) {
        long t = Math.floorMod(dayTicks, 24000L);
        int hours = (int) ((t / 1000 + 6) % 24), minutes = (int) ((t % 1000) * 60 / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", hours, minutes);
    }

    static String phase(long dayTicks) {
        long t = Math.floorMod(dayTicks, 24000L);
        if (t < 12000) return "Day";
        if (t < 13000) return "Dusk";
        if (t < 23000) return "Night";
        return "Dawn";
    }

    /** Pulls "minecraft:plains" out of ResourceKey#toString, which looks like "ResourceKey[minecraft:worldgen/biome / minecraft:plains]". */
    static String keyId(String resourceKey) {
        if (resourceKey == null) return "";
        int cut = resourceKey.lastIndexOf(" / ");
        String id = cut >= 0 ? resourceKey.substring(cut + 3) : resourceKey;
        return id.endsWith("]") ? id.substring(0, id.length() - 1) : id;
    }

    /** "minecraft:dark_forest" -> "biome.minecraft.dark_forest" (the vanilla translation key of a biome). */
    static String biomeLangKey(String id) {
        int colon = id.indexOf(':');
        String ns = colon >= 0 ? id.substring(0, colon) : "minecraft";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return "biome." + ns + "." + path.replace('/', '.');
    }

    /** Fallback name when a biome has no translation: "minecraft:dark_forest" -> "Dark Forest". */
    static String pretty(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        path = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder out = new StringBuilder();
        for (String word : path.split("_")) {
            if (word.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.length() == 0 ? id : out.toString();
    }

    /** Green above half, yellow above a quarter, red below. */
    static int durabilityColor(int left, int max) {
        float ratio = max <= 0 ? 1f : left / (float) max;
        if (ratio > 0.5f) return 0xFF55FF55;
        if (ratio > 0.25f) return 0xFFFFFF55;
        return 0xFFFF5555;
    }
}
