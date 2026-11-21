package com.mineberry.essentials.modules;

import com.mojang.blaze3d.platform.InputConstants;
import com.mineberry.essentials.Config;
import com.mineberry.essentials.MineBerryEssentials;
import com.mineberry.essentials.Terms;
import com.mineberry.essentials.MineBerryModule;
import com.mineberry.essentials.RadialMenuScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import org.lwjgl.glfw.GLFW;

/**
 * Radial menu: hold a key and a pie menu opens around the crosshair with a few quick commands (spawn, tpa,
 * tpaccept, rtp). Move towards a slice and let go of the key to run it. The slices are fixed and can't be changed.
 *
 * The menu is a screen so the mouse is free. While a screen is open Minecraft no longer tracks held keys, so this
 * module asks GLFW directly whether the key is still down; the moment it is let go, the highlighted slice runs.
 *
 * Commands are sent with sendCommand, so Tournament Mode and PvP Command Lock apply to the radial menu like to
 * everything else.
 */
public class RadialMenuModule extends MineBerryModule {

    private KeyMapping radialKey;

    public RadialMenuModule() {
        super("radial_menu", "Radial Menu", "Hold V for a pie menu of quick commands");
    }

    @Override
    public void init() {
        radialKey = Terms.registerKey(new KeyMapping(
                "key.mineberry_essentials.radial_menu",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                MineBerryEssentials.CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        while (radialKey.consumeClick()) {
            if (enabled() && mc.player != null && mc.level != null && mc.gui.screen() == null) {
                mc.gui.setScreen(new RadialMenuScreen());
            }
        }
        if (mc.gui.screen() instanceof RadialMenuScreen radial) {
            if (mc.player == null) mc.gui.setScreen(null);
            else if (!held()) radial.release();
        }
    }

    /** True while the radial key (or mouse button) is physically held down. */
    private boolean held() {
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) return false;
        InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(radialKey);
        int code = bound.getValue();
        if (code < 0) return false;                       // unbound
        if (bound.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, code) == GLFW.GLFW_PRESS;
        }
        if (bound.getType() == InputConstants.Type.KEYSYM) {
            return GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS;
        }
        return false;
    }

    /** Runs a slice: sends the command, or opens chat with it typed when it still needs an argument ("tpa <player>"). */
    public static void run(Minecraft mc, Config.RadialSlice slice) {
        String command = Config.cleanCommand(slice.command);
        if (command.isEmpty() || mc.player == null) return;

        int placeholder = command.indexOf('<');
        if (placeholder >= 0) {
            String typed = command.substring(0, placeholder).stripTrailing();
            mc.gui.setScreen(new ChatScreen("/" + typed + (typed.isEmpty() ? "" : " "), false));
            return;
        }
        if (mc.getConnection() != null) mc.getConnection().sendCommand(command);
    }
}
