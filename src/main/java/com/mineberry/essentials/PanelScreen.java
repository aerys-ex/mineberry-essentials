package com.mineberry.essentials;

import com.mineberry.essentials.modules.EvidenceModule;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The MineBerry Essentials control panel. A top bar (MODULES / TOOLS tabs, close), and below it one flat panel:
 * MODULES shows a category filter, a search box and a scrollable grid of cards (name, icon, Enabled / Disabled);
 * TOOLS holds the coordinate converter, the evidence export and the appearance buttons.
 */
public class PanelScreen extends Screen {

    private static final int PANEL_W_MAX = 540, PAD = 10, GAP = 6, SCROLL_W = 4, MIN_CARD_W = 100;
    private static final int TOP_H = 22, TOP_GAP = 6, FILTER_H = 20, FILTER_GAP = 8, CARD_H = 72, BTN_H = 20;
    /** Everything on the MODULES page except the card grid: padding, filter row, gaps and the hint line. */
    private static final int MODS_OVERHEAD = PAD + FILTER_H + FILTER_GAP + 8 + 8 + PAD;

    // TOOLS page, all y values relative to the panel top
    private static final int CONV_LABEL_Y = PAD, CONV_Y = CONV_LABEL_Y + 14, CONV_BAR_Y = CONV_Y + BTN_H + 4;
    private static final int EV_LABEL_Y = CONV_BAR_Y + 16 + 10, EV_Y = EV_LABEL_Y + 14;
    private static final int APP_LABEL_Y = EV_Y + 28 + 10, APP_Y = APP_LABEL_Y + 14;
    private static final int UPD_LABEL_Y = APP_Y + BTN_H + 10, UPD_Y = UPD_LABEL_Y + 14;
    private static final int TOOLS_H = UPD_Y + BTN_H + PAD + 2;

    private static final int GREEN_FILL = 0xFF1B5E2C, GREEN_EDGE = 0xFF2C7D42, GREEN_TEXT = 0xFF6EE08C;

    private static final String[] CATEGORIES = {"All", "Chat", "Safety", "Tools"};
    private static final Map<String, String> CATEGORY_OF = Map.of(
            "clan_chat", "Chat", "admin_chat", "Chat", "party_chat", "Chat", "chat_extras", "Chat", "quick_reply", "Chat",
            "spawn_escape", "Safety", "tp_danger_escape", "Safety",
            "evidence", "Tools", "worldeditcui", "Tools", "waypoints", "Tools");

    /** One card: a module or an option. */
    private static final class Entry {
        final String id, name, hint, category;
        final BooleanSupplier on;
        final Runnable toggle;
        final String gearHint;     // null = no settings button
        final Runnable gearAction;
        Button card, gear;

        Entry(String id, String name, String hint, String category, BooleanSupplier on, Runnable toggle, String gearHint, Runnable gearAction) {
            this.id = id; this.name = name; this.hint = hint; this.category = category;
            this.on = on; this.toggle = toggle; this.gearHint = gearHint; this.gearAction = gearAction;
        }
    }

    private final Screen parent;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Entry> shown = new ArrayList<>();      // entries that pass the filter, in grid order
    private final List<Button> pageTabs = new ArrayList<>();
    private final List<Button> catTabs = new ArrayList<>();
    private final List<Button> plain = new ArrayList<>();     // buttons painted in the normal outlined style
    private Button closeBtn, checkBtn, updateBtn;
    private EditBox searchBox, xInput, zInput;

    private int page = 0;                // 0 = MODULES, 1 = TOOLS
    private int category = 0;
    private String search = "";
    private int scroll = 0;              // first visible grid row

    private String xText = "", zText = "";
    private String reportStatus = "No report exported this session";
    private String conversionResult = "Enter X and Z, then pick a direction";
    private boolean conversionOk = false;

    private int px, py, pw, ph, topY, tabsEnd;
    private int cols, cardW, visibleRows, gridX, gridY, searchX, searchW;

    public PanelScreen(Screen parent) {
        super(Component.literal("MineBerry Essentials"));
        this.parent = parent;
    }

    // ---------------------------------------------------------------- setup

    @Override
    protected void init() {
        Ui.applyTheme();
        // keep what was typed when the screen is rebuilt (resize, switching tab)
        if (xInput != null) xText = xInput.getValue();
        if (zInput != null) zText = zInput.getValue();
        if (searchBox != null) search = searchBox.getValue();
        entries.clear(); shown.clear(); pageTabs.clear(); catTabs.clear(); plain.clear();
        closeBtn = null; checkBtn = null; updateBtn = null; searchBox = null; xInput = null; zInput = null;

        buildEntries();

        // size: the card grid adapts to the window, the panel never grows past it
        pw = Math.min(PANEL_W_MAX, this.width - 16);
        int gridW = pw - 2 * PAD - SCROLL_W - 4;
        cols = Math.max(2, Math.min(4, (gridW + GAP) / (MIN_CARD_W + GAP)));
        cardW = (gridW - (cols - 1) * GAP) / cols;
        int totalRows = Math.max(1, (entries.size() + cols - 1) / cols);
        int rowsFit = Math.max(1, (this.height - 12 - TOP_H - TOP_GAP - MODS_OVERHEAD + GAP) / (CARD_H + GAP));
        int rows = Math.min(totalRows, rowsFit);
        ph = Math.max(TOOLS_H, MODS_OVERHEAD + rows * CARD_H + (rows - 1) * GAP);
        visibleRows = Math.max(1, Math.min(totalRows, (ph - MODS_OVERHEAD + GAP) / (CARD_H + GAP)));
        topY = Math.max(6, (this.height - (TOP_H + TOP_GAP + ph)) / 2);
        py = topY + TOP_H + TOP_GAP;
        px = (this.width - pw) / 2;
        gridX = px + PAD;
        gridY = py + PAD + FILTER_H + FILTER_GAP;

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> drawDecor(g, mouseX, mouseY));

        initTopBar();
        UpdateChecker.check();   // no-op if already started or disabled
        if (page == 0) initModules(); else initTools();

        // painted over the vanilla buttons (added last so it renders on top)
        this.addRenderableOnly((g, mouseX, mouseY, delta) -> paintWidgets(g, mouseX, mouseY));
    }

    private <T extends AbstractWidget> T add(T widget) {
        return this.addRenderableWidget(widget);
    }

    private Button addPlain(Button button) {
        plain.add(button);
        return add(button);
    }

    private void buildEntries() {
        for (MineBerryModule module : MineBerryEssentials.modules()) {
            String gearHint = null;
            Runnable gearAction = null;
            if (module.id().equals("waypoints")) {
                gearHint = "Open the waypoint list";
                gearAction = () -> this.minecraft.gui.setScreen(new WaypointScreen(this));
            } else if (module.id().equals("worldeditcui")) {
                gearHint = "WorldEdit CUI settings";
                gearAction = () -> {
                    var cui = org.enginehub.worldeditcui.fabric.FabricModWorldEditCUI.getInstance();
                    if (cui != null && cui.getController() != null) {
                        this.minecraft.gui.setScreen(new org.enginehub.worldeditcui.gui.CUIConfigPanel(this, cui.getController().getConfiguration()));
                    }
                };
            }
            entries.add(new Entry(module.id(), module.name(), module.description(),
                    CATEGORY_OF.getOrDefault(module.id(), "Tools"), module::enabled, module::toggle, gearHint, gearAction));
        }
        entries.add(new Entry("opt_close_on_danger", "Close on danger",
                "Close this panel when a PvP alert fires or you take damage", "Safety",
                Config::autoClosePanelOnDanger, () -> Config.setAutoClosePanelOnDanger(!Config.autoClosePanelOnDanger()), null, null));
        entries.add(new Entry("opt_chat_timestamps", "Chat timestamps",
                "Add a timestamp to chat messages", "Chat",
                Config::chatTimestamps, () -> Config.setChatTimestamps(!Config.chatTimestamps()), null, null));
    }

    private void initTopBar() {
        String[] names = {"MODULES", "TOOLS"};
        int x = px;
        for (int i = 0; i < names.length; i++) {
            final int target = i;
            int w = this.font.width(names[i]) + 24;
            pageTabs.add(add(Button.builder(Component.literal(names[i]), button -> switchPage(target))
                    .bounds(x, topY, w, TOP_H).build()));
            x += w + 4;
        }
        tabsEnd = x - 4;
        closeBtn = add(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(px + pw - TOP_H, topY, TOP_H, TOP_H).build());
    }

    private void switchPage(int target) {
        if (page == target) return;
        page = target;
        this.rebuildWidgets();
    }

    private void initModules() {
        int right = px + pw - PAD;
        int fy = py + PAD;

        // category tabs
        int x = px + PAD;
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int index = i;
            int w = this.font.width(CATEGORIES[i]) + 20;
            catTabs.add(add(Button.builder(Component.literal(CATEGORIES[i]), button -> { category = index; scroll = 0; relayout(); })
                    .bounds(x, fy, w, FILTER_H).build()));
            x += w + 4;
        }

        // reset (only when there is room) + search box on the right
        int resetW = this.font.width("Reset") + 16;
        int free = right - (x - 4) - 8;
        boolean showReset = free - resetW - 4 >= 80;
        searchW = Math.max(40, Math.min(150, free - (showReset ? resetW + 4 : 0)));
        searchX = right - searchW;
        if (showReset) {
            addPlain(Button.builder(Component.literal("Reset"), button -> resetFilters())
                    .bounds(searchX - 4 - resetW, fy, resetW, FILTER_H).build());
        }
        // borderless field, padded inside the box that drawDecor() paints behind it
        searchBox = new EditBox(this.font, searchX + 18, fy + 6, searchW - 22, 12, Component.literal("Search"));
        searchBox.setBordered(false);
        searchBox.setMaxLength(24);
        searchBox.setValue(search);
        add(searchBox);

        // cards (the settings button is added first so it wins clicks where it overlaps its card)
        for (Entry entry : entries) {
            if (entry.gearAction != null) {
                entry.gear = add(Button.builder(Component.literal("Settings"), button -> entry.gearAction.run())
                        .bounds(0, 0, 18, 18).build());
            }
            entry.card = add(Button.builder(Component.literal(entry.name), button -> entry.toggle.run())
                    .bounds(0, 0, cardW, CARD_H).build());
        }
        relayout();
    }

    private void initTools() {
        int left = px + PAD, right = px + pw - PAD;

        // converter
        int cy = py + CONV_Y;
        // borderless fields, padded inside the boxes that drawDecor() paints behind them
        xInput = new EditBox(this.font, left + 12 + 4, cy + 6, 62, 12, Component.literal("X coordinate"));
        zInput = new EditBox(this.font, left + 104 + 4, cy + 6, 62, 12, Component.literal("Z coordinate"));
        xInput.setBordered(false); zInput.setBordered(false);
        xInput.setMaxLength(18); zInput.setMaxLength(18);
        xInput.setValue(xText); zInput.setValue(zText);
        add(xInput); add(zInput);
        addPlain(Button.builder(Component.literal("To Overworld"), button -> convert(8.0, "Overworld"))
                .bounds(right - 78 - 4 - 78, cy, 78, BTN_H).build());
        addPlain(Button.builder(Component.literal("To Nether"), button -> convert(0.125, "Nether"))
                .bounds(right - 78, cy, 78, BTN_H).build());

        // evidence
        addPlain(Button.builder(Component.literal("Export"), button -> exportReport())
                .bounds(right - 50, py + EV_Y + 4, 50, BTN_H).build());
        addPlain(Button.builder(Component.literal("Copy"), button -> copyReport())
                .bounds(right - 50 - 4 - 36, py + EV_Y + 4, 36, BTN_H).build());

        // appearance
        addPlain(Button.builder(Component.literal("Theme: " + Ui.themeName()), button -> {
            Config.setPanelTheme(Ui.nextTheme());
            Ui.applyTheme();
            button.setMessage(Component.literal("Theme: " + Ui.themeName()));
        }).bounds(left, py + APP_Y, 100, BTN_H).build());
        addPlain(Button.builder(Component.literal("Gradient"), button -> this.minecraft.gui.setScreen(new GradientScreen(this)))
                .bounds(left + 104, py + APP_Y, 80, BTN_H).build());
        // updates: manual check, plus the download button once a newer version is known
        int uy = py + UPD_Y;
        checkBtn = addPlain(Button.builder(Component.literal("Check for updates"), button -> UpdateChecker.checkNow())
                .bounds(left, uy, 120, BTN_H).build());
        updateBtn = addPlain(Button.builder(Component.literal("Update"), button -> {
            UpdateChecker.Result r = UpdateChecker.result();
            if (r != null) net.minecraft.util.Util.getPlatform().openUri(java.net.URI.create(r.url()));
        }).bounds(right - 140, uy, 140, BTN_H).build());
        updateBtn.visible = false;
    }

    // ---------------------------------------------------------------- grid + filter

    private void resetFilters() {
        category = 0;
        search = "";
        scroll = 0;
        if (searchBox != null) searchBox.setValue("");
        relayout();
    }

    private int totalGridRows() {
        return (shown.size() + cols - 1) / cols;
    }

    private int maxScroll() {
        return Math.max(0, totalGridRows() - visibleRows);
    }

    /** Applies the filter and scroll position: moves the visible cards into the grid and hides the rest. */
    private void relayout() {
        shown.clear();
        String query = search.trim().toLowerCase(Locale.ROOT);
        for (Entry entry : entries) {
            boolean categoryOk = category == 0 || CATEGORIES[category].equals(entry.category);
            boolean textOk = query.isEmpty() || entry.name.toLowerCase(Locale.ROOT).contains(query);
            if (categoryOk && textOk) shown.add(entry);
        }
        scroll = Math.max(0, Math.min(scroll, maxScroll()));
        for (Entry entry : entries) {
            entry.card.visible = false;
            if (entry.gear != null) entry.gear.visible = false;
        }
        for (int i = 0; i < shown.size(); i++) {
            int row = i / cols - scroll;
            if (row < 0 || row >= visibleRows) continue;
            Entry entry = shown.get(i);
            int x = gridX + (i % cols) * (cardW + GAP), y = gridY + row * (CARD_H + GAP);
            entry.card.setX(x); entry.card.setY(y); entry.card.visible = true;
            if (entry.gear != null) {
                entry.gear.setX(x + 5); entry.gear.setY(y + CARD_H - 24); entry.gear.visible = true;
            }
        }
    }

    /** Mouse wheel scrolls the card grid one row at a time. */
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (page == 0 && scrollY != 0 && maxScroll() > 0) {
            scroll += scrollY > 0 ? -1 : 1;
            relayout();
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- tools

    private void exportReport() {
        for (MineBerryModule module : MineBerryEssentials.modules()) if (module instanceof EvidenceModule evidence) {
            Path zip = evidence.exportLatest();
            reportStatus = zip == null ? "No incident recorded yet" : "Saved: reports/" + zip.getFileName();
            return;
        }
    }

    private void copyReport() {
        for (MineBerryModule module : MineBerryEssentials.modules()) if (module instanceof EvidenceModule evidence) {
            reportStatus = evidence.copyLatestReport() ? "Report text copied to clipboard" : "No incident recorded yet";
            return;
        }
    }

    private void convert(double multiplier, String target) {
        try {
            double x = Double.parseDouble(xInput.getValue().trim()), z = Double.parseDouble(zInput.getValue().trim());
            conversionResult = target + "   X " + formatCoordinate(x * multiplier) + "   Z " + formatCoordinate(z * multiplier);
            conversionOk = true;
        } catch (NumberFormatException ignored) {
            conversionResult = "Use valid numbers for both X and Z";
            conversionOk = false;
        }
    }

    private static String formatCoordinate(double value) {
        return String.format(Locale.ROOT, "%.3f", value).replaceAll("(\\.\\d*?[1-9])0+$|\\.0+$", "$1");
    }

    private static boolean updateAvailable() {
        UpdateChecker.Result update = UpdateChecker.result();
        return update != null && update.updateAvailable();
    }

    // ---------------------------------------------------------------- drawing: backgrounds and text

    private void drawDecor(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        // keep the filter in sync with what is typed in the search box
        if (page == 0 && searchBox != null && !searchBox.getValue().equals(search)) {
            search = searchBox.getValue();
            scroll = 0;
            relayout();
        }

        Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
        Ui.rect(g, px, py, pw, ph, Ui.BORDER, Ui.BG);

        // brand in the top bar, only when there is room for it
        int textY = topY + (TOP_H - 8) / 2 + 1;
        int brandW = this.font.width("MineBerry") + 6 + this.font.width("Essentials");
        int brandX = px + pw - TOP_H - 10 - brandW;
        if (brandX > tabsEnd + 12) {
            Ui.txt(g, this.font, "MineBerry", brandX, textY, Ui.ACCENT, false);
            Ui.txt(g, this.font, "Essentials", brandX + this.font.width("MineBerry") + 6, textY, Ui.MUTED, false);
        }

        if (page == 0) drawModules(g, mouseX, mouseY); else drawTools(g);
    }

    private void drawModules(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int left = px + PAD, right = px + pw - PAD;
        int fy = py + PAD;

        // search box
        Ui.rect(g, searchX, fy, searchW, FILTER_H, searchBox.isFocused() ? Ui.STRONG : Ui.BORDER, Ui.INSET);
        Icons.draw(g, Icons.SEARCH, searchX + 6, fy + 6, 1, Ui.MUTED);
        if (searchBox.getValue().isEmpty() && !searchBox.isFocused()) {
            Ui.txt(g, this.font, "Search", searchBox.getX(), searchBox.getY() + (searchBox.getHeight() - 8) / 2, Ui.MUTED, false);
        }

        if (shown.isEmpty()) {
            String none = "No modules found";
            Ui.txt(g, this.font, none, left + (cardW * cols + GAP * (cols - 1) - this.font.width(none)) / 2, gridY + 24, Ui.MUTED, false);
        }

        // scrollbar
        if (maxScroll() > 0) {
            int trackX = right - SCROLL_W, trackH = visibleRows * CARD_H + (visibleRows - 1) * GAP;
            Ui.fill(g, trackX, gridY, trackX + SCROLL_W, gridY + trackH, Ui.INSET);
            int thumbH = Math.max(16, trackH * visibleRows / totalGridRows());
            int thumbY = gridY + (trackH - thumbH) * scroll / maxScroll();
            Ui.fill(g, trackX, thumbY, trackX + SCROLL_W, thumbY + thumbH, Ui.STRONG);
        }

        // hint line: what the hovered card does
        String hint = "Click a card to switch it on or off";
        for (Entry entry : shown) {
            if (entry.gear != null && over(entry.gear, mouseX, mouseY)) { hint = entry.gearHint; break; }
            if (over(entry.card, mouseX, mouseY)) { hint = entry.hint; break; }
        }
        Ui.txt(g, this.font, this.font.plainSubstrByWidth(hint, right - left), left, py + ph - PAD - 8, Ui.MUTED, false);
    }

    private void drawTools(GuiGraphicsExtractor g) {
        int left = px + PAD, right = px + pw - PAD;

        // converter
        Ui.section(g, this.font, left, right, py + CONV_LABEL_Y, "COORDINATE CONVERTER");
        int cy = py + CONV_Y;
        Ui.txt(g, this.font, "X", left, cy + 6, Ui.MUTED, false);
        Ui.txt(g, this.font, "Z", left + 92, cy + 6, Ui.MUTED, false);
        drawInput(g, xInput);
        drawInput(g, zInput);
        int barY = py + CONV_BAR_Y;
        Ui.fill(g, left, barY, right, barY + 16, Ui.INSET);
        Ui.txt(g, this.font, this.font.plainSubstrByWidth(conversionResult, right - left - 12), left + 6, barY + 4,
                conversionOk ? Ui.ACCENT : Ui.MUTED, false);

        // evidence
        Ui.section(g, this.font, left, right, py + EV_LABEL_Y, "EVIDENCE");
        Ui.txt(g, this.font, "Export latest incident (.zip)", left, py + EV_Y + 5, Ui.TEXT, false);
        Ui.txt(g, this.font, this.font.plainSubstrByWidth(reportStatus, pw - 2 * PAD - 50 - 4 - 36 - 12), left, py + EV_Y + 16, Ui.MUTED, false);

        // appearance
        Ui.section(g, this.font, left, right, py + APP_LABEL_Y, "APPEARANCE");

        // updates
        Ui.section(g, this.font, left, right, py + UPD_LABEL_Y, "UPDATES");
        UpdateChecker.State state = UpdateChecker.state();
        UpdateChecker.Result update = UpdateChecker.result();
        boolean hasUpdate = updateAvailable();
        if (checkBtn != null) checkBtn.active = state != UpdateChecker.State.CHECKING;
        if (updateBtn != null) {
            updateBtn.visible = hasUpdate;
            if (hasUpdate) updateBtn.setMessage(Component.literal("Update: v" + update.latest()));
        }
        String status;
        int statusColor = Ui.MUTED;
        switch (state) {
            case CHECKING -> status = "Checking...";
            case UP_TO_DATE -> { status = "You're up to date (v" + UpdateChecker.installed() + ")"; statusColor = Ui.ACCENT; }
            case UPDATE -> { status = "New version: v" + update.latest() + " (you have v" + update.current() + ")"; statusColor = Ui.ACCENT; }
            case FAILED -> status = "Couldn't reach GitHub. Try again";
            default -> status = "You have v" + UpdateChecker.installed();
        }
        int statusX = left + 120 + 8;
        int statusMax = (hasUpdate ? right - 140 - 8 : right) - statusX;
        Ui.txt(g, this.font, this.font.plainSubstrByWidth(status, Math.max(0, statusMax)), statusX, py + UPD_Y + 6, statusColor, false);
    }

    /** Dark square field behind a borderless EditBox. */
    private void drawInput(GuiGraphicsExtractor g, EditBox box) {
        Ui.rect(g, box.getX() - 4, box.getY() - 6, box.getWidth() + 8, 20,
                box.isFocused() ? Ui.STRONG : Ui.BORDER, Ui.INSET);
    }

    // ---------------------------------------------------------------- drawing: widgets (on top of the vanilla buttons)

    private static boolean over(AbstractWidget w, int mouseX, int mouseY) {
        return w.visible && mouseX >= w.getX() && mouseX < w.getX() + w.getWidth()
                && mouseY >= w.getY() && mouseY < w.getY() + w.getHeight();
    }

    private void paintWidgets(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int solid = Ui.BG | 0xFF000000;

        for (int i = 0; i < pageTabs.size(); i++) paintTab(g, pageTabs.get(i), i == page, mouseX, mouseY, solid);
        if (page != 1 && updateAvailable()) {
            Button tools = pageTabs.get(1);
            Ui.fill(g, tools.getX() + tools.getWidth() - 8, tools.getY() + 5, tools.getX() + tools.getWidth() - 4, tools.getY() + 9, Ui.STRONG);
        }
        paintClose(g, closeBtn, mouseX, mouseY, solid);

        if (page == 0) {
            for (int i = 0; i < catTabs.size(); i++) paintTab(g, catTabs.get(i), i == category, mouseX, mouseY, solid);
            for (Entry entry : shown) {
                if (entry.card.visible) paintCard(g, entry, mouseX, mouseY, solid);
            }
        }
        for (Button button : plain) {
            if (button.visible) paintButton(g, button, mouseX, mouseY, solid);
        }
    }

    /** Tab: the selected one is filled with the accent color, the others are outlined. */
    private void paintTab(GuiGraphicsExtractor g, Button b, boolean active, int mouseX, int mouseY, int solid) {
        int x = b.getX(), y = b.getY(), w = b.getWidth(), h = b.getHeight();
        boolean hover = over(b, mouseX, mouseY);
        Ui.fill(g, x, y, x + w, y + h, solid);   // hide the vanilla button completely
        int textColor;
        if (active) {
            Ui.fill(g, x, y, x + w, y + h / 2, Ui.STRONG);
            Ui.fill(g, x, y + h / 2, x + w, y + h, Ui.lerp(Ui.STRONG, 0xFF000000, 0.18f));
            textColor = 0xFFFFFFFF;
        } else {
            Ui.rect(g, x, y, w, h, hover ? Ui.lerp(Ui.BORDER, 0xFFFFFFFF, 0.25f) : Ui.BORDER,
                    Ui.lerp(solid, 0xFFFFFFFF, hover ? 0.09f : 0.04f));
            textColor = hover ? Ui.TEXT : Ui.lerp(Ui.MUTED, Ui.TEXT, 0.5f);
        }
        String label = b.getMessage().getString();
        Ui.txt(g, this.font, label, x + (w - this.font.width(label)) / 2, y + (h - 8) / 2 + 1, textColor, false);
    }

    /** Plain outlined button (Reset, converter, evidence, appearance). */
    private void paintButton(GuiGraphicsExtractor g, Button b, int mouseX, int mouseY, int solid) {
        int x = b.getX(), y = b.getY(), w = b.getWidth(), h = b.getHeight();
        boolean hover = b.active && over(b, mouseX, mouseY);
        Ui.fill(g, x, y, x + w, y + h, solid);
        Ui.rect(g, x, y, w, h, hover ? Ui.lerp(Ui.BORDER, 0xFFFFFFFF, 0.25f) : Ui.BORDER,
                Ui.lerp(solid, 0xFFFFFFFF, hover ? 0.09f : 0.04f));
        String label = b.getMessage().getString();
        Ui.txt(g, this.font, label, x + (w - this.font.width(label)) / 2, y + (h - 8) / 2 + 1, b.active ? Ui.TEXT : Ui.MUTED, false);
    }

    private void paintClose(GuiGraphicsExtractor g, Button b, int mouseX, int mouseY, int solid) {
        int x = b.getX(), y = b.getY(), w = b.getWidth(), h = b.getHeight();
        boolean hover = over(b, mouseX, mouseY);
        Ui.fill(g, x, y, x + w, y + h, solid);
        Ui.rect(g, x, y, w, h, hover ? Ui.lerp(Ui.BORDER, 0xFFFFFFFF, 0.25f) : Ui.BORDER,
                Ui.lerp(solid, 0xFFFFFFFF, hover ? 0.09f : 0.04f));
        int cx = x + w / 2 - 3, cy = y + h / 2 - 3, color = hover ? Ui.TEXT : Ui.MUTED;
        for (int i = 0; i < 7; i++) {
            Ui.fill(g, cx + i, cy + i, cx + i + 1, cy + i + 1, color);
            Ui.fill(g, cx + 6 - i, cy + i, cx + 7 - i, cy + i + 1, color);
        }
    }

    /** Card: name on top, icon in the middle, settings gear bottom left, Enabled / Disabled bottom right. */
    private void paintCard(GuiGraphicsExtractor g, Entry entry, int mouseX, int mouseY, int solid) {
        int x = entry.card.getX(), y = entry.card.getY(), w = entry.card.getWidth(), h = entry.card.getHeight();
        boolean on = entry.on.getAsBoolean();
        boolean hover = over(entry.card, mouseX, mouseY);

        Ui.fill(g, x, y, x + w, y + h, solid);   // hide the vanilla button completely
        Ui.rect(g, x, y, w, h, hover ? Ui.lerp(Ui.BORDER, 0xFFFFFFFF, 0.25f) : Ui.BORDER,
                Ui.lerp(solid, 0xFFFFFFFF, hover ? 0.06f : 0.025f));
        if (on) Ui.fill(g, x, y, x + w, y + 2, Ui.STRONG);   // accent line on top of enabled cards

        int dim = Ui.lerp(Ui.MUTED, Ui.TEXT, 0.6f);
        Ui.txt(g, this.font, this.font.plainSubstrByWidth(entry.name, w - 14), x + 7, y + 8, on ? Ui.TEXT : dim, false);

        String[] icon = Icons.forId(entry.id);
        Icons.draw(g, icon, x + (w - Icons.width(icon, 2)) / 2, y + 20, 2, on ? Ui.TEXT : dim);

        if (entry.gear != null) {
            boolean gearHover = over(entry.gear, mouseX, mouseY);
            Icons.draw(g, Icons.GEAR, x + 8, y + h - 20, 1, gearHover ? Ui.TEXT : Ui.MUTED);
        }

        int sx = x + 26, sy = y + h - 24, sw = w - 26 - 6, sh = 18;
        String label = on ? "Enabled" : "Disabled";
        if (on) Ui.rect(g, sx, sy, sw, sh, GREEN_EDGE, GREEN_FILL);
        Ui.txt(g, this.font, label, sx + (sw - this.font.width(label)) / 2, sy + (sh - 8) / 2 + 1, on ? GREEN_TEXT : Ui.MUTED, false);
    }

    // ---------------------------------------------------------------- closing (instant)

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
