package com.mineberry.essentials.modules;

import com.mojang.blaze3d.platform.InputConstants;
import com.mineberry.essentials.Config;
import com.mineberry.essentials.MineBerryEssentials;
import com.mineberry.essentials.MineBerryModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import org.lwjgl.glfw.GLFW;

/**
 * Sends /spawn on a rebindable key, and protects against a sudden teleport into a
 * tight pit.  The automatic check is deliberately armed only after a large server
 * position change, so ordinary caves and corners cannot trigger it.
 */
public class SpawnEscapeModule extends MineBerryModule {

    private static final int TELEPORT_GRACE_TICKS = 60;
    private KeyMapping spawnKey;
    private double previousX, previousY, previousZ;
    private boolean havePreviousPosition;
    private int teleportGraceTicks;
    private long lastSpawnMs;

    public SpawnEscapeModule() {
        super("spawn_escape", "Spawn Escape", "Right Alt: /spawn; auto-escapes a teleport pit");
    }

    @Override
    public void init() {
        spawnKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mineberry_essentials.spawn_escape",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_RIGHT_ALT,
                MineBerryEssentials.CATEGORY));

        HudElementRegistry.addLast(MineBerryEssentials.id("spawn_escape_status"), (g, delta) -> drawHud(g));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        while (spawnKey.consumeClick()) {
            // The manual escape key is intentionally independent of the module
            // switch; disabling the module only turns off automatic pit detection.
            sendSpawn(mc, false);
        }
        if (mc.player == null || mc.level == null) {
            havePreviousPosition = false;
            teleportGraceTicks = 0;
            return;
        }

        double x = mc.player.getX(), y = mc.player.getY(), z = mc.player.getZ();
        if (havePreviousPosition) {
            double dx = x - previousX, dy = y - previousY, dz = z - previousZ;
            // A normal player movement cannot cover this in one client tick.
            if (dx * dx + dz * dz >= 64.0 || Math.abs(dy) >= 4.0) {
                teleportGraceTicks = TELEPORT_GRACE_TICKS;
            }
        }
        previousX = x;
        previousY = y;
        previousZ = z;
        havePreviousPosition = true;

        if (teleportGraceTicks > 0) {
            if (enabled() && isTightPit(mc.level, BlockPos.containing(x, y + 0.1, z))) {
                sendSpawn(mc, true);
                teleportGraceTicks = 0;
            } else {
                teleportGraceTicks--;
            }
        }

    }

    /** A pit has a solid floor and at least three closed sides at both body heights. */
    private static boolean isTightPit(Level level, BlockPos feet) {
        if (!isSolid(level, feet.below())) return false;
        int lowerWalls = 0;
        int upperWalls = 0;
        for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) {
            if (isSolid(level, feet.relative(direction))) lowerWalls++;
            if (isSolid(level, feet.above().relative(direction))) upperWalls++;
        }
        return lowerWalls >= 3 && upperWalls >= 2;
    }

    private static boolean isSolid(Level level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    private void sendSpawn(Minecraft mc, boolean automatic) {
        sendSpawn(mc, automatic, "Teleport pit detected");
    }

    private void sendSpawn(Minecraft mc, boolean automatic, String reason) {
        if (mc.getConnection() == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSpawnMs < 1_000) return;
        lastSpawnMs = now;
        mc.getConnection().sendCommand(Config.spawnCommand());
        if (automatic) {
            mc.player.sendSystemMessage(Component.literal("[MineBerry] ")
                    .withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal(reason + " - sent /" + Config.spawnCommand())
                            .withStyle(ChatFormatting.GREEN)));
        }
    }

    private void drawHud(GuiGraphicsExtractor g) {
        if (MineBerryEssentials.hudHidden() || !enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        var font = mc.font;
        String title = "SPAWN ESCAPE: ON";
        String subtitle = "[" + spawnKey.getTranslatedKeyMessage().getString() + "] /" + Config.spawnCommand()
                + "  |  pit guard armed";
        int w = Math.max(font.width(title), font.width(subtitle)) + 18;
        int x = mc.getWindow().getGuiScaledWidth() - w - 6;
        int y = 40;
        g.fill(x - 1, y - 1, x + w + 1, y + 30, 0x66000000);
        g.fill(x, y, x + w, y + 29, 0xE0182534);
        g.fill(x + w - 3, y, x + w, y + 29, 0xFF48A9FF);
        g.text(font, title, x + 8, y + 5, 0xFF67BEFF, true);
        g.text(font, subtitle, x + 8, y + 17, 0xFFE0E0E0, false);
    }
}
