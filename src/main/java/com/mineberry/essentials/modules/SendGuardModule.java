package com.mineberry.essentials.modules;

import com.mineberry.essentials.Config;
import com.mineberry.essentials.MineBerryModule;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Send Guard: some things you only want to send on purpose. The first time you send a guarded command (by default
 * /suicide) or a message with a guarded word in it, nothing is sent and a chat line tells you so. Send exactly the same
 * thing again within a few seconds (Up arrow, Enter) and it goes through.
 *
 * Messages and commands are both covered, so it also works in Clan / Admin / Party chat mode and for /msg, /r and
 * radial menu commands (they all end up in the same send events). It is registered after Tournament Mode and PvP
 * Command Lock, so a command that those block is never asked about twice.
 *
 * The lists and the confirm time are in the config file: "guardedCommands", "guardedWords" and "guardConfirmSeconds".
 * A word only counts as a whole word, so a longer word that happens to start with it is left alone.
 */
public class SendGuardModule extends MineBerryModule {

    private final GuardRules.Confirmation confirmation = new GuardRules.Confirmation();

    public SendGuardModule() {
        super("send_guard", "Send Guard", "Asks you to send it twice: /suicide and risky words");
    }

    @Override
    public void init() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> allow("chat:" + GuardRules.key(message), null, message));
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> allow("cmd:" + GuardRules.key(command), command, command));
    }

    /** True lets the send through. command is null for normal chat; text is what gets checked for guarded words. */
    private boolean allow(String key, String command, String text) {
        if (!enabled()) return true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return true;

        String root = command == null ? "" : CommandRules.root(command);
        boolean guardedCommand = command != null && GuardRules.isGuardedCommand(command, Config.guardedCommands());
        boolean guardedWord = !guardedCommand && GuardRules.containsGuardedWord(text, Config.guardedWords());
        if (!guardedCommand && !guardedWord) return true;

        // the same thing again in time: confirmed. Otherwise it is held back and the player is told.
        if (confirmation.confirmed(key, System.currentTimeMillis(), Config.guardConfirmSeconds() * 1000L)) return true;

        String what = guardedCommand ? "/" + root + " was not sent yet."
                                     : "That message has a word that can get you muted or banned. It was not sent yet.";
        String how = " Send it again within " + Config.guardConfirmSeconds() + "s to confirm (press Up, then Enter).";
        mc.execute(() -> {
            if (mc.player == null) return;
            mc.player.sendSystemMessage(Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal(what + how).withStyle(ChatFormatting.YELLOW)));
        });
        return false;
    }
}
