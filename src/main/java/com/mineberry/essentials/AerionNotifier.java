package com.mineberry.essentials;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;

/**
 * Aerion's HUD alerts: a small card at the top centre of the screen for things that matter ("update available",
 * "you reached your waypoint"). Up to three show at once, newest on top, and each fades out by itself.
 *
 * Anything in the mod can call {@link #push}. It does nothing while the Aerion Alerts module is switched off
 * (Control Center, or just tell Aerion "turn off alerts").
 */
public final class AerionNotifier {

    public enum Level {
        INFO(0xFFE65B93, "Aerion"), WARN(0xFFFF9B3D, "Aerion  -  heads up"), ALERT(0xFFFF5566, "Aerion  -  important");

        final int accent;
        final String title;

        Level(int accent, String title) {
            this.accent = accent;
            this.title = title;
        }
    }

    /** The module id (see AerionAlertsModule). */
    public static final String MODULE_ID = "aerion_alerts";

    private static final int MAX_SHOWN = 3, MAX_QUEUED = 8, WRAP_W = 230, MAX_LINES = 3;
    private static final long SHOW_MS = 7_000, SHOW_ALERT_MS = 14_000, FADE_MS = 600, DUPLICATE_MS = 20_000;

    private static final class Note {
        final Level level;
        final String text;
        final long born = Clock.ms();
        long shownAt;   // set when it is first drawn, so a note raised in a menu is not used up before you see it

        Note(Level level, String text) {
            this.level = level;
            this.text = text;
        }

        long duration() { return level == Level.ALERT ? SHOW_ALERT_MS : SHOW_MS; }
    }

    private static final List<Note> NOTES = new ArrayList<>();

    private AerionNotifier() {}

    public static void init() {
        HudElementRegistry.addLast(MineBerryEssentials.id("aerion_alerts"), (g, delta) -> draw(g));
    }

    /** True while alerts are switched on (and the Terms were accepted). */
    public static boolean enabled() {
        return Terms.accepted() && Config.isEnabled(MODULE_ID);
    }

    /** Shows an alert. Safe to call from any thread. The same text twice within 20 seconds shows once. */
    public static void push(Level level, String text) {
        if (text == null || text.isBlank() || !enabled()) return;
        long now = Clock.ms();
        synchronized (NOTES) {
            for (Note n : NOTES) if (n.text.equals(text) && now - n.born < DUPLICATE_MS) return;
            NOTES.add(0, new Note(level, text.strip()));
            while (NOTES.size() > MAX_QUEUED) NOTES.remove(NOTES.size() - 1);
        }
    }

    public static void info(String text) { push(Level.INFO, text); }

    public static void warn(String text) { push(Level.WARN, text); }

    public static void alert(String text) { push(Level.ALERT, text); }

    private static void draw(GuiGraphicsExtractor g) {
        if (!enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        long now = Clock.ms();
        var font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();

        List<Note> visible = new ArrayList<>();
        synchronized (NOTES) {
            NOTES.removeIf(n -> n.shownAt != 0 && now - n.shownAt > n.duration());
            for (Note n : NOTES) {
                if (visible.size() >= MAX_SHOWN) break;
                if (n.shownAt == 0) n.shownAt = now;
                visible.add(n);
            }
        }

        int y = 10;
        for (Note n : visible) {
            List<String> lines = AerionTalk.wrap(font, n.text, WRAP_W);
            if (lines.size() > MAX_LINES) lines = lines.subList(0, MAX_LINES);
            int textW = font.width(n.level.title);
            for (String s : lines) textW = Math.max(textW, font.width(s));
            int w = textW + 24, h = 20 + lines.size() * 10 + 4;
            int x = (screenW - w) / 2;

            long left = n.duration() - (now - n.shownAt);
            float a = left < FADE_MS ? Math.max(0.05f, left / (float) FADE_MS) : 1f;
            if (!Ui.toast(g, x, y, w, h, n.level.accent, a)) {
                g.fill(x - 1, y - 1, x + w + 1, y + h + 1, fade(0x66000000, a));
                g.fill(x, y, x + w, y + h, fade(0xE0241024, a));
                g.fill(x, y, x + 4, y + h, fade(n.level.accent, a));
            }
            g.text(font, n.level.title, x + 12, y + 6, fade(0xFFFF8FBF, a), true);
            int ty = y + 19;
            for (String s : lines) { g.text(font, s, x + 12, ty, fade(0xFFFFFFFF, a), false); ty += 10; }
            y += h + 4;
        }
    }

    /** The colour with its alpha multiplied by a (0..1). */
    private static int fade(int argb, float a) {
        int alpha = Math.round(((argb >>> 24) & 255) * a);
        return (alpha << 24) | (argb & 0xFFFFFF);
    }
}
