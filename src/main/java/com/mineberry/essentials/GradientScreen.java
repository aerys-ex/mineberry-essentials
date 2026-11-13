package com.mineberry.essentials;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Chat gradient generator, in the spirit of Birdflop's RGBirdflop: type some text, pick 2 to 6 hex colors,
 * see a live preview and copy the result in the format your server understands. Same flat Lunar look as the other screens.
 */
public class GradientScreen extends Screen {

    private static final int PANEL_W = 372, HEADER_H = 20, MARGIN = 14, GAP = 6, BTN_H = 20, CARD_H = 24;
    private static final int MAX_COLORS = 6, MIN_COLORS = 2, CHAT_LIMIT = 256, MAX_TEXT = 64;
    private static final int WARN = 0xFFE5534B;

    private static final int BOLD = 1, ITALIC = 2, UNDERLINE = 4, STRIKE = 8;

    /** Output formats, cycled with the Format button. Saved by index in the config. */
    static final String[] FORMATS = {"&#RRGGBB", "<#RRGGBB>", "&x&R&R&G&G&B&B", "MiniMessage"};

    private record Preset(String name, List<String> colors) {}

    private static final List<Preset> PRESETS = List.of(
            new Preset("MineBerry", List.of("#E65B93", "#8A5CF6")),
            new Preset("Sunset", List.of("#FF512F", "#F09819")),
            new Preset("Ocean", List.of("#2193B0", "#6DD5ED")),
            new Preset("Mint", List.of("#00B09B", "#96C93D")),
            new Preset("Fire", List.of("#F12711", "#F5AF19")),
            new Preset("Rainbow", List.of("#FF0000", "#FFFF00", "#00FF00", "#00FFFF", "#0000FF", "#FF00FF")));

    /** The text survives closing and reopening the screen during a session. */
    private static String lastText = "MineBerry";

    private final Screen parent;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final Map<AbstractWidget, Ui.Card> cards = new HashMap<>();

    private final String[] hex = new String[MAX_COLORS];
    private int colorCount, format, styles, presetIndex = -1;
    private String status = "Type text, pick colors, then copy";

    // y values relative to py
    private int px, py, pw, ph;
    private int textLabelY, textY, colorLabelY, colorY, swatchY, ctrlY, styleY, previewLabelY, previewY, outY, footerY;
    private int slotW;

    private EditBox textBox;
    private final EditBox[] hexBoxes = new EditBox[MAX_COLORS];

    public GradientScreen(Screen parent) {
        super(Component.literal("MineBerry Gradient"));
        this.parent = parent;

        List<String> saved = Config.gradientColors();
        colorCount = Math.max(MIN_COLORS, Math.min(MAX_COLORS, saved.size()));
        for (int i = 0; i < MAX_COLORS; i++) hex[i] = i < saved.size() && saved.get(i) != null ? saved.get(i) : "#FFFFFF";
        format = Math.max(0, Math.min(FORMATS.length - 1, Config.gradientFormat()));
        styles = Config.gradientStyles();
    }

    @Override
    protected void init() {
        Ui.applyTheme();
        widgets.clear(); cards.clear();
        java.util.Arrays.fill(hexBoxes, null);

        pw = Math.min(PANEL_W, this.width - 20);
        textLabelY = HEADER_H + 10;
        textY = textLabelY + 14;
        colorLabelY = textY + BTN_H + 8;
        colorY = colorLabelY + 14;
        swatchY = colorY + BTN_H + 1;
        ctrlY = swatchY + 4 + 6;
        styleY = ctrlY + BTN_H + 6;
        previewLabelY = styleY + CARD_H + 10;
        previewY = previewLabelY + 14;
        outY = previewY + 18 + 4;
        footerY = outY + 16 + 8;
        ph = footerY + 32;
        px = (this.width - pw) / 2;
        py = Math.max(6, (this.height - ph) / 2);

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> drawDecor(g));

        int left = px + MARGIN, right = px + pw - MARGIN;

        // text field
        textBox = new EditBox(this.font, left + 4, py + textY + 6, right - left - 8, 12, Component.literal("Text"));
        textBox.setBordered(false);
        textBox.setMaxLength(MAX_TEXT);
        textBox.setValue(lastText);
        textBox.setResponder(value -> lastText = value);
        add(textBox);

        // color fields
        slotW = Math.min(52, (right - left - (MAX_COLORS - 1) * 4) / MAX_COLORS);
        for (int i = 0; i < colorCount; i++) {
            final int index = i;
            EditBox box = new EditBox(this.font, left + i * (slotW + 4) + 4, py + colorY + 6, slotW - 8, 12, Component.literal("Color " + (i + 1)));
            box.setBordered(false);
            box.setMaxLength(7);
            box.setValue(hex[i]);
            box.setResponder(value -> {
                if (value.matches("#?[0-9a-fA-F]{0,6}")) {
                    hex[index] = value;
                } else if (!value.equals(hex[index])) {
                    box.setValue(hex[index]);   // reject invalid input, restore last valid text
                }
            });
            hexBoxes[i] = box;
            add(box);
        }

        // color controls
        int third = (right - left - 2 * 4) / 3;
        add(Button.builder(Component.literal("+ Color"), button -> {
            hex[colorCount] = hex[colorCount - 1];
            colorCount++;
            presetIndex = -1;
            this.rebuildWidgets();
        }).bounds(left, py + ctrlY, third, BTN_H).build()).active = colorCount < MAX_COLORS;
        add(Button.builder(Component.literal("- Color"), button -> {
            colorCount--;
            presetIndex = -1;
            this.rebuildWidgets();
        }).bounds(left + third + 4, py + ctrlY, third, BTN_H).build()).active = colorCount > MIN_COLORS;
        String presetLabel = "Preset: " + (presetIndex < 0 ? "-" : PRESETS.get(presetIndex).name());
        add(Button.builder(Component.literal(presetLabel), button -> {
            presetIndex = (presetIndex + 1) % PRESETS.size();
            List<String> colors = PRESETS.get(presetIndex).colors();
            colorCount = colors.size();
            for (int i = 0; i < MAX_COLORS; i++) hex[i] = i < colors.size() ? colors.get(i) : "#FFFFFF";
            this.rebuildWidgets();
        }).bounds(left + 2 * (third + 4), py + ctrlY, right - (left + 2 * (third + 4)), BTN_H).build());

        // text styles (cards with a toggle switch)
        int cw = (right - left - 3 * GAP) / 4;
        styleCard(left, cw, 0, "Bold", BOLD);
        styleCard(left, cw, 1, "Italic", ITALIC);
        styleCard(left, cw, 2, "Under", UNDERLINE);
        styleCard(left, cw, 3, "Strike", STRIKE);

        // footer
        add(Button.builder(Component.literal("Format: " + FORMATS[format]), button -> {
            format = (format + 1) % FORMATS.length;
            button.setMessage(Component.literal("Format: " + FORMATS[format]));
        }).bounds(left, py + footerY + 6, 150, BTN_H).build());
        add(Button.builder(Component.literal("Copy"), button -> copy())
                .bounds(right - 60 - 4 - 60, py + footerY + 6, 60, BTN_H).build());
        add(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(right - 60, py + footerY + 6, 60, BTN_H).build());

        // Lunar look drawn over the vanilla buttons (added last so it renders on top)
        this.addRenderableOnly((g, mouseX, mouseY, delta) -> Ui.buttons(g, this.font, widgets, cards, mouseX, mouseY));
    }

    private <T extends AbstractWidget> T add(T widget) {
        widgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    private void styleCard(int left, int cw, int column, String name, int bit) {
        Button b = add(Button.builder(stateText((styles & bit) != 0), button -> {
            styles ^= bit;
            button.setMessage(stateText((styles & bit) != 0));
        }).bounds(left + column * (cw + GAP), py + styleY, cw, CARD_H).build());
        cards.put(b, new Ui.Card(name, cw, false));
    }

    private static Component stateText(boolean on) {
        return Component.literal(on ? "ON" : "OFF");
    }

    // ---------------------------------------------------------------- gradient logic

    /** Parses "#RGB", "#RRGGBB" (the # is optional). Returns -1 when the text is not a valid color. */
    static int parseHex(String s) {
        if (s == null) return -1;
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.length() == 3) t = "" + t.charAt(0) + t.charAt(0) + t.charAt(1) + t.charAt(1) + t.charAt(2) + t.charAt(2);
        if (t.length() != 6) return -1;
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The colors currently typed in, as RGB. An invalid field reuses the previous valid color (white for the first). */
    private int[] stops() {
        int[] out = new int[colorCount];
        int last = 0xFFFFFF;
        for (int i = 0; i < colorCount; i++) {
            int c = parseHex(hex[i]);
            out[i] = c >= 0 ? c : last;
            last = out[i];
        }
        return out;
    }

    private boolean allValid() {
        for (int i = 0; i < colorCount; i++) if (parseHex(hex[i]) < 0) return false;
        return true;
    }

    /** Color at position t (0..1) along a multi-stop gradient, interpolated in RGB. */
    static int colorAt(int[] stops, float t) {
        if (stops.length == 1) return stops[0];
        float scaled = Math.max(0f, Math.min(1f, t)) * (stops.length - 1);
        int seg = Math.min((int) scaled, stops.length - 2);
        float f = scaled - seg;
        int a = stops[seg], b = stops[seg + 1];
        int r = Math.round(((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * f);
        int g = Math.round(((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * f);
        int bl = Math.round((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * f);
        return (r << 16) | (g << 8) | bl;
    }

    private static String styleCodes(int styles) {
        StringBuilder sb = new StringBuilder();
        if ((styles & BOLD) != 0) sb.append("&l");
        if ((styles & ITALIC) != 0) sb.append("&o");
        if ((styles & UNDERLINE) != 0) sb.append("&n");
        if ((styles & STRIKE) != 0) sb.append("&m");
        return sb.toString();
    }

    private static String colorCode(int format, int rgb) {
        String h = String.format(Locale.ROOT, "%06x", rgb);
        return switch (format) {
            case 1 -> "<#" + h + ">";
            case 2 -> {
                StringBuilder sb = new StringBuilder("&x");
                for (char c : h.toCharArray()) sb.append('&').append(c);
                yield sb.toString();
            }
            default -> "&#" + h;
        };
    }

    /** Builds the text to paste into chat. Legacy formats put a color code before every letter, as Birdflop does. */
    static String build(String text, int[] stops, int format, int styles) {
        if (text.isEmpty()) return "";
        if (format == 3) {
            StringBuilder sb = new StringBuilder();
            String[] open = {"<bold>", "<italic>", "<underlined>", "<strikethrough>"};
            String[] close = {"</bold>", "</italic>", "</underlined>", "</strikethrough>"};
            for (int i = 0; i < open.length; i++) if ((styles & (1 << i)) != 0) sb.append(open[i]);
            sb.append("<gradient");
            for (int stop : stops) sb.append(":#").append(String.format(Locale.ROOT, "%06x", stop));
            sb.append('>').append(text.replace("\\", "\\\\").replace("<", "\\<")).append("</gradient>");
            for (int i = open.length - 1; i >= 0; i--) if ((styles & (1 << i)) != 0) sb.append(close[i]);
            return sb.toString();
        }
        int[] cps = text.codePoints().toArray();
        String codes = styleCodes(styles);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cps.length; i++) {
            if (Character.isWhitespace(cps[i])) { sb.appendCodePoint(cps[i]); continue; }
            float t = cps.length == 1 ? 0f : i / (float) (cps.length - 1);
            sb.append(colorCode(format, colorAt(stops, t))).append(codes).appendCodePoint(cps[i]);
        }
        return sb.toString();
    }

    private String output() {
        return build(textBox == null ? lastText : textBox.getValue(), stops(), format, styles);
    }

    private void copy() {
        String out = output();
        if (out.isEmpty()) { status = "Nothing to copy: type some text first"; return; }
        if (this.minecraft == null) return;
        this.minecraft.keyboardHandler.setClipboard(out);
        int len = out.length();
        status = len > CHAT_LIMIT ? "Copied, but " + len + " chars is over the chat limit" : "Copied " + len + " characters";
    }

    // ---------------------------------------------------------------- drawing

    private void drawDecor(GuiGraphicsExtractor g) {
        int left = px + MARGIN, right = px + pw - MARGIN;

        Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
        Ui.frame(g, px, py, pw, ph, HEADER_H);

        // header
        int headerTextY = py + 1 + (HEADER_H - 8) / 2;
        Ui.txt(g, this.font, "MineBerry", left, headerTextY, Ui.ACCENT, false);
        Ui.txt(g, this.font, "Gradient", left + this.font.width("MineBerry") + 6, headerTextY, Ui.MUTED, false);
        String headerStatus = this.font.plainSubstrByWidth(status, pw - 2 * MARGIN - this.font.width("MineBerry Gradient") - 16);
        Ui.headerRight(g, this.font, right, headerTextY, headerStatus);

        // text
        Ui.section(g, this.font, left, right, py + textLabelY, "TEXT");
        Ui.inputBox(g, textBox);

        // colors: field box + a swatch of what it parses to
        Ui.section(g, this.font, left, right, py + colorLabelY, "COLORS");
        boolean valid = allValid();
        for (int i = 0; i < colorCount; i++) {
            EditBox box = hexBoxes[i];
            if (box == null) continue;
            Ui.inputBox(g, box);
            int c = parseHex(hex[i]);
            int sx = left + i * (slotW + 4);
            Ui.fill(g, sx + 1, py + swatchY, sx + slotW - 1, py + swatchY + 4, c >= 0 ? (c | 0xFF000000) : WARN);
        }

        // live preview
        int[] stops = stops();
        String text = textBox.getValue();
        Ui.section(g, this.font, left, right, py + previewLabelY, "PREVIEW");
        Ui.fill(g, left, py + previewY, right, py + previewY + 18, Ui.INSET);
        if (text.isEmpty()) {
            Ui.txt(g, this.font, "Type something above", left + 6, py + previewY + 5, Ui.MUTED, false);
        } else {
            g.text(this.font, preview(text, stops, right - left - 12), left + 6, py + previewY + 5, 0xFFFFFFFF, false);
        }

        // output line + length counter
        String out = output();
        int outTop = py + outY;
        Ui.fill(g, left, outTop, right, outTop + 16, Ui.INSET);
        String counter = out.length() + "/" + CHAT_LIMIT;
        int counterW = this.font.width(counter);
        Ui.txt(g, this.font, counter, right - 6 - counterW, outTop + 4, out.length() > CHAT_LIMIT ? WARN : Ui.MUTED, false);
        String shown = !valid ? "Fix the colors marked red (use #RRGGBB)" : out.isEmpty() ? "Output appears here" : out;
        Ui.txt(g, this.font, this.font.plainSubstrByWidth(shown, right - left - 12 - counterW - 6), left + 6, outTop + 4,
                !valid ? WARN : out.isEmpty() ? Ui.MUTED : Ui.ACCENT, false);

        Ui.footerLine(g, left, right, py + footerY);
    }

    /** The text colored letter by letter with the chosen styles, cut off where it would leave the preview box. */
    private Component preview(String text, int[] stops, int maxWidth) {
        MutableComponent out = Component.empty();
        int[] cps = text.codePoints().toArray();
        int width = 0;
        for (int i = 0; i < cps.length; i++) {
            float t = cps.length == 1 ? 0f : i / (float) (cps.length - 1);
            Style style = Style.EMPTY.withColor(colorAt(stops, t))
                    .withBold((styles & BOLD) != 0 ? Boolean.TRUE : null)
                    .withItalic((styles & ITALIC) != 0 ? Boolean.TRUE : null)
                    .withUnderlined((styles & UNDERLINE) != 0 ? Boolean.TRUE : null)
                    .withStrikethrough((styles & STRIKE) != 0 ? Boolean.TRUE : null);
            MutableComponent piece = Component.literal(new String(Character.toChars(cps[i]))).withStyle(style);
            width += this.font.width(piece);
            if (width > maxWidth) break;
            out.append(piece);
        }
        return out;
    }

    // ---------------------------------------------------------------- closing (instant)

    @Override
    public void onClose() {
        List<String> colors = new ArrayList<>();
        for (int i = 0; i < colorCount; i++) {
            int c = parseHex(hex[i]);
            colors.add(c >= 0 ? String.format(Locale.ROOT, "#%06X", c) : "#FFFFFF");
        }
        Config.setGradient(colors, format, styles);
        this.minecraft.gui.setScreen(parent);
    }
}
