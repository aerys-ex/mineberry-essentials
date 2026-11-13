package com.mineberry.essentials;

import com.mineberry.essentials.modules.EvidenceModule;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** The MineBerry Essentials control panel, Lunar style: a grid of module cards with toggle switches, plus two small tools. */
public class PanelScreen extends Screen {

    private static final int PANEL_W = 372, HEADER_H = 20, MARGIN = 14, GAP = 6;
    private static final int CARD_H = 24, CARD_STEP = 28, BTN_H = 20;

    private final Screen parent;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final Map<AbstractWidget, Ui.Card> cards = new HashMap<>();
    private final Map<AbstractWidget, String> hints = new HashMap<>();

    // all y values are relative to py
    private int px, py, pw, ph;
    private int modLabelY, gridY, hintY, optLabelY, optY, convLabelY, convY, evLabelY, evY, footerY;

    private String reportStatus = "No report exported this session";
    private String conversionResult = "Enter X and Z, then pick a direction";
    private boolean conversionOk = false;
    private EditBox xInput, zInput;

    public PanelScreen(Screen parent) {
        super(Component.literal("MineBerry Essentials"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Ui.applyTheme();
        widgets.clear(); cards.clear(); hints.clear();
        List<MineBerryModule> modules = MineBerryEssentials.modules();

        pw = Math.min(PANEL_W, this.width - 20);
        int rows = (modules.size() + 1) / 2;
        modLabelY = HEADER_H + 10;
        gridY = modLabelY + 14;
        hintY = gridY + rows * CARD_STEP - (CARD_STEP - CARD_H) + 5;
        optLabelY = hintY + 16;
        optY = optLabelY + 14;
        convLabelY = optY + CARD_H + 10;
        convY = convLabelY + 14;
        evLabelY = convY + BTN_H + 4 + 16 + 10;
        evY = evLabelY + 14;
        footerY = evY + 28 + 6;
        ph = footerY + 32;
        px = (this.width - pw) / 2;
        py = Math.max(6, (this.height - ph) / 2);

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> drawDecor(g, mouseX, mouseY));

        int left = px + MARGIN, right = px + pw - MARGIN;
        int cardW = (right - left - GAP) / 2;

        // module cards (click the card to toggle; small "..." button for extra settings)
        for (int i = 0; i < modules.size(); i++) {
            MineBerryModule module = modules.get(i);
            int cx = left + (i % 2) * (cardW + GAP), cy = py + gridY + (i / 2) * CARD_STEP;
            boolean gear = module.id().equals("waypoints") || module.id().equals("worldeditcui");
            addCard(cx, cy, cardW, module.name(), module.description(), module.enabled(), () -> { module.toggle(); return module.enabled(); }, gear);
            if (module.id().equals("waypoints")) {
                addGear(cx, cy, cardW, "Open the waypoint list", button -> this.minecraft.gui.setScreen(new WaypointScreen(this)));
            }
            if (module.id().equals("worldeditcui")) {
                addGear(cx, cy, cardW, "WorldEdit CUI settings", button -> {
                    var cui = org.enginehub.worldeditcui.fabric.FabricModWorldEditCUI.getInstance();
                    if (cui != null && cui.getController() != null) {
                        this.minecraft.gui.setScreen(new org.enginehub.worldeditcui.gui.CUIConfigPanel(this, cui.getController().getConfiguration()));
                    }
                });
            }
        }

        // options
        int oy = py + optY;
        addCard(left, oy, cardW, "Close on danger", "Close this panel when a PvP alert fires or you take damage",
                Config.autoClosePanelOnDanger(), () -> {
                    Config.setAutoClosePanelOnDanger(!Config.autoClosePanelOnDanger());
                    return Config.autoClosePanelOnDanger();
                }, false);
        addCard(left + cardW + GAP, oy, cardW, "Chat timestamps", "Add a timestamp to chat messages",
                Config.chatTimestamps(), () -> {
                    Config.setChatTimestamps(!Config.chatTimestamps());
                    return Config.chatTimestamps();
                }, false);

        // converter
        int cy = py + convY;
        // borderless fields, padded inside the rounded boxes that drawDecor() paints behind them
        xInput = new EditBox(this.font, left + 12 + 4, cy + 6, 62, 12, Component.literal("X coordinate"));
        zInput = new EditBox(this.font, left + 104 + 4, cy + 6, 62, 12, Component.literal("Z coordinate"));
        xInput.setBordered(false); zInput.setBordered(false);
        xInput.setMaxLength(18); zInput.setMaxLength(18);
        add(xInput); add(zInput);
        add(Button.builder(Component.literal("To Overworld"), button -> convert(8.0, "Overworld"))
                .bounds(right - 78 - 4 - 78, cy, 78, BTN_H).build());
        add(Button.builder(Component.literal("To Nether"), button -> convert(0.125, "Nether"))
                .bounds(right - 78, cy, 78, BTN_H).build());

        // evidence
        add(Button.builder(Component.literal("Export"), button -> exportReport())
                .bounds(right - 50, py + evY + 4, 50, BTN_H).build());
        add(Button.builder(Component.literal("Copy"), button -> copyReport())
                .bounds(right - 50 - 4 - 36, py + evY + 4, 36, BTN_H).build());

        // footer
        add(Button.builder(Component.literal("Theme: " + Ui.themeName()), button -> {
            Config.setPanelTheme(Ui.nextTheme());
            Ui.applyTheme();
            button.setMessage(Component.literal("Theme: " + Ui.themeName()));
        }).bounds(left, py + footerY + 6, 100, BTN_H).build());
        add(Button.builder(Component.literal("Gradient"), button -> this.minecraft.gui.setScreen(new GradientScreen(this)))
                .bounds(left + 100 + 4, py + footerY + 6, 80, BTN_H).build());
        add(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(right - 60, py + footerY + 6, 60, BTN_H).build());

        // update checker: a button appears between Gradient and Close when a newer release exists
        UpdateChecker.check();   // no-op if already started or disabled
        UpdateChecker.Result update = UpdateChecker.result();
        if (update != null && update.updateAvailable()) {
            int btnX = left + 100 + 4 + 80 + 4, btnW = (right - 60 - 4) - btnX;
            add(Button.builder(Component.literal("Update: v" + update.latest()), button ->
                            net.minecraft.util.Util.getPlatform().openUri(java.net.URI.create(update.url())))
                    .bounds(btnX, py + footerY + 6, btnW, BTN_H).build());
        }

        // Lunar look drawn over the vanilla buttons (added last so it renders on top)
        this.addRenderableOnly((g, mouseX, mouseY, delta) -> Ui.buttons(g, this.font, widgets, cards, mouseX, mouseY));
    }

    private <T extends AbstractWidget> T add(T widget) {
        widgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    /** A clickable module card. With gear = true the right 24px stay free for a small side button. */
    private void addCard(int x, int y, int w, String name, String hint, boolean on, BooleanSupplier flip, boolean gear) {
        Button b = add(Button.builder(stateText(on), button -> button.setMessage(stateText(flip.getAsBoolean())))
                .bounds(x, y, gear ? w - 24 : w, CARD_H).build());
        cards.put(b, new Ui.Card(name, w, gear));
        hints.put(b, hint);
    }

    private void addGear(int cardX, int y, int cardW, String hint, Button.OnPress action) {
        Button b = add(Button.builder(Component.literal("..."), action).bounds(cardX + cardW - 22, y + 4, 18, 16).build());
        hints.put(b, hint);
    }

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

    private static Component stateText(boolean on) {
        return on ? Component.literal("ON").withStyle(ChatFormatting.GREEN)
                : Component.literal("OFF").withStyle(ChatFormatting.GRAY);
    }

    // ---------------------------------------------------------------- drawing

    private void drawDecor(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int left = px + MARGIN, right = px + pw - MARGIN;
        List<MineBerryModule> modules = MineBerryEssentials.modules();

        Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
        Ui.frame(g, px, py, pw, ph, HEADER_H);

        // header
        int headerTextY = py + 1 + (HEADER_H - 8) / 2;
        Ui.txt(g, this.font, "MineBerry", left, headerTextY, Ui.ACCENT, false);
        Ui.txt(g, this.font, "Essentials", left + this.font.width("MineBerry") + 6, headerTextY, Ui.MUTED, false);
        int on = 0;
        for (MineBerryModule module : modules) if (module.enabled()) on++;
        Ui.headerRight(g, this.font, right, headerTextY, on + " / " + modules.size() + " on");

        // modules + hint line (shows what the hovered card does)
        Ui.section(g, this.font, left, right, py + modLabelY, "MODULES");
        String hint = "Click a module to switch it on or off";
        for (Map.Entry<AbstractWidget, String> e : hints.entrySet()) {
            AbstractWidget w = e.getKey();
            if (mouseX >= w.getX() && mouseX < w.getX() + w.getWidth() && mouseY >= w.getY() && mouseY < w.getY() + w.getHeight()) {
                hint = e.getValue();
                break;
            }
        }
        Ui.txt(g, this.font, this.font.plainSubstrByWidth(hint, right - left), left, py + hintY, Ui.MUTED, false);

        // options
        Ui.section(g, this.font, left, right, py + optLabelY, "OPTIONS");

        // converter
        Ui.section(g, this.font, left, right, py + convLabelY, "COORDINATE CONVERTER");
        int cy = py + convY;
        Ui.txt(g, this.font, "X", left, cy + 6, Ui.MUTED, false);
        Ui.txt(g, this.font, "Z", left + 92, cy + 6, Ui.MUTED, false);
        Ui.inputBox(g, xInput);
        Ui.inputBox(g, zInput);
        int barY = cy + BTN_H + 4;
        Ui.fill(g, left, barY, right, barY + 16, Ui.INSET);
        Ui.txt(g, this.font, this.font.plainSubstrByWidth(conversionResult, right - left - 12), left + 6, barY + 4,
                conversionOk ? Ui.ACCENT : Ui.MUTED, false);

        // evidence
        Ui.section(g, this.font, left, right, py + evLabelY, "EVIDENCE");
        Ui.txt(g, this.font, "Export latest incident (.zip)", left, py + evY + 5, Ui.TEXT, false);
        Ui.txt(g, this.font, this.font.plainSubstrByWidth(reportStatus, pw - 2 * MARGIN - 50 - 4 - 36 - 12), left, py + evY + 16, Ui.MUTED, false);

        // footer
        Ui.footerLine(g, left, right, py + footerY);
    }

    // ---------------------------------------------------------------- closing (instant)

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
