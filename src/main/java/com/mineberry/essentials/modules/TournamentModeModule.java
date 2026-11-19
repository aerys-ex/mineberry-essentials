package com.mineberry.essentials.modules;

import com.mineberry.essentials.Config;
import com.mineberry.essentials.Terms;
import com.mineberry.essentials.MineBerryEssentials;
import com.mineberry.essentials.MineBerryModule;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Tournament Mode: while it is ON, no command leaves the client, so nobody can type (or click) a command by
 * accident and get sent to another gamemode in the middle of a tournament.
 *
 * This covers commands typed in chat, commands run from clickable chat messages, and the commands this mod sends
 * itself (Clan / Admin / Party chat). Normal chat messages are not affected.
 * Off by default, and it stays on until you switch it off. Use "tournamentAllowedCommands" in the config file
 * to let specific commands through (empty by default).
 */
public class TournamentModeModule extends MineBerryModule {

    public TournamentModeModule() {
        super("tournament_mode", "Tournament Mode", "Blocks every command so nobody gets teleported out by accident");
    }

    /** Unlike the other modules this one starts OFF. */
    @Override
    public boolean enabled() {
        return Terms.accepted() && Config.isEnabled(id(), false);
    }

    @Override
    public void init() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            if (!enabled()) return true;
            String root = CommandRules.root(command);
            if (CommandRules.normalize(Config.tournamentAllowedCommands()).contains(root)) return true;
            say("Tournament Mode: /" + root + " was blocked. Turn it off in the Control Center to use commands.", ChatFormatting.RED);
            return false;
        });
        HudElementRegistry.addLast(MineBerryEssentials.id("tournament_mode_status"), (g, delta) -> drawHud(g));
    }

    @Override
    protected void onToggled(boolean nowEnabled) {
        say(nowEnabled ? "Tournament Mode ON - all commands are blocked" : "Tournament Mode OFF - commands work again",
                nowEnabled ? ChatFormatting.RED : ChatFormatting.GREEN);
    }

    private static void say(String text, ChatFormatting color) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player == null) return;
            mc.player.sendSystemMessage(Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal(text).withStyle(color)));
        });
    }

    /** A constant reminder, so nobody forgets it is on and wonders why commands do nothing. */
    private void drawHud(GuiGraphicsExtractor g) {
        if (MineBerryEssentials.hudHidden() || !enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var font = mc.font;
        String title = "TOURNAMENT MODE";
        String subtitle = "Commands are disabled";
        int w = Math.max(font.width(title), font.width(subtitle)) + 18;
        int x = mc.getWindow().getGuiScaledWidth() - w - 6;
        int y = com.mineberry.essentials.StatusStack.take(29);
        g.fill(x - 1, y - 1, x + w + 1, y + 30, 0x66000000);
        g.fill(x, y, x + w, y + 29, 0xE0341212);
        g.fill(x + w - 3, y, x + w, y + 29, 0xFFFF4D4D);
        g.text(font, title, x + 8, y + 5, 0xFFFF7A7A, true);
        g.text(font, subtitle, x + 8, y + 17, 0xFFE0E0E0, false);
    }
}
