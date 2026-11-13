package com.mineberry.essentials.modules;

import com.mojang.blaze3d.platform.InputConstants;
import com.mineberry.essentials.Config;
import com.mineberry.essentials.MineBerryEssentials;
import com.mineberry.essentials.MineBerryModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Press X (rebindable) to switch between MAIN chat (normal) and PARTY chat (mini games).
 * In PARTY mode every normal chat message is sent as "/pc <message>".
 * Commands you type yourself (starting with "/") are never touched.
 */
public class PartyChatModule extends MineBerryModule {

    private static PartyChatModule instance;
    private boolean partyMode = false;
    private KeyMapping toggleKey;

    public PartyChatModule() {
        super("party_chat", "Party Chat Toggle", "Press X to send chat as /pc instead of main chat");
    }

    /** Called by Admin/Clan chat so only one chat mode is active at a time. */
    public static void forceOff() {
        if (instance != null) instance.partyMode = false;
    }

    @Override
    public void init() {
        instance = this;
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mineberry_essentials.toggle_party",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                MineBerryEssentials.CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                if (!enabled() || client.player == null) continue;
                partyMode = !partyMode;
                if (partyMode) { AdminChatModule.forceOff(); ClanChatModule.forceOff(); }
                announce(client);
            }
        });

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!enabled() || !partyMode) return true;
            ClientPacketListener conn = Minecraft.getInstance().getConnection();
            if (conn == null) return true;
            conn.sendCommand(Config.partyCommand() + " " + message);
            return false; // cancel the normal public message
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> partyMode = false);
        HudElementRegistry.addLast(MineBerryEssentials.id("party_chat_status"), (g, delta) -> drawHud(g));
    }

    @Override
    protected void onToggled(boolean nowEnabled) {
        if (!nowEnabled) partyMode = false;
    }

    private void announce(Minecraft client) {
        Component mode = partyMode
                ? Component.literal("PARTY (/" + Config.partyCommand() + ")").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD)
                : Component.literal("MAIN").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
        client.player.sendSystemMessage(
                Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                        .append(Component.literal("Chat mode: ").withStyle(ChatFormatting.GRAY))
                        .append(mode));
    }

    /** A persistent reminder prevents party messages being sent by accident. */
    private void drawHud(net.minecraft.client.gui.GuiGraphicsExtractor g) {
        if (MineBerryEssentials.hudHidden() || !enabled() || !partyMode) return;
        Minecraft mc = Minecraft.getInstance();
        var font = mc.font;
        String title = "PARTY CHAT ENABLED";
        int w = font.width(title) + 18;
        int x = mc.getWindow().getGuiScaledWidth() - w - 6;
        int y = 6;
        g.fill(x - 1, y - 1, x + w + 1, y + 22, 0x66000000);
        g.fill(x, y, x + w, y + 21, 0xE0182430);
        g.fill(x + w - 3, y, x + w, y + 21, 0xFF4FC3F7);
        g.text(font, title, x + 8, y + 6, 0xFF8FDBFF, true);
    }
}
