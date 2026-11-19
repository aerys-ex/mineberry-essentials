package com.mineberry.essentials.modules;

import com.mineberry.essentials.Clock;

import com.mineberry.essentials.Config;
import com.mineberry.essentials.MineBerryModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

import java.util.regex.Pattern;

/** A separately-toggleable pre-combat escape after an accepted incoming teleport request. */
public class TpDangerEscapeModule extends MineBerryModule {

    private static final long REQUEST_VALID_MS = 60_000;
    private static final long DANGER_WINDOW_MS = 12_000;
    private static final double MELEE_RANGE_SQR = 16.0;
    private static final double FACING_DOT = 0.80;
    private static final Pattern TELEPORT_REQUEST = Pattern.compile("~?(\\w{1,16}) has requested that you teleport to them", Pattern.CASE_INSENSITIVE);
    private static final Pattern TELEPORT_ACCEPTED = Pattern.compile("Teleport request accepted", Pattern.CASE_INSENSITIVE);

    private long requestTime;
    private long dangerWindowEnds;
    private long lastSpawnMs;
    private static TpDangerEscapeModule instance;

    public TpDangerEscapeModule() {
        super("tp_danger_escape", "TP Danger Escape", "Auto /spawn after a tp if a player lines up to hit you. Can fire on friends' tpa");
        instance = this;
    }

    @Override
    public void init() {
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) handleTeleportMessage(message); });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> handleTeleportMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void handleTeleportMessage(Component message) {
        if (!enabled()) return;
        String text = message.getString();
        long now = Clock.ms();
        if (TELEPORT_REQUEST.matcher(text).find()) {
            requestTime = now;
        } else if (TELEPORT_ACCEPTED.matcher(text).find() && now - requestTime <= REQUEST_VALID_MS) {
            dangerWindowEnds = now + DANGER_WINDOW_MS;
            requestTime = 0;
        }
    }

    private void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            dangerWindowEnds = 0;
            return;
        }
        if (enabled() && Clock.ms() < dangerWindowEnds && hostilePlayerIsLinedUp(mc)) {
            dangerWindowEnds = 0;
            sendSpawn(mc);
        }
    }

    private static boolean hostilePlayerIsLinedUp(Minecraft mc) {
        Vec3 localEyes = mc.player.getEyePosition();
        for (var other : mc.level.players()) {
            if (other == mc.player || other.isSpectator() || other.distanceToSqr(mc.player) > MELEE_RANGE_SQR) continue;
            Vec3 towardLocal = localEyes.subtract(other.getEyePosition());
            if (towardLocal.lengthSqr() != 0 && other.getViewVector(1.0F).dot(towardLocal.normalize()) >= FACING_DOT) return true;
        }
        return false;
    }

    private void sendSpawn(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) return;
        long now = Clock.ms();
        if (now - lastSpawnMs < 1_000) return;
        lastSpawnMs = now;
        mc.getConnection().sendCommand(Config.spawnCommand());
        mc.player.sendSystemMessage(Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("Nearby player lined up for a hit - sent /" + Config.spawnCommand())
                        .withStyle(ChatFormatting.GREEN)));
    }

    /**
     * The two lines of the status box (title, subtitle), or null while the module is off. The box itself is drawn by the HUD layout
     * (ClientHud, "TP Danger Escape"), so it can be hidden, moved and resized like the other HUD boxes.
     */
    public static String[] hudStatus() {
        TpDangerEscapeModule m = instance;
        if (m == null || !m.enabled()) return null;
        long leftMs = m.dangerWindowEnds - Clock.ms();
        return new String[]{"TP DANGER ESCAPE: ON",
                leftMs > 0 ? "Guard armed: " + ((leftMs + 999) / 1000) + "s" : "Waiting for an accepted TP request"};
    }

    /** Off: forget any pending request. On: tell the player it can't tell a friend from an enemy. */
    @Override
    protected void onToggled(boolean nowEnabled) {
        if (!nowEnabled) {
            requestTime = 0;
            dangerWindowEnds = 0;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player == null) return;
            mc.player.sendSystemMessage(Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal("TP Danger Escape ON. It works well, but it can't tell friends from enemies, so it can "
                            + "sometimes send /" + Config.spawnCommand() + " when a friend teleports you to them and stands close. "
                            + "Keep that in mind.").withStyle(ChatFormatting.YELLOW)));
        });
    }
}
