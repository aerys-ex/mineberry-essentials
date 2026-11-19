package com.mineberry.essentials;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
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

/** The MineBerry Client-style HUD mods (FPS, Ping, Coordinates, Sprint, Clock, Keystrokes, Armor, Potions, Compass, Biome, Tournament Players), drawn as small boxes in the screen corners. */
public final class ClientHud {

    static final String[] CORNERS = {"Top left", "Top right", "Bottom left", "Bottom right"};
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * The vanilla sidebar scoreboard: not drawn by us (see init()), it only has a switch to hide it and a size (50-250%).
     * On by default so nothing changes until the player touches it.
     */
    static final HudMod SCOREBOARD = new HudMod("scoreboard", "Scoreboard", 1, true, null, new HudWidgets.Scoreboard(), true);

    static final List<HudMod> MODS = List.of(
            // the watermark: on by default, top left, drawn without a box. Switch it off or move it in the HUD menu
            new HudMod("watermark", "Watermark", 0, true, null, new HudWidgets.Watermark(), true),
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
            new HudMod("tournament", "Tournament Players", 3, true, null, new HudWidgets.TournamentPlayers(), true),
            SCOREBOARD);

    private static final int PAD = 4, GAP = 3, EDGE = 6;

    private ClientHud() {}

    /** Call once from a ClientModInitializer. */
    private static boolean ready, logged;

    public static void init() {
        if (ready) return;
        ready = true;
        registerKeys();
        MineBerryEssentials.LOGGER.info("HUD registered");
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("mineberry_essentials", "client_hud"), ClientHud::draw);
        HudElementRegistry.replaceElement(VanillaHudElements.SCOREBOARD, ClientHud::wrapScoreboard);
    }

    /**
     * The vanilla scoreboard, hidden when its switch is off and scaled around the middle of the right screen edge (where vanilla puts it)
     * otherwise. The Y key (HUD hidden) deliberately does not touch it: it is not one of our boxes.
     */
    private static HudElement wrapScoreboard(HudElement vanilla) {
        return (g, tick) -> {
            if (!Terms.accepted()) {                 // before agreeing the vanilla scoreboard is left completely alone
                vanilla.extractRenderState(g, tick);
                return;
            }
            if (!SCOREBOARD.enabled()) return;
            float s = SCOREBOARD.scale();
            if (s == 1f) {
                vanilla.extractRenderState(g, tick);
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            float ax = mc.getWindow().getGuiScaledWidth(), ay = mc.getWindow().getGuiScaledHeight() / 2f;
            g.pose().pushMatrix();
            g.pose().translate(ax, ay);
            g.pose().scale(s, s);
            g.pose().translate(-ax, -ay);
            try {
                vanilla.extractRenderState(g, tick);
            } finally {
                g.pose().popMatrix();
            }
        };
    }

    /** Right Shift opens the HUD menu (like most game clients); change it in Options > Controls. HUD show/hide has its own key in MineBerryEssentials. */
    private static void registerKeys() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("mineberry_essentials", "client_menu"));
        KeyMapping openMenu = Terms.registerKey(new KeyMapping(
                "key.mineberry_essentials.client_menu", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openMenu.consumeClick()) {
                if (client.gui.screen() == null) client.gui.setScreen(new ClientMenu(null));
            }
        });
    }

    private static void draw(GuiGraphicsExtractor g, DeltaTracker tick) {
        Minecraft mc = Minecraft.getInstance();
        StatusStack.begin(EDGE);   // the status boxes on the right stack below whatever the HUD uses in the top corners
        if (mc.player == null || !Terms.accepted() || Config.hudHidden() || mc.gui.screen() instanceof HudEditScreen) return;
        if (!logged) { logged = true; MineBerryEssentials.LOGGER.debug("HUD is drawing"); }
        Ui.applyTheme();
        Font font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        for (Placed p : layout(mc, font, sw, sh, false, null)) drawBox(g, mc, font, p);
    }

    /**
     * One HUD box and the place it is drawn at. size is null for one-line text boxes (and for empty placeholders in the editor).
     * w and h are the size on screen, already multiplied by scale (the box's size setting, 1 = normal).
     */
    record Placed(HudMod mod, int x, int y, int w, int h, String text, int[] size, boolean free, float scale) {}

    static void drawBox(GuiGraphicsExtractor g, Minecraft mc, Font font, Placed p) {
        float s = p.scale();
        boolean scaled = s != 1f;
        int x = p.x(), y = p.y(), w = p.w(), h = p.h();
        if (scaled) {   // draw the box at its normal size in its own coordinates, scaled around its top-left corner
            g.pose().pushMatrix();
            g.pose().translate(x, y);
            g.pose().scale(s, s);
            x = 0;
            y = 0;
            w = Math.round(w / s);
            h = Math.round(h / s);
        }
        boolean plain = p.size() != null && p.mod().widget().plain();
        if (!plain) {
            Ui.fill(g, x, y, x + w, y + h, 0x90000000);
            Ui.fill(g, x, y, x + 1, y + h, Ui.ACCENT);
        }
        if (p.size() != null) p.mod().widget().draw(g, mc, font, x + PAD, y + PAD);
        else Ui.txt(g, font, p.text(), x + PAD, y + PAD + 1, 0xFFFFFFFF, true);
        if (scaled) g.pose().popMatrix();
    }

    /**
     * Works out where every enabled HUD box goes. Boxes with a free position (set by dragging in the layout editor) are anchored to
     * their corner and clamped to the screen, so they survive window resizes; the others stack in their corner as before.
     * editing = also place boxes that have nothing to show right now (as a labelled placeholder). skip = a box that is being dragged:
     * it is measured but takes no place in the stack.
     */
    static List<Placed> layout(Minecraft mc, Font font, int sw, int sh, boolean editing, HudMod skip) {
        List<Placed> out = new java.util.ArrayList<>();
        int[] used = new int[4];     // height already taken in the current column of each corner
        int[] shift = new int[4];    // how far the current column is moved away from the screen edge
        int[] colW = new int[4];     // width of the widest box in the current column
        for (HudMod mod : MODS) {
            if (!mod.enabled() || mod.virtual()) continue;
            String text = null;
            int[] size = null;
            int cw, ch;
            if (mod.widget() != null) {
                size = mod.widget().size(mc, font);
                if (size == null) {
                    if (!editing) continue;
                    text = mod.name();
                    cw = font.width(text);
                    ch = 9;
                } else {
                    cw = size[0];
                    ch = size[1];
                }
            } else {
                text = mod.text().apply(mc);
                if (text == null) {
                    if (!editing) continue;
                    text = mod.name();
                }
                cw = font.width(text);
                ch = 9;
            }
            float sc = mod.scale();
            int corner = mod.corner(), w = Math.round((cw + PAD * 2) * sc), h = Math.round((ch + PAD * 2) * sc);
            if (mod == skip) {
                out.add(new Placed(mod, 0, 0, w, h, text, size, true, sc));
                continue;
            }
            int[] pos = Config.hudPos(mod.id());
            if (pos != null) {
                int fx = corner % 2 == 0 ? pos[0] : sw - w - pos[0];
                int fy = corner < 2 ? pos[1] : sh - h - pos[1];
                fx = Math.max(0, Math.min(fx, sw - w));
                fy = Math.max(0, Math.min(fy, sh - h));
                out.add(new Placed(mod, fx, fy, w, h, text, size, true, sc));
                continue;
            }
            if (used[corner] > 0 && used[corner] + h > sh - EDGE * 2) {   // the column is full: start the next one
                shift[corner] += colW[corner] + GAP;
                used[corner] = 0;
                colW[corner] = 0;
            }
            int x = corner % 2 == 0 ? EDGE + shift[corner] : sw - EDGE - shift[corner] - w;
            int y = corner < 2 ? EDGE + used[corner] : sh - EDGE - h - used[corner];
            used[corner] += h + GAP;
            colW[corner] = Math.max(colW[corner], w);
            if (!editing && shift[corner] == 0) {   // only the column next to the screen edge matters for the status boxes
                if (corner == 1) StatusStack.rightTop = EDGE + used[1];
                else if (corner == 0) StatusStack.leftTop = EDGE + used[0];
            }
            out.add(new Placed(mod, x, y, w, h, text, size, false, sc));
        }
        return out;
    }

    // ---------------------------------------------------------------- profiles

    static java.util.Map<String, Integer> snapshot() {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        for (HudMod m : MODS) {
            out.put("on_" + m.id(), m.enabled() ? 1 : 0);
            out.put("c_" + m.id(), m.corner());
            out.put("s_" + m.id(), m.scalePct());
            int[] pos = Config.hudPos(m.id());
            if (pos != null) { out.put("p_" + m.id(), 1); out.put("px_" + m.id(), pos[0]); out.put("py_" + m.id(), pos[1]); }
        }
        return out;
    }

    static void ensureProfiles() {
        if (!Config.profiles().containsKey("Default")) { Config.profiles().put("Default", snapshot()); Config.setActiveProfile("Default"); }
    }

    /** Saves the current mod state into the active profile (MineBerry Client autosaves the active profile too). */
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
            int corner = p.getOrDefault("c_" + m.id(), m.defaultCorner());
            Config.setHudCorner(m.id(), corner);
            Config.setHudScalePct(m.id(), p.getOrDefault("s_" + m.id(), 100));
            if (p.getOrDefault("p_" + m.id(), 0) == 1) Config.setHudPos(m.id(), corner, p.getOrDefault("px_" + m.id(), 0), p.getOrDefault("py_" + m.id(), 0));
            else Config.clearHudPos(m.id());
        }
        Config.setActiveProfile(name);
    }
}
