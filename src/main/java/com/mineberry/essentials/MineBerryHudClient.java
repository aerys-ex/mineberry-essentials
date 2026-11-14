package com.mineberry.essentials;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Right Shift opens the mod menu (like Lunar); change it in Options > Controls.
 * HUD show/hide already has a keybind in MineBerryEssentials, so none is added here.
 * Add this class as a "client" entrypoint in fabric.mod.json (or call LunarHud.init() from your own initializer).
 */
public class MineBerryHudClient implements ClientModInitializer {

    private static KeyMapping openMenu;

    private static boolean registered;

    @Override
    public void onInitializeClient() {
        LunarHud.init();
    }

    static void registerKeys() {
        if (registered) return;
        registered = true;
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("mineberry_essentials", "lunar_menu"));
        openMenu = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mineberry_essentials.lunar_menu", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenu.consumeClick()) {
                if (client.gui.screen() == null) client.gui.setScreen(new LunarMenu(null));
            }
        });
    }
}
