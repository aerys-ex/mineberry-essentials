package com.mineberry.essentials;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Map;

/** Tiny pixel icons for the panel cards. Each icon is a bitmap ('#' = pixel) drawn with plain fills. */
final class Icons {

    private static final String[] CHAT = {
            "............",
            ".##########.",
            ".#........#.",
            ".#........#.",
            ".#.#.#.#..#.",
            ".#........#.",
            ".#........#.",
            ".##########.",
            "..###.......",
            "..##........",
            "..#.........",
            "............"};
    private static final String[] SHIELD = {
            "............",
            "..########..",
            "..#......#..",
            "..#......#..",
            "..#......#..",
            "..#......#..",
            "..#......#..",
            "...#....#...",
            "....#..#....",
            ".....##.....",
            "............",
            "............"};
    private static final String[] CROWN = {
            "............",
            "............",
            ".#...##...#.",
            ".##..##..##.",
            ".#.#.##.#.#.",
            ".#..####..#.",
            ".#........#.",
            ".##########.",
            ".#........#.",
            ".##########.",
            "............",
            "............"};
    private static final String[] PERSON = {
            "............",
            "....####....",
            "...#....#...",
            "...#....#...",
            "....####....",
            "............",
            "..########..",
            ".#........#.",
            ".#........#.",
            ".#........#.",
            ".##########.",
            "............"};
    private static final String[] DOOR = {
            "............",
            "..########..",
            "..#......#..",
            "..#......#..",
            "..#......#..",
            "..#....#.#..",
            "..#....#.#..",
            "..#......#..",
            "..#......#..",
            "..#......#..",
            ".##########.",
            "............"};
    private static final String[] WARNING = {
            "............",
            ".....##.....",
            ".....##.....",
            "....#..#....",
            "....#..#....",
            "...#.##.#...",
            "...#.##.#...",
            "..#......#..",
            "..#..##..#..",
            ".##########.",
            "............",
            "............"};
    private static final String[] CAMERA = {
            "............",
            "............",
            "....####....",
            ".##########.",
            ".#........#.",
            ".#..####..#.",
            ".#.#....#.#.",
            ".#.#....#.#.",
            ".#..####..#.",
            ".#........#.",
            ".##########.",
            "............"};
    private static final String[] CUBE = {
            "............",
            ".....##.....",
            "...##..##...",
            ".##......##.",
            ".#.##..##.#.",
            ".#...##...#.",
            ".#...##...#.",
            ".#...##...#.",
            ".##..##..##.",
            "...##..##...",
            ".....##.....",
            "............"};
    private static final String[] SELECTION = {
            "............",
            "............",
            ".#.#.#.#.#..",
            ".#.......#..",
            "............",
            ".#.......#..",
            "............",
            ".#.......#..",
            "............",
            ".#.#.#.#.#..",
            "............",
            "............"};
    private static final String[] PIN = {
            "............",
            "....####....",
            "...#....#...",
            "..#..##..#..",
            "..#..##..#..",
            "..#......#..",
            "...#....#...",
            "....#..#....",
            ".....##.....",
            ".....##.....",
            "............",
            "............"};
    private static final String[] CLOCK = {
            "............",
            "...######...",
            "..#......#..",
            ".#...##...#.",
            ".#...##...#.",
            ".#...###..#.",
            ".#........#.",
            ".#........#.",
            "..#......#..",
            "...######...",
            "............",
            "............"};

    private static final String[] REPLY = {
            "............",
            "....#.......",
            "...##.......",
            "..#########.",
            "...##.....#.",
            "....#.....#.",
            "..........#.",
            "..........#.",
            "....#######.",
            "............",
            "............",
            "............"};

    /** Small gear (10 rows) for the "settings" button on a card. */
    static final String[] GEAR = {
            ".....##.....",
            ".#..####..#.",
            "..########..",
            "..##....##..",
            "###......###",
            "###......###",
            "..##....##..",
            "..########..",
            ".#..####..#.",
            ".....##....."};

    /** Magnifier (7 rows) for the search box. */
    static final String[] SEARCH = {
            "..###..",
            ".#...#.",
            ".#...#.",
            ".#...#.",
            "..###..",
            ".....#.",
            "......#"};

    private static final Map<String, String[]> BY_ID = Map.ofEntries(
            Map.entry("clan_chat", SHIELD),
            Map.entry("admin_chat", CROWN),
            Map.entry("party_chat", PERSON),
            Map.entry("chat_extras", CHAT),
            Map.entry("quick_reply", REPLY),
            Map.entry("spawn_escape", DOOR),
            Map.entry("tp_danger_escape", WARNING),
            Map.entry("evidence", CAMERA),
            Map.entry("worldeditcui", SELECTION),
            Map.entry("waypoints", PIN),
            Map.entry("opt_close_on_danger", WARNING),
            Map.entry("opt_chat_timestamps", CLOCK));

    private Icons() {}

    /** The icon for a module / option id; modules added later get a plain cube. */
    static String[] forId(String id) {
        return BY_ID.getOrDefault(id, CUBE);
    }

    /** Draws a bitmap with its top-left at (x, y), every pixel scale x scale screen pixels. */
    static void draw(GuiGraphicsExtractor g, String[] bitmap, int x, int y, int scale, int color) {
        for (int row = 0; row < bitmap.length; row++) {
            String line = bitmap[row];
            int col = 0;
            while (col < line.length()) {
                if (line.charAt(col) != '#') { col++; continue; }
                int start = col;
                while (col < line.length() && line.charAt(col) == '#') col++;
                Ui.fill(g, x + start * scale, y + row * scale, x + col * scale, y + (row + 1) * scale, color);
            }
        }
    }

    static int width(String[] bitmap, int scale) {
        return bitmap[0].length() * scale;
    }

    static int height(String[] bitmap, int scale) {
        return bitmap.length * scale;
    }
}
