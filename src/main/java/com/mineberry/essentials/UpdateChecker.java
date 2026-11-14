package com.mineberry.essentials;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Checks GitHub for a newer release of the mod.
 *
 * How it works: GitHub's API returns your newest published Release (https://github.com/aerys-ex/mineberry-essentials/releases).
 * The release's tag (e.g. "v1.3.0") is compared with the version of the installed mod. If the tag is higher, the player
 * gets one chat message when they join a world, and the Control Center shows an "Update" button.
 * Nothing is downloaded or installed automatically, the player just gets a link.
 *
 * Turn it off by setting "checkForUpdates": false in config/mineberry_essentials.json.
 */
public final class UpdateChecker {

    private static final String REPO = "aerys-ex/mineberry-essentials";
    private static final String API_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String RELEASES_URL = "https://github.com/" + REPO + "/releases/latest";
    /** The "id" in fabric.mod.json. If yours differs, the fallback below still finds a mod whose id contains "mineberry". */
    private static final String MOD_ID = "mineberry-essentials";

    /** Outcome of a finished check. */
    public record Result(String current, String latest, String url, boolean updateAvailable) {}

    /** Where the check currently stands. The Tools tab shows this next to the "Check for updates" button. */
    public enum State { IDLE, CHECKING, UP_TO_DATE, UPDATE, FAILED }

    private static final AtomicBoolean started = new AtomicBoolean(false);
    private static volatile State state = State.IDLE;
    private static volatile Result result;      // null until the check has finished (or if it failed)
    private static volatile boolean announced, popupShown;
    private static volatile Screen title;       // the title screen currently open, if any

    private UpdateChecker() {}

    /** Call once from your client initializer, after Config.load(). Safe to call more than once. */
    public static void init() {
        if (!Config.checkForUpdates()) return;
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> announce(client));

        // remember the title screen so the popup can open on it, even if the answer from GitHub arrives a moment later
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen && title != screen) {
                title = screen;
                ScreenEvents.remove(screen).register(removed -> { if (title == removed) title = null; });
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(UpdateChecker::tick);
        check();
    }

    /** Opens the update popup once per launch, as soon as both the title screen and the result are there. */
    private static void tick(Minecraft mc) {
        Result r = result;
        Screen t = title;
        if (popupShown || r == null || !r.updateAvailable() || t == null) return;
        popupShown = true;
        title = null;
        mc.gui.setScreen(new UpdateScreen(t, r));
    }

    /** The finished result, or null if the check is disabled, still running, or failed (offline, rate limited, no releases). */
    public static Result result() {
        return result;
    }

    public static State state() {
        return state;
    }

    /** The installed version, without a leading "v". */
    public static String installed() {
        return currentVersion();
    }

    /** Starts the automatic background check (once per launch). Does nothing when disabled in the config. */
    public static void check() {
        if (!Config.checkForUpdates() || !started.compareAndSet(false, true)) return;
        run(true);
    }

    /**
     * Manual check, for the "Check for updates" button. Works even when automatic checking is turned off,
     * and can be repeated, so a failed check (offline, rate limited) can simply be tried again.
     */
    public static void checkNow() {
        if (state == State.CHECKING) return;
        started.set(true);
        run(false);
    }

    private static void run(boolean automatic) {
        state = State.CHECKING;
        String current = currentVersion();
        Thread thread = new Thread(() -> {
            try {
                String[] found = fetchLatest(current);          // {tag without "v", url}, or null
                if (found == null) {
                    state = State.FAILED;
                    return;
                }
                boolean newer = isNewer(found[0], current);
                result = new Result(current, found[0], found[1], newer);
                state = newer ? State.UPDATE : State.UP_TO_DATE;
                if (automatic && newer) {
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> announce(mc));             // already in a world when the answer arrives
                }
            } catch (Exception e) {
                state = State.FAILED;                           // never let an update check break the game
            }
        }, "MineBerry-UpdateCheck");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Finds the newest release. First the plain github.com "latest" link, which redirects to the newest release's page:
     * it has no API rate limit, so it keeps working on shared networks. If that fails, the GitHub API is tried.
     */
    private static String[] fetchLatest(String current) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String[] viaRedirect = null;
        try {
            viaRedirect = latestFromRedirect(client, current);
        } catch (Exception ignored) {
        }
        if (viaRedirect != null) return viaRedirect;
        try {
            return latestFromApi(client, current);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String[] latestFromRedirect(HttpClient client, String current) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(RELEASES_URL))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "MineBerry-Essentials/" + current)
                .GET().build();
        HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
        int code = response.statusCode();
        if (code < 300 || code > 399) return null;                              // 404 = no releases yet
        String location = response.headers().firstValue("location").orElse(null);
        if (location == null) return null;
        String absolute = URI.create(RELEASES_URL).resolve(location).toString();
        int at = absolute.indexOf("/releases/tag/");
        if (!absolute.startsWith("https://github.com/") || at < 0) return null;
        String rawTag = absolute.substring(at + "/releases/tag/".length());
        String tag = URLDecoder.decode(rawTag, StandardCharsets.UTF_8);
        if (tag.isBlank()) return null;
        return new String[]{stripV(tag), absolute};
    }

    private static String[] latestFromApi(HttpClient client, String current) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                .timeout(Duration.ofSeconds(8))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MineBerry-Essentials/" + current)       // GitHub rejects requests without a User-Agent
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) return null;                          // 404 = no releases yet, 403 = rate limited
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String latest = stripV(json.get("tag_name").getAsString());
        String url = json.has("html_url") ? json.get("html_url").getAsString() : RELEASES_URL;
        if (!url.startsWith("https://github.com/")) url = RELEASES_URL;
        return new String[]{latest, url};
    }

    /** Chat fallback: only when the popup was not shown (e.g. the answer arrived after the player was already in a world). */
    private static void announce(Minecraft mc) {
        Result r = result;
        if (announced || popupShown || r == null || !r.updateAvailable() || mc.player == null) return;
        announced = true;
        Component link = Component.literal("[Download]").withStyle(style -> style
                .applyFormat(ChatFormatting.AQUA)
                .withUnderlined(true)
                .withClickEvent(new ClickEvent.OpenUrl(URI.create(r.url()))));
        mc.player.sendSystemMessage(Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("Update available: v" + r.latest() + " (you have v" + r.current() + ") ")
                        .withStyle(ChatFormatting.WHITE))
                .append(link));
    }

    // ---------------------------------------------------------------- versions

    private static String currentVersion() {
        try {
            var container = FabricLoader.getInstance().getModContainer(MOD_ID);
            if (container.isPresent()) return stripV(container.get().getMetadata().getVersion().getFriendlyString());
            for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
                if (mod.getMetadata().getId().contains("mineberry")) return stripV(mod.getMetadata().getVersion().getFriendlyString());
            }
        } catch (Exception ignored) {
        }
        return "0.0.0";
    }

    private static String stripV(String v) {
        v = v.trim();
        return v.startsWith("v") || v.startsWith("V") ? v.substring(1) : v;
    }

    /** True when {@code latest} is a higher version than {@code current}, comparing 1.2.0 style numbers part by part. */
    static boolean isNewer(String latest, String current) {
        int[] a = parts(latest), b = parts(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parts(String version) {
        String core = stripV(version).split("[-+]", 2)[0];                      // drop "-beta", "+build" suffixes
        String[] pieces = core.split("\\.");
        int[] out = new int[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            String digits = pieces[i].replaceAll("\\D.*$", "");
            out[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits.length() > 9 ? digits.substring(0, 9) : digits);
        }
        return out;
    }
}
