package com.mineberry.essentials.modules;

import com.mineberry.essentials.ChatSearchScreen;
import com.mineberry.essentials.MineBerryEssentials;
import com.mineberry.essentials.Terms;
import com.mineberry.essentials.MineBerryModule;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Press F8 (rebindable) to search the chat you have received this session: a message, coordinates, a player's name.
 * The last 3000 lines are kept in memory only (nothing is written to disk) and cleared when you leave a server.
 */
public class ChatSearchModule extends MineBerryModule {

    /** One remembered chat line. */
    public record Line(long time, String text) {}

    private static final int MAX_LINES = 3000, MAX_RESULTS = 500;
    private static final long DUPLICATE_MS = 300;

    private static final ArrayDeque<Line> LINES = new ArrayDeque<>();
    private static String lastText = null;
    private static long lastTime = 0;

    private KeyMapping searchKey;

    public ChatSearchModule() {
        super("chat_search", "Chat Search", "Press F8 to search the chat history");
    }

    @Override
    public void init() {
        searchKey = Terms.registerKey(new KeyMapping(
                "key.mineberry_essentials.chat_search",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_F8,
                MineBerryEssentials.CATEGORY));

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) record(message); });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> record(message));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (searchKey.consumeClick()) {
                if (enabled() && client.player != null && client.gui.screen() == null) {
                    client.gui.setScreen(new ChatSearchScreen(null));
                }
            }
        });
    }

    @Override
    protected void onToggled(boolean nowEnabled) {
        if (!nowEnabled) clear();
    }

    private void record(Component message) {
        if (!enabled()) return;
        String text = message.getString().strip();
        if (text.isEmpty()) return;
        long now = System.currentTimeMillis();
        // some servers' lines arrive through both events: keep one
        if (text.equals(lastText) && now - lastTime < DUPLICATE_MS) return;
        lastText = text;
        lastTime = now;
        synchronized (LINES) {
            LINES.addLast(new Line(now, text));
            while (LINES.size() > MAX_LINES) LINES.removeFirst();
        }
    }

    private static void clear() {
        synchronized (LINES) { LINES.clear(); }
        lastText = null;
    }

    /** Lines that contain every word of the query (any order, ignoring case), newest first. An empty query lists everything. */
    public static List<Line> search(String query) {
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("\\s+");
        List<Line> out = new ArrayList<>();
        synchronized (LINES) {
            var it = LINES.descendingIterator();
            while (it.hasNext() && out.size() < MAX_RESULTS) {
                Line line = it.next();
                String lower = line.text().toLowerCase(Locale.ROOT);
                boolean all = true;
                for (String w : words) {
                    if (!w.isEmpty() && !lower.contains(w)) { all = false; break; }
                }
                if (all) out.add(line);
            }
        }
        return out;
    }

    public static int total() {
        synchronized (LINES) { return LINES.size(); }
    }
}
