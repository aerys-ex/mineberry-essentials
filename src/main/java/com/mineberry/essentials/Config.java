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
        String spawnCommand = "spawn";
        boolean hudHidden = false;
        boolean autoClosePanelOnDanger = true;
        String panelTheme = "rose";
        String pvpModePattern = DEFAULT_PVP_MODE;
        List<Waypoint> waypoints = new ArrayList<>();
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
    public static final String DEFAULT_PVP_MODE = "pvp mode|combat mode|combat tag|entered combat";

    private static Data data = new Data();

    private Config() {}

    public static void load() {
        // one-time migration of settings saved under the old name
        Path old = FILE.resolveSibling("peakberry_essentials.json");
        if (!Files.exists(FILE) && Files.exists(old)) {
            try { Files.copy(old, FILE); } catch (Exception e) { e.printStackTrace(); }
        }
        if (!Files.exists(FILE)) { save(); return; }
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            Data loaded = GSON.fromJson(r, Data.class);
            if (loaded != null) data = loaded;
            if (data.modules == null) data.modules = new HashMap<>();
            if (data.clanCommand == null || data.clanCommand.isBlank()) data.clanCommand = "cc";
            if (data.adminChatCommand == null || data.adminChatCommand.isBlank()) data.adminChatCommand = "ac";
            if (data.spawnCommand == null || data.spawnCommand.isBlank()) data.spawnCommand = "spawn";
            if (data.waypoints == null) data.waypoints = new ArrayList<>();
            if (data.pvpModePattern == null || data.pvpModePattern.isBlank()) data.pvpModePattern = DEFAULT_PVP_MODE;
            if (data.panelTheme == null || !data.panelTheme.matches("rose|aurora|ember|midnight")) data.panelTheme = "rose";
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

    /** Visual theme used by the MineBerry - Essentials Control Center. */
    public static String panelTheme() {
        return data.panelTheme;
    }

    public static void setPanelTheme(String theme) {
        if (theme.matches("rose|aurora|ember|midnight")) {
            data.panelTheme = theme;
            save();
        }
    }

    /** The saved waypoints (live list). Call save() after changing it. */
    public static List<Waypoint> waypoints() {
        return data.waypoints;
    }
}
