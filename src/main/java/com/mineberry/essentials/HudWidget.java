package com.mineberry.essentials;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * A HUD element that needs more than one line of text (keys, item icons, a compass strip...).
 * size() runs first every frame and may keep what it computed for the draw() call that follows it.
 */
interface HudWidget {

    /** Content size {width, height} in GUI pixels, without padding, or null to hide it for now. Called every frame: keep it cheap. */
    int[] size(Minecraft mc, Font font);

    /** Draws the content with its top-left corner at x, y. Always called right after size() returned non-null. */
    void draw(GuiGraphicsExtractor g, Minecraft mc, Font font, int x, int y);

    /** True for elements that draw themselves without the dark box and accent line (the watermark). */
    default boolean plain() {
        return false;
    }

    /**
     * True for elements the layout never places or draws (the vanilla scoreboard): they only get a card with an on/off switch
     * and a size, and whoever draws the real thing reads those settings.
     */
    default boolean virtual() {
        return false;
    }
}
