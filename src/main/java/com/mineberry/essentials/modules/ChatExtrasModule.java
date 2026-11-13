package com.mineberry.essentials.modules;

import com.mojang.blaze3d.platform.InputConstants;
import com.mineberry.essentials.Config;
import com.mineberry.essentials.MineBerryEssentials;
import com.mineberry.essentials.MineBerryModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chat extras, all in one module:
 *  - mention highlight + sound when someone says your name (extra words: "mentionNames" in the config)
 *  - optional [HH:mm] timestamps (the Timestamps button in the panel, or "chatTimestamps" in the config)
 *  - a quick reply key (default R) that opens chat with "/r " already typed
 *
 * Highlight and timestamps change messages the server sends as system/game messages (what most chat
 * plugins use). The sound also works for normal player chat.
 */
public class ChatExtrasModule extends MineBerryModule {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final long DUPLICATE_MSG_MS = 300;

    private KeyMapping quickReplyKey;
    private String lastText = null;
    private long lastTextTime = 0;

    private Pattern mentionPattern = null;
    private String mentionSource = null;

    public ChatExtrasModule() {
        super("chat_extras", "Chat Extras", "Mentions + sound, timestamps, R to reply");
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

        // sound for mentions (both system messages and normal player chat)
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) onMessage(msg); });
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) -> onMessage(msg));

        // highlight + timestamp (system/game messages)
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) -> overlay ? message : modify(message));
    }

    // ---------------------------------------------------------------- mentions

    /** Pattern that matches your username and the extra mention names, or null if there is nothing to match. */
    private Pattern mentions() {
        Minecraft mc = Minecraft.getInstance();
        List<String> names = new ArrayList<>();
        String own = mc.getUser() == null ? null : mc.getUser().getName();
        if (own != null && !own.isBlank()) names.add(own);
        for (String n : Config.mentionNames()) if (n != null && !n.isBlank()) names.add(n.trim());
        if (names.isEmpty()) return null;

        StringBuilder src = new StringBuilder("(?i)(?<![A-Za-z0-9_])(?:");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) src.append('|');
            src.append(Pattern.quote(names.get(i)));
        }
        src.append(")(?![A-Za-z0-9_])");
        String key = src.toString();
        if (mentionPattern == null || !key.equals(mentionSource)) {
            mentionPattern = Pattern.compile(key);
            mentionSource = key;
        }
        return mentionPattern;
    }

    /** True for lines you wrote yourself (your name followed by ':' or '>' near the start) and for our own messages. */
    private boolean isOwnOrMod(String text) {
        if (text.startsWith("[MineBerry]")) return true;
        Minecraft mc = Minecraft.getInstance();
        String own = mc.getUser() == null ? null : mc.getUser().getName();
        if (own == null || own.isBlank()) return false;
        return Pattern.compile("^.{0,40}?(?<![A-Za-z0-9_])" + Pattern.quote(own) + "(?![A-Za-z0-9_])\\s*[:>\u00bb]",
                Pattern.CASE_INSENSITIVE).matcher(text).find();
    }

    private void onMessage(Component msg) {
        if (!enabled() || !Config.mentionSound()) return;
        String s = msg.getString();
        long now = System.currentTimeMillis();
        // the same line can reach us twice (system + chat channel) -> react once
        if (s.equals(lastText) && now - lastTextTime < DUPLICATE_MSG_MS) return;
        lastText = s;
        lastTextTime = now;

        Pattern p = mentions();
        if (p == null || isOwnOrMod(s) || !p.matcher(s).find()) return;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2F)));
    }

    // ---------------------------------------------------------------- highlight + timestamp

    private Component modify(Component message) {
        if (!enabled()) return message;
        Component result = message;

        Pattern p = mentions();
        if (p != null) {
            String s = message.getString();
            if (!isOwnOrMod(s) && p.matcher(s).find()) result = highlight(message, p);
        }

        if (Config.chatTimestamps()) {
            result = Component.literal("[" + LocalTime.now().format(TIME) + "] ")
                    .withStyle(ChatFormatting.DARK_GRAY)
                    .append(result);
        }
        return result;
    }

    /** Rebuilds the message with every mention shown in bold gold; all other styling is kept. */
    private static Component highlight(Component message, Pattern p) {
        MutableComponent out = Component.empty();
        message.<Void>visit((Style style, String str) -> {
            Matcher m = p.matcher(str);
            int last = 0;
            while (m.find()) {
                if (m.start() > last) out.append(Component.literal(str.substring(last, m.start())).withStyle(style));
                out.append(Component.literal(m.group()).withStyle(style.withColor(ChatFormatting.GOLD).withBold(true)));
                last = m.end();
            }
            if (last < str.length()) out.append(Component.literal(str.substring(last)).withStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }
}
