package com.mineberry.essentials;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * "What's New" popup: the release notes of the version(s) the player just updated to, in the same flat Lunar look as
 * the other screens. Scrolls with the mouse wheel when the notes are longer than the window.
 */
public class ChangelogScreen extends Screen {

    private static final int PANEL_W = 300, HEADER_H = 20, MARGIN = 14, BTN_H = 20, LINE_H = 11, GAP_H = 6;
    private static final int MIN_H = 110;

    private enum Kind { VERSION, SECTION, BULLET, CONTINUATION, GAP }

    private record Line(Kind kind, String text) {
        int height() { return kind == Kind.GAP ? GAP_H : LINE_H; }
    }

    private final Screen parent;
    private final List<Changelog.Entry> entries;
    private final String current;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private int px, py, pw, ph;
    private int viewTop, viewH;
    private int scrollIdx;                      // index of the first line drawn

    public ChangelogScreen(Screen parent, List<Changelog.Entry> entries, String current) {
        super(Component.literal("MineBerry What's New"));
        this.parent = parent;
        this.entries = entries;
        this.current = current;
    }

    @Override
    protected void init() {
        Ui.applyTheme();
        widgets.clear();

        pw = Math.min(PANEL_W, this.width - 20);
        px = (this.width - pw) / 2;
        int left = px + MARGIN;
        int right = px + pw - MARGIN;
        int textW = right - left - 8;           // leaves room for the scrollbar

        buildLines(textW);

        int contentH = 0;
        for (Line l : lines) contentH += l.height();
        int chrome = HEADER_H + 10 + 8 + BTN_H + MARGIN;                    // header, gap, gap, button, bottom margin
        ph = Math.max(MIN_H, Math.min(this.height - 16, chrome + contentH));
        py = Math.max(6, (this.height - ph) / 2);

        viewTop = py + HEADER_H + 10;
        int btnY = py + ph - MARGIN - BTN_H;
        viewH = btnY - 8 - viewTop;
        scrollIdx = Math.min(scrollIdx, maxScroll());

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> drawDecor(g, left, right, btnY));

        add(Button.builder(Component.literal("Got it"), button -> onClose())
                .bounds(left, btnY, right - left, BTN_H).build());

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> Ui.buttons(g, this.font, widgets, Map.of(), mouseX, mouseY));
    }

    private <T extends AbstractWidget> T add(T widget) {
        widgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    // ---------------------------------------------------------------- content

    private void buildLines(int textW) {
        lines.clear();
        boolean first = true;
        for (Changelog.Entry entry : entries) {
            if (!first) lines.add(new Line(Kind.GAP, ""));
            first = false;
            String head = "v" + entry.version() + (entry.title().isBlank() ? "" : "  -  " + entry.title());
            lines.add(new Line(Kind.VERSION, this.font.plainSubstrByWidth(head, textW)));
            for (Map.Entry<String, List<String>> section : entry.sections().entrySet()) {
                lines.add(new Line(Kind.GAP, ""));
                lines.add(new Line(Kind.SECTION, section.getKey().toUpperCase()));
                for (String bullet : section.getValue()) {
                    List<String> wrapped = wrap(bullet, textW - 8);
                    for (int i = 0; i < wrapped.size(); i++) {
                        lines.add(new Line(i == 0 ? Kind.BULLET : Kind.CONTINUATION, wrapped.get(i)));
                    }
                }
            }
        }
    }

    /** Word wrap by pixel width. A single word wider than the line is cut at draw time. */
    private List<String> wrap(String text, int maxW) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String trial = cur.isEmpty() ? word : cur + " " + word;
            if (cur.isEmpty() || this.font.width(trial) <= maxW) {
                cur = new StringBuilder(trial);
            } else {
                out.add(cur.toString());
                cur = new StringBuilder(word);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    private int totalHeight() {
        int sum = 0;
        for (Line l : lines) sum += l.height();
        return sum;
    }

    private int heightBefore(int idx) {
        int sum = 0;
        for (int i = 0; i < idx && i < lines.size(); i++) sum += lines.get(i).height();
        return sum;
    }

    /** The highest first-line index that still fills the view (0 when everything fits). */
    private int maxScroll() {
        int remaining = totalHeight();
        for (int i = 0; i < lines.size(); i++) {
            if (remaining <= viewH) return i;
            remaining -= lines.get(i).height();
        }
        return 0;
    }

    // ---------------------------------------------------------------- drawing

    private void drawDecor(GuiGraphicsExtractor g, int left, int right, int btnY) {
        Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
        Ui.frame(g, px, py, pw, ph, HEADER_H);

        int headerTextY = py + 1 + (HEADER_H - 8) / 2;
        Ui.txt(g, this.font, "MineBerry", left, headerTextY, Ui.ACCENT, false);
        Ui.txt(g, this.font, "What's New", left + this.font.width("MineBerry") + 6, headerTextY, Ui.MUTED, false);
        Ui.headerRight(g, this.font, right, headerTextY, "v" + current);

        // only lines that fit completely are drawn, so there is no clipping to get wrong
        int y = viewTop;
        int viewBottom = viewTop + viewH;
        for (int i = scrollIdx; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (y + l.height() > viewBottom) break;
            switch (l.kind()) {
                case VERSION -> Ui.txt(g, this.font, l.text(), left, y, Ui.ACCENT, false);
                case SECTION -> Ui.section(g, this.font, left, right - 8, y, l.text());
                case BULLET -> {
                    Ui.fill(g, left + 1, y + 3, left + 4, y + 6, Ui.STRONG);
                    Ui.txt(g, this.font, this.font.plainSubstrByWidth(l.text(), right - left - 16), left + 8, y, Ui.TEXT, false);
                }
                case CONTINUATION -> Ui.txt(g, this.font, this.font.plainSubstrByWidth(l.text(), right - left - 16), left + 8, y, Ui.TEXT, false);
                case GAP -> { }
            }
            y += l.height();
        }

        drawScrollbar(g, right);
        Ui.footerLine(g, px + 1, px + pw - 1, btnY - 6);
    }

    private void drawScrollbar(GuiGraphicsExtractor g, int right) {
        int total = totalHeight();
        if (total <= viewH) return;
        int x = right - 3;
        Ui.fill(g, x, viewTop, x + 3, viewTop + viewH, Ui.DIVIDER);
        int thumbH = Math.max(10, viewH * viewH / total);
        int travel = viewH - thumbH;
        int scrollable = Math.max(1, total - viewH);
        int thumbY = viewTop + Math.min(travel, travel * heightBefore(scrollIdx) / scrollable);
        Ui.fill(g, x, thumbY, x + 3, thumbY + thumbH, Ui.ACCENT);
    }

    // ---------------------------------------------------------------- input

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int max = maxScroll();
        if (scrollY == 0 || max == 0) return false;
        scrollIdx = Math.max(0, Math.min(max, scrollIdx + (scrollY > 0 ? -1 : 1)));
        return true;
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
