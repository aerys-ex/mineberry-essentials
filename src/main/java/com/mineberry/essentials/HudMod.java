package com.mineberry.essentials;

import net.minecraft.client.Minecraft;

import java.util.function.Function;

/**
 * One HUD element: an id, a name and either a function that returns the text to show (null = hide for now)
 * or a {@link HudWidget} for richer content. defaultOn is what the switch shows until the player changes it.
 */
record HudMod(String id, String name, int defaultCorner, boolean fresh, boolean defaultOn,
              Function<Minecraft, String> text, HudWidget widget) {

    /** A one-line text mod that is on by default. */
    HudMod(String id, String name, int defaultCorner, boolean fresh, Function<Minecraft, String> text) {
        this(id, name, defaultCorner, fresh, true, text, null);
    }

    /** A multi-line widget mod. Off by default, so updating the mod does not suddenly fill the screen. */
    static HudMod ofWidget(String id, String name, int defaultCorner, HudWidget widget) {
        return new HudMod(id, name, defaultCorner, true, false, null, widget);
    }

    boolean enabled() {
        return Config.isEnabled("hud_" + id, defaultOn);
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
