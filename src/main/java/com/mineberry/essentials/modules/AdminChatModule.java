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
 * Press K (rebindable) to switch between MAIN chat (normal) and ADMIN chat.
 * In ADMIN mode every normal chat message is sent as "/ac <message>".
 * Commands you type yourself (starting with "/") are never touched.
 * Admin chat and Clan chat are mutually exclusive: turning one on turns the other off.
 */
public class AdminChatModule extends MineBerryModule {

    private static AdminChatModule instance;
    private boolean adminMode = false;
    private KeyMapping toggleKey;

    public AdminChatModule() {
        super("admin_chat", "Admin Chat Toggle", "Press K to send chat as /ac instead of main chat");
    }

    /** Called by ClanChatModule so only one chat mode is active at a time. */
    public static void forceOff() {
        if (instance != null) instance.adminMode = false;
    }

    @Override
    public void init() {
        instance = this;
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mineberry_essentials.toggle_admin",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                MineBerryEssentials.CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                if (!enabled() || client.player == null) continue;
                adminMode = !adminMode;
                if (adminMode) ClanChatModule.forceOff();
                announce(client);
            }
        });

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!enabled() || !adminMode) return true;
            ClientPacketListener conn = Minecraft.getInstance().getConnection();
            if (conn == null) return true;
            conn.sendCommand(Config.adminChatCommand() + " " + message);
            return false; // cancel the normal public message
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> adminMode = false);
        HudElementRegistry.addLast(MineBerryEssentials.id("admin_chat_status"), (g, delta) -> drawHud(g));
    }

    @Override
    protected void onToggled(boolean nowEnabled) {
        if (!nowEnabled) adminMode = false;
    }

    private void announce(Minecraft client) {
        Component mode = adminMode
                ? Component.literal("ADMIN (/" + Config.adminChatCommand() + ")").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)
                : Component.literal("MAIN").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
        client.player.sendSystemMessage(
                Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                        .append(Component.literal("Chat mode: ").withStyle(ChatFormatting.GRAY))
                        .append(mode));
    }

    /** A persistent reminder prevents admin messages being sent by accident. */
    private void drawHud(net.minecraft.client.gui.GuiGraphicsExtractor g) {
        if (MineBerryEssentials.hudHidden() || !enabled() || !adminMode) return;
        Minecraft mc = Minecraft.getInstance();
        var font = mc.font;
        String title = "ADMIN CHAT ENABLED";
        int w = font.width(title) + 18;
        int x = mc.getWindow().getGuiScaledWidth() - w - 6;
        int y = 6;    // same slot as the clan badge (the two modes are mutually exclusive)
        g.fill(x - 1, y - 1, x + w + 1, y + 22, 0x66000000);
        g.fill(x, y, x + w, y + 21, 0xE0301818);
        g.fill(x + w - 3, y, x + w, y + 21, 0xFFFF5566);
        g.text(font, title, x + 8, y + 6, 0xFFFF8A94, true);
    }
}
