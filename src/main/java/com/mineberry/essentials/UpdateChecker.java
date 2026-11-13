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
    private static final String MOD_ID = "mineberry_essentials";

    /** Outcome of a finished check. */
    public record Result(String current, String latest, String url, boolean updateAvailable) {}

    private static final AtomicBoolean started = new AtomicBoolean(false);
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

    /** Starts the background check (once). Does nothing when disabled in the config. */
    public static void check() {
        if (!Config.checkForUpdates() || !started.compareAndSet(false, true)) return;
        String current = currentVersion();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(API_URL))
                    .timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "MineBerry-Essentials/" + current)   // GitHub rejects requests without a User-Agent
                    .GET().build();
            client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> handle(response, current))
                    .exceptionally(error -> null);                              // offline etc.: stay silent
        } catch (Exception ignored) {
            // never let an update check break the game
        }
    }

    private static void handle(HttpResponse<String> response, String current) {
        if (response.statusCode() != 200) return;                               // 404 = no releases yet, 403 = rate limited
        try {
            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String latest = stripV(json.get("tag_name").getAsString());
            String url = json.has("html_url") ? json.get("html_url").getAsString() : RELEASES_URL;
            if (!url.startsWith("https://github.com/")) url = RELEASES_URL;
            result = new Result(current, latest, url, isNewer(latest, current));
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> announce(mc));                                     // already in a world when the answer arrives
        } catch (Exception ignored) {
        }
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
