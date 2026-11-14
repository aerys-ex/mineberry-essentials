package com.mineberry.essentials;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "What's New" popup shown once after the mod was updated.
 *
 * How it works: the text lives in the jar, in assets/mineberry_essentials/changelog.json, so it always matches the
 * installed version and works offline. The config remembers the last version the player has seen. When the installed
 * version is different (and the file has entries for it), the popup opens on the title screen and the new version is
 * saved, so it shows exactly once. If the player skipped several versions, the skipped ones are listed too.
 *
 * A brand new install does not get a popup (there is nothing "new" to a first-time player); it is still reachable
 * from Control Center > Tools > What's New. Turn the automatic popup off with "showChangelog": false in the config.
 *
 * To write a changelog for a release: add an entry at the top of changelog.json. That is all.
 */
public final class Changelog {

    private static final String RESOURCE = "assets/mineberry_essentials/changelog.json";
    /** How many versions the popup lists at most (newest first). */
    private static final int MAX_VERSIONS = 3;

    /** One released version: a version number, an optional headline, and named sections of bullet points. */
    public record Entry(String version, String title, Map<String, List<String>> sections) {}

    private static List<Entry> all;                     // loaded lazily, newest first
    private static List<Entry> pending = List.of();     // what the automatic popup will show
    private static volatile Screen title;               // the title screen currently open, if any
    private static boolean shown;

    private Changelog() {}

    /** Call once from the client initializer, after Config.load(). */
    public static void init() {
        String current = UpdateChecker.installed();
        String seen = Config.lastSeenVersion();
        if (seen.equals(current)) return;                               // already seen this version

        if (seen.isEmpty() && Config.freshInstall()) {                  // first ever launch: nothing is "new"
            Config.setLastSeenVersion(current);
            return;
        }
        if (!Config.showChangelog()) return;                            // popup disabled: stay quiet, keep the old marker

        pending = between(seen.isEmpty() ? "0" : seen, current);
        if (pending.isEmpty()) {                                        // nothing written for this version
            Config.setLastSeenVersion(current);
            return;
        }

        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen && title != screen) {
                title = screen;
                ScreenEvents.remove(screen).register(removed -> { if (title == removed) title = null; });
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(Changelog::tick);
    }

    /** Opens the popup once per launch, as soon as the title screen is up and nothing else is covering it. */
    private static void tick(Minecraft mc) {
        Screen t = title;
        if (shown || t == null || mc.gui.screen() != t) return;
        shown = true;
        title = null;
        Config.setLastSeenVersion(UpdateChecker.installed());           // saved now, so it never shows twice
        mc.gui.setScreen(new ChangelogScreen(t, pending, UpdateChecker.installed()));
    }

    /** True when the jar contains at least one changelog entry (decides whether the Tools button is shown). */
    public static boolean hasEntries() {
        return !entries().isEmpty();
    }

    /** The screen for the manual "What's New" button: the newest versions up to the one installed. */
    public static Screen screen(Screen parent) {
        String current = UpdateChecker.installed();
        List<Entry> list = between("0", current);
        if (list.isEmpty() && !entries().isEmpty()) list = List.of(entries().get(0));   // e.g. a dev build
        return new ChangelogScreen(parent, list, current);
    }

    /** Entries newer than {@code after} and not newer than {@code upTo}, newest first, at most MAX_VERSIONS. */
    private static List<Entry> between(String after, String upTo) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries()) {
            if (UpdateChecker.isNewer(e.version(), after) && !UpdateChecker.isNewer(e.version(), upTo)) {
                out.add(e);
                if (out.size() == MAX_VERSIONS) break;
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- loading

    private static synchronized List<Entry> entries() {
        if (all == null) all = load();
        return all;
    }

    private static List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        try (Reader reader = open()) {
            if (reader == null) return out;
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (JsonElement el : root.getAsJsonArray("versions")) {
                JsonObject o = el.getAsJsonObject();
                String version = o.get("version").getAsString().trim();
                String headline = o.has("title") ? o.get("title").getAsString() : "";
                Map<String, List<String>> sections = new LinkedHashMap<>();
                if (o.has("sections")) {
                    for (Map.Entry<String, JsonElement> s : o.getAsJsonObject("sections").entrySet()) {
                        List<String> lines = new ArrayList<>();
                        JsonArray array = s.getValue().getAsJsonArray();
                        for (JsonElement line : array) lines.add(line.getAsString());
                        if (!lines.isEmpty()) sections.put(s.getKey(), lines);
                    }
                }
                if (!version.isEmpty() && !sections.isEmpty()) out.add(new Entry(version, headline, sections));
            }
        } catch (Exception e) {
            MineBerryEssentials.LOGGER.warn("Could not read changelog.json", e);   // never let this break the game
            return new ArrayList<>();
        }
        out.sort((a, b) -> UpdateChecker.isNewer(a.version(), b.version()) ? -1
                : UpdateChecker.isNewer(b.version(), a.version()) ? 1 : 0);        // newest first, whatever the file order
        return out;
    }

    private static Reader open() throws Exception {
        Optional<java.nio.file.Path> path = FabricLoader.getInstance().getModContainer(MineBerryEssentials.MOD_ID)
                .flatMap(c -> c.findPath(RESOURCE));
        if (path.isPresent()) return Files.newBufferedReader(path.get(), StandardCharsets.UTF_8);
        InputStream in = Changelog.class.getClassLoader().getResourceAsStream(RESOURCE);
        return in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8);
    }
}
