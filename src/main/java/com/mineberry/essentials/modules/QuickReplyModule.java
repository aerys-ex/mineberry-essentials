package com.mineberry.essentials.modules;

import com.mojang.blaze3d.platform.InputConstants;
import com.mineberry.essentials.MineBerryEssentials;
import com.mineberry.essentials.MineBerryModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.ChatScreen;
import org.lwjgl.glfw.GLFW;

/** Press R (rebindable) to open chat with "/r " already typed. Has its own switch in the panel. */
public class QuickReplyModule extends MineBerryModule {

    private KeyMapping quickReplyKey;

    public QuickReplyModule() {
        super("quick_reply", "Quick Reply", "Press R to open chat with /r already typed");
    }

    @Override
    public void init() {
        quickReplyKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mineberry_essentials.quick_reply",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                MineBerryEssentials.CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (quickReplyKey.consumeClick()) {
                if (!enabled() || client.player == null || client.gui.screen() != null) continue;
                client.gui.setScreen(new ChatScreen("/r ", false));
            }
        });
    }
}
