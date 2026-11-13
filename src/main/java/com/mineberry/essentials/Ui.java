package com.mineberry.essentials;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;

import java.util.List;

/**
 * Shared look of the MineBerry screens: dark flat surfaces, a 1px coloured outline with rounded corners,
 * a lighter header strip, dim divider lines and outlined rounded buttons. No shadows, gradients or glows.
 * Every colour goes through fade() so screens can fade in and out.
 */
final class Ui {
    static final int DIM = 0x80000000;
    static final String[] THEME_IDS = {"rose", "aurora", "ember", "midnight"};

    // Palette (set by applyTheme)
    static int BG, HEADER, BORDER, ACCENT, TEXT, MUTED;
    static int ON = 0xFF5FD38A;
    // Derived
    static int DIVIDER, INSET, FOOT, BTN, BTN_HOVER, BTN_BORDER, BTN_BORDER_HOVER;

    /** 0 = invisible, 1 = fully shown. Set by the screen each frame before drawing. */
    static float fadeAmt = 1f;

    private Ui() {}

    static void applyTheme() {
        switch (Config.panelTheme()) {
            case "aurora" -> set(0xF50B1317, 0xFF11242B, 0xFF2FA9BA, 0xFF9BE6F2, 0xFFEFFAFC, 0xFF86A5AD);
            case "ember" -> set(0xF5130D09, 0xFF251810, 0xFFE08A2E, 0xFFFFC98A, 0xFFFFF4E8, 0xFFAB9580);
            case "midnight" -> set(0xF5090B14, 0xFF141934, 0xFF5C7CF0, 0xFFB3C4FF, 0xFFF0F3FF, 0xFF8A91B0);
            default -> set(0xF5140C12, 0xFF241326, 0xFFD14D73, 0xFFFFA3C0, 0xFFFFF0F5, 0xFFA98896);
        }
        int solid = BG | 0xFF000000;
        DIVIDER = lerp(solid, TEXT, 0.10f);
        INSET = lerp(solid, 0xFF000000, 0.35f);
        FOOT = lerp(solid, BORDER, 0.375f);
        BTN = lerp(solid, 0xFFFFFFFF, 0.085f);
        BTN_HOVER = lerp(solid, 0xFFFFFFFF, 0.16f);
        BTN_BORDER = lerp(BTN, BORDER, 0.30f);
        BTN_BORDER_HOVER = lerp(BTN, BORDER, 0.80f);
    }

    private static void set(int bg, int header, int border, int accent, int text, int muted) {
        BG = bg; HEADER = header; BORDER = border; ACCENT = accent; TEXT = text; MUTED = muted;
    }

    static String themeName() {
        return switch (Config.panelTheme()) {
            case "aurora" -> "Aurora";
            case "ember" -> "Ember";
            case "midnight" -> "Midnight";
            default -> "Rose";
        };
    }

    static String nextTheme() {
        return switch (Config.panelTheme()) {
            case "rose" -> "aurora";
            case "aurora" -> "ember";
            case "ember" -> "midnight";
            default -> "rose";
        };
    }

    // ---------------------------------------------------------------- fade

    static float fadeValue(boolean closing, long openedAt, long closeStart, long inMs, long outMs) {
        long t = System.currentTimeMillis();
        float f = closing ? 1f - (t - closeStart) / (float) outMs : (t - openedAt) / (float) inMs;
        f = Math.max(0f, Math.min(1f, f));
        return f * f * (3f - 2f * f);
    }

    static int fade(int color) {
        int a = Math.round((color >>> 24) * fadeAmt);
        return (a << 24) | (color & 0xFFFFFF);
    }

    // ---------------------------------------------------------------- primitives

    static void fill(GuiGraphicsExtractor g, int x1, int y1, int x2, int y2, int color) {
        int c = fade(color);
        if ((c >>> 24) == 0) return;
        g.fill(x1, y1, x2, y2, c);
    }

    static void txt(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color, boolean shadow) {
        int c = fade(color);
        if ((c >>> 24) < 4) return;   // vanilla draws text with alpha < 4 as fully opaque
        g.text(font, text, x, y, c, shadow);
    }

    /**
     * Rounded box: 1px border, corners cut in three steps (like the reference panel). Pieces never overlap, so
     * translucent fills stay clean. With headerH > 0 the first headerH rows inside the border use headerFill.
     */
    static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int border, int fill, int headerFill, int headerH) {
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
    }

    /** Window frame: rounded outline, flat body, lighter header strip (no line under it). */
    static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int headerH) {
        box(g, x, y, w, h, BORDER, BG, HEADER, headerH);
    }

    /** Header text on the right edge, muted (like "No mutes recorded"). */
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
        box(g, x, y, w, h, box.isFocused() ? BTN_BORDER_HOVER : BTN_BORDER, INSET, 0, 0);
    }

    /**
     * Draws the rounded outlined look over the vanilla buttons. Call it from a renderable that was added
     * AFTER the widgets, so it lands on top. Corners are covered with the panel colour first.
     */
    static void buttons(GuiGraphicsExtractor g, Font font, List<AbstractWidget> widgets, int mouseX, int mouseY) {
        int solid = BG | 0xFF000000;
        for (AbstractWidget w : widgets) {
            if (w instanceof EditBox) continue;
            int x = w.getX(), y = w.getY(), bw = w.getWidth(), bh = w.getHeight();
            boolean hover = w.active && mouseX >= x && mouseX < x + bw && mouseY >= y && mouseY < y + bh;
            // hide the square corners of the vanilla button underneath
            fill(g, x, y, x + 3, y + 3, solid);
            fill(g, x + bw - 3, y, x + bw, y + 3, solid);
            fill(g, x, y + bh - 3, x + 3, y + bh, solid);
            fill(g, x + bw - 3, y + bh - 3, x + bw, y + bh, solid);
            int fillC = hover ? BTN_HOVER : BTN, borderC = hover ? BTN_BORDER_HOVER : BTN_BORDER;
            if (!w.active) { fillC = lerp(solid, BTN, 0.5f); borderC = lerp(solid, BTN_BORDER, 0.5f); }
            box(g, x, y, bw, bh, borderC, fillC, 0, 0);
            String label = w.getMessage().getString();
            int color = !w.active ? MUTED : label.equals("ON") ? ON : label.equals("OFF") ? MUTED : TEXT;
            txt(g, font, label, x + (bw - font.width(label)) / 2, y + (bh - 8) / 2 + 1, color, false);
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
