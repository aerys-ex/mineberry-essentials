package com.mineberry.essentials.modules;

import com.mineberry.essentials.Config;
import com.mineberry.essentials.MineBerryEssentials;
import com.mineberry.essentials.MineBerryModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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

    public TpDangerEscapeModule() {
        super("tp_danger_escape", "TP Danger Escape", "Auto /spawn before a nearby player can hit after TP");
    }

    @Override
    public void init() {
        HudElementRegistry.addLast(MineBerryEssentials.id("tp_danger_escape_status"), (g, delta) -> drawHud(g));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) handleTeleportMessage(message); });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> handleTeleportMessage(message));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void handleTeleportMessage(Component message) {
        if (!enabled()) return;
        String text = message.getString();
        long now = System.currentTimeMillis();
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
        if (enabled() && System.currentTimeMillis() < dangerWindowEnds && hostilePlayerIsLinedUp(mc)) {
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
        long now = System.currentTimeMillis();
        if (now - lastSpawnMs < 1_000) return;
        lastSpawnMs = now;
        mc.getConnection().sendCommand(Config.spawnCommand());
        mc.player.sendSystemMessage(Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                .append(Component.literal("Nearby player lined up for a hit - sent /" + Config.spawnCommand())
                        .withStyle(ChatFormatting.GREEN)));
    }

    private void drawHud(GuiGraphicsExtractor g) {
        if (MineBerryEssentials.hudHidden() || !enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        var font = mc.font;
        long leftMs = dangerWindowEnds - System.currentTimeMillis();
        String title = "TP DANGER ESCAPE: ON";
        String subtitle = leftMs > 0 ? "Guard armed: " + ((leftMs + 999) / 1000) + "s" : "Waiting for an accepted TP request";
        int w = Math.max(font.width(title), font.width(subtitle)) + 18;
        int x = mc.getWindow().getGuiScaledWidth() - w - 6;
        int y = 74;
        g.fill(x - 1, y - 1, x + w + 1, y + 30, 0x66000000);
        g.fill(x, y, x + w, y + 29, 0xE0341A12);
        g.fill(x + w - 3, y, x + w, y + 29, 0xFFFF8A3D);
        g.text(font, title, x + 8, y + 5, 0xFFFFB066, true);
        g.text(font, subtitle, x + 8, y + 17, 0xFFE0E0E0, false);
    }

    @Override
    protected void onToggled(boolean nowEnabled) {
        if (!nowEnabled) {
            requestTime = 0;
            dangerWindowEnds = 0;
        }
    }
}
