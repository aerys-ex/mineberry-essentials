package com.mineberry.essentials;

import net.minecraft.client.Minecraft;

import java.util.function.Function;

/**
 * One HUD element. Simple mods return one line of text (null = hide for now); widget mods draw several lines
 * themselves through a HudWidget. defaultOn is what the mod does until the player switches it.
 */
record HudMod(String id, String name, int defaultCorner, boolean fresh, Function<Minecraft, String> text,
              HudWidget widget, boolean defaultOn) {

    /** A one-line text mod that is on by default. */
    HudMod(String id, String name, int defaultCorner, boolean fresh, Function<Minecraft, String> text) {
        this(id, name, defaultCorner, fresh, text, null, true);
    }

    /** A multi-line widget mod. New widgets start off so the HUD does not suddenly fill up after an update. */
    static HudMod widget(String id, String name, int defaultCorner, HudWidget widget) {
        return new HudMod(id, name, defaultCorner, true, null, widget, false);
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

    /** Size in percent (100 = normal), changed in the layout editor. */
    int scalePct() {
        return Config.hudScalePct(id);
    }

    float scale() {
        return scalePct() / 100f;
    }

    /** True for elements that are not laid out by ClientHud (the vanilla scoreboard). */
    boolean virtual() {
        return widget != null && widget.virtual();
    }

    void nextCorner() {
        Config.setHudCorner(id, (corner() + 1) % 4);
    }
}
