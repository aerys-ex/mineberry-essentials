package com.mineberry.essentials;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** "Edit HUD Layout": every enabled mod shows in its corner. Click one to move it to the next corner. */
public class HudEditScreen extends Screen {

    private static final int PAD = 6, GAP = 3, EDGE = 6, H = 20;

    private final Screen parent;
    private final List<AbstractWidget> widgets = new ArrayList<>();

    public HudEditScreen(Screen parent) {
        super(Component.literal("Edit HUD Layout"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Ui.applyTheme();
        widgets.clear();
        this.addRenderableOnly((g, mx, my, d) -> {
            Ui.fill(g, 0, 0, this.width, this.height, Ui.DIM);
            String hint = "Click a mod to move it to the next corner";
            Ui.txt(g, this.font, hint, (this.width - this.font.width(hint)) / 2, this.height / 2 - 4, Ui.TEXT, true);
        });
        int[] used = new int[4];
        for (HudMod mod : LunarHud.MODS) {
            if (!mod.enabled()) continue;
            int corner = mod.corner(), w = this.font.width(mod.name()) + PAD * 2 + 4;
            int x = corner % 2 == 0 ? EDGE : this.width - EDGE - w;
            int y = corner < 2 ? EDGE + used[corner] : this.height - EDGE - H - used[corner];
            used[corner] += H + GAP;
            Button b = Button.builder(Component.literal(mod.name()), btn -> {
                mod.nextCorner();
                LunarHud.sync();
                this.rebuildWidgets();
            }).bounds(x, y, w, H).build();
            widgets.add(b);
            this.addRenderableWidget(b);
        }
        Button done = Button.builder(Component.literal("Done"), btn -> onClose())
                .bounds(this.width / 2 - 40, this.height / 2 + 12, 80, H).build();
        widgets.add(done);
        this.addRenderableWidget(done);
        this.addRenderableOnly((g, mx, my, d) -> Ui.buttons(g, this.font, widgets, new HashMap<>(), mx, my));
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(parent);
    }
}
