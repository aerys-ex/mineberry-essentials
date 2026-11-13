package com.mineberry.essentials.modules;

import com.mojang.blaze3d.platform.InputConstants;
import com.mineberry.essentials.Config;
import com.mineberry.essentials.MineBerryEssentials;
import com.mineberry.essentials.MineBerryModule;
import com.mineberry.essentials.WaypointScreen;
import com.mineberry.essentials.render.GizmoDraw;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * Waypoints: saved coordinates drawn as outlined boxes in the world (also through walls, with a tall beam),
 * plus a small HUD list with an arrow and the distance to the nearest ones.
 *
 * B = add a waypoint at your feet, N = open the waypoint list. Both are rebindable.
 * The list screen is also reachable from the Control Center (List button on the Waypoints row).
 */
public class WaypointsModule extends MineBerryModule {

    public static final int[] PALETTE = {
            0xFFE65B93, 0xFF36B6C7, 0xFFFF9B3D, 0xFF6C8CFF, 0xFF56F086, 0xFFFFDD57, 0xFFB57BFF, 0xFFFF5566};

    private static final int HUD_MAX = 4;
    private static final double MAX_DRAW_DIST = 3000;
    private static final double BEAM_MIN_DIST = 6;
    private static final double BEAM_HEIGHT = 320;

    private KeyMapping addKey, menuKey;

    public WaypointsModule() {
        super("waypoints", "Waypoints", "Saved coordinates with in-world markers + HUD");
    }

    @Override
    public void init() {
        addKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mineberry_essentials.add_waypoint", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, MineBerryEssentials.CATEGORY));
        menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mineberry_essentials.waypoint_menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, MineBerryEssentials.CATEGORY));

        HudElementRegistry.addLast(MineBerryEssentials.id("waypoints_hud"), (g, delta) -> drawHud(g));
        LevelRenderEvents.BEFORE_GIZMOS.register(context -> render());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (addKey.consumeClick()) {
                if (enabled() && client.player != null && client.level != null) {
                    Config.Waypoint wp = addHere(client, null);
                    say(client, "Waypoint added: " + wp.name + " (" + wp.x + ", " + wp.y + ", " + wp.z + ")", ChatFormatting.GREEN);
                }
            }
            while (menuKey.consumeClick()) {
                if (enabled() && client.gui.screen() == null) client.gui.setScreen(new WaypointScreen(null));
            }
        });
    }

    // ---------------------------------------------------------------- data helpers (also used by WaypointScreen)

    /** Dimension id of the current level, e.g. "minecraft:overworld". */
    public static String dimensionId(Minecraft mc) {
        if (mc.level == null) return "unknown";
        String s = String.valueOf(mc.level.dimension());   // "ResourceKey[minecraft:dimension / minecraft:overworld]"
        int slash = s.lastIndexOf("/ ");
        if (slash >= 0 && s.endsWith("]")) return s.substring(slash + 2, s.length() - 1).trim();
        return s;
    }

    public static String shortDimension(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }

    /** Adds a waypoint at the player's feet. A blank name becomes "Waypoint N". */
    public static Config.Waypoint addHere(Minecraft mc, String name) {
        return add(mc, name, (int) Math.floor(mc.player.getX()), (int) Math.floor(mc.player.getY()), (int) Math.floor(mc.player.getZ()));
    }

    public static Config.Waypoint add(Minecraft mc, String name, int x, int y, int z) {
        List<Config.Waypoint> list = Config.waypoints();
        Config.Waypoint wp = new Config.Waypoint();
        wp.name = name == null || name.isBlank() ? "Waypoint " + (list.size() + 1) : name.trim();
        wp.x = x; wp.y = y; wp.z = z;
        wp.dim = dimensionId(mc);
        wp.color = PALETTE[list.size() % PALETTE.length];
        wp.visible = true;
        list.add(wp);
        Config.save();
        return wp;
    }

    public static double distanceTo(Minecraft mc, Config.Waypoint wp) {
        double dx = wp.x + 0.5 - mc.player.getX(), dy = wp.y + 0.5 - mc.player.getY(), dz = wp.z + 0.5 - mc.player.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static String formatDistance(double d) {
        return d >= 10_000 ? String.format("%.0fk", d / 1000) : d >= 1000 ? String.format("%.1fk", d / 1000) : Math.round(d) + "m";
    }

    private static void say(Minecraft mc, String text, ChatFormatting color) {
        if (mc.player == null) return;
        mc.player.sendSystemMessage(
                Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                        .append(Component.literal(text).withStyle(color)));
    }

    /** Visible waypoints of the current dimension, nearest first. */
    private List<Config.Waypoint> nearby(Minecraft mc) {
        String dim = dimensionId(mc);
        List<Config.Waypoint> out = new ArrayList<>();
        for (Config.Waypoint wp : Config.waypoints()) if (wp.visible && dim.equals(wp.dim)) out.add(wp);
        out.sort((a, b) -> Double.compare(distanceTo(mc, a), distanceTo(mc, b)));
        return out;
    }

    // ---------------------------------------------------------------- world markers

    private void render() {
        if (!enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        for (Config.Waypoint wp : nearby(mc)) {
            double dist = distanceTo(mc, wp);
            if (dist > MAX_DRAW_DIST) continue;
            GizmoDraw.box(new AABB(wp.x, wp.y, wp.z, wp.x + 1, wp.y + 1, wp.z + 1).inflate(0.003), wp.color, 3f);
            if (dist > BEAM_MIN_DIST) {
                GizmoDraw.line(new Vec3(wp.x + 0.5, wp.y + 1, wp.z + 0.5), new Vec3(wp.x + 0.5, wp.y + BEAM_HEIGHT, wp.z + 0.5), wp.color, 2f);
            }
        }
    }

    // ---------------------------------------------------------------- HUD

    private void drawHud(GuiGraphicsExtractor g) {
        if (!enabled() || Config.hudHidden()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        var font = mc.font;
        int screenW = mc.getWindow().getGuiScaledWidth();
        float yaw = mc.player.getYRot();
        int row = 0;
        for (Config.Waypoint wp : nearby(mc)) {
            if (row >= HUD_MAX) break;
            double dx = wp.x + 0.5 - mc.player.getX(), dz = wp.z + 0.5 - mc.player.getZ();
            String text = arrow(yaw, dx, dz) + " " + wp.name + "  " + formatDistance(distanceTo(mc, wp));
            int x = screenW - font.width(text) - 6;
            g.text(font, text, x, 104 + row * 11, wp.color | 0xFF000000, true);
            row++;
        }
    }

    /** Arrow showing where the waypoint is relative to where the player is looking. */
    private static String arrow(float playerYaw, double dx, double dz) {
        double target = Math.toDegrees(Math.atan2(-dx, dz));   // Minecraft yaw: 0 = south (+Z), 90 = west (-X)
        double rel = target - playerYaw;
        rel = ((rel % 360) + 540) % 360 - 180;                  // wrap to [-180, 180)
        double a = Math.abs(rel);
        if (a < 22.5) return "\u2191";
        if (a > 157.5) return "\u2193";
        boolean right = rel > 0;
        if (a < 67.5) return right ? "\u2197" : "\u2196";
        if (a < 112.5) return right ? "\u2192" : "\u2190";
        return right ? "\u2198" : "\u2199";
    }
}
