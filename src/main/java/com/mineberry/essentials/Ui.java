package com.mineberry.essentials;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;

import java.util.List;
import java.util.Map;

/**
 * Shared look of the MineBerry screens. The default is dark flat surfaces with an orange accent and one thin outline;
 * Aurora, Ember and Midnight are colour variants of it. Liquid Glass is the optional glassy one: frosted translucent
 * panels, a bright specular edge along the top, soft white outlines and ice-blue switches. Everything is plain fills,
 * so screens open and close instantly.
 */
public final class Ui {
    static int DIM = 0x70000000;   // lighter in Liquid Glass so the blurred world shows through the panels

    // Palette (set by applyTheme)
    static int BG, HEADER, BORDER, ACCENT, TEXT, MUTED, STRONG;
    /** True while the Liquid Glass theme is selected: translucent surfaces plus the glass highlights. */
    static boolean GLASS;
    // Derived
    static int DIVIDER, INSET, FOOT, BTN, BTN_HOVER, BTN_BORDER, BTN_BORDER_HOVER, TRACK_ON, TRACK_ON_EDGE, TRACK_OFF;

    /** A module card: a wide clickable button drawn as "name ........ [toggle]" (+ room for a small side button). */
    record Card(String name, int fullW, boolean gear) {}

    private static final int TOGGLE_W = 26, TOGGLE_H = 12;

    private Ui() {}

    static void applyTheme() {
        GLASS = false;
        DIM = 0x70000000;
        switch (Config.panelTheme()) {
            case "glass" -> {
                // Liquid Glass. The panel is 80% opaque. Its RGB is also what the screens use to hide the vanilla buttons
                // (BG | 0xFF000000), so it is the colour the panel looks like over a dimmed world.
                set(0xA81A2232, 0xB0394A6A, 0x70FFFFFF, 0xFF8EC5FF, 0xFFF6F9FF, 0xFFB9C3D6, 0xFF6FB0FF);
                GLASS = true;
                DIM = 0x44000000;
            }
            case "aurora" -> set(0xF50B1317, 0xFF11242B, 0xFF2FA9BA, 0xFF9BE6F2, 0xFFEFFAFC, 0xFF86A5AD, 0xFF2FA9BA);
            case "ember" -> set(0xF5130D09, 0xFF251810, 0xFFE08A2E, 0xFFFFC98A, 0xFFFFF4E8, 0xFFAB9580, 0xFFE08A2E);
            case "midnight" -> set(0xF5090B14, 0xFF141934, 0xFF5C7CF0, 0xFFB3C4FF, 0xFFF0F3FF, 0xFF8A91B0, 0xFF5C7CF0);
            // default (config id "default"): neutral dark surfaces with an orange accent
            default -> set(0xF2111111, 0xFF171717, 0xFF2D2D2D, 0xFFE8943A, 0xFFE8E8E8, 0xFF8E8E8E, 0xFFD9822B);
        }
        if (GLASS) {
            DIVIDER = 0x26FFFFFF;
            INSET = 0x59000000;
            FOOT = 0x33FFFFFF;
            BTN = 0x1FFFFFFF;
            BTN_HOVER = 0x3C9CCBFF;   // hovering lights the glass ice blue
            BTN_BORDER = 0x48FFFFFF;
            BTN_BORDER_HOVER = 0xB0C4E2FF;
            TRACK_ON = 0xD84C9BFF;
            TRACK_ON_EDGE = 0xFFB4D8FF;
            TRACK_OFF = 0x40FFFFFF;
            return;
        }
        int solid = BG | 0xFF000000;
        DIVIDER = lerp(solid, TEXT, 0.08f);
        INSET = lerp(solid, 0xFF000000, 0.35f);
        FOOT = lerp(solid, BORDER, 0.375f);
        BTN = lerp(solid, 0xFFFFFFFF, 0.06f);
        BTN_HOVER = lerp(solid, 0xFFFFFFFF, 0.12f);
        BTN_BORDER = lerp(BTN, BORDER, 0.30f);
        BTN_BORDER_HOVER = lerp(BTN, BORDER, 0.80f);
        TRACK_ON = lerp(BORDER, ACCENT, 0.45f);
        TRACK_ON_EDGE = lerp(BORDER, ACCENT, 0.70f);
        TRACK_OFF = lerp(solid, 0xFFFFFFFF, 0.14f);
    }

    private static void set(int bg, int header, int border, int accent, int text, int muted, int strong) {
        BG = bg; HEADER = header; BORDER = border; ACCENT = accent; TEXT = text; MUTED = muted; STRONG = strong;
    }

    static String themeName() {
        return switch (Config.panelTheme()) {
            case "glass" -> "Liquid Glass";
            case "aurora" -> "Aurora";
            case "ember" -> "Ember";
            case "midnight" -> "Midnight";
            default -> "Default";
        };
    }

    static String nextTheme() {
        return switch (Config.panelTheme()) {
            case "default" -> "glass";
            case "glass" -> "aurora";
            case "aurora" -> "ember";
            case "ember" -> "midnight";
            default -> "default";
        };
    }

    // ---------------------------------------------------------------- primitives

    static void fill(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        if ((color >>> 24) == 0) return;
        g.fill(x1, y1, x2, y2, color);
    }

    static void txt(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color, boolean shadow) {
        if ((color >>> 24) < 4) return;   // vanilla draws text with alpha < 4 as fully opaque
        g.text(font, text, x, y, color, shadow);
    }

    /**
     * Rounded box: 1px border, corners cut in three steps. Pieces never overlap, so translucent fills stay clean.
     * With headerH > 0 the first headerH rows inside the border use headerFill.
     */
    static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int border, int fill, int headerFill, int headerH) {
        if (GLASS && w >= 8 && h >= 8) {   // real rounded corners, a lit top edge and a faint bottom shade
            roundBox(g, x, y, w, h, h >= 14 ? 3 : 2, border, fill, headerH, headerFill);
            fill(g, x + 3, y + 1, x + w - 3, y + 2, 0x40FFFFFF);
            if (h >= 14) fill(g, x + 3, y + h - 2, x + w - 3, y + h - 1, 0x1C000000);
            return;
        }
        int x2 = x + w, y2 = y + h;
        // border: top / bottom lines, the two corner steps, left / right sides
        fill(g, x + 3, y, x2 - 3, y + 1, border);
        fill(g, x + 3, y2 - 1, x2 - 3, y2, border);
        fill(g, x + 1, y + 1, x + 3, y + 2, border);
        fill(g, x2 - 3, y + 1, x2 - 1, y + 2, border);
        fill(g, x + 1, y2 - 2, x + 3, y2 - 1, border);
        fill(g, x2 - 3, y2 - 2, x2 - 1, y2 - 1, border);
        fill(g, x, y + 2, x + 1, y2 - 2, border);
        fill(g, x2 - 1, y + 2, x2, y2 - 2, border);
        // interior
        int bodyTop = y + 1;
        if (headerH > 0) {
            int hb = y + 1 + headerH;
            fill(g, x + 3, y + 1, x2 - 3, y + 2, headerFill);
            fill(g, x + 1, y + 2, x2 - 1, hb, headerFill);
            bodyTop = hb;
            fill(g, x + 1, bodyTop, x2 - 1, y2 - 2, fill);
        } else {
            fill(g, x + 3, y + 1, x2 - 3, y + 2, fill);
            fill(g, x + 1, y + 2, x2 - 1, y2 - 2, fill);
        }
        fill(g, x + 3, y2 - 2, x2 - 3, y2 - 1, fill);
        if (GLASS && h >= 14 && w >= 14) {   // the specular edge: a bright line just under the top of the glass
            fill(g, x + 3, y + 1, x2 - 3, y + 2, 0x38FFFFFF);
        }
    }

    /** Square box: 1px border, flat fill. Pieces never overlap, so translucent fills stay clean. */
    static void rect(GuiGraphicsExtractor g, int x, int y, int w, int h, int border, int fill) {
        int x2 = x + w, y2 = y + h;
        fill(g, x, y, x2, y + 1, border);
        fill(g, x, y2 - 1, x2, y2, border);
        fill(g, x, y + 1, x + 1, y2 - 1, border);
        fill(g, x2 - 1, y + 1, x2, y2 - 1, border);
        fill(g, x + 1, y + 1, x2 - 1, y2 - 1, fill);
    }

    /** Window frame: rounded outline, flat body, lighter header strip (no line under it). */
    static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int headerH) {
        if (GLASS) {
            glassFrame(g, x, y, w, h, headerH);
            return;
        }
        box(g, x, y, w, h, BORDER, BG, HEADER, headerH);
    }

    /**
     * In Liquid Glass the vanilla buttons are made almost invisible instead of being painted over with a solid patch, so
     * the translucent glass drawn on top really is see-through. Other themes keep the vanilla button at full strength.
     */
    static void ghost(AbstractWidget w) {
        w.setAlpha(GLASS ? 0.04f : 1f);
    }

    /**
     * Ghosts every vanilla button of the screen. Screens draw this first each frame, BEFORE the widgets, so a freshly built
     * button (screen just opened, page or tab changed) never gets drawn at full strength for one frame. That one frame was
     * the flicker in Liquid Glass: the glass layer that ghosts the buttons is drawn after them.
     */
    static void ghostAll(net.minecraft.client.gui.screens.Screen screen) {
        for (var child : screen.children()) if (child instanceof Button b) ghost(b);
    }

    /** Hides a vanilla button with a solid patch (flat themes only; Liquid Glass ghosts the button instead). */
    static void cover(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int solid) {
        if (!GLASS) fill(g, x1, y1, x2, y2, solid);
    }

    /** A window without a header strip (the Control Center): the glass frame in Liquid Glass, the flat square panel otherwise. */
    static void window(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (GLASS) {
            glassFrame(g, x, y, w, h, 0);
            return;
        }
        rect(g, x, y, w, h, BORDER, BG);
    }

    private static void glassFrame(GuiGraphicsExtractor g, int x, int y, int w, int h, int headerH) {
        glassShadow(g, x, y, w, h);
        roundBox(g, x, y, w, h, GLASS_R, BORDER, BG, headerH, HEADER);
        glassDepth(g, x, y, w, h, GLASS_R, headerH);
        glassSheen(g, x, y, w, h, GLASS_R);
    }

    /**
     * Gives the flat tint some depth: the body darkens and cools towards the bottom (the glass is thicker there), a faint
     * second rim sits just inside the outline, and the header gets a soft line under it so it reads as its own pane.
     */
    private static int bandAlpha(int i, int bands) {
        float t = i / (float) (bands - 1);
        return Math.round(0x3A * t * t + 0x04);   // eases in: mostly at the bottom
    }

    private static void glassDepth(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int headerH) {
        int x2 = x + w, y2 = y + h;
        int top = headerH > 0 ? y + 2 + headerH : y + r, bottom = y2 - r;
        int span = bottom - top;
        if (span > 12) {
            int bands = 12;
            int last = 0;
            for (int i = 0; i < bands; i++) {
                int b0 = top + span * i / bands, b1 = top + span * (i + 1) / bands;
                last = bandAlpha(i, bands);
                fill(g, x + 1, b0, x2 - 1, b1, (last << 24) | 0x0A1020);
            }
            for (int i = r - 1; i >= 1; i--) {   // carry the last shade down through the rounded bottom corners, so no lighter strip is left
                int xa = Math.max(cornerInset(i, r) + 1, cornerInset(i - 1, r));
                fill(g, x + xa, y2 - 1 - i, x2 - xa, y2 - i, (last << 24) | 0x0A1020);
            }
        }
        fill(g, x + 2, y2 - 2, x2 - 2, y2 - 1, 0x14FFFFFF);                  // inner lower rim
        fill(g, x + 1, y + r, x + 2, y2 - r, 0x0CFFFFFF);                    // inner side rims
        fill(g, x2 - 2, y + r, x2 - 1, y2 - r, 0x0CFFFFFF);
        if (headerH > 0) {
            int hy = y + 1 + headerH;
            fill(g, x + 1, hy, x2 - 1, hy + 1, 0x2EFFFFFF);                  // light line under the header
            fill(g, x + 1, hy + 1, x2 - 1, hy + 3, 0x18000000);              // and a soft shadow under that
        }
    }

    /** Two faint rings just outside the window so it floats above the world. */
    private static void glassShadow(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        int x2 = x + w, y2 = y + h;
        fill(g, x + 4, y - 1, x2 - 4, y, 0x26000000);
        fill(g, x + 4, y2, x2 - 4, y2 + 1, 0x36000000);
        fill(g, x - 1, y + 4, x, y2 - 4, 0x26000000);
        fill(g, x2, y + 4, x2 + 1, y2 - 4, 0x26000000);
        fill(g, x + 5, y - 2, x2 - 5, y - 1, 0x12000000);
        fill(g, x + 5, y2 + 1, x2 - 5, y2 + 2, 0x1C000000);
        fill(g, x - 2, y + 5, x - 1, y2 - 5, 0x12000000);
        fill(g, x2 + 1, y + 5, x2 + 2, y2 - 5, 0x12000000);
        for (int i = 0; i < 4; i++) {                                        // the shadow falls a little downwards
            int a = 0x0E - i * 3;
            if (a <= 0) break;
            fill(g, x + 7 + i, y2 + 2 + i, x2 - 7 - i, y2 + 3 + i, a << 24);
        }
    }

    /** Text field / checkbox box. focused (or checked) lights the outline. */
    static void field(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean focused) {
        if (GLASS) {
            roundBox(g, x, y, w, h, 3, focused ? STRONG : BTN_BORDER, INSET, 0, 0);
            if (w >= 8 && h >= 8) fill(g, x + 2, y + 1, x + w - 2, y + 2, 0x30000000);   // inset: the top edge is in shadow
            return;
        }
        rect(g, x, y, w, h, focused ? STRONG : BORDER, INSET);
    }

    /**
     * Button / tab / card surface of the Control Center. Draw it over a solid patch that hides the vanilla button.
     * hoverA and idleA are how much white the flat themes mix into the patch.
     */
    static void surface(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean hover, float hoverA, float idleA) {
        if (GLASS) {
            roundBox(g, x, y, w, h, 3, hover ? BTN_BORDER_HOVER : BTN_BORDER, hover ? BTN_HOVER : BTN, 0, 0);
            if (w >= 8 && h >= 8) fill(g, x + 2, y + 1, x + w - 2, y + 2, hover ? 0x59FFFFFF : 0x33FFFFFF);
            return;
        }
        int solid = BG | 0xFF000000;
        rect(g, x, y, w, h, hover ? lerp(BORDER, 0xFFFFFFFF, 0.25f) : BORDER, lerp(solid, 0xFFFFFFFF, hover ? hoverA : idleA));
    }

    /** The selected tab: accent filled in the flat themes, a lit blue glass pill in Liquid Glass. */
    static void activeTab(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (GLASS) {
            roundBox(g, x, y, w, h, 3, TRACK_ON_EDGE, TRACK_ON, 0, 0);
            if (w >= 8 && h >= 8) fill(g, x + 2, y + 1, x + w - 2, y + 2, 0x80FFFFFF);
            return;
        }
        fill(g, x, y, x + w, y + h / 2, STRONG);
        fill(g, x, y + h / 2, x + w, y + h, lerp(STRONG, 0xFF000000, 0.18f));
    }

    /** The 2px accent line on top of an enabled card (kept clear of the rounded corners in glass). */
    static void accentLine(GuiGraphicsExtractor g, int x, int y, int w) {
        if (GLASS) fill(g, x + 4, y, x + w - 4, y + 2, STRONG);
        else fill(g, x, y, x + w, y + 2, STRONG);
    }

    /** Small coloured status pill (the green Enabled / Start chat); translucent in Liquid Glass. */
    static void pill(GuiGraphicsExtractor g, int x, int y, int w, int h, int edge, int fill) {
        if (GLASS) {
            roundBox(g, x, y, w, h, 3, (0xB0 << 24) | (edge & 0xFFFFFF), (0x66 << 24) | (fill & 0xFFFFFF), 0, 0);
            if (w >= 8 && h >= 8) fill(g, x + 2, y + 1, x + w - 2, y + 2, 0x40FFFFFF);
            return;
        }
        rect(g, x, y, w, h, edge, fill);
    }

    /**
     * The Enabled / Disabled badge on a module card. Flat themes keep the green box with plain text for Disabled; Liquid Glass
     * draws a badge for both states (lit green with a dot when on, a dim outlined one when off) so they look like one control.
     */
    static void statusPill(GuiGraphicsExtractor g, Font font, int x, int y, int w, int h, boolean on, int edge, int fill, int onText) {
        String label = on ? "Enabled" : "Disabled";
        int ty = y + (h - 8) / 2 + 1;
        if (!GLASS) {
            if (on) rect(g, x, y, w, h, edge, fill);
            txt(g, font, label, x + (w - font.width(label)) / 2, ty, on ? onText : MUTED, false);
            return;
        }
        roundBox(g, x, y, w, h, 3, on ? 0xB045C76B : 0x33FFFFFF, on ? 0x5A2E9E4F : 0x14FFFFFF, 0, 0);
        if (w >= 8 && h >= 8) fill(g, x + 2, y + 1, x + w - 2, y + 2, on ? 0x4DFFFFFF : 0x1FFFFFFF);
        int total = 4 + 5 + font.width(label);
        int sx = x + (w - total) / 2, dotY = y + (h - 4) / 2;
        fill(g, sx, dotY, sx + 4, dotY + 4, on ? 0xFF6EE08C : 0xFF7A8499);
        txt(g, font, label, sx + 9, ty, on ? onText : MUTED, false);
    }

    /** Fill for a selected chip: the plain border colour in the flat themes (opaque), lit glass blue in Liquid Glass. */
    static int chipOn() {
        return GLASS ? TRACK_ON : BORDER;
    }

    /** Fill for the primary (accent) button. */
    static int primary(boolean hover) {
        if (GLASS) return hover ? 0xE05FA8FF : TRACK_ON;
        return hover ? ACCENT : BORDER;
    }

    private static final int GLASS_R = 6;

    /** Config check that works even before applyTheme has run (HUD notices are drawn from modules). */
    static boolean glassOn() {
        return "glass".equals(Config.panelTheme());
    }

    private static int cornerInset(int i, int r) {
        double dy = r - i - 0.5;
        return r - (int) Math.round(Math.sqrt(r * r - dy * dy));
    }

    private static void shaded(GuiGraphicsExtractor g, int xa, int ya, int xb, int yb, int fill, int headerEnd, int headerFill) {
        if (xb <= xa || yb <= ya) return;
        if (headerEnd > ya) {
            int mid = Math.min(headerEnd, yb);
            fill(g, xa, ya, xb, mid, headerFill);
            if (mid < yb) fill(g, xa, mid, xb, yb, fill);
        } else {
            fill(g, xa, ya, xb, yb, fill);
        }
    }

    /** Rounded box with a circular-ish corner of radius r. Pieces never overlap, so translucent fills stay clean. */
    static void roundBox(GuiGraphicsExtractor g, int x, int y, int w, int h, int r, int border, int fill, int headerH, int headerFill) {
        int x2 = x + w, y2 = y + h;
        r = Math.max(1, Math.min(r, Math.min(w, h) / 2));
        int headerEnd = headerH > 0 ? y + 1 + headerH : Integer.MIN_VALUE;
        for (int i = 0; i < r; i++) {
            int a = cornerInset(i, r);
            int b = Math.max(a + 1, i == 0 ? 0 : cornerInset(i - 1, r));
            for (int side = 0; side < 2; side++) {
                int ry = side == 0 ? y + i : y2 - 1 - i;
                if (i == 0) {
                    fill(g, x + a, ry, x2 - a, ry + 1, border);
                    continue;
                }
                fill(g, x + a, ry, x + b, ry + 1, border);
                fill(g, x2 - b, ry, x2 - a, ry + 1, border);
                shaded(g, x + b, ry, x2 - b, ry + 1, fill, headerEnd, headerFill);
            }
        }
        fill(g, x, y + r, x + 1, y2 - r, border);
        fill(g, x2 - 1, y + r, x2, y2 - r, border);
        shaded(g, x + 1, y + r, x2 - 1, y2 - r, fill, headerEnd, headerFill);
    }

    private static int scaleAlpha(int argb, float a) {
        int alpha = Math.round((argb >>> 24) * Math.max(0f, Math.min(1f, a)));
        return (alpha << 24) | (argb & 0xFFFFFF);
    }

    /** Small frosted glass panel for the HUD and notices: lighter than a window, same bright rim. */
    static void glassPanel(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int border) {
        int r = Math.min(4, Math.min(w, h) / 2);
        roundBox(g, x, y, w, h, r, border, fill, 0, 0);
        if (w < 10 || h < 10) return;
        int x2 = x + w, y2 = y + h, in0 = cornerInset(0, r);
        fill(g, x + in0, y, x2 - in0, y + 1, 0x99FFFFFF);                   // bright top rim
        for (int i = 0; i < 4; i++) {                                       // short sheen under it
            int alpha = 0x18 - i * 5;
            if (alpha <= 0 || y + 2 + i * 2 + 2 >= y2 - 2) break;
            fill(g, x + 1, y + 2 + i * 2, x2 - 1, y + 4 + i * 2, (alpha << 24) | 0xFFFFFF);
        }
        fill(g, x + in0, y2 - 1, x2 - in0, y2, 0x40FFFFFF);                 // faint lower rim
    }

    /** HUD box: a glass panel in the Liquid Glass theme, the usual dark box with an accent edge otherwise. */
    static void hudPanel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        if (GLASS) {
            glassPanel(g, x, y, w, h, 0x70141C28, 0x66FFFFFF);
            return;
        }
        fill(g, x, y, x + w, y + h, 0x90000000);
        fill(g, x, y, x + 1, y + h, ACCENT);
    }

    /** Key / mouse button box of the keystrokes HUD. */
    static void keyBox(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean down) {
        if (glassOn()) {
            int edge = down ? ACCENT : 0x80FFFFFF;
            int body = down ? ((ACCENT & 0xFFFFFF) | 0x99000000) : 0x38FFFFFF;
            roundBox(g, x, y, w, h, 2, edge, body, 0, 0);
            if (w >= 8 && h >= 8) fill(g, x + 2, y + 1, x + w - 2, y + 2, 0x66FFFFFF);
            return;
        }
        rect(g, x, y, w, h, down ? ACCENT : 0x66FFFFFF, down ? ((ACCENT & 0xFFFFFF) | 0x99000000) : 0x40000000);
    }

    /**
     * Notice box (airdrop, evidence, safe logout, screenshot, Aerion). In the Liquid Glass theme it draws a frosted panel
     * tinted with the notice colour and returns true; otherwise it draws nothing and returns false so the caller keeps its own look.
     * alpha fades the whole thing (1 = fully visible).
     */
    public static boolean toast(GuiGraphicsExtractor g, int x, int y, int w, int h, int accent, float alpha) {
        if (!glassOn()) return false;
        int dark = 0xB0121A26;
        int tint = lerp(dark, (0xB0 << 24) | (accent & 0xFFFFFF), 0.20f);
        glassPanel(g, x, y, w, h, scaleAlpha(tint, alpha), scaleAlpha(0x80FFFFFF, alpha));
        fill(g, x + 3, y + 5, x + 5, y + h - 5, scaleAlpha(accent, alpha));   // accent pill on the left
        return true;
    }

    /**
     * The liquid part of Liquid Glass, painted over a window frame: a bright rim that is strongest at the top corners,
     * soft side rims fading downwards, a sheen fading out over the first rows, a faint glare in the top-left
     * corner and a soft glow along the bottom. Plain translucent white fills, no shaders.
     */
    private static void glassSheen(GuiGraphicsExtractor g, int x, int y, int w, int h, int r) {
        int x2 = x + w, y2 = y + h, in0 = cornerInset(0, r);
        fill(g, x + in0, y, x2 - in0, y + 1, 0xCCFFFFFF);                  // bright top rim
        for (int i = 1; i < r; i++) {                                      // rim light on the rounded top corners
            int a = cornerInset(i, r), b = Math.max(a + 1, cornerInset(i - 1, r));
            fill(g, x + a, y + i, x + b, y + i + 1, 0x80FFFFFF);
            fill(g, x2 - b, y + i, x2 - a, y + i + 1, 0x80FFFFFF);
        }
        int sideTop = y + r, sideH = Math.max(0, h - r * 2);
        for (int i = 0; i < 4 && sideH > 0; i++) {                         // side rims fading downwards
            int s0 = sideTop + sideH * i / 4, s1 = sideTop + sideH * (i + 1) / 4;
            int alpha = 0x5A - i * 0x16;
            fill(g, x, s0, x + 1, s1, (alpha << 24) | 0xFFFFFF);
            fill(g, x2 - 1, s0, x2, s1, (alpha << 24) | 0xFFFFFF);
        }
        fill(g, x + in0, y + 2, x2 - in0, y + 3, 0x2EFFFFFF);              // specular line under the rim
        for (int i = 0; i < 10; i++) {                                     // sheen fading downwards
            int alpha = 0x1A - i * 2;
            if (alpha <= 0) break;
            int top = y + 3 + i * 2;
            if (top + 2 >= y2 - 2) break;
            fill(g, x + 1, top, x2 - 1, top + 2, (alpha << 24) | 0xFFFFFF);
        }
        int glare = Math.min(26, h / 4);                                   // faint glare in the top-left corner
        for (int i = 0; i < glare; i++) {
            int len = Math.min(w / 3, (glare - i) * 3);
            if (len <= 0) break;
            fill(g, x + 2, y + 4 + i, x + 2 + len, y + 5 + i, 0x0AFFFFFF);
        }
        fill(g, x + in0, y2 - 1, x2 - in0, y2, 0x66FFFFFF);                // soft lower rim
        fill(g, x + 1, y2 - 3, x2 - 1, y2 - 2, 0x16FFFFFF);                // glow just above it
    }

    /** Header text on the right edge, muted. */
    static void headerRight(GuiGraphicsExtractor g, Font font, int right, int y, String text) {
        txt(g, font, text, right - font.width(text), y, MUTED, false);
    }

    /** Dim line above the footer buttons. */
    static void footerLine(GuiGraphicsExtractor g, int x1, int x2, int y) {
        fill(g, x1, y, x2, y + 1, FOOT);
    }

    /** Dark rounded field behind an EditBox (the EditBox itself is drawn without its own border). */
    static void inputBox(GuiGraphicsExtractor g, EditBox box) {
        int x = box.getX() - 4, y = box.getY() - 6, w = box.getWidth() + 8, h = 20;
        if (GLASS) {
            field(g, x, y, w, h, box.isFocused());
            if (box.isFocused()) fill(g, x + 3, y + h - 1, x + w - 3, y + h, 0x886FB0FF);   // lit lower edge while typing
            return;
        }
        box(g, x, y, w, h, box.isFocused() ? BTN_BORDER_HOVER : BTN_BORDER, INSET, 0, 0);
    }

    /** A glass button: rounded, lit along the top, with a faint shade along the bottom. Same look for cards and buttons. */
    private static void glassButton(GuiGraphicsExtractor g, int x, int y, int w, int h, int border, int fill, boolean hover) {
        roundBox(g, x, y, w, h, 3, border, fill, 0, 0);
        if (w < 8 || h < 8) return;
        fill(g, x + 2, y + 1, x + w - 2, y + 2, hover ? 0x70FFFFFF : 0x40FFFFFF);          // top highlight
        fill(g, x + 1, y + 2, x + w - 1, y + 2 + Math.max(1, h / 3), hover ? 0x14FFFFFF : 0x0DFFFFFF);   // gloss on the upper third
        fill(g, x + 2, y + h - 2, x + w - 2, y + h - 1, 0x22000000);                       // bottom shade
    }

    // ---------------------------------------------------------------- toggle + cards

    /** Small pill switch: knob on the left when off, on the right (accent track) when on. */
    static void toggle(GuiGraphicsExtractor g, int x, int y, boolean on) {
        if (GLASS) {
            roundBox(g, x, y, TOGGLE_W, TOGGLE_H, 5, on ? TRACK_ON_EDGE : BTN_BORDER, on ? TRACK_ON : TRACK_OFF, 0, 0);
            fill(g, x + 3, y + 1, x + TOGGLE_W - 3, y + 2, on ? 0x80FFFFFF : 0x30FFFFFF);
            if (on) fill(g, x + 3, y + TOGGLE_H - 3, x + TOGGLE_W - 3, y + TOGGLE_H - 2, 0x30FFFFFF);   // light glowing through
            int gx = on ? x + TOGGLE_W - 10 : x + 2;
            roundBox(g, gx, y + 2, 8, TOGGLE_H - 4, 3, on ? 0xFFFFFFFF : 0x90FFFFFF, on ? 0xFFF4F9FF : 0xFFB9C3D6, 0, 0);   // the knob is solid glass
            fill(g, gx + 2, y + 3, gx + 6, y + 4, 0x90FFFFFF);
            return;
        }
        box(g, x, y, TOGGLE_W, TOGGLE_H, on ? TRACK_ON_EDGE : BTN_BORDER, on ? TRACK_ON : TRACK_OFF, 0, 0);
        int kx = on ? x + TOGGLE_W - 10 : x + 2;
        fill(g, kx, y + 2, kx + 8, y + TOGGLE_H - 2, on ? TEXT : MUTED);
    }

    static void card(GuiGraphicsExtractor g, Font font, int x, int y, int h, Card card, boolean on, boolean hover) {
        int w = card.fullW();
        if (GLASS) glassButton(g, x, y, w, h, hover ? BTN_BORDER_HOVER : (on ? 0x70A9D3FF : BTN_BORDER), hover ? BTN_HOVER : (on ? 0x2E6FB0FF : BTN), hover);
        else box(g, x, y, w, h, hover ? BTN_BORDER_HOVER : BTN_BORDER, hover ? BTN_HOVER : BTN, 0, 0);
        int toggleX = x + w - (card.gear() ? 26 : 8) - TOGGLE_W;
        String name = font.plainSubstrByWidth(card.name(), toggleX - 4 - (x + 8));
        txt(g, font, name, x + 8, y + (h - 8) / 2 + 1, on ? TEXT : MUTED, false);
        toggle(g, toggleX, y + (h - TOGGLE_H) / 2, on);
    }

    /**
     * Draws the MineBerry look over the vanilla buttons. Call it from a renderable that was added AFTER the widgets,
     * so it lands on top. Widgets listed in cards become module cards; everything else a rounded outlined button.
     */
    static void buttons(GuiGraphicsExtractor g, Font font, List<AbstractWidget> widgets, Map<AbstractWidget, Card> cards,
                        int mouseX, int mouseY) {
        int solid = BG | 0xFF000000;
        for (AbstractWidget w : widgets) {
            if (w instanceof EditBox) continue;
            ghost(w);
            int x = w.getX(), y = w.getY(), bw = w.getWidth(), bh = w.getHeight();
            boolean hover = w.active && mouseX >= x && mouseX < x + bw && mouseY >= y && mouseY < y + bh;
            Card card = cards.get(w);
            if (card != null) {
                cover(g, x, y, x + bw, y + bh, solid);   // hide the vanilla button completely
                card(g, font, x, y, bh, card, w.getMessage().getString().equals("ON"), hover);
                continue;
            }
            // hide the square corners of the vanilla button underneath
            cover(g, x, y, x + 3, y + 3, solid);
            cover(g, x + bw - 3, y, x + bw, y + 3, solid);
            cover(g, x, y + bh - 3, x + 3, y + bh, solid);
            cover(g, x + bw - 3, y + bh - 3, x + bw, y + bh, solid);
            int fillC = hover ? BTN_HOVER : BTN, borderC = hover ? BTN_BORDER_HOVER : BTN_BORDER;
            if (!w.active) { fillC = lerp(solid, BTN, 0.5f); borderC = lerp(solid, BTN_BORDER, 0.5f); }
            if (GLASS) glassButton(g, x, y, bw, bh, borderC, fillC, hover && w.active);
            else box(g, x, y, bw, bh, borderC, fillC, 0, 0);
            String label = w.getMessage().getString();
            txt(g, font, label, x + (bw - font.width(label)) / 2, y + (bh - 8) / 2 + 1, w.active ? TEXT : MUTED, false);
        }
    }

    static void divider(GuiGraphicsExtractor g, int x1, int x2, int y) {
        fill(g, x1, y, x2, y + 1, DIVIDER);
    }

    /** Muted label with a thin line running to the right edge. */
    static void section(GuiGraphicsExtractor g, Font font, int x1, int x2, int y, String label) {
        txt(g, font, label, x1, y, MUTED, false);
        int lineX = x1 + font.width(label) + 6;
        if (lineX < x2) divider(g, lineX, x2, y + 4);
    }

    static int lerp(int a, int b, float t) {
        int out = 0;
        for (int shift = 24; shift >= 0; shift -= 8) {
            int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
            out |= (Math.round(ca + (cb - ca) * t) & 0xFF) << shift;
        }
        return out;
    }
}
