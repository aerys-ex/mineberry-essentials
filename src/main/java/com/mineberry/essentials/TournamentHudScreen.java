package com.mineberry.essentials;

import com.mineberry.essentials.modules.TournamentHudModule;
import com.mineberry.essentials.modules.TournamentParser;
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
 * The friends list of the Tournament HUD: type a username and press Add, or press Remove next to a name.
 * Each friend shows what the HUD currently knows (alive, dead, left, or not in the match).
 */
public class TournamentHudScreen extends Screen {

    private static final int PANEL_W = 400, HEADER_H = 20, MARGIN = 14, ROW_H = 22, PER_PAGE = 6, BTN_H = 20;
    private static final int ADD_W = 56, REMOVE_W = 56, RESET_W = 100, STATUS_X = 150;

    private final Screen parent;
    private final List<AbstractWidget> widgets = new ArrayList<>();
    private final List<Button> removeButtons = new ArrayList<>();
    private Button prevBtn, nextBtn;
    private EditBox nameBox;

    private int page = 0;
    private String status = "";

    private int px, py, pw, ph, addY, listLabelY, rowsY, pagerY, footerY;

    public TournamentHudScreen(Screen parent) {
        super(Component.literal("MineBerry Tournament HUD"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Ui.applyTheme();
        this.addRenderableOnly((g, mx, my, d) -> Ui.ghostAll(this));   // first thing drawn: hides the vanilla buttons before THEY are drawn (no flash on open)
        widgets.clear();
        removeButtons.clear();

        pw = Math.min(PANEL_W, this.width - 20);
        addY = HEADER_H + 12;
        listLabelY = addY + BTN_H + 8;
        rowsY = listLabelY + 14;
        pagerY = rowsY + PER_PAGE * ROW_H + 6;
        footerY = pagerY + BTN_H + 12;
        ph = footerY + 36;
        px = (this.width - pw) / 2;
        py = Math.max(6, (this.height - ph) / 2);

        this.addRenderableOnly((g, mouseX, mouseY, delta) -> drawDecor(g));

        int left = px + MARGIN, right = px + pw - MARGIN;

        nameBox = new EditBox(this.font, left + 4, py + addY + 6, right - left - 8 - ADD_W - 6, 12, Component.literal("Username"));
        nameBox.setBordered(false);
        nameBox.setMaxLength(17);
        nameBox.setResponder(text -> status = "");
        add(nameBox);
        this.setInitialFocus(nameBox);
        add(Button.builder(Component.literal("Add"), button -> addFriend())
                .bounds(right - ADD_W, py + addY, ADD_W, BTN_H).build());

        for (int i = 0; i < PER_PAGE; i++) {
            final int row = i;
            removeButtons.add(add(Button.builder(Component.literal("Remove"), button -> removeFriend(row))
                    .bounds(right - REMOVE_W, py + rowsY + i * ROW_H + 1, REMOVE_W, BTN_H).build()));
        }

        prevBtn = add(Button.builder(Component.literal("< Prev"), button -> { page--; refresh(); })
                .bounds(left, py + pagerY, 56, BTN_H).build());
        nextBtn = add(Button.builder(Component.literal("Next >"), button -> { page++; refresh(); })
                .bounds(right - 56, py + pagerY, 56, BTN_H).build());

        add(Button.builder(Component.literal("Reset statuses"), button -> {
            TournamentHudModule.resetStatuses();
            status = "Statuses reset";
        }).bounds(left, py + footerY + 8, RESET_W, BTN_H).build());
        add(Button.builder(Component.literal("Close"), button -> onClose())
                .bounds(right - 60, py + footerY + 8, 60, BTN_H).build());

        // hidden Remove buttons (rows with no friend) must not be painted
        this.addRenderableOnly((g, mouseX, mouseY, delta) ->
                Ui.buttons(g, this.font, widgets.stream().filter(w -> w.visible).toList(), Map.of(), mouseX, mouseY));
        refresh();
    }

    private <T extends AbstractWidget> T add(T widget) {
        widgets.add(widget);
        return this.addRenderableWidget(widget);
    }

    private List<String> friends() {
        return Config.tournamentFriends();
    }

    private int pageCount() {
        return Math.max(1, (friends().size() + PER_PAGE - 1) / PER_PAGE);
    }

    /** Keeps the page in range and shows the Remove buttons only for the rows that have a friend. No widgets are rebuilt. */
    private void refresh() {
        int pages = pageCount();
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        for (int i = 0; i < removeButtons.size(); i++) {
            boolean has = page * PER_PAGE + i < friends().size();
            removeButtons.get(i).visible = has;
            removeButtons.get(i).active = has;
        }
        prevBtn.active = page > 0;
        nextBtn.active = page < pages - 1;
    }

    // ---------------------------------------------------------------- actions

    private void addFriend() {
        var name = TournamentParser.cleanName(nameBox.getValue());
        if (name.isEmpty()) {
            status = "Not a valid username (letters, numbers and _, up to 16)";
            return;
        }
        for (String known : friends()) {
            if (known.equalsIgnoreCase(name.get())) {
                status = name.get() + " is already in the list";
                return;
            }
        }
        if (friends().size() >= TournamentHudModule.MAX_FRIENDS) {
            status = "The list is full (" + TournamentHudModule.MAX_FRIENDS + " friends)";
            return;
        }
        friends().add(name.get());
        Config.save();
        nameBox.setValue("");
        status = "Added " + name.get();
        page = pageCount() - 1;   // show the new friend
        refresh();
    }

    private void removeFriend(int row) {
        int index = page * PER_PAGE + row;
        if (index >= friends().size()) return;
        String removed = friends().remove(index);
        Config.save();
        status = "Removed " + removed;
        refresh();
    }

    // ---------------------------------------------------------------- drawing

    private void drawDecor(GuiGraphicsExtractor g) {
        int left = px + MARGIN, right = px + pw - MARGIN;
        boolean on = TournamentHudModule.active();

        Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
        Ui.frame(g, px, py, pw, ph, HEADER_H);

        int headerTextY = py + 1 + (HEADER_H - 8) / 2;
        Ui.txt(g, this.font, "MineBerry", left, headerTextY, Ui.ACCENT, false);
        Ui.txt(g, this.font, "Tournament HUD", left + this.font.width("MineBerry") + 6, headerTextY, Ui.MUTED, false);
        int count = friends().size();
        Ui.headerRight(g, this.font, right, headerTextY, count + (count == 1 ? " friend" : " friends"));

        Ui.inputBox(g, nameBox);
        if (nameBox.getValue().isEmpty() && !nameBox.isFocused()) {
            Ui.txt(g, this.font, "Username to add", left + 4, py + addY + 6, Ui.MUTED, false);
        }

        Ui.section(g, this.font, left, right, py + listLabelY, count == 0 ? "NO FRIENDS YET" : "FRIENDS");

        List<TournamentHudModule.Row> live = TournamentHudModule.rows();
        for (int i = 0; i < PER_PAGE; i++) {
            int index = page * PER_PAGE + i;
            if (index >= count) break;
            String name = friends().get(index);
            int y = py + rowsY + i * ROW_H;
            Ui.txt(g, this.font, name, left, y + 7, Ui.TEXT, false);
            String text = on ? statusOf(live, name) : "";
            Ui.txt(g, this.font, text, left + STATUS_X, y + 7, Ui.MUTED, false);
            if (i < PER_PAGE - 1 && index < count - 1) Ui.divider(g, left, right, y + ROW_H - 1);
        }

        String pageLabel = (page + 1) + " / " + pageCount();
        Ui.txt(g, this.font, pageLabel, px + pw / 2 - this.font.width(pageLabel) / 2, py + pagerY + 6, Ui.MUTED, false);
        Ui.footerLine(g, left, right, py + footerY);

        String line = !status.isEmpty() ? status : !on ? "Tournament HUD is off: switch it on in the Control Center" : "";
        if (!line.isEmpty()) {
            int room = (right - 60 - 8) - (left + RESET_W + 8);
            Ui.txt(g, this.font, this.font.plainSubstrByWidth(line, room), left + RESET_W + 8, py + footerY + 14, Ui.MUTED, false);
        }
    }

    private static String statusOf(List<TournamentHudModule.Row> live, String name) {
        for (TournamentHudModule.Row row : live) {
            if (row.name().equalsIgnoreCase(name)) return row.label();
        }
        return "not in match";
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
