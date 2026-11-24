package com.mineberry.essentials.modules;

import com.mineberry.essentials.AerionChat;
import com.mineberry.essentials.AerionTalk;
import com.mineberry.essentials.MineBerryModule;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;

import java.util.Optional;

/**
 * Talk to Aerion in normal chat: type {@code !aerion how much is legend rank} and the answer appears in your chat,
 * only for you. The message is never sent to the server. It is the same conversation as the Aerion tab and the mini chat.
 *
 * Registered before the chat-mode modules (clan / admin / party), so a chat mode never swallows it.
 */
public class AerionChatCommandModule extends MineBerryModule {

    public AerionChatCommandModule() {
        super("aerion_chat", "Aerion in Chat", "Type !aerion <message> in chat to ask Aerion");
    }

    @Override
    public void init() {
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!enabled()) return true;
            Optional<String> question = AerionChatCommand.parse(message);
            if (question.isEmpty()) return true;
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> handle(mc, question.get()));
            return false;   // stays between you and Aerion
        });
    }

    private static void handle(Minecraft mc, String question) {
        if (mc.player == null) return;
        if (question.isEmpty()) {
            say(mc, "Say something after !aerion, like !aerion airdrop info", ChatFormatting.GRAY);
            return;
        }
        AerionChat.echo(mc, question);
        AerionTalk.ask(AerionTalk.currentServerId(), question, answer -> mc.execute(() -> {
            AerionChat.sayLines(mc, answer, ChatFormatting.WHITE);
        }));
    }

    private static void say(Minecraft mc, String text, ChatFormatting color) {
        AerionChat.say(mc, text, color);
    }
}
