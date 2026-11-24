package com.mineberry.essentials.modules;

import com.mineberry.essentials.Config;
import com.mineberry.essentials.MineBerryModule;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Set;

/**
 * While the server has you in PvP mode, only a few commands work (by default /r, /reply, /msg, /m, /w, /tell and
 * your clan chat command). Everything else is blocked until the server says you are out of PvP mode.
 *
 * It follows the same server messages as Safe Logout ("You've entered PvP mode!" ... "You're out of PvP mode!"),
 * and lets go by itself after pvpFailsafeSeconds (180 by default) in case the end message was missed. The allowed list is
 * "pvpAllowedCommands" in the config file.
 */
public class PvpCommandLockModule extends MineBerryModule {

    public PvpCommandLockModule() {
        super("pvp_command_lock", "PvP Command Lock", "Only /r, /msg, /m and /cc work while you're in PvP mode");
    }

    @Override
    public void init() {
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            if (!enabled()) return true;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.isLocalServer() || !SafeLogoutModule.inPvpMode()) return true;

            String root = CommandRules.root(command);
            Set<String> allowed = CommandRules.normalize(Config.pvpAllowedCommands());
            allowed.add(CommandRules.root(Config.clanCommand()));   // the chat modes keep working even if you renamed their commands
            allowed.add(CommandRules.root(Config.adminChatCommand()));
            allowed.add(CommandRules.root(Config.partyCommand()));
            if (allowed.contains(root)) return true;

            mc.execute(() -> {
                if (mc.player == null) return;
                mc.player.sendSystemMessage(Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                        .append(Component.literal("In PvP mode - /" + root + " is blocked until the server says you're out")
                                .withStyle(ChatFormatting.RED)));
            });
            return false;
        });
    }
}
