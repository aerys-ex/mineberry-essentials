package com.mineberry.essentials.modules;

import com.mineberry.essentials.Clock;

import com.mineberry.essentials.Config;
import com.mineberry.essentials.MineBerryModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;

/**
 * Protects against a sudden teleport into a tight pit by sending /spawn.  The check is
 * deliberately armed only after a large server position change, so ordinary caves and
 * corners cannot trigger it.  (There used to be a Right Alt key for /spawn; it was removed in 1.6.3.)
 */
public class SpawnEscapeModule extends MineBerryModule {

    private static final int TELEPORT_GRACE_TICKS = 60;
    private double previousX, previousY, previousZ;
    private boolean havePreviousPosition;
    private int teleportGraceTicks;
    private long lastSpawnMs;
    private static SpawnEscapeModule instance;

    public SpawnEscapeModule() {
        super("spawn_escape", "Spawn Escape", "Pit guard: sends /spawn if a teleport drops you in a tight pit. Can fire when a friend teleports you (tpa)");
        instance = this;
    }

    @Override
    public void init() {
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    /** Shown when the module is switched on in the panel: the automatic pit guard can also catch a friend's tpa. */
    @Override
    protected void onToggled(boolean nowEnabled) {
        if (!nowEnabled) return;
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player == null) return;
            mc.player.sendSystemMessage(Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal("Spawn Escape ON. It works well, but the pit guard can sometimes send /"
                            + Config.spawnCommand() + " when a friend teleports you to them (tpa). Keep that in mind.")
                            .withStyle(ChatFormatting.YELLOW)));
        });
    }

    private void tick(Minecraft mc) {
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
        long now = Clock.ms();
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

    /**
     * The two lines of the status box (title, subtitle), or null while the module is off. The box itself is drawn by the HUD layout
     * (ClientHud, "Spawn Escape"), so it can be hidden, moved and resized like the other HUD boxes.
     */
    public static String[] hudStatus() {
        SpawnEscapeModule m = instance;
        if (m == null || !m.enabled()) return null;
        return new String[]{"SPAWN ESCAPE: ON",
                "Pit guard armed  |  /" + Config.spawnCommand()};
    }
}
