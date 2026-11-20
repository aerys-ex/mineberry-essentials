package com.mineberry.essentials;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Small "new version available" popup shown over the title screen once per launch. Same flat MineBerry look as the
 * other screens. It has a GitHub and a Modrinth button, a Later button, and a "Don't remind me" checkbox: ticking it
 * saves this version in the config, so the popup and the chat message stay quiet until a newer version is released.
 */
public class UpdateScreen extends Screen {

    private static final int PANEL_W = 280, HEADER_H = 20, MARGIN = 14, BTN_H = 20, BOX = 11;

    private final Screen parent;
    private final UpdateChecker.Result update;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private int px, py, pw, ph;
    private int checkX, checkY, checkW;
    private boolean dontRemind;

    public UpdateScreen(Screen parent, UpdateChecker.Result update) {
        super(Component.literal("MineBerry Update"));
        this.parent = parent;
        this.update = update;
    }

    @Override
    protected void init() {
        Ui.applyTheme();
        widgets.clear();

        pw = Math.min(PANEL_W, this.width - 20);
        ph = HEADER_H + 122;
        px = (this.width - pw) / 2;
        py = Math.max(6, (this.height - ph) / 2);
        int left = px + MARGIN, right = px + pw - MARGIN;
        int laterY = py + ph - MARGIN - BTN_H;                          // bottom row: Later
        int linkY = laterY - 6 - BTN_H, btnW = (right - left - 6) / 2;  // row above: GitHub and Modrinth
        checkX = left;
        checkY = py + HEADER_H + 44;                                    // checkbox row, above the buttons
        checkW = BOX + 5 + this.font.width(checkLabel());

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> drawDecor(g, left, mouseX, mouseY));

        // the popup stays open after a click, so the player can still pick the other site
        add(Button.builder(Component.literal("GitHub"), button ->
                net.minecraft.util.Util.getPlatform().openUri(URI.create(update.url())))
                .bounds(left, linkY, btnW, BTN_H).build());
        add(Button.builder(Component.literal("Modrinth"), button ->
                net.minecraft.util.Util.getPlatform().openUri(URI.create(UpdateChecker.modrinthUrl())))
                .bounds(right - btnW, linkY, btnW, BTN_H).build());
        add(Button.builder(Component.literal("Later"), button -> onClose())
                .bounds(left, laterY, right - left, BTN_H).build());

        // the checkbox: an invisible click area (addWidget = clickable but not drawn), the box and the tick are drawn by hand
        this.addWidget(Button.builder(Component.empty(), button -> toggleDontRemind())
                .bounds(checkX, checkY - 1, checkW, BOX + 2).build());

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> Ui.buttons(g, this.font, widgets, Map.of(), mouseX, mouseY));
    }

    private <T extends AbstractWidget> T add(T widget) {
        widgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    private String checkLabel() {
        return "Don't remind me about v" + update.latest();
    }

    /** Saved at once, so closing the popup with Esc or Later keeps the choice. Unticking clears it again. */
    private void toggleDontRemind() {
        dontRemind = !dontRemind;
        Config.setSkippedUpdateVersion(dontRemind ? update.latest() : "");
    }

    private void drawDecor(GuiGraphicsExtractor g, int left, int mouseX, int mouseY) {
        Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
        Ui.frame(g, px, py, pw, ph, HEADER_H);

        int headerTextY = py + 1 + (HEADER_H - 8) / 2;
        Ui.txt(g, this.font, "MineBerry", left, headerTextY, Ui.ACCENT, false);
        Ui.txt(g, this.font, "Update", left + this.font.width("MineBerry") + 6, headerTextY, Ui.MUTED, false);

        Ui.txt(g, this.font, "A new version is available: v" + update.latest(), left, py + HEADER_H + 14, Ui.TEXT, false);
        Ui.txt(g, this.font, "You are on v" + update.current(), left, py + HEADER_H + 28, Ui.MUTED, false);

        // "Don't remind me" checkbox
        boolean hover = mouseX >= checkX && mouseX < checkX + checkW && mouseY >= checkY - 1 && mouseY < checkY + BOX + 1;
        Ui.rect(g, checkX, checkY, BOX, BOX, dontRemind || hover ? Ui.STRONG : Ui.BORDER, Ui.INSET);
        if (dontRemind) drawTick(g, checkX, checkY);
        Ui.txt(g, this.font, checkLabel(), checkX + BOX + 5, checkY + (BOX - 8) / 2 + 1,
                dontRemind ? Ui.TEXT : Ui.MUTED, false);
    }

    /** A small tick mark inside the 11x11 box. */
    private static void drawTick(GuiGraphicsExtractor g, int x, int y) {
        int[][] dots = {{2, 5}, {3, 6}, {4, 7}, {5, 6}, {6, 5}, {7, 4}, {8, 3}};
        for (int[] d : dots) Ui.fill(g, x + d[0], y + d[1], x + d[0] + 1, y + d[1] + 2, Ui.ACCENT);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
