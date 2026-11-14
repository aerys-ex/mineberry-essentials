package com.mineberry.essentials;

import net.minecraft.client.Minecraft;

import java.util.function.Function;

/** One small HUD element: an id, a name and a function that returns the text to show (null = hide for now). */
record HudMod(String id, String name, int defaultCorner, boolean fresh, Function<Minecraft, String> text) {

    boolean enabled() {
        return Config.isEnabled("hud_" + id);
    }

    void setEnabled(boolean on) {
        Config.setEnabled("hud_" + id, on);
    }

    int corner() {
        return Config.hudCorner(id, defaultCorner);
    }

    void nextCorner() {
        Config.setHudCorner(id, (corner() + 1) % 4);
    }
}
