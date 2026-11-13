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
 * Press J (rebindable) to switch between MAIN chat (normal) and CLAN chat.
 * In CLAN mode every normal chat message is sent as "/cc <message>".
 * Commands you type yourself (starting with "/") are never touched.
 */
public class ClanChatModule extends MineBerryModule {

    private static ClanChatModule instance;
    private boolean clanMode = false;
    private KeyMapping toggleKey;

    public ClanChatModule() {
        super("clan_chat", "Clan Chat Toggle", "Press J to send chat as /cc instead of main chat");
    }

    /** Called by AdminChatModule so only one chat mode is active at a time. */
    public static void forceOff() {
        if (instance != null) instance.clanMode = false;
    }

    @Override
    public void init() {
        instance = this;
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mineberry_essentials.toggle_clan",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                MineBerryEssentials.CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.consumeClick()) {
                if (!enabled() || client.player == null) continue;
                clanMode = !clanMode;
                if (clanMode) AdminChatModule.forceOff();
                announce(client);
            }
        });

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!enabled() || !clanMode) return true;
            ClientPacketListener conn = Minecraft.getInstance().getConnection();
            if (conn == null) return true;
            conn.sendCommand(Config.clanCommand() + " " + message);
            return false; // cancel the normal public message
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clanMode = false);
        HudElementRegistry.addLast(MineBerryEssentials.id("clan_chat_status"), (g, delta) -> drawHud(g));
    }

    @Override
    protected void onToggled(boolean nowEnabled) {
        if (!nowEnabled) clanMode = false;
    }

    private void announce(Minecraft client) {
        Component mode = clanMode
                ? Component.literal("CLAN (/" + Config.clanCommand() + ")").withStyle(ChatFormatting.GREEN, ChatFormatting.BOLD)
                : Component.literal("MAIN").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);
        client.player.sendSystemMessage(
                Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                        .append(Component.literal("Chat mode: ").withStyle(ChatFormatting.GRAY))
                        .append(mode));
    }

    /** A persistent reminder prevents clan messages being sent by accident. */
    private void drawHud(net.minecraft.client.gui.GuiGraphicsExtractor g) {
        if (MineBerryEssentials.hudHidden() || !enabled() || !clanMode) return;
        Minecraft mc = Minecraft.getInstance();
        var font = mc.font;
        String title = "CLAN CHAT ENABLED";
        int w = font.width(title) + 18;
        int x = mc.getWindow().getGuiScaledWidth() - w - 6;
        int y = 6;
        g.fill(x - 1, y - 1, x + w + 1, y + 22, 0x66000000);
        g.fill(x, y, x + w, y + 21, 0xE0183022);
        g.fill(x + w - 3, y, x + w, y + 21, 0xFF3DDC84);
        g.text(font, title, x + 8, y + 6, 0xFF68F09D, true);
    }
}
