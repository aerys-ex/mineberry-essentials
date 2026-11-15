package com.mineberry.essentials;

import com.mineberry.essentials.modules.ScreenshotModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Your screenshots: copy one to the clipboard (paste it into Discord with Ctrl+V), open it, or open the folder. */
public class ScreenshotScreen extends Screen {

    private static final int PANEL_W = 340, HEADER_H = 20, MARGIN = 14, ROW_H = 28, PER_PAGE = 5, BTN_H = 20;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(ZoneId.systemDefault());

    private final Screen parent;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private List<Path> files = new ArrayList<>();
    private boolean loaded = false;
    private volatile String status = "Press F9 in game to take one";
    private int page = 0;

    private int px, py, pw, ph, topY, listLabelY, rowsY, pagerY, footerY;

    public ScreenshotScreen(Screen parent) {
        super(Component.literal("MineBerry Screenshots"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Ui.applyTheme();
        widgets.clear();
        if (!loaded) { files = ScreenshotModule.list(Minecraft.getInstance()); loaded = true; }

        pw = Math.min(PANEL_W, this.width - 20);
        topY = HEADER_H + 10;
        listLabelY = topY + BTN_H + 12;
        rowsY = listLabelY + 14;
        pagerY = rowsY + PER_PAGE * ROW_H + 6;
        footerY = pagerY + BTN_H + 12;
        ph = footerY + 36;
        px = (this.width - pw) / 2;
        py = Math.max(6, (this.height - ph) / 2);

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> drawDecor(g));

        int left = px + MARGIN, right = px + pw - MARGIN;

        add(Button.builder(Component.literal("Open folder"), button -> ScreenshotModule.openFolder(Minecraft.getInstance()))
                .bounds(left, py + topY, 84, BTN_H).build());
        add(Button.builder(Component.literal("Refresh"), button -> { loaded = false; status = "Refreshed"; this.rebuildWidgets(); })
                .bounds(left + 88, py + topY, 60, BTN_H).build());

        int pages = pageCount();
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= files.size()) break;
            Path file = files.get(index);
            int y = py + rowsY + i * ROW_H + 4;
            add(Button.builder(Component.literal("Open"), button -> ScreenshotModule.open(file))
                    .bounds(right - 40, y, 40, BTN_H).build());
            add(Button.builder(Component.literal("Copy"), button -> {
                status = "Copying...";
                ScreenshotModule.copyAsync(file, ok -> status = ok ? "Copied. Press Ctrl+V to paste it"
                        : "Couldn't copy the image, file path copied instead");
            }).bounds(right - 40 - 4 - 40, y, 40, BTN_H).build());
        }

        add(Button.builder(Component.literal("< Prev"), button -> { page--; this.rebuildWidgets(); })
                .bounds(left, py + pagerY, 56, BTN_H).build()).active = page > 0;
        add(Button.builder(Component.literal("Next >"), button -> { page++; this.rebuildWidgets(); })
                .bounds(right - 56, py + pagerY, 56, BTN_H).build()).active = page < pages - 1;
        add(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(right - 60, py + footerY + 8, 60, BTN_H).build());

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> Ui.buttons(g, this.font, widgets, Map.of(), mouseX, mouseY));
    }

    private <T extends AbstractWidget> T add(T widget) {
        widgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    private int pageCount() { return Math.max(1, (files.size() + PER_PAGE - 1) / PER_PAGE); }

    private static String size(Path file) {
        try {
            long b = java.nio.file.Files.size(file);
            return b >= 1_048_576 ? String.format("%.1f MB", b / 1_048_576.0) : (b / 1024) + " KB";
        } catch (java.io.IOException e) {
            return "";
        }
    }

    // ---------------------------------------------------------------- drawing

    private void drawDecor(GuiGraphicsExtractor g) {
        int left = px + MARGIN, right = px + pw - MARGIN;

        Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
        Ui.frame(g, px, py, pw, ph, HEADER_H);

        int headerTextY = py + 1 + (HEADER_H - 8) / 2;
        Ui.txt(g, this.font, "MineBerry", left, headerTextY, Ui.ACCENT, false);
        Ui.txt(g, this.font, "Screenshots", left + this.font.width("MineBerry") + 6, headerTextY, Ui.MUTED, false);
        int count = files.size();
        Ui.headerRight(g, this.font, right, headerTextY, count == 0 ? "No screenshots" : count + (count == 1 ? " screenshot" : " screenshots"));

        Ui.txt(g, this.font, this.font.plainSubstrByWidth(status, pw - 2 * MARGIN - 160),
                left + 156, py + topY + 6, Ui.MUTED, false);

        Ui.section(g, this.font, left, right, py + listLabelY, "RECENT");
        if (files.isEmpty()) {
            Ui.txt(g, this.font, "Nothing here yet. Press F9 in game to take one.", left, py + rowsY + 10, Ui.MUTED, false);
        }
        int textW = pw - 2 * MARGIN - (40 + 4 + 40 + 8);
        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= files.size()) break;
            Path file = files.get(index);
            int y = py + rowsY + i * ROW_H;
            String name = file.getFileName().toString().replaceFirst("(?i)\\.png$", "");
            Ui.txt(g, this.font, this.font.plainSubstrByWidth(name, textW), left, y + 5, Ui.TEXT, false);
            String sub = WHEN.format(Instant.ofEpochMilli(ScreenshotModule.modified(file))) + "  " + size(file);
            Ui.txt(g, this.font, this.font.plainSubstrByWidth(sub, textW), left, y + 16, Ui.MUTED, false);
            if (i < PER_PAGE - 1 && index < files.size() - 1) Ui.divider(g, left, right, y + ROW_H - 1);
        }

        String pageLabel = (page + 1) + " / " + pageCount();
        Ui.txt(g, this.font, pageLabel, px + pw / 2 - this.font.width(pageLabel) / 2, py + pagerY + 6, Ui.MUTED, false);
        Ui.footerLine(g, left, right, py + footerY);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
