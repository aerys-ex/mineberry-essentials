package com.mineberry.essentials;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
        boolean hudHidden = false;
        boolean autoClosePanelOnDanger = true;
        boolean chatTimestamps = false;
        boolean mentionSound = true;
        List<String> mentionNames = new ArrayList<>();
        boolean autoCopyReportedName = true;
        boolean checkForUpdates = true;
        String skippedUpdateVersion = "";   // "Don't remind me" in the update popup: stay quiet up to this version
        String panelTheme = "default";
        List<String> gradientColors = new ArrayList<>(List.of("#E65B93", "#8A5CF6"));
        int gradientFormat = 0;
        int gradientStyles = 0;
        Map<String, Integer> hudCorner = new HashMap<>();
        Map<String, int[]> hudPos = new HashMap<>();   // free HUD positions: {dx, dy} from the mod's corner
        Map<String, Integer> hudScale = new HashMap<>();   // HUD box size in percent (100 = normal), only stored when changed
        Map<String, Map<String, Integer>> profiles = new java.util.LinkedHashMap<>();
        String activeProfile = "Default";
        String pvpModePattern = DEFAULT_PVP_MODE;
        List<Waypoint> waypoints = new ArrayList<>();
        String lastSeenVersion = "";
        boolean showChangelog = true;
        int termsAcceptedVersion = 0;     // the Terms version the player agreed to (0 = never agreed)
        int termsPromptedVersion = 0;     // the Terms version that was already shown automatically on the title screen
        int combatSeconds = 30;
        String safeLogoutMode = "warn";
        String combatEndPattern = DEFAULT_COMBAT_END;
        int pvpFailsafeSeconds = 180;
        List<String> tournamentAllowedCommands = new ArrayList<>();
        List<String> pvpAllowedCommands = new ArrayList<>(DEFAULT_PVP_ALLOWED);
        List<String> tournamentFriends = new ArrayList<>();
        int tournamentMessageSeconds = 15;
        String tournamentStartPattern = DEFAULT_TOURNAMENT_START;
    }

    /**
     * One slice of the radial menu. command has no slash. A command that contains a placeholder such as
     * "tpa <player>" is not sent: chat opens with "/tpa " typed so you can finish it.
     */
    public static class RadialSlice {
        public String label = "";
        public String command = "";

        public RadialSlice() {}

        public RadialSlice(String label, String command) {
            this.label = label;
            this.command = command;
        }

        /** The label, or the command's first word with a capital when no label was set. */
        public String displayLabel() {
            String l = label == null ? "" : label.trim();
            if (!l.isEmpty()) return l;
            String c = cleanCommand(command);
            int cut = c.indexOf('<');
            if (cut >= 0) c = c.substring(0, cut).trim();
            int space = c.indexOf(' ');
            if (space > 0) c = c.substring(0, space);
            return c.isEmpty() ? "?" : Character.toUpperCase(c.charAt(0)) + c.substring(1);
        }
    }

    /** "  /Home " -> "Home". Strips spaces and the leading slash. */
    public static String cleanCommand(String command) {
        String c = command == null ? "" : command.trim();
        while (c.startsWith("/")) c = c.substring(1).trim();
        return c;
    }

    private static List<RadialSlice> defaultRadialSlices() {
        return new ArrayList<>(List.of(
                new RadialSlice("Spawn", "spawn"),
                new RadialSlice("TPA", "tpa <player>"),
                new RadialSlice("Accept", "tpaccept")));
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
    public static final String DEFAULT_PVP_MODE = "entered pvp mode|entered combat|combat mode|combat tag";

    /** The default before 1.6.0. It matched ANY line containing "pvp mode", e.g. "Player X has left the game while in PvP mode". */
    private static final String OLD_DEFAULT_PVP_MODE = "entered pvp mode|pvp mode|combat mode|combat tag|entered combat";

    /** Regex (case-insensitive) for the server's "you left combat" chat line. Editable in the config file. */
    public static final String DEFAULT_COMBAT_END = "out of pvp mode|no longer in combat|left combat|combat ended|combat is over|you may now log out";

    /** Regex (case-insensitive) for the server's "a round / match / game started" line: the Tournament HUD forgets old statuses. Empty turns it off. */
    public static final String DEFAULT_TOURNAMENT_START = "(?:round|match|game|tournament|battle)\\s+(?:has\\s+)?(?:started|begun|begins)";

    /** Commands that still work while the server has you in PvP mode (PvP Command Lock). Editable in the config file. */
    public static final List<String> DEFAULT_PVP_ALLOWED = List.of("r", "reply", "msg", "m", "w", "tell", "cc");

    private static Data data = new Data();
    private static boolean freshInstall = false;
    private static volatile boolean dirty;
    private static volatile long dirtySince;
    /** Changes are written to disk this long after the first one, so dragging a HUD widget doesn't hit the disk every tick. */
    private static final long SAVE_DELAY_MS = 500;

    private Config() {}

    public static void load() {
        // one-time migration of settings saved under the old name
        Path old = FILE.resolveSibling("peakberry_essentials.json");
        if (!Files.exists(FILE) && Files.exists(old)) {
            try { Files.copy(old, FILE); } catch (Exception e) { MineBerryEssentials.LOGGER.warn("Could not migrate the old settings file", e); }
        }
        if (!Files.exists(FILE)) { freshInstall = true; writeNow(); return; }
        try (Reader r = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
            Data loaded = GSON.fromJson(r, Data.class);
            if (loaded != null) data = loaded;
            if (data.modules == null) data.modules = new HashMap<>();
            if (data.clanCommand == null || data.clanCommand.isBlank()) data.clanCommand = "cc";
            if (data.adminChatCommand == null || data.adminChatCommand.isBlank()) data.adminChatCommand = "ac";
            if (data.partyCommand == null || data.partyCommand.isBlank()) data.partyCommand = "pc";
            if (data.profiles == null) data.profiles = new java.util.LinkedHashMap<>();
            if (data.activeProfile == null) data.activeProfile = "Default";
            if (data.hudCorner == null) data.hudCorner = new HashMap<>();
            if (data.hudPos == null) data.hudPos = new HashMap<>();
            data.hudPos.values().removeIf(p -> p == null || p.length != 2);
            if (data.hudScale == null) data.hudScale = new HashMap<>();
            data.hudScale.values().removeIf(p -> p == null || p < HUD_SCALE_MIN || p > HUD_SCALE_MAX);
            if (data.waypoints == null) data.waypoints = new ArrayList<>();
            if (data.mentionNames == null) data.mentionNames = new ArrayList<>();
            if (data.pvpModePattern == null || data.pvpModePattern.isBlank()
                    || data.pvpModePattern.equals(OLD_DEFAULT_PVP_MODE)) data.pvpModePattern = DEFAULT_PVP_MODE;
            if (data.gradientColors == null || data.gradientColors.size() < 2) data.gradientColors = new ArrayList<>(List.of("#E65B93", "#8A5CF6"));
            if (data.gradientFormat < 0 || data.gradientFormat > 3) data.gradientFormat = 0;
            if ("lunar".equals(data.panelTheme)) data.panelTheme = "default";   // old id of the default theme
            if (data.panelTheme == null || !data.panelTheme.matches("default|aurora|ember|midnight")) data.panelTheme = "default";
            if (data.lastSeenVersion == null) data.lastSeenVersion = "";
            if (data.skippedUpdateVersion == null) data.skippedUpdateVersion = "";
            if (data.combatSeconds < 1 || data.combatSeconds > 300) data.combatSeconds = 30;
            if (data.safeLogoutMode == null || !data.safeLogoutMode.matches("warn|block")) data.safeLogoutMode = "warn";
            if (data.combatEndPattern == null || data.combatEndPattern.isBlank()) data.combatEndPattern = DEFAULT_COMBAT_END;
            if (data.pvpFailsafeSeconds < 10 || data.pvpFailsafeSeconds > 3600) data.pvpFailsafeSeconds = 180;
            if (data.tournamentAllowedCommands == null) data.tournamentAllowedCommands = new ArrayList<>();
            if (data.pvpAllowedCommands == null) data.pvpAllowedCommands = new ArrayList<>(DEFAULT_PVP_ALLOWED);
            data.tournamentFriends = cleanFriends(data.tournamentFriends);
            if (data.tournamentMessageSeconds < 3 || data.tournamentMessageSeconds > 120) data.tournamentMessageSeconds = 15;
            if (data.tournamentStartPattern == null) data.tournamentStartPattern = DEFAULT_TOURNAMENT_START;
            dropRemovedModules();   // Spawn Escape and TP Danger Escape were removed in 1.7.0
            writeNow(); // writes any newly added keys so they can be edited
        } catch (Exception e) {
            // never overwrite a file we could not read: keep it as a backup so the player's waypoints and profiles are not lost
            Path backup = FILE.resolveSibling("mineberry_essentials.json.broken");
            try {
                Files.move(FILE, backup, StandardCopyOption.REPLACE_EXISTING);
                MineBerryEssentials.LOGGER.error("Settings file could not be read; it was kept as {} and defaults are used", backup, e);
            } catch (Exception moveFailed) {
                MineBerryEssentials.LOGGER.error("Settings file could not be read and could not be backed up", e);
            }
            data = new Data();
        }
    }

    /** Marks the settings as changed. They are written shortly after (see flush), not immediately. */
    public static void save() {
        if (!dirty) dirtySince = Clock.ms();
        dirty = true;
    }

    /** Called every client tick: writes pending changes once they are SAVE_DELAY_MS old. */
    public static void flush() {
        if (dirty && Clock.ms() - dirtySince >= SAVE_DELAY_MS) writeNow();
    }

    /** Writes pending changes right now (used when the game closes). */
    public static void flushNow() {
        if (dirty) writeNow();
    }

    /** Writes to a temp file first and then swaps it in, so a crash mid-write can't leave a half-written config. */
    private static synchronized void writeNow() {
        dirty = false;
        Path tmp = FILE.resolveSibling("mineberry_essentials.json.tmp");
        try {
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(data, w);
            }
            try {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            dirty = true;   // try again later
            MineBerryEssentials.LOGGER.warn("Could not save settings", e);
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

    /** 1.7.0 removed Spawn Escape and TP Danger Escape: forget their saved switches and HUD settings so old configs stay clean. */
    private static void dropRemovedModules() {
        for (String id : new String[]{"spawn_escape", "tp_danger_escape"}) {
            data.modules.remove(id);
            data.hudCorner.remove(id);
            data.hudPos.remove(id);
            data.hudScale.remove(id);
            for (Map<String, Integer> profile : data.profiles.values()) {
                if (profile == null) continue;
                profile.keySet().removeIf(k -> k.endsWith("_" + id));
            }
        }
    }

    public static boolean hudHidden() {
        return data.hudHidden;
    }

    /** Removes a HUD profile. Returns false (and does nothing) for the last remaining one. */
    public static boolean deleteProfile(String name) {
        if (data.profiles.size() <= 1 || !data.profiles.containsKey(name)) return false;
        data.profiles.remove(name);
        if (name.equals(data.activeProfile)) data.activeProfile = data.profiles.keySet().iterator().next();
        save();
        return true;
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
        if (theme.matches("default|aurora|ember|midnight")) {
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

    /** Free position of a HUD mod: {dx, dy} = distance from the edges of its corner, or null while it sits in the automatic stack. */
    public static int[] hudPos(String id) {
        return data.hudPos.get(id);
    }

    public static void setHudPos(String id, int corner, int dx, int dy) {
        data.hudCorner.put(id, corner);
        data.hudPos.put(id, new int[]{dx, dy});
        save();
    }

    public static void clearHudPos(String id) {
        if (data.hudPos.remove(id) != null) save();
    }

    public static final int HUD_SCALE_MIN = 50, HUD_SCALE_MAX = 250;

    /** Size of a HUD box in percent (100 = normal). Works for every HUD mod id and for the vanilla scoreboard ("scoreboard"). */
    public static int hudScalePct(String id) {
        return data.hudScale.getOrDefault(id, 100);
    }

    public static void setHudScalePct(String id, int pct) {
        pct = Math.max(HUD_SCALE_MIN, Math.min(HUD_SCALE_MAX, pct));
        if (pct == 100) data.hudScale.remove(id);
        else data.hudScale.put(id, pct);
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

    /** The version the player chose "Don't remind me" for in the update popup; empty if none. Newer versions still notify. */
    public static String skippedUpdateVersion() {
        return data.skippedUpdateVersion;
    }

    public static void setSkippedUpdateVersion(String version) {
        data.skippedUpdateVersion = version == null ? "" : version;
        save();
    }

    /** Whether the "what's new" popup shows after an update. Edit in the config file to turn it off. */
    public static boolean showChangelog() {
        return data.showChangelog;
    }

    /** The version of the Terms the player agreed to, 0 if they never did (see Terms). */
    public static int termsAcceptedVersion() {
        return data.termsAcceptedVersion;
    }

    public static void setTermsAcceptedVersion(int version) {
        data.termsAcceptedVersion = version;
        save();
    }

    /** The Terms version that was already offered automatically on the title screen, so it is not shown on every launch. */
    public static int termsPromptedVersion() {
        return data.termsPromptedVersion;
    }

    public static void setTermsPromptedVersion(int version) {
        data.termsPromptedVersion = version;
        save();
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

    /** If the server's "out of PvP mode" line is missed, Safe Logout and PvP Command Lock let go after this many seconds. */
    public static int pvpFailsafeSeconds() {
        return data.pvpFailsafeSeconds;
    }

    /** Commands (no slash) that still work in Tournament Mode. Empty by default: every command is blocked. */
    public static List<String> tournamentAllowedCommands() {
        return data.tournamentAllowedCommands;
    }

    /** Commands (no slash) that still work while in PvP mode. */
    public static List<String> pvpAllowedCommands() {
        return data.pvpAllowedCommands;
    }

    /** Regex (case-insensitive) for the server's "combat ended" chat line. Edit in the config file. */
    public static String combatEndPattern() {
        return data.combatEndPattern;
    }

    /** Valid player names only, no duplicates (ignoring case), at most TournamentHudModule.MAX_FRIENDS of them. */
    private static List<String> cleanFriends(List<String> raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String entry : raw) {
            var name = com.mineberry.essentials.modules.TournamentParser.cleanName(entry);
            if (name.isEmpty()) continue;
            boolean duplicate = false;
            for (String known : out) if (known.equalsIgnoreCase(name.get())) { duplicate = true; break; }
            if (!duplicate && out.size() < com.mineberry.essentials.modules.TournamentHudModule.MAX_FRIENDS) out.add(name.get());
        }
        return out;
    }

    /** The friends shown on the Tournament HUD (live list). Call save() after changing it. */
    public static List<String> tournamentFriends() {
        return data.tournamentFriends;
    }

    /** Seconds a friend's last chat message stays on the Tournament HUD. Edit in the config file. */
    public static int tournamentMessageSeconds() {
        return data.tournamentMessageSeconds;
    }

    /** Regex (case-insensitive) for the server's "round started" line. Edit in the config file; empty turns the automatic reset off. */
    public static String tournamentStartPattern() {
        return data.tournamentStartPattern;
    }

    /** The radial menu slices. They are fixed and can't be changed. */
    public static List<RadialSlice> radialSlices() {
        return defaultRadialSlices();
    }
}
