package com.mineberry.essentials.modules;

import com.mineberry.essentials.Config;
import com.mineberry.essentials.MineBerryModule;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;

/**
 * Color Code Chat: while this module is on, every normal chat message you send gets your colour code in front,
 * like "&e&l" + "hello" -> "&e&lhello". Ranks that cannot use chat colours can usually still type colour codes,
 * so this is the quick way to talk in colour without typing the code each time.
 *
 * It works like the Clan, Admin and Party chat modes: only plain chat is touched, and commands (starting with /) are
 * never changed. A chat mode that is switched on wins, because it sends its own command before this point. A message
 * that already starts with "&" is left alone (you picked a colour yourself), and so is a message that would become
 * longer than the 256 characters a server accepts.
 *
 * The code is set in the module's Settings (the gear on its card) and saved in the config. Config.setColorCode checks
 * it, so it can only ever be colour and format codes like &e or &c&l (no hex colours, no text, no commands) and can
 * never turn chat into a command.
 */
public class ColorCodeChatModule extends MineBerryModule {

    private static final int MAX_CHAT_LENGTH = 256;

    public ColorCodeChatModule() {
        super("color_code_chat", "Color Code Chat", "Starts every chat message with your colour code, like &e&l");
    }

    @Override
    public void init() {
        ClientSendMessageEvents.MODIFY_CHAT.register(this::modify);
    }

    private String modify(String message) {
        if (!enabled() || message == null || message.isEmpty()) return message;
        if (message.startsWith("/") || message.startsWith("&")) return message;
        String out = Config.colorCode() + message;
        return out.length() > MAX_CHAT_LENGTH ? message : out;
    }
}
