package com.mineberry.essentials;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tiny JSON config stored in .minecraft/config/mineberry_essentials.json */
public final class Config {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("mineberry_essentials.json");

    private static class Data {
        Map<String, Boolean> modules = new HashMap<>();
        String clanCommand = "cc";
        String adminChatCommand = "ac";
        String partyCommand = "pc";
        String spawnCommand = "spawn";
        boolean hudHidden = false;
        boolean autoClosePanelOnDanger = true;
        boolean chatTimestamps = false;
        boolean mentionSound = true;
        List<String> mentionNames = new ArrayList<>();
        boolean autoCopyReportedName = true;
        boolean checkForUpdates = true;
        String panelTheme = "lunar";
        List<String> gradientColors = new ArrayList<>(List.of("#E65B93", "#8A5CF6"));
        int gradientFormat = 0;
        int gradientStyles = 0;
        Map<String, Integer> hudCorner = new HashMap<>();
        Map<String, Map<String, Integer>> profiles = new java.util.LinkedHashMap<>();
        String activeProfile = "Default";
        String pvpModePattern = DEFAULT_PVP_MODE;
        List<Waypoint> waypoints = new ArrayList<>();
        String lastSeenVersion = "";
        boolean showChangelog = true;
        int combatSeconds = 30;
        String safeLogoutMode = "warn";
        String combatEndPattern = DEFAULT_COMBAT_END;
    }

    /** A saved coordinate. dim is the dimension id, e.g. "minecraft:overworld". color is ARGB. */
    public static class Waypoint {
        public String name = "Waypoint";
        public int x, y, z;
        public String dim = "minecraft:overworld";
        public int color = 0xFFE65B93;
        public boolean visible = true;
    }

    /** Regex (case-insensitive) for the server's "you entered PvP mode" chat line. Editable in the config file. */
    public static final String DEFAULT_PVP_MODE = "entered pvp mode|pvp mode|combat mode|combat tag|entered combat";

    /** Regex (case-insensitive) for the server's "you left combat" chat line. Editable in the config file. */
    public static final String DEFAULT_COMBAT_END = "out of pvp mode|no longer in combat|left combat|combat ended|combat is over|you may now log out";

    private static Data data = new Data();
    private static boolean freshInstall = false;

    private Config() {}

    public static void load() {
        LunarHud.init();   // makes sure the HUD and keybinds register even without a client entrypoint
        // one-time migration of settings saved under the old name
        Path old = FILE.resolveSibling("peakberry_essentials.json");
        if (!Files.exists(FILE) && Files.exists(old)) {
            try { Files.copy(old, FILE); } catch (Exception e) { e.printStackTrace(); }
        }
        if (!Files.exists(FILE)) { freshInstall = true; save(); return; }
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            Data loaded = GSON.fromJson(r, Data.class);
            if (loaded != null) data = loaded;
            if (data.modules == null) data.modules = new HashMap<>();
            if (data.clanCommand == null || data.clanCommand.isBlank()) data.clanCommand = "cc";
            if (data.adminChatCommand == null || data.adminChatCommand.isBlank()) data.adminChatCommand = "ac";
            if (data.partyCommand == null || data.partyCommand.isBlank()) data.partyCommand = "pc";
            if (data.spawnCommand == null || data.spawnCommand.isBlank()) data.spawnCommand = "spawn";
            if (data.profiles == null) data.profiles = new java.util.LinkedHashMap<>();
            if (data.activeProfile == null) data.activeProfile = "Default";
            if (data.hudCorner == null) data.hudCorner = new HashMap<>();
            if (data.waypoints == null) data.waypoints = new ArrayList<>();
            if (data.mentionNames == null) data.mentionNames = new ArrayList<>();
            if (data.pvpModePattern == null || data.pvpModePattern.isBlank()) data.pvpModePattern = DEFAULT_PVP_MODE;
            if (data.gradientColors == null || data.gradientColors.size() < 2) data.gradientColors = new ArrayList<>(List.of("#E65B93", "#8A5CF6"));
            if (data.gradientFormat < 0 || data.gradientFormat > 3) data.gradientFormat = 0;
            if (data.panelTheme == null || !data.panelTheme.matches("lunar|aurora|ember|midnight")) data.panelTheme = "lunar";
            if (data.lastSeenVersion == null) data.lastSeenVersion = "";
            if (data.combatSeconds < 1 || data.combatSeconds > 300) data.combatSeconds = 30;
            if (data.safeLogoutMode == null || !data.safeLogoutMode.matches("warn|block")) data.safeLogoutMode = "warn";
            if (data.combatEndPattern == null || data.combatEndPattern.isBlank()) data.combatEndPattern = DEFAULT_COMBAT_END;
            save(); // writes any newly added keys so they can be edited
        } catch (Exception e) {
            e.printStackTrace();
            data = new Data();
        }
    }

    public static void save() {
        try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
            GSON.toJson(data, w);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean isEnabled(String moduleId) {
        return data.modules.getOrDefault(moduleId, true);
    }

    /** Like isEnabled(id), but with the value to use when the module was never switched (some HUD mods start off). */
    public static boolean isEnabled(String moduleId, boolean fallback) {
        return data.modules.getOrDefault(moduleId, fallback);
    }

    public static void setEnabled(String moduleId, boolean enabled) {
        data.modules.put(moduleId, enabled);
        save();
    }

    public static String pvpModePattern() {
        return data.pvpModePattern;
    }

    /** Command used for clan chat, without the slash. Edit in the config file to change it. */
    public static String clanCommand() {
        return data.clanCommand.startsWith("/") ? data.clanCommand.substring(1) : data.clanCommand;
    }

    /** Command used for admin chat, without the slash. Edit in the config file to change it. */
    public static String adminChatCommand() {
        return data.adminChatCommand.startsWith("/") ? data.adminChatCommand.substring(1) : data.adminChatCommand;
    }

    /** Command used for party chat, without the slash. Edit in the config file to change it. */
    public static String partyCommand() {
        return data.partyCommand.startsWith("/") ? data.partyCommand.substring(1) : data.partyCommand;
    }

    /** Command used by Spawn Escape, without the slash. Edit in the config file to change it. */
    public static String spawnCommand() {
        return data.spawnCommand.startsWith("/") ? data.spawnCommand.substring(1) : data.spawnCommand;
    }

    public static boolean hudHidden() {
        return data.hudHidden;
    }

    /** Persists the HUD choice between game sessions. */
    public static void setHudHidden(boolean hidden) {
        data.hudHidden = hidden;
        save();
    }

    /** Whether the Control Center closes itself when PvP danger or damage is detected. */
    public static boolean autoClosePanelOnDanger() {
        return data.autoClosePanelOnDanger;
    }

    public static void setAutoClosePanelOnDanger(boolean enabled) {
        data.autoClosePanelOnDanger = enabled;
        save();
    }

    /** Whether chat lines get a [HH:mm] prefix (Chat Extras module). */
    public static boolean chatTimestamps() {
        return data.chatTimestamps;
    }

    public static void setChatTimestamps(boolean enabled) {
        data.chatTimestamps = enabled;
        save();
    }

    /** Whether a sound plays when someone says your name (Chat Extras module). */
    public static boolean mentionSound() {
        return data.mentionSound;
    }

    /** Extra names/words that count as a mention besides your own username. Edit in the config file. */
    public static List<String> mentionNames() {
        return data.mentionNames;
    }

    /** Whether the reported player's name is copied to the clipboard when evidence is confirmed. */
    public static boolean autoCopyReportedName() {
        return data.autoCopyReportedName;
    }

    /** Whether the mod looks for a newer release on GitHub at startup. Edit in the config file to turn it off. */
    public static boolean checkForUpdates() {
        return data.checkForUpdates;
    }

    /** Visual theme used by the MineBerry - Essentials Control Center. */
    public static String panelTheme() {
        return data.panelTheme;
    }

    public static void setPanelTheme(String theme) {
        if (theme.matches("lunar|aurora|ember|midnight")) {
            data.panelTheme = theme;
            save();
        }
    }

    /** Hex colors of the gradient generator (2 to 6 entries like "#E65B93"). */
    public static List<String> gradientColors() {
        return data.gradientColors;
    }

    /** Output format index of the gradient generator (see GradientScreen.FORMATS). */
    public static int gradientFormat() {
        return data.gradientFormat;
    }

    /** Bit mask of the gradient generator's text styles: 1 bold, 2 italic, 4 underline, 8 strikethrough. */
    public static int gradientStyles() {
        return data.gradientStyles;
    }

    public static void setGradient(List<String> colors, int format, int styles) {
        data.gradientColors = new ArrayList<>(colors);
        data.gradientFormat = format;
        data.gradientStyles = styles;
        save();
    }

    /** The saved waypoints (live list). Call save() after changing it. */
    public static List<Waypoint> waypoints() {
        return data.waypoints;
    }

    /** Screen corner of a HUD mod: 0 top-left, 1 top-right, 2 bottom-left, 3 bottom-right. */
    public static int hudCorner(String id, int fallback) {
        return data.hudCorner.getOrDefault(id, fallback);
    }

    public static void setHudCorner(String id, int corner) {
        data.hudCorner.put(id, corner);
        save();
    }

    /** HUD profiles: name -> saved state ("on_<id>" 0/1 and "c_<id>" corner). */
    public static Map<String, Map<String, Integer>> profiles() {
        return data.profiles;
    }

    public static String activeProfile() {
        return data.activeProfile;
    }

    public static void setActiveProfile(String name) {
        data.activeProfile = name;
        save();
    }

    /** True only on the very first launch (no config file existed yet). */
    public static boolean freshInstall() {
        return freshInstall;
    }

    /** Version whose changelog the player has already seen; empty if none yet. */
    public static String lastSeenVersion() {
        return data.lastSeenVersion;
    }

    public static void setLastSeenVersion(String version) {
        data.lastSeenVersion = version == null ? "" : version;
        save();
    }

    /** Whether the "what's new" popup shows after an update. Edit in the config file to turn it off. */
    public static boolean showChangelog() {
        return data.showChangelog;
    }

    /** Seconds after the last hit during which Safe Logout guards quitting. Edit in the config file. */
    public static int combatSeconds() {
        return data.combatSeconds;
    }

    /** Safe Logout behaviour: "block" (quitting is refused) or "warn" (the first try is refused, a second click quits). */
    public static String safeLogoutMode() {
        return data.safeLogoutMode;
    }

    public static void setSafeLogoutMode(String mode) {
        if (mode != null && mode.matches("warn|block")) {
            data.safeLogoutMode = mode;
            save();
        }
    }

    /** Regex (case-insensitive) for the server's "combat ended" chat line. Edit in the config file. */
    public static String combatEndPattern() {
        return data.combatEndPattern;
    }
}
