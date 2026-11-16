package com.mineberry.essentials;

import com.mineberry.essentials.modules.RadialMenuModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The pie menu that opens while the radial key is held (see RadialMenuModule). It is centred on the screen, which is
 * where the crosshair is, and Minecraft puts the mouse cursor there when a screen opens, so a flick in any direction
 * picks a slice. The slice is chosen by angle only, so you don't have to reach the ring. Nothing is drawn with
 * images: the pie is painted row by row with plain fills, in the colours of the selected panel theme.
 */
public class RadialMenuScreen extends Screen {

    private static final float R_IN = 36f, R_OUT = 96f, GROW = 7f;
    private static final double DEAD_ZONE = 14.0, GAP_PX = 2.0;
    private static final long ANIM_MS = 150;

    private final List<Config.RadialSlice> slices = Config.radialSlices();
    private final long openedAt = Clock.ms();
    private int hovered = -1;
    private boolean done;
    private float scale = 1f;

    public RadialMenuScreen() {
        super(Component.literal("Radial Menu"));
    }

    @Override
    protected void init() {
        Ui.applyTheme();
        // small windows: shrink the pie so it always fits
        float fit = (Math.min(this.width, this.height) / 2f - 16f) / (R_OUT + GROW);
        scale = Math.max(0.45f, Math.min(1f, fit));
        this.addRenderableOnly((g, mx, my, delta) -> draw(g, mx, my));
    }

    /** Pie menus never pause a singleplayer game. */
    public boolean isPauseScreen() {
        return false;
    }

    /** Called by the module when the radial key is let go: runs the highlighted slice, if any, and closes. */
    public void release() {
        if (done) return;
        done = true;
        Minecraft mc = this.minecraft != null ? this.minecraft : Minecraft.getInstance();
        Config.RadialSlice chosen = hovered >= 0 && hovered < slices.size() ? slices.get(hovered) : null;
        mc.gui.setScreen(null);
        if (chosen != null) RadialMenuModule.run(mc, chosen);
    }

    // ---------------------------------------------------------------- geometry

    /** Which slice the direction (dx, dy) points at. Slice 0 is centred straight up, the rest follow clockwise. */
    private static int sectorOf(double dx, double dy, int n) {
        double sector = 2 * Math.PI / n;
        double a = Math.atan2(dx, -dy) + sector / 2;
        a = ((a % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
        return Math.min(n - 1, (int) (a / sector));
    }

    /** The slice a pixel (relative to the centre) belongs to, or -1 when it is outside the ring or in a gap. */
    private int cell(double dx, double dy, int n, float rIn, float rOut, float grow) {
        double dist = Math.sqrt(dx * dx + dy * dy);
        if (dist < rIn || dist > rOut + grow) return -1;
        double sector = 2 * Math.PI / n;
        double a = Math.atan2(dx, -dy) + sector / 2;
        a = ((a % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
        int s = Math.min(n - 1, (int) (a / sector));
        if (dist > rOut + (s == hovered ? grow : 0f)) return -1;
        if (n > 1) {
            double frac = a - s * sector;
            if (Math.min(frac, sector - frac) * dist < GAP_PX / 2.0) return -1;   // the gap between two slices
        }
        return s;
    }

    private static int alpha(int color, int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (color & 0x00FFFFFF);
    }

    private static void disk(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int oy = -r; oy <= r; oy++) {
            int half = (int) Math.round(Math.sqrt((double) r * r - (double) oy * oy));
            Ui.fill(g, cx - half, cy + oy, cx + half, cy + oy + 1, color);
        }
    }

    // ---------------------------------------------------------------- drawing

    private void draw(GuiGraphicsExtractor g, int mx, int my) {
        float t = Math.min(1f, (Clock.ms() - openedAt) / (float) ANIM_MS);
        float ease = 1f - (1f - t) * (1f - t) * (1f - t);        // fast start, soft landing
        int cx = this.width / 2, cy = this.height / 2;
        int n = slices.size();
        float open = 0.65f + 0.35f * ease;
        float rIn = R_IN * scale * open, rOut = R_OUT * scale * open, grow = GROW * scale * open;

        Ui.fill(g, 0, 0, this.width, this.height, alpha(0x000000, (int) (0x40 * ease)));

        double dx = mx - cx, dy = my - cy;
        hovered = n > 0 && Math.sqrt(dx * dx + dy * dy) >= DEAD_ZONE ? sectorOf(dx, dy, n) : -1;

        // slice colours: two close tones so neighbours read apart, the hovered one in the accent colour
        int[] colors = new int[n];
        for (int i = 0; i < n; i++) {
            int base = i % 2 == 0 ? Ui.BTN : Ui.lerp(Ui.BTN, 0xFFFFFFFF, 0.05f);
            colors[i] = i == hovered ? alpha(Ui.lerp(Ui.BTN, Ui.ACCENT, 0.6f), (int) (0xF0 * ease))
                                     : alpha(base, (int) (0xD2 * ease));
        }

        // the pie: every pixel row is scanned once and runs of the same slice become one fill
        int reach = (int) Math.ceil(rOut + grow) + 1;
        for (int oy = -reach; oy <= reach; oy++) {
            int start = 0, current = -1;
            for (int ox = -reach; ox <= reach + 1; ox++) {
                int s = ox <= reach ? cell(ox + 0.5, oy + 0.5, n, rIn, rOut, grow) : -1;
                if (s == current) continue;
                if (current >= 0) Ui.fill(g, cx + start, cy + oy, cx + ox, cy + oy + 1, colors[current]);
                current = s;
                start = ox;
            }
        }

        // centre disk with a crosshair mark
        int rc = Math.max(8, (int) (rIn - 6f));
        disk(g, cx, cy, rc + 1, alpha(hovered >= 0 ? Ui.ACCENT : Ui.BTN_BORDER_HOVER, (int) (0xFF * ease)));
        disk(g, cx, cy, rc, alpha(Ui.BG, (int) (0xF0 * ease)));
        int textColor = alpha(Ui.TEXT, (int) (0xFF * ease));
        Ui.fill(g, cx - 3, cy, cx + 4, cy + 1, textColor);
        Ui.fill(g, cx, cy - 3, cx + 1, cy + 4, textColor);

        // labels
        float rMid = (rIn + rOut) / 2f;
        for (int i = 0; i < n; i++) {
            double mid = i * 2 * Math.PI / n;
            float r = rMid + (i == hovered ? grow / 2f : 0f);
            int lx = cx + (int) Math.round(Math.sin(mid) * r);
            int ly = cy - (int) Math.round(Math.cos(mid) * r);
            int maxW = Math.max(26, Math.min(74, (int) (2 * Math.PI * r / n) - 6));

            Config.RadialSlice s = slices.get(i);
            String label = this.font.plainSubstrByWidth(s.displayLabel(), maxW);
            String sub = this.font.plainSubstrByWidth(shown(s), maxW);
            boolean on = i == hovered;
            Ui.txt(g, this.font, label, lx - this.font.width(label) / 2, ly - 9, on ? alpha(0xFFFFFF, (int) (0xFF * ease)) : textColor, true);
            Ui.txt(g, this.font, sub, lx - this.font.width(sub) / 2, ly + 2,
                    alpha(on ? Ui.TEXT : Ui.MUTED, (int) (0xFF * ease)), false);
        }

        // hint under the pie
        String hint;
        if (n == 0) hint = "No commands set - open the Radial Menu settings";
        else if (hovered < 0) hint = "Move to a slice, then let go of the key";
        else hint = (slices.get(hovered).command.contains("<") ? "Let go to type " : "Let go to run ") + shown(slices.get(hovered));
        int hintY = Math.min(this.height - 14, cy + (int) (rOut + grow) + 10);
        Ui.txt(g, this.font, hint, cx - this.font.width(hint) / 2, hintY, alpha(Ui.TEXT, (int) (0xE0 * ease)), true);
    }

    /** "/home", or "/tpa ..." for a command that still needs an argument. */
    private static String shown(Config.RadialSlice s) {
        String c = Config.cleanCommand(s.command);
        int cut = c.indexOf('<');
        return cut >= 0 ? "/" + c.substring(0, cut).trim() + " ..." : "/" + c;
    }
}
