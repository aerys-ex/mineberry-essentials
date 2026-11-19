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
 * The agreement the player must accept before any feature works (see Terms). Same flat MineBerry look as the other
 * popups. Scrolls with the mouse wheel when the text is longer than the window. Esc counts as "I Disagree".
 *
 * "I Agree" saves the choice and continues to {@code next} (or back to {@code parent}). "I Disagree" just goes back
 * to {@code parent}: the mod stays off and this screen opens again the next time a feature is used.
 */
public class TermsScreen extends Screen {

    private static final int PANEL_W = 320, HEADER_H = 20, MARGIN = 14, BTN_H = 20, LINE_H = 11, GAP_H = 6;
    private static final int MIN_H = 120;

    private enum Kind { LEAD, SECTION, BULLET, CONTINUATION, NOTE, GAP }

    private record Line(Kind kind, String text) {
        int height() { return kind == Kind.GAP ? GAP_H : LINE_H; }
    }

    private record Block(Kind kind, String text) {}

    /** The text. Edit here, and raise Terms.VERSION when everybody has to agree again. */
    private static final List<Block> TEXT = List.of(
            new Block(Kind.LEAD, "Welcome! Just a quick note before you start."),
            new Block(Kind.SECTION, "THE SHORT VERSION"),
            new Block(Kind.BULLET, "MineBerry - Essentials is a free, community-made mod, provided \"as is\"."),
            new Block(Kind.BULLET, "By clicking I Agree, you accept that the mod developers are not held accountable for anything that goes wrong while you use it."),
            new Block(Kind.SECTION, "YOUR DATA"),
            new Block(Kind.BULLET, "Your settings stay on your computer. The only thing the mod ever contacts is GitHub, to check for a new version "
                    + "(you can turn that off in config/mineberry_essentials.json)."),
            new Block(Kind.SECTION, "NOT AGREEING"),
            new Block(Kind.BULLET, "That's fine too. The mod just stays off until you agree, and this window pops up again whenever you try a feature."),
            new Block(Kind.GAP, ""),
            new Block(Kind.NOTE, "Click I Agree to continue."));

    private final Screen parent;
    private final Screen next;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private Button agreeBtn;
    private int px, py, pw, ph;
    private int viewTop, viewH;
    private int scrollIdx;                      // index of the first line drawn

    /** @param parent where to go on "I Disagree" (null = back to the game); @param next where to go on "I Agree" (null = parent) */
    public TermsScreen(Screen parent, Screen next) {
        super(Component.literal("MineBerry Terms of Use"));
        this.parent = parent;
        this.next = next;
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

        int contentH = totalHeight();
        int chrome = HEADER_H + 10 + 8 + BTN_H + MARGIN;                    // header, gap, gap, buttons, bottom margin
        ph = Math.max(MIN_H, Math.min(this.height - 16, chrome + contentH));
        py = Math.max(6, (this.height - ph) / 2);

        viewTop = py + HEADER_H + 10;
        int btnY = py + ph - MARGIN - BTN_H;
        viewH = btnY - 8 - viewTop;
        scrollIdx = Math.min(scrollIdx, maxScroll());

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> drawDecor(g, left, right, btnY));

        int btnW = (right - left - 6) / 2;
        agreeBtn = add(Button.builder(Component.literal("I Agree"), button -> agree())
                .bounds(left, btnY, btnW, BTN_H).build());
        add(Button.builder(Component.literal("I Disagree"), button -> onClose())
                .bounds(right - btnW, btnY, btnW, BTN_H).build());

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> {
            Ui.buttons(g, this.font, widgets, Map.of(), mouseX, mouseY);
            paintAgree(g, mouseX, mouseY);
        });
    }

    private <T extends AbstractWidget> T add(T widget) {
        widgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    // ---------------------------------------------------------------- content

    private void buildLines(int textW) {
        lines.clear();
        for (Block block : TEXT) {
            switch (block.kind()) {
                case GAP -> lines.add(new Line(Kind.GAP, ""));
                case SECTION -> {
                    lines.add(new Line(Kind.GAP, ""));
                    lines.add(new Line(Kind.SECTION, block.text()));
                }
                case BULLET -> {
                    List<String> wrapped = wrap(block.text(), textW - 8);
                    for (int i = 0; i < wrapped.size(); i++) {
                        lines.add(new Line(i == 0 ? Kind.BULLET : Kind.CONTINUATION, wrapped.get(i)));
                    }
                }
                default -> {                       // LEAD, NOTE: plain wrapped paragraphs
                    for (String part : wrap(block.text(), textW)) lines.add(new Line(block.kind(), part));
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
        Ui.txt(g, this.font, "Terms of Use", left + this.font.width("MineBerry") + 6, headerTextY, Ui.MUTED, false);
        Ui.headerRight(g, this.font, right, headerTextY, "v" + UpdateChecker.installed());

        // only lines that fit completely are drawn, so there is no clipping to get wrong
        int y = viewTop;
        int viewBottom = viewTop + viewH;
        for (int i = scrollIdx; i < lines.size(); i++) {
            Line l = lines.get(i);
            if (y + l.height() > viewBottom) break;
            switch (l.kind()) {
                case LEAD -> Ui.txt(g, this.font, l.text(), left, y, Ui.TEXT, false);
                case NOTE -> Ui.txt(g, this.font, l.text(), left, y, Ui.ACCENT, false);
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

    /** Draws "I Agree" with the accent colour over the plain button look, so it is the clear main choice. */
    private void paintAgree(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int x = agreeBtn.getX(), y = agreeBtn.getY(), w = agreeBtn.getWidth(), h = agreeBtn.getHeight();
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        Ui.box(g, x, y, w, h, hover ? Ui.ACCENT : Ui.TRACK_ON_EDGE, hover ? Ui.lerp(Ui.TRACK_ON, Ui.ACCENT, 0.25f) : Ui.TRACK_ON, 0, 0);
        String label = agreeBtn.getMessage().getString();
        Ui.txt(g, this.font, label, x + (w - this.font.width(label)) / 2, y + (h - 8) / 2 + 1, Ui.TEXT, false);
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

    private void agree() {
        Terms.accept(this.minecraft);
        this.minecraft.gui.setScreen(next != null ? next : parent);
    }

    /** "I Disagree" and Esc: back to where the player came from. Nothing is saved, so the mod stays off. */
    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
