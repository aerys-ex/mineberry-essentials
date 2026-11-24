package com.mineberry.essentials;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Settings of Color Code Chat: type the colour code that goes in front of every chat message (for example &e&l)
 * and press Save. A live preview shows how a message will look.
 */
public class ColorCodeScreen extends Screen {

    private static final int PANEL_W = 400, HEADER_H = 20, MARGIN = 14, BTN_H = 20, SAVE_W = 56;
    private static final String SAMPLE = "Hello everyone!";

    private final Screen parent;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private EditBox codeBox;
    private String status = "";

    private int px, py, pw, ph, inputY, previewY, helpY, footerY;

    public ColorCodeScreen(Screen parent) {
        super(Component.literal("MineBerry Color Code Chat"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Ui.applyTheme();
        this.addRenderableOnly((g, mx, my, d) -> Ui.ghostAll(this));   // first thing drawn: hides the vanilla buttons before THEY are drawn
        String typed = codeBox != null ? codeBox.getValue() : Config.colorCode();
        widgets.clear();

        pw = Math.min(PANEL_W, this.width - 20);
        inputY = HEADER_H + 26;
        previewY = inputY + BTN_H + 10;
        helpY = previewY + 30;
        footerY = helpY + 52;
        ph = footerY + 36;
        px = (this.width - pw) / 2;
        py = Math.max(6, (this.height - ph) / 2);

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> drawDecor(g));

        int left = px + MARGIN, right = px + pw - MARGIN;

        codeBox = new EditBox(this.font, left + 4, py + inputY + 6, right - left - 8 - SAVE_W - 6, 12, Component.literal("Colour code"));
        codeBox.setBordered(false);
        codeBox.setMaxLength(Config.MAX_COLOR_CODE_LENGTH);
        codeBox.setValue(typed);
        codeBox.setResponder(text -> status = "");
        add(codeBox);
        this.setInitialFocus(codeBox);
        add(Button.builder(Component.literal("Save"), button -> save())
                .bounds(right - SAVE_W, py + inputY, SAVE_W, BTN_H).build());

        add(Button.builder(Component.literal("Reset to " + Config.DEFAULT_COLOR_CODE), button -> {
            codeBox.setValue(Config.DEFAULT_COLOR_CODE);
            save();
        }).bounds(left, py + footerY + 8, 100, BTN_H).build());
        add(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(right - 60, py + footerY + 8, 60, BTN_H).build());

        this.addRenderableOnly((g, mouseX, mouseY, delta) ->
                Ui.buttons(g, this.font, widgets.stream().filter(w -> w.visible).toList(), Map.of(), mouseX, mouseY));
    }

    private <T extends AbstractWidget> T add(T widget) {
        widgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    private void save() {
        String value = codeBox.getValue().trim();
        if (Config.setColorCode(value)) {
            status = "Saved " + value;
        } else {
            status = "Not a valid code: use pairs like &e or &c&l (up to " + Config.MAX_COLOR_CODE_LENGTH + " characters)";
        }
    }

    /** The sample message styled by a colour code, the way the server shows it. */
    private static Component preview(String code) {
        Style style = Style.EMPTY;
        if (Config.validColorCode(code)) {
            for (int i = 1; i < code.length(); i += 2) {
                char c = Character.toLowerCase(code.charAt(i));
                if (c == 'r') { style = Style.EMPTY; continue; }
                ChatFormatting format = ChatFormatting.getByCode(c);
                if (format != null) style = style.applyFormat(format);
            }
        }
        return Component.literal(SAMPLE).withStyle(style);
    }

    private void drawDecor(GuiGraphicsExtractor g) {
        int left = px + MARGIN, right = px + pw - MARGIN;
        boolean on = MineBerryEssentials.modules().stream().anyMatch(m -> m.id().equals("color_code_chat") && m.enabled());

        Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
        Ui.frame(g, px, py, pw, ph, HEADER_H);

        int headerTextY = py + 1 + (HEADER_H - 8) / 2;
        Ui.txt(g, this.font, "MineBerry", left, headerTextY, Ui.ACCENT, false);
        Ui.txt(g, this.font, "Color Code Chat", left + this.font.width("MineBerry") + 6, headerTextY, Ui.MUTED, false);
        Ui.headerRight(g, this.font, right, headerTextY, on ? "On" : "Off");

        Ui.section(g, this.font, left, right, py + HEADER_H + 8, "YOUR COLOUR CODE");
        Ui.inputBox(g, codeBox);
        if (codeBox.getValue().isEmpty() && !codeBox.isFocused()) {
            Ui.txt(g, this.font, "For example &e&l", left + 4, py + inputY + 6, Ui.MUTED, false);
        }

        Ui.section(g, this.font, left, right, py + previewY, "PREVIEW");
        g.text(this.font, preview(codeBox.getValue().trim()), left + 4, py + previewY + 16, 0xFFFFFFFF, false);

        Ui.txt(g, this.font, "Colours: &0 to &9 and &a to &f", left, py + helpY, Ui.MUTED, false);
        Ui.txt(g, this.font, "Styles: &l bold, &o italic, &n underline, &m strike, &k magic, &r reset", left, py + helpY + 12, Ui.MUTED, false);
        Ui.txt(g, this.font, "Commands (/...) and messages you start with & are sent as typed.", left, py + helpY + 24, Ui.MUTED, false);

        Ui.footerLine(g, left, right, py + footerY);
        String line = !status.isEmpty() ? status : !on ? "Color Code Chat is off: switch it on in the Control Center" : "";
        if (!line.isEmpty()) {
            int room = (right - 60 - 8) - (left + 100 + 8);
            Ui.txt(g, this.font, this.font.plainSubstrByWidth(line, room), left + 108, py + footerY + 14, Ui.MUTED, false);
        }
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
