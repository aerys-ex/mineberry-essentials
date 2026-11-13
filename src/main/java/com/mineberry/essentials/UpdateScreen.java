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

/** Small "new version available" popup shown over the title screen. Same flat Lunar look as the other screens. */
public class UpdateScreen extends Screen {

    private static final int PANEL_W = 280, HEADER_H = 20, MARGIN = 14, BTN_H = 20;

    private final Screen parent;
    private final UpdateChecker.Result update;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private int px, py, pw, ph;

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
        ph = HEADER_H + 74;
        px = (this.width - pw) / 2;
        py = Math.max(6, (this.height - ph) / 2);
        int left = px + MARGIN, right = px + pw - MARGIN;
        int btnY = py + ph - MARGIN - BTN_H, btnW = (right - left - 6) / 2;

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> drawDecor(g, left, right));

        add(Button.builder(Component.literal("Download"), button -> {
            net.minecraft.util.Util.getPlatform().openUri(URI.create(update.url()));
            onClose();
        }).bounds(left, btnY, btnW, BTN_H).build());
        add(Button.builder(Component.literal("Later"), button -> onClose())
                .bounds(right - btnW, btnY, btnW, BTN_H).build());

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> Ui.buttons(g, this.font, widgets, Map.of(), mouseX, mouseY));
    }

    private <T extends AbstractWidget> T add(T widget) {
        widgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    private void drawDecor(GuiGraphicsExtractor g, int left, int right) {
        Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
        Ui.frame(g, px, py, pw, ph, HEADER_H);

        int headerTextY = py + 1 + (HEADER_H - 8) / 2;
        Ui.txt(g, this.font, "MineBerry", left, headerTextY, Ui.ACCENT, false);
        Ui.txt(g, this.font, "Update", left + this.font.width("MineBerry") + 6, headerTextY, Ui.MUTED, false);

        Ui.txt(g, this.font, "A new version is available: v" + update.latest(), left, py + HEADER_H + 14, Ui.TEXT, false);
        Ui.txt(g, this.font, "You are on v" + update.current(), left, py + HEADER_H + 28, Ui.MUTED, false);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
