package com.mineberry.essentials;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** The Lunar-style HUD mods (FPS, Ping, Coordinates, Sprint, Clock, Keystrokes, Armor, Potions, Compass, Biome, Tournament Players), drawn as small boxes in the screen corners. */
public final class LunarHud {

    static final String[] CORNERS = {"Top left", "Top right", "Bottom left", "Bottom right"};
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    static final List<HudMod> MODS = List.of(
            new HudMod("fps", "FPS", 0, false, mc -> mc.getFps() + " FPS"),
            new HudMod("ping", "Ping", 0, false, mc -> {
                if (mc.player == null || mc.getConnection() == null) return null;
                var info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
                return info == null ? null : info.getLatency() + " ms";
            }),
            new HudMod("coords", "Coordinates", 1, false, mc -> mc.player == null ? null : String.format(Locale.ROOT,
                    "X: %d  Y: %d  Z: %d", (int) Math.floor(mc.player.getX()), (int) Math.floor(mc.player.getY()), (int) Math.floor(mc.player.getZ()))),
            new HudMod("sprint", "Sprint Status", 2, true, mc -> {
                if (mc.player == null) return null;
                if (mc.player.isShiftKeyDown()) return "[Sneaking]";
                return mc.player.isSprinting() ? "[Sprinting]" : null;
            }),
            new HudMod("clock", "Clock", 1, true, mc -> LocalTime.now().format(CLOCK)),
            HudMod.widget("keystrokes", "Keystrokes / CPS", 2, new HudWidgets.Keystrokes()),
            HudMod.widget("armor", "Armor Status", 3, new HudWidgets.Armor()),
            HudMod.widget("potions", "Potion Effects", 1, new HudWidgets.Potions()),
            HudMod.widget("compass", "Compass", 0, new HudWidgets.Compass()),
            HudMod.widget("biome", "Biome / Time", 3, new HudWidgets.BiomeTime()),
            // follows the Tournament HUD module (off by default), so its HUD switch starts on
            new HudMod("tournament", "Tournament Players", 3, true, null, new HudWidgets.TournamentPlayers(), true));

    private static final int PAD = 4, GAP = 3, EDGE = 6;

    private LunarHud() {}

    /** Call once from a ClientModInitializer. */
    private static boolean ready, logged;

    public static void init() {
        if (ready) return;
        ready = true;
        registerKeys();
        MineBerryEssentials.LOGGER.info("HUD registered");
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("mineberry_essentials", "lunar_hud"), LunarHud::draw);
    }

    /** Right Shift opens the HUD menu (like Lunar); change it in Options > Controls. HUD show/hide has its own key in MineBerryEssentials. */
    private static void registerKeys() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("mineberry_essentials", "lunar_menu"));
        KeyMapping openMenu = Terms.registerKey(new KeyMapping(
                "key.mineberry_essentials.lunar_menu", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenu.consumeClick()) {
                if (client.gui.screen() == null) client.gui.setScreen(new LunarMenu(null));
            }
        });
    }

    private static void draw(GuiGraphicsExtractor g, DeltaTracker tick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || Config.hudHidden() || mc.gui.screen() instanceof HudEditScreen) return;
        if (!logged) { logged = true; MineBerryEssentials.LOGGER.debug("HUD is drawing"); }
        Ui.applyTheme();
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int[] used = new int[4];     // height already taken in the current column of each corner
        int[] shift = new int[4];    // how far the current column is moved away from the screen edge
        int[] colW = new int[4];     // width of the widest box in the current column
        for (HudMod mod : MODS) {
            if (!mod.enabled()) continue;
            String text = null;
            int[] size = null;
            int cw, ch;
            if (mod.widget() != null) {
                size = mod.widget().size(mc, font);
                if (size == null) continue;
                cw = size[0];
                ch = size[1];
            } else {
                text = mod.text().apply(mc);
                if (text == null) continue;
                cw = font.width(text);
                ch = 9;
            }
            int corner = mod.corner(), w = cw + PAD * 2, h = ch + PAD * 2;
            if (used[corner] > 0 && used[corner] + h > sh - EDGE * 2) {   // the column is full: start the next one
                shift[corner] += colW[corner] + GAP;
                used[corner] = 0;
                colW[corner] = 0;
            }
            int x = corner % 2 == 0 ? EDGE + shift[corner] : sw - EDGE - shift[corner] - w;
            int y = corner < 2 ? EDGE + used[corner] : sh - EDGE - h - used[corner];
            used[corner] += h + GAP;
            colW[corner] = Math.max(colW[corner], w);
            Ui.hudPanel(g, x, y, w, h);
            if (mod.widget() != null) mod.widget().draw(g, mc, font, x + PAD, y + PAD);
            else Ui.txt(g, font, text, x + PAD, y + PAD + 1, 0xFFFFFFFF, true);
        }
    }

    // ---------------------------------------------------------------- profiles

    static java.util.Map<String, Integer> snapshot() {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        for (HudMod m : MODS) { out.put("on_" + m.id(), m.enabled() ? 1 : 0); out.put("c_" + m.id(), m.corner()); }
        return out;
    }

    static void ensureProfiles() {
        if (!Config.profiles().containsKey("Default")) { Config.profiles().put("Default", snapshot()); Config.setActiveProfile("Default"); }
    }

    /** Saves the current mod state into the active profile (Lunar autosaves the active profile too). */
    static void sync() {
        ensureProfiles();
        Config.profiles().put(Config.activeProfile(), snapshot());
        Config.save();
    }

    static void apply(String name) {
        java.util.Map<String, Integer> p = Config.profiles().get(name);
        if (p == null) return;
        for (HudMod m : MODS) {
            m.setEnabled(p.getOrDefault("on_" + m.id(), m.defaultOn() ? 1 : 0) == 1);
            Config.setHudCorner(m.id(), p.getOrDefault("c_" + m.id(), m.defaultCorner()));
        }
        Config.setActiveProfile(name);
    }
}
