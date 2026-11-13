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
import java.util.List;
import java.util.Locale;

/** The MineBerry Essentials control panel: flat sections of divider-separated rows. */
public class PanelScreen extends Screen {

    private static final int PANEL_W = 380, HEADER_H = 20, MARGIN = 14, ROW_H = 28;
    private static final int BTN_W = 50, BTN_H = 20;
    private static final long FADE_IN_MS = 180, FADE_OUT_MS = 140;

    private final Screen parent;
    private final long openedAt = System.currentTimeMillis();
    private boolean closing = false;
    private long closeStart = 0;
    private final List<AbstractWidget> fadeWidgets = new ArrayList<>();

    // all y values are relative to py
    private int px, py, pw, ph;
    private int modLabelY, rowsY, safeLabelY, safeRowY, convLabelY, convY, evLabelY, evRowY, footerY;

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
        fadeWidgets.clear();
        List<MineBerryModule> modules = MineBerryEssentials.modules();

        pw = Math.min(PANEL_W, this.width - 20);
        modLabelY = HEADER_H + 10;
        rowsY = modLabelY + 14;
        safeLabelY = rowsY + modules.size() * ROW_H + 12;
        safeRowY = safeLabelY + 14;
        convLabelY = safeRowY + ROW_H + 12;
        convY = convLabelY + 14;
        evLabelY = convY + 44 + 12;
        evRowY = evLabelY + 14;
        footerY = evRowY + ROW_H + 12;
        ph = footerY + 36;
        px = (this.width - pw) / 2;
        py = Math.max(6, (this.height - ph) / 2);

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> {
            Ui.fadeAmt = Ui.fadeValue(closing, openedAt, closeStart, FADE_IN_MS, FADE_OUT_MS);
            for (AbstractWidget w : fadeWidgets) w.setAlpha(Ui.fadeAmt);
            drawDecor(g);
        });

        int right = px + pw - MARGIN;

        // modules
        for (int i = 0; i < modules.size(); i++) {
            MineBerryModule module = modules.get(i);
            int y = py + rowsY + i * ROW_H + 4;
            add(Button.builder(stateText(module.enabled()), button -> { module.toggle(); button.setMessage(stateText(module.enabled())); })
                    .bounds(right - BTN_W, y, BTN_W, BTN_H).build());
            if (module.id().equals("waypoints")) {
                add(Button.builder(Component.literal("List"), button -> this.minecraft.gui.setScreen(new WaypointScreen(this)))
                        .bounds(right - BTN_W - 4 - 36, y, 36, BTN_H).build());
            }
            if (module.id().equals("worldeditcui")) {
                add(Button.builder(Component.literal("Settings"), button -> {
                            var cui = org.enginehub.worldeditcui.fabric.FabricModWorldEditCUI.getInstance();
                            if (cui != null && cui.getController() != null) {
                                this.minecraft.gui.setScreen(new org.enginehub.worldeditcui.gui.CUIConfigPanel(this, cui.getController().getConfiguration()));
                            }
                        })
                        .bounds(right - BTN_W - 4 - 52, y, 52, BTN_H).build());
            }
        }

        // safety
        add(Button.builder(stateText(Config.autoClosePanelOnDanger()), button -> {
            Config.setAutoClosePanelOnDanger(!Config.autoClosePanelOnDanger());
            button.setMessage(stateText(Config.autoClosePanelOnDanger()));
        }).bounds(right - BTN_W, py + safeRowY + 4, BTN_W, BTN_H).build());

        // converter
        int cy = py + convY;
        // borderless fields, padded inside the rounded boxes that drawDecor() paints behind them
        xInput = new EditBox(this.font, px + MARGIN + 12 + 4, cy + 6, 62, 12, Component.literal("X coordinate"));
        zInput = new EditBox(this.font, px + MARGIN + 104 + 4, cy + 6, 62, 12, Component.literal("Z coordinate"));
        xInput.setBordered(false); zInput.setBordered(false);
        xInput.setMaxLength(18); zInput.setMaxLength(18);
        add(xInput); add(zInput);
        add(Button.builder(Component.literal("To Overworld"), button -> convert(8.0, "Overworld"))
                .bounds(right - 82 - 4 - 82, cy, 82, BTN_H).build());
        add(Button.builder(Component.literal("To Nether"), button -> convert(0.125, "Nether"))
                .bounds(right - 82, cy, 82, BTN_H).build());

        // evidence
        add(Button.builder(Component.literal("Export"), button -> exportReport())
                .bounds(right - BTN_W, py + evRowY + 4, BTN_W, BTN_H).build());

        // footer
        add(Button.builder(Component.literal("Theme: " + Ui.themeName()), button -> {
            Config.setPanelTheme(Ui.nextTheme());
            Ui.applyTheme();
            button.setMessage(Component.literal("Theme: " + Ui.themeName()));
        }).bounds(px + MARGIN, py + footerY + 8, 110, BTN_H).build());
        add(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(right - 60, py + footerY + 8, 60, BTN_H).build());

        // rounded outlined button look, drawn over the vanilla buttons (added last so it renders on top)
        this.addRenderableOnly((g, mouseX, mouseY, delta) -> Ui.buttons(g, this.font, fadeWidgets, mouseX, mouseY));
    }

    private <T extends AbstractWidget> T add(T widget) {
        fadeWidgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    private void exportReport() {
        for (MineBerryModule module : MineBerryEssentials.modules()) if (module instanceof EvidenceModule evidence) {
            Path zip = evidence.exportLatest();
            reportStatus = zip == null ? "No incident recorded yet" : "Saved: reports/" + zip.getFileName();
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

    private void drawDecor(GuiGraphicsExtractor g) {
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
        Ui.headerRight(g, this.font, right, headerTextY, on + " of " + modules.size() + " modules on");

        // modules
        Ui.section(g, this.font, left, right, py + modLabelY, "MODULES");
        int descW = pw - 2 * MARGIN - BTN_W - 4 - 36 - 8;
        for (int i = 0; i < modules.size(); i++) {
            MineBerryModule module = modules.get(i);
            int y = py + rowsY + i * ROW_H;
            Ui.txt(g, this.font, module.name(), left, y + 5, Ui.TEXT, false);
            Ui.txt(g, this.font, this.font.plainSubstrByWidth(module.description(), descW), left, y + 16, Ui.MUTED, false);
            if (i < modules.size() - 1) Ui.divider(g, left, right, y + ROW_H - 1);
        }

        // safety
        Ui.section(g, this.font, left, right, py + safeLabelY, "SAFETY");
        Ui.txt(g, this.font, "Close panel on danger", left, py + safeRowY + 5, Ui.TEXT, false);
        Ui.txt(g, this.font, "When a PvP alert fires or you take damage", left, py + safeRowY + 16, Ui.MUTED, false);

        // converter
        Ui.section(g, this.font, left, right, py + convLabelY, "COORDINATE CONVERTER");
        int cy = py + convY;
        Ui.txt(g, this.font, "X", left, cy + 6, Ui.MUTED, false);
        Ui.txt(g, this.font, "Z", left + 92, cy + 6, Ui.MUTED, false);
        Ui.inputBox(g, xInput);
        Ui.inputBox(g, zInput);
        int barY = cy + 26;
        Ui.fill(g, left, barY, right, barY + 18, Ui.INSET);
        Ui.txt(g, this.font, this.font.plainSubstrByWidth(conversionResult, right - left - 12), left + 6, barY + 5,
                conversionOk ? Ui.ACCENT : Ui.MUTED, false);

        // evidence
        Ui.section(g, this.font, left, right, py + evLabelY, "EVIDENCE");
        Ui.txt(g, this.font, "Export latest incident (.zip)", left, py + evRowY + 5, Ui.TEXT, false);
        Ui.txt(g, this.font, this.font.plainSubstrByWidth(reportStatus, pw - 2 * MARGIN - BTN_W - 12), left, py + evRowY + 16, Ui.MUTED, false);

        // footer
        Ui.footerLine(g, left, right, py + footerY);
    }

    // ---------------------------------------------------------------- closing (fade out first)

    @Override
    public void tick() {
        super.tick();
        if (closing && System.currentTimeMillis() - closeStart >= FADE_OUT_MS) this.minecraft.gui.setScreen(parent);
    }

    @Override
    public void onClose() {
        if (closing) return;
        closing = true;
        closeStart = System.currentTimeMillis();
    }
}
