package com.mineberry.essentials;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Edit the radial menu: one row per slice with a label and a command. An empty row is skipped. Changes are saved when
 * the screen closes (Done or Esc). Same flat look as the other MineBerry screens.
 */
public class RadialEditScreen extends Screen {

    private static final int PANEL_W = 380, HEADER_H = 20, MARGIN = 14, BTN_H = 20, LABEL_W = 96, CLEAR_W = 22;
    private static final int SLOTS = Config.RADIAL_MAX;

    private final Screen parent;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final List<EditBox> boxes = new ArrayList<>();
    private final String[] labels = new String[SLOTS];
    private final String[] commands = new String[SLOTS];

    private int px, py, pw, ph, rowsY, rowH, footerY;

    public RadialEditScreen(Screen parent) {
        super(Component.literal("MineBerry Radial Menu"));
        this.parent = parent;
        load(Config.radialSlices());
    }

    /** Fills the rows from a list of slices; commands are shown with their slash. */
    private void load(List<Config.RadialSlice> list) {
        for (int i = 0; i < SLOTS; i++) {
            Config.RadialSlice s = i < list.size() ? list.get(i) : null;
            String c = s == null ? "" : Config.cleanCommand(s.command);
            labels[i] = s == null || s.label == null ? "" : s.label;
            commands[i] = c.isEmpty() ? "" : "/" + c;
        }
    }

    @Override
    protected void init() {
        Ui.applyTheme();
        widgets.clear();
        boxes.clear();

        pw = Math.min(PANEL_W, this.width - 20);
        rowH = Math.max(20, Math.min(24, (this.height - 110) / SLOTS));
        rowsY = HEADER_H + 6 + 20 + 14;
        footerY = rowsY + SLOTS * rowH + 4;
        ph = footerY + 6 + BTN_H + 8;
        px = (this.width - pw) / 2;
        py = Math.max(6, (this.height - ph) / 2);

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> drawDecor(g));

        int left = px + MARGIN, right = px + pw - MARGIN;
        int cmdX = left + LABEL_W + 6;
        int cmdW = (right - CLEAR_W - 6) - cmdX;

        for (int i = 0; i < SLOTS; i++) {
            final int slot = i;
            int oy = py + rowsY + i * rowH + (rowH - 20) / 2;     // top of the 20px high field

            EditBox labelBox = new EditBox(this.font, left + 4, oy + 6, LABEL_W - 8, 12, Component.literal("Label"));
            labelBox.setBordered(false);
            labelBox.setMaxLength(12);
            labelBox.setValue(labels[i]);
            labelBox.setResponder(value -> labels[slot] = value);
            add(labelBox);
            boxes.add(labelBox);

            EditBox commandBox = new EditBox(this.font, cmdX + 4, oy + 6, cmdW - 8, 12, Component.literal("Command"));
            commandBox.setBordered(false);
            commandBox.setMaxLength(64);
            commandBox.setValue(commands[i]);
            commandBox.setResponder(value -> commands[slot] = value);
            add(commandBox);
            boxes.add(commandBox);

            add(Button.builder(Component.literal("x"), button -> {
                labels[slot] = "";
                commands[slot] = "";
                this.rebuildWidgets();
            }).bounds(right - CLEAR_W, oy, CLEAR_W, BTN_H).build());
        }

        add(Button.builder(Component.literal("Reset defaults"), button -> {
            load(Config.defaultRadialSlices());
            this.rebuildWidgets();
        }).bounds(left, py + footerY + 6, 100, BTN_H).build());
        add(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(right - 70, py + footerY + 6, 70, BTN_H).build());

        // rounded outlined button look, drawn over the vanilla buttons (added last so it renders on top)
        this.addRenderableOnly((g, mouseX, mouseY, delta) -> Ui.buttons(g, this.font, widgets, Map.of(), mouseX, mouseY));
    }

    private <T extends AbstractWidget> T add(T widget) {
        widgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    private int sliceCount() {
        int n = 0;
        for (String c : commands) if (!Config.cleanCommand(c).isEmpty()) n++;
        return n;
    }

    // ---------------------------------------------------------------- drawing

    private void drawDecor(GuiGraphicsExtractor g) {
        int left = px + MARGIN, right = px + pw - MARGIN;

        Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
        Ui.frame(g, px, py, pw, ph, HEADER_H);

        int headerTextY = py + 1 + (HEADER_H - 8) / 2;
        Ui.txt(g, this.font, "MineBerry", left, headerTextY, Ui.ACCENT, false);
        Ui.txt(g, this.font, "Radial Menu", left + this.font.width("MineBerry") + 6, headerTextY, Ui.MUTED, false);
        int n = sliceCount();
        Ui.headerRight(g, this.font, right, headerTextY, n == 0 ? "Defaults will be used" : n + (n == 1 ? " slice" : " slices"));

        int hintW = right - left;
        Ui.txt(g, this.font, this.font.plainSubstrByWidth("One slice per row, up to " + SLOTS + ". An empty row is skipped.", hintW),
                left, py + HEADER_H + 6, Ui.MUTED, false);
        Ui.txt(g, this.font, this.font.plainSubstrByWidth("Use <player> to finish a command in chat, e.g. /tpa <player>", hintW),
                left, py + HEADER_H + 16, Ui.MUTED, false);

        int colY = py + rowsY - 11;
        Ui.txt(g, this.font, "Label", left, colY, Ui.MUTED, false);
        Ui.txt(g, this.font, "Command", left + LABEL_W + 6, colY, Ui.MUTED, false);

        for (EditBox box : boxes) Ui.inputBox(g, box);

        Ui.footerLine(g, left, right, py + footerY);
        Ui.txt(g, this.font, "Saved when you close", left + 108, py + footerY + 12, Ui.MUTED, false);
    }

    // ---------------------------------------------------------------- closing

    @Override
    public void onClose() {
        List<Config.RadialSlice> list = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            String command = Config.cleanCommand(commands[i]);
            if (command.isEmpty()) continue;
            list.add(new Config.RadialSlice(labels[i] == null ? "" : labels[i].trim(), command));
        }
        if (list.isEmpty()) Config.resetRadialSlices();      // an empty menu is useless: fall back to the defaults
        else Config.setRadialSlices(list);
        this.minecraft.gui.setScreen(parent);
    }
}
