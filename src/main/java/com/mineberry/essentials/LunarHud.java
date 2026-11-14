package com.mineberry.essentials;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** The simple Lunar-style HUD mods (FPS, Ping, Coordinates, Sprint, Clock), drawn as small boxes in the screen corners. */
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
            new HudMod("clock", "Clock", 1, true, mc -> LocalTime.now().format(CLOCK)));

    private static final int PAD = 4, GAP = 3, EDGE = 6;

    private LunarHud() {}

    /** Call once from a ClientModInitializer. */
    private static boolean ready, logged;

    public static void init() {
        if (ready) return;
        ready = true;
        MineBerryHudClient.registerKeys();
        System.out.println("[MineBerry] HUD registered");
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("mineberry_essentials", "lunar_hud"), LunarHud::draw);
    }

    private static void draw(GuiGraphicsExtractor g, DeltaTracker tick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || Config.hudHidden() || mc.gui.screen() instanceof HudEditScreen) return;
        if (!logged) { logged = true; System.out.println("[MineBerry] HUD is drawing"); }
        Ui.applyTheme();
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int[] used = new int[4];   // height already taken in each corner
        for (HudMod mod : MODS) {
            if (!mod.enabled()) continue;
            String text = mod.text().apply(mc);
            if (text == null) continue;
            int corner = mod.corner(), w = font.width(text) + PAD * 2, h = 9 + PAD * 2;
            int x = corner % 2 == 0 ? EDGE : sw - EDGE - w;
            int y = corner < 2 ? EDGE + used[corner] : sh - EDGE - h - used[corner];
            used[corner] += h + GAP;
            Ui.fill(g, x, y, x + w, y + h, 0x90000000);
            Ui.fill(g, x, y, x + 1, y + h, Ui.ACCENT);
            Ui.txt(g, font, text, x + PAD, y + PAD + 1, 0xFFFFFFFF, true);
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
            m.setEnabled(p.getOrDefault("on_" + m.id(), 1) == 1);
            Config.setHudCorner(m.id(), p.getOrDefault("c_" + m.id(), m.defaultCorner()));
        }
        Config.setActiveProfile(name);
    }
}
