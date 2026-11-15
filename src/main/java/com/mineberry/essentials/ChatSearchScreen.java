package com.mineberry.essentials;

import com.mineberry.essentials.modules.ChatSearchModule;
import com.mineberry.essentials.modules.ChatSearchModule.Line;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Search the chat received this session. Type to filter live; Copy puts the full line on the clipboard. */
public class ChatSearchScreen extends Screen {

    private static final int PANEL_W = 400, HEADER_H = 20, MARGIN = 14, ROW_H = 22, PER_PAGE = 7, BTN_H = 20, COPY_W = 40;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Screen parent;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final List<Button> copyButtons = new ArrayList<>();
    private Button prevBtn, nextBtn;
    private EditBox searchBox;

    private static String lastQuery = "";
    private List<Line> results = new ArrayList<>();
    private int page = 0;
    private String status = "";

    private int px, py, pw, ph, searchY, listLabelY, rowsY, pagerY, footerY;

    public ChatSearchScreen(Screen parent) {
        super(Component.literal("MineBerry Chat Search"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Ui.applyTheme();
        widgets.clear();
        copyButtons.clear();

        pw = Math.min(PANEL_W, this.width - 20);
        searchY = HEADER_H + 12;
        listLabelY = searchY + BTN_H + 8;
        rowsY = listLabelY + 14;
        pagerY = rowsY + PER_PAGE * ROW_H + 6;
        footerY = pagerY + BTN_H + 12;
        ph = footerY + 36;
        px = (this.width - pw) / 2;
        py = Math.max(6, (this.height - ph) / 2);

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> drawDecor(g));

        int left = px + MARGIN, right = px + pw - MARGIN;

        searchBox = new EditBox(this.font, left + 4, py + searchY + 6, pw - 2 * MARGIN - 8, 12, Component.literal("Search"));
        searchBox.setBordered(false);
        searchBox.setMaxLength(60);
        searchBox.setValue(lastQuery);
        searchBox.setResponder(text -> { lastQuery = text; page = 0; status = ""; refresh(); });
        add(searchBox);
        this.setInitialFocus(searchBox);

        for (int i = 0; i < PER_PAGE; i++) {
            final int row = i;
            copyButtons.add(add(Button.builder(Component.literal("Copy"), button -> copyRow(row))
                    .bounds(right - COPY_W, py + rowsY + i * ROW_H + 1, COPY_W, BTN_H).build()));
        }

        prevBtn = add(Button.builder(Component.literal("< Prev"), button -> { page--; refresh(); })
                .bounds(left, py + pagerY, 56, BTN_H).build());
        nextBtn = add(Button.builder(Component.literal("Next >"), button -> { page++; refresh(); })
                .bounds(right - 56, py + pagerY, 56, BTN_H).build());
        add(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(right - 60, py + footerY + 8, 60, BTN_H).build());

        // hidden Copy buttons (rows with no result) must not be painted
        this.addRenderableOnly((g, mouseX, mouseY, delta) ->
                Ui.buttons(g, this.font, widgets.stream().filter(w -> w.visible).toList(), Map.of(), mouseX, mouseY));
        refresh();
    }

    private <T extends AbstractWidget> T add(T widget) {
        widgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    private int pageCount() { return Math.max(1, (results.size() + PER_PAGE - 1) / PER_PAGE); }

    /** Re-runs the search and shows or hides the Copy buttons for the rows that exist. No widgets are rebuilt, so typing keeps focus. */
    private void refresh() {
        results = ChatSearchModule.search(lastQuery);
        int pages = pageCount();
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        for (int i = 0; i < copyButtons.size(); i++) {
            boolean has = page * PER_PAGE + i < results.size();
            copyButtons.get(i).visible = has;
            copyButtons.get(i).active = has;
        }
        prevBtn.active = page > 0;
        nextBtn.active = page < pages - 1;
    }

    private void copyRow(int row) {
        int index = page * PER_PAGE + row;
        if (index >= results.size()) return;
        this.minecraft.keyboardHandler.setClipboard(results.get(index).text());
        status = "Copied";
    }

    // ---------------------------------------------------------------- drawing

    private void drawDecor(GuiGraphicsExtractor g) {
        int left = px + MARGIN, right = px + pw - MARGIN;

        Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
        Ui.frame(g, px, py, pw, ph, HEADER_H);

        int headerTextY = py + 1 + (HEADER_H - 8) / 2;
        Ui.txt(g, this.font, "MineBerry", left, headerTextY, Ui.ACCENT, false);
        Ui.txt(g, this.font, "Chat Search", left + this.font.width("MineBerry") + 6, headerTextY, Ui.MUTED, false);
        Ui.headerRight(g, this.font, right, headerTextY, ChatSearchModule.total() + " lines remembered");

        Ui.inputBox(g, searchBox);
        if (searchBox.getValue().isEmpty() && !searchBox.isFocused()) {
            Ui.txt(g, this.font, "Type a word, name or coordinates", left + 4, py + searchY + 6, Ui.MUTED, false);
        }

        String label = results.isEmpty() ? "NO MATCHES" : results.size() + (results.size() == 1 ? " MATCH" : " MATCHES") + ", NEWEST FIRST";
        Ui.section(g, this.font, left, right, py + listLabelY, label);

        int textW = pw - 2 * MARGIN - COPY_W - 8 - this.font.width("00:00:00") - 6;
        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= results.size()) break;
            Line line = results.get(index);
            int y = py + rowsY + i * ROW_H;
            Ui.txt(g, this.font, TIME.format(Instant.ofEpochMilli(line.time())), left, y + 7, Ui.MUTED, false);
            Ui.txt(g, this.font, this.font.plainSubstrByWidth(line.text(), textW),
                    left + this.font.width("00:00:00") + 6, y + 7, Ui.TEXT, false);
            if (i < PER_PAGE - 1 && index < results.size() - 1) Ui.divider(g, left, right, y + ROW_H - 1);
        }

        String pageLabel = (page + 1) + " / " + pageCount();
        Ui.txt(g, this.font, pageLabel, px + pw / 2 - this.font.width(pageLabel) / 2, py + pagerY + 6, Ui.MUTED, false);
        Ui.footerLine(g, left, right, py + footerY);
        if (!status.isEmpty()) Ui.txt(g, this.font, status, left, py + footerY + 14, Ui.MUTED, false);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
