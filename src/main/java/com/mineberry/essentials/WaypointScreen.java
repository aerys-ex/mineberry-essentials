package com.mineberry.essentials;

import com.mineberry.essentials.modules.WaypointsModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Add, show/hide and delete waypoints. Same flat Lunar look as the main panel. */
public class WaypointScreen extends Screen {

    private static final int PANEL_W = 340, HEADER_H = 20, MARGIN = 14, ROW_H = 28, PER_PAGE = 4, BTN_H = 20;

    private final Screen parent;
    private final List<AbstractWidget> widgets = new ArrayList<>();

    // y values relative to py
    private int px, py, pw, ph, addLabelY, inputsY, buttonsY, listLabelY, rowsY, pagerY, footerY;
    private int page = 0;
    private String status = "Leave X Y Z empty to use your position";
    private String nameText = "", xText = "", yText = "", zText = "";
    private EditBox nameBox, xBox, yBox, zBox;

    public WaypointScreen(Screen parent) {
        super(Component.literal("MineBerry Waypoints"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Ui.applyTheme();
        widgets.clear();

        pw = Math.min(PANEL_W, this.width - 20);
        addLabelY = HEADER_H + 10;
        inputsY = addLabelY + 26;               // leaves room for the small field labels
        buttonsY = inputsY + BTN_H + 6;
        listLabelY = buttonsY + BTN_H + 14;
        rowsY = listLabelY + 14;
        pagerY = rowsY + PER_PAGE * ROW_H + 6;
        footerY = pagerY + BTN_H + 12;
        ph = footerY + 36;
        px = (this.width - pw) / 2;
        py = Math.max(6, (this.height - ph) / 2);

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> drawDecor(g));

        int left = px + MARGIN, right = px + pw - MARGIN;

        // add form
        // borderless fields, padded inside the rounded boxes that drawDecor() paints behind them
        nameBox = new EditBox(this.font, left + 4, py + inputsY + 6, 112, 12, Component.literal("Name"));
        xBox = new EditBox(this.font, left + 128 + 4, py + inputsY + 6, 40, 12, Component.literal("X"));
        yBox = new EditBox(this.font, left + 180 + 4, py + inputsY + 6, 40, 12, Component.literal("Y"));
        zBox = new EditBox(this.font, left + 232 + 4, py + inputsY + 6, 40, 12, Component.literal("Z"));
        nameBox.setBordered(false); xBox.setBordered(false); yBox.setBordered(false); zBox.setBordered(false);
        nameBox.setMaxLength(24);
        xBox.setMaxLength(9); yBox.setMaxLength(9); zBox.setMaxLength(9);
        nameBox.setValue(nameText); xBox.setValue(xText); yBox.setValue(yText); zBox.setValue(zText);
        add(nameBox); add(xBox); add(yBox); add(zBox);

        add(Button.builder(Component.literal("My pos"), button -> fillMyPosition())
                .bounds(left, py + buttonsY, 60, BTN_H).build());
        add(Button.builder(Component.literal("Add"), button -> addWaypoint())
                .bounds(right - 60, py + buttonsY, 60, BTN_H).build());

        // list rows
        List<Config.Waypoint> list = Config.waypoints();
        int pages = pageCount();
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= list.size()) break;
            Config.Waypoint wp = list.get(index);
            int y = py + rowsY + i * ROW_H + 4;
            add(Button.builder(Component.literal("Del"), button -> {
                snapshot();
                list.remove(wp);
                Config.save();
                this.rebuildWidgets();
            }).bounds(right - 34, y, 34, BTN_H).build());
            add(Button.builder(Component.literal(wp.visible ? "Shown" : "Hidden"), button -> {
                wp.visible = !wp.visible;
                Config.save();
                button.setMessage(Component.literal(wp.visible ? "Shown" : "Hidden"));
            }).bounds(right - 34 - 4 - 52, y, 52, BTN_H).build());
        }

        // pager + close
        add(Button.builder(Component.literal("< Prev"), button -> { snapshot(); page--; this.rebuildWidgets(); })
                .bounds(left, py + pagerY, 56, BTN_H).build()).active = page > 0;
        add(Button.builder(Component.literal("Next >"), button -> { snapshot(); page++; this.rebuildWidgets(); })
                .bounds(right - 56, py + pagerY, 56, BTN_H).build()).active = page < pages - 1;
        add(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(right - 60, py + footerY + 8, 60, BTN_H).build());

        // rounded outlined button look, drawn over the vanilla buttons (added last so it renders on top)
        this.addRenderableOnly((g, mouseX, mouseY, delta) -> Ui.buttons(g, this.font, widgets, Map.of(), mouseX, mouseY));
    }

    private <T extends AbstractWidget> T add(T widget) {
        widgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    private int pageCount() { return Math.max(1, (Config.waypoints().size() + PER_PAGE - 1) / PER_PAGE); }

    /** Keeps what was typed when the widgets are rebuilt. */
    private void snapshot() {
        if (nameBox == null) return;
        nameText = nameBox.getValue(); xText = xBox.getValue(); yText = yBox.getValue(); zText = zBox.getValue();
    }

    private void fillMyPosition() {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.player == null) { status = "Join a world first"; return; }
        xBox.setValue(String.valueOf((int) Math.floor(mc.player.getX())));
        yBox.setValue(String.valueOf((int) Math.floor(mc.player.getY())));
        zBox.setValue(String.valueOf((int) Math.floor(mc.player.getZ())));
        status = "Position filled in";
    }

    private void addWaypoint() {
        Minecraft mc = this.minecraft;
        if (mc == null || mc.player == null || mc.level == null) { status = "Join a world first"; return; }
        String xs = xBox.getValue().trim(), ys = yBox.getValue().trim(), zs = zBox.getValue().trim();
        Config.Waypoint wp;
        try {
            if (xs.isEmpty() && ys.isEmpty() && zs.isEmpty()) {
                wp = WaypointsModule.addHere(mc, nameBox.getValue());
            } else if (xs.isEmpty() || ys.isEmpty() || zs.isEmpty()) {
                status = "Fill in all of X, Y and Z (or none)";
                return;
            } else {
                wp = WaypointsModule.add(mc, nameBox.getValue(), Integer.parseInt(xs), Integer.parseInt(ys), Integer.parseInt(zs));
            }
        } catch (NumberFormatException e) {
            status = "X, Y and Z must be whole numbers";
            return;
        }
        status = "Added: " + wp.name;
        nameText = ""; xText = ""; yText = ""; zText = "";
        page = pageCount() - 1;            // jump to the new waypoint
        this.rebuildWidgets();
    }

    // ---------------------------------------------------------------- drawing

    private void drawDecor(GuiGraphicsExtractor g) {
        int left = px + MARGIN, right = px + pw - MARGIN;

        Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
        Ui.frame(g, px, py, pw, ph, HEADER_H);

        // header
        int headerTextY = py + 1 + (HEADER_H - 8) / 2;
        Ui.txt(g, this.font, "MineBerry", left, headerTextY, Ui.ACCENT, false);
        Ui.txt(g, this.font, "Waypoints", left + this.font.width("MineBerry") + 6, headerTextY, Ui.MUTED, false);
        int count = Config.waypoints().size();
        Ui.headerRight(g, this.font, right, headerTextY, count == 0 ? "No waypoints saved" : count + (count == 1 ? " waypoint" : " waypoints"));

        // add form
        Ui.section(g, this.font, left, right, py + addLabelY, "ADD");
        Ui.inputBox(g, nameBox); Ui.inputBox(g, xBox); Ui.inputBox(g, yBox); Ui.inputBox(g, zBox);
        int labelY = py + inputsY - 10;
        Ui.txt(g, this.font, "Name", left, labelY, Ui.MUTED, false);
        Ui.txt(g, this.font, "X", left + 128, labelY, Ui.MUTED, false);
        Ui.txt(g, this.font, "Y", left + 180, labelY, Ui.MUTED, false);
        Ui.txt(g, this.font, "Z", left + 232, labelY, Ui.MUTED, false);
        Ui.txt(g, this.font, this.font.plainSubstrByWidth(status, pw - 2 * MARGIN - 60 - 60 - 20),
                left + 68, py + buttonsY + 6, Ui.MUTED, false);

        // saved list
        Ui.section(g, this.font, left, right, py + listLabelY, "SAVED");
        List<Config.Waypoint> list = Config.waypoints();
        Minecraft mc = Minecraft.getInstance();
        String here = mc.level != null ? WaypointsModule.dimensionId(mc) : "";
        if (list.isEmpty()) {
            Ui.txt(g, this.font, "No waypoints yet. Press B in game to add one here.", left, py + rowsY + 10, Ui.MUTED, false);
        }
        int textW = pw - 2 * MARGIN - 12 - (34 + 4 + 52 + 8);
        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= list.size()) break;
            Config.Waypoint wp = list.get(index);
            int y = py + rowsY + i * ROW_H;
            int swatch = wp.visible ? wp.color : Ui.MUTED;
            Ui.fill(g, left, y + 7, left + 6, y + 13, swatch);
            Ui.txt(g, this.font, this.font.plainSubstrByWidth(wp.name, textW), left + 12, y + 5, wp.visible ? Ui.TEXT : Ui.MUTED, false);
            String sub = wp.x + ", " + wp.y + ", " + wp.z + "  " + WaypointsModule.shortDimension(wp.dim);
            if (mc.player != null && wp.dim.equals(here)) sub += "  " + WaypointsModule.formatDistance(WaypointsModule.distanceTo(mc, wp));
            Ui.txt(g, this.font, this.font.plainSubstrByWidth(sub, textW), left + 12, y + 16, Ui.MUTED, false);
            if (i < PER_PAGE - 1 && index < list.size() - 1) Ui.divider(g, left, right, y + ROW_H - 1);
        }

        // pager label + footer line
        String pageLabel = (page + 1) + " / " + pageCount();
        Ui.txt(g, this.font, pageLabel, px + pw / 2 - this.font.width(pageLabel) / 2, py + pagerY + 6, Ui.MUTED, false);
        Ui.footerLine(g, left, right, py + footerY);
    }

    // ---------------------------------------------------------------- closing (instant)

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
