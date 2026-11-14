package com.mineberry.essentials;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Lunar-style menu: MODS / SETTINGS / WAYPOINTS tabs, a profiles sidebar, ALL / NEW / HUD chips, a search box
 * and a 3-column grid of mod cards (icon, name, OPTIONS, ENABLED). Same flat look as the other MineBerry screens.
 */
public class LunarMenu extends Screen {

    private static final int HEADER_H = 22, PAD = 8, SIDE_W = 100, COLS = 3, GAP = 6, CARD_H = 91, ROWS = 2, BTN = 16;
    private static final int GREEN = 0xFF2E9E4F, GREEN_EDGE = 0xFF45C76B;

    private static int tab = 0, filter = 0;       // tab: 0 mods, 1 settings; filter: 0 all, 1 new, 2 hud
    private static String search = "";
    private static boolean refocus = false;

    private final Screen parent;
    private final List<AbstractWidget> widgets = new ArrayList<>();      // drawn as generic Lunar buttons
    private final Map<AbstractWidget, Integer> fancy = new HashMap<>();  // drawn by us: 0 chip, 1 toggle, 2 edit-layout, 3 profile
    private final Map<AbstractWidget, Boolean> flag = new HashMap<>();   // selected / enabled state of fancy widgets

    private int px, py, pw, ph, bodyY, mainX, mainW, cardW, gridY;
    private EditBox box;
    private final List<HudMod> shown = new ArrayList<>();
    private final int[] tabX = new int[3], tabW = new int[3];

    public LunarMenu(Screen parent) {
        super(Component.literal("MineBerry Mods"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Ui.applyTheme();
        LunarHud.ensureProfiles();
        widgets.clear(); fancy.clear(); flag.clear(); shown.clear();

        pw = Math.min(440, this.width - 20);
        ph = HEADER_H + PAD + 20 + PAD + ROWS * CARD_H + (ROWS - 1) * GAP + PAD;
        px = (this.width - pw) / 2;
        py = Math.max(6, (this.height - ph) / 2);
        bodyY = py + HEADER_H + PAD;
        mainX = px + PAD + SIDE_W + PAD;
        mainW = px + pw - PAD - mainX;
        cardW = (mainW - (COLS - 1) * GAP) / COLS;
        gridY = bodyY + 20 + PAD;

        this.addRenderableOnly((g, mx, my, d) -> drawDecor(g));

        // header: tabs + close
        String[] names = {"MODS", "SETTINGS", "WAYPOINTS"};
        int tx = px + 150;
        for (int i = 0; i < 3; i++) {
            final int index = i;
            tabX[i] = tx; tabW[i] = this.font.width(names[i]) + 20;
            add(Button.builder(Component.literal(names[i]), b -> {
                if (index == 2) { this.minecraft.gui.setScreen(new WaypointScreen(this)); return; }
                tab = index; this.rebuildWidgets();
            }).bounds(tx, py + 3, tabW[i], 16).build());
            tx += tabW[i] + 6;
        }
        add(Button.builder(Component.literal("x"), b -> onClose()).bounds(px + pw - 22, py + 3, 16, 16).build());

        sidebar();
        if (tab == 0) modsTab(); else settingsTab();

        this.addRenderableOnly((g, mx, my, d) -> overlay(g, mx, my));
    }

    // ---------------------------------------------------------------- sidebar (profiles)

    private void sidebar() {
        int x = px + PAD, y = bodyY;
        for (String name : new ArrayList<>(Config.profiles().keySet())) {
            Button b = add(Button.builder(Component.literal(name), btn -> {
                LunarHud.apply(name); this.rebuildWidgets();
            }).bounds(x, y, SIDE_W, 18).build());
            fancy.put(b, 3);
            flag.put(b, name.equals(Config.activeProfile()));
            y += 20;
        }
        boolean room = Config.profiles().size() < 6;
        Button save = add(Button.builder(Component.literal("Save as new profile"), btn -> {
            String name = "Profile " + Config.profiles().size();
            Config.profiles().put(name, LunarHud.snapshot());
            Config.setActiveProfile(name);
            this.rebuildWidgets();
        }).bounds(x, py + ph - PAD - 18 - 22, SIDE_W, 18).build());
        save.active = room;
        Button edit = add(Button.builder(Component.literal("EDIT HUD LAYOUT"), btn -> this.minecraft.gui.setScreen(new HudEditScreen(this)))
                .bounds(x, py + ph - PAD - 18, SIDE_W, 18).build());
        fancy.put(edit, 2);
    }

    // ---------------------------------------------------------------- mods tab

    private void modsTab() {
        String[] chips = {"ALL", "NEW", "HUD"};
        int x = mainX;
        for (int i = 0; i < 3; i++) {
            final int index = i;
            int w = this.font.width(chips[i]) + 16;
            Button b = add(Button.builder(Component.literal(chips[i]), btn -> { filter = index; this.rebuildWidgets(); })
                    .bounds(x, bodyY, w, 20).build());
            fancy.put(b, 0);
            flag.put(b, filter == i);
            x += w + 4;
        }
        int sx = x + 4;
        box = new EditBox(this.font, sx + 4 + 12, bodyY + 6, px + pw - PAD - sx - 8 - 12, 12, Component.literal("Search"));
        box.setBordered(false);
        box.setMaxLength(20);
        box.setValue(search);
        box.setResponder(v -> { if (!v.equals(search)) { search = v; refocus = true; this.rebuildWidgets(); } });
        add(box);
        if (refocus) { this.setInitialFocus(box); refocus = false; }

        String q = search.toLowerCase(Locale.ROOT).trim();
        for (HudMod m : LunarHud.MODS) {
            if (filter == 1 && !m.fresh()) continue;
            if (!q.isEmpty() && !m.name().toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(m);
        }
        for (int i = 0; i < Math.min(shown.size(), COLS * ROWS); i++) {
            HudMod mod = shown.get(i);
            int cx = mainX + (i % COLS) * (cardW + GAP), cy = gridY + (i / COLS) * (CARD_H + GAP);
            int by = cy + CARD_H - 4 - BTN;
            add(Button.builder(Component.literal(LunarHud.CORNERS[mod.corner()].toUpperCase(Locale.ROOT)), btn -> {
                mod.nextCorner(); LunarHud.sync();
                btn.setMessage(Component.literal(LunarHud.CORNERS[mod.corner()].toUpperCase(Locale.ROOT)));
            }).bounds(cx + 4, by - BTN - 2, cardW - 8, BTN).build());
            Button t = add(Button.builder(Component.literal("T"), btn -> {
                mod.setEnabled(!mod.enabled()); LunarHud.sync();
                flag.put(btn, mod.enabled());
            }).bounds(cx + 4, by, cardW - 8, BTN).build());
            fancy.put(t, 1);
            flag.put(t, mod.enabled());
        }
    }

    // ---------------------------------------------------------------- settings tab

    private void settingsTab() {
        int w = Math.min(200, mainW), x = mainX, y = bodyY;
        add(Button.builder(Component.literal("Theme: " + Ui.themeName()), b -> {
            Config.setPanelTheme(Ui.nextTheme()); Ui.applyTheme();
            b.setMessage(Component.literal("Theme: " + Ui.themeName()));
        }).bounds(x, y, w, 20).build());
        y += 26;
        add(Button.builder(Component.literal("HUD: " + (Config.hudHidden() ? "Hidden" : "Shown")), b -> {
            Config.setHudHidden(!Config.hudHidden());
            b.setMessage(Component.literal("HUD: " + (Config.hudHidden() ? "Hidden" : "Shown")));
        }).bounds(x, y, w, 20).build());
        y += 26;
        add(Button.builder(Component.literal("Control Center"), b -> this.minecraft.gui.setScreen(new PanelScreen(this)))
                .bounds(x, y, w, 20).build());
        y += 26;
        add(Button.builder(Component.literal("Gradient Generator"), b -> this.minecraft.gui.setScreen(new GradientScreen(this)))
                .bounds(x, y, w, 20).build());
    }

    private <T extends AbstractWidget> T add(T widget) {
        widgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    // ---------------------------------------------------------------- drawing

    private void drawDecor(GuiGraphicsExtractor g) {
        Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
        Ui.frame(g, px, py, pw, ph, HEADER_H);
        Ui.txt(g, this.font, "MINEBERRY", px + PAD + 2, py + 1 + (HEADER_H - 8) / 2, Ui.ACCENT, false);
        Ui.txt(g, this.font, "CLIENT", px + PAD + 2 + this.font.width("MINEBERRY ") , py + 1 + (HEADER_H - 8) / 2, Ui.TEXT, false);
        int sel = tab;
        Ui.fill(g, tabX[sel], py + 19, tabX[sel] + tabW[sel], py + 21, Ui.ACCENT);
        // sidebar panel
        Ui.box(g, px + PAD - 2, bodyY - 2, SIDE_W + 4, py + ph - PAD - bodyY + 4, Ui.BTN_BORDER, Ui.INSET, 0, 0);
        if (tab != 0) return;
        Ui.inputBox(g, box);
        Ui.txt(g, this.font, "/", box.getX() - 10, bodyY + 6, Ui.MUTED, false);
        for (int i = 0; i < Math.min(shown.size(), COLS * ROWS); i++) {
            HudMod mod = shown.get(i);
            int cx = mainX + (i % COLS) * (cardW + GAP), cy = gridY + (i / COLS) * (CARD_H + GAP);
            Ui.box(g, cx, cy, cardW, CARD_H, Ui.BTN_BORDER, Ui.BTN, 0, 0);
            int col = mod.enabled() ? Ui.TEXT : Ui.MUTED;
            icon(g, mod.id(), cx + cardW / 2, cy + 6, col);
            String n = this.font.plainSubstrByWidth(mod.name(), cardW - 8);
            Ui.txt(g, this.font, n, cx + (cardW - this.font.width(n)) / 2, cy + 40, col, false);
        }
        if (shown.isEmpty()) Ui.txt(g, this.font, "No mods found", mainX, gridY + 4, Ui.MUTED, false);
    }

    /** Small pixel icons, drawn with rectangles (centered on cxm, top at y; about 28 x 28). */
    private void icon(GuiGraphicsExtractor g, String id, int cxm, int y, int c) {
        int x = cxm - 14;
        switch (id) {
            case "fps" -> { Ui.fill(g, x + 2, y + 18, x + 8, y + 28, c); Ui.fill(g, x + 11, y + 10, x + 17, y + 28, c); Ui.fill(g, x + 20, y + 2, x + 26, y + 28, c); }
            case "ping" -> { for (int i = 0; i < 4; i++) Ui.fill(g, x + 2 + i * 6, y + 26 - i * 7, x + 6 + i * 6, y + 28, c); }
            case "clock" -> { Ui.fill(g, x + 2, y + 2, x + 26, y + 4, c); Ui.fill(g, x + 2, y + 26, x + 26, y + 28, c);
                Ui.fill(g, x + 2, y + 2, x + 4, y + 28, c); Ui.fill(g, x + 24, y + 2, x + 26, y + 28, c);
                Ui.fill(g, x + 13, y + 6, x + 15, y + 16, c); Ui.fill(g, x + 13, y + 14, x + 21, y + 16, c); }
            case "sprint" -> { for (int i = 0; i < 2; i++) { int o = i * 10; Ui.fill(g, x + 4 + o, y + 4, x + 8 + o, y + 10, c); Ui.fill(g, x + 8 + o, y + 10, x + 12 + o, y + 18, c); Ui.fill(g, x + 4 + o, y + 18, x + 8 + o, y + 24, c); } }
            case "coords" -> { Ui.fill(g, x + 13, y + 2, x + 15, y + 16, c); Ui.fill(g, x + 3, y + 22, x + 14, y + 24, c); Ui.fill(g, x + 14, y + 16, x + 24, y + 18, c); Ui.fill(g, x + 22, y + 22, x + 26, y + 26, c); }
            default -> Ui.txt(g, this.font, id.substring(0, 1).toUpperCase(Locale.ROOT), cxm - 3, y + 10, c, false);
        }
    }

    /** Draws chips, ENABLED buttons, profile rows and the blue layout button over the vanilla widgets, then the rest. */
    private void overlay(GuiGraphicsExtractor g, int mx, int my) {
        Ui.buttons(g, this.font, widgets.stream().filter(w -> !fancy.containsKey(w)).toList(), new HashMap<>(), mx, my);
        int solid = Ui.BG | 0xFF000000;
        for (Map.Entry<AbstractWidget, Integer> e : fancy.entrySet()) {
            AbstractWidget w = e.getKey();
            int x = w.getX(), y = w.getY(), bw = w.getWidth(), bh = w.getHeight();
            boolean hover = mx >= x && mx < x + bw && my >= y && my < y + bh, on = flag.getOrDefault(w, false);
            String label = w.getMessage().getString();
            Ui.fill(g, x, y, x + bw, y + bh, solid);
            switch (e.getValue()) {
                case 0 -> { Ui.box(g, x, y, bw, bh, on ? Ui.ACCENT : Ui.BTN_BORDER, on ? Ui.BORDER : (hover ? Ui.BTN_HOVER : Ui.BTN), 0, 0);
                    Ui.txt(g, this.font, label, x + (bw - this.font.width(label)) / 2, y + (bh - 8) / 2 + 1, on ? 0xFFFFFFFF : Ui.MUTED, false); }
                case 1 -> { String t = on ? "ENABLED" : "DISABLED";
                    Ui.box(g, x, y, bw, bh, on ? GREEN_EDGE : Ui.BTN_BORDER, on ? GREEN : (hover ? Ui.BTN_HOVER : Ui.BTN), 0, 0);
                    Ui.txt(g, this.font, t, x + (bw - this.font.width(t)) / 2, y + (bh - 8) / 2 + 1, on ? 0xFFFFFFFF : Ui.MUTED, false); }
                case 2 -> { Ui.box(g, x, y, bw, bh, Ui.ACCENT, hover ? Ui.ACCENT : Ui.BORDER, 0, 0);
                    Ui.txt(g, this.font, label, x + (bw - this.font.width(label)) / 2, y + (bh - 8) / 2 + 1, 0xFFFFFFFF, false); }
                default -> { Ui.box(g, x, y, bw, bh, on ? Ui.BTN_BORDER_HOVER : Ui.BTN_BORDER, on ? Ui.BTN_HOVER : (hover ? Ui.BTN_HOVER : Ui.BTN), 0, 0);
                    Ui.txt(g, this.font, label, x + 8, y + (bh - 8) / 2 + 1, on ? Ui.TEXT : Ui.MUTED, false); }
            }
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
