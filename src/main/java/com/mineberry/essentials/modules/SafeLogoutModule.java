package com.mineberry.essentials.modules;

import com.mineberry.essentials.Config;
import com.mineberry.essentials.MineBerryEssentials;
import com.mineberry.essentials.MineBerryModule;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWWindowCloseCallback;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Stops accidental combat-logging.
 *
 * "In combat" starts when you hit a player, when you take damage with another player close by, or when the server
 * prints its PvP/combat line (the same regex the Evidence Recorder uses, "pvpModePattern" in the config). It ends after
 * "combatSeconds" without a new hit, when you die, or when the server says combat is over ("combatEndPattern").
 *
 * While in combat, on a multiplayer server, the quit paths are guarded:
 *  - the pause menu's Disconnect button,
 *  - the window's close button / Alt+F4.
 * Mode "warn": the first try is refused and arms the button for a few seconds, a second try goes through.
 * Mode "block": quitting is refused until combat ends. (Switch with the gear on the module card.)
 *
 * Nothing here touches the game's quit code: the pause menu button is swapped for a look-alike that only lets the
 * real one through when it is safe, and the window close request is cancelled the way GLFW intends.
 * Crashes, kicks and killing the process are of course not (and cannot be) affected.
 */
public class SafeLogoutModule extends MineBerryModule {

    private static final long ARM_MS = 4_000;           // how long the second click is accepted
    private static final double NEARBY_PLAYER_SQR = 10.0 * 10.0;

    private long combatUntil;                           // ms timestamp, 0 = not in combat
    private boolean wasInCombat;
    private boolean wasHurt;
    private long pauseArmedUntil, closeArmedUntil;

    private Pattern pvpMode, combatEnd;
    private String pvpModeSource, combatEndSource;

    private boolean closeHookInstalled;
    private GLFWWindowCloseCallback closeCallback;      // kept so it is never garbage collected
    private Guard guard;                                // the pause menu currently open, if any

    /** The vanilla Disconnect button and the look-alike that stands in front of it. */
    private static final class Guard {
        final Screen screen;
        final Button original, fake;
        final Component originalLabel;
        Guard(Screen screen, Button original, Button fake) {
            this.screen = screen; this.original = original; this.fake = fake; this.originalLabel = original.getMessage();
        }
    }

    public SafeLogoutModule() {
        super("safe_logout", "Safe Logout", "Warns or blocks quitting while you're in combat");
    }

    @Override
    public void init() {
        HudElementRegistry.addLast(MineBerryEssentials.id("safe_logout_status"), (g, delta) -> drawHud(g));

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> { if (!overlay) onMessage(message); });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, timestamp) -> onMessage(message));

        // you hit a player
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClientSide() && enabled() && entity instanceof Player other && other != player && !other.isSpectator()) {
                tag();
            }
            return InteractionResult.PASS;
        });

        // the pause menu: put a guard in front of its Disconnect button
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof PauseScreen) installGuard(screen);
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    // ---------------------------------------------------------------- combat state

    /** True while the quit guards apply. */
    private boolean inCombat() {
        return combatUntil > System.currentTimeMillis();
    }

    private boolean guarding(Minecraft mc) {
        return enabled() && inCombat() && mc.player != null && !mc.isLocalServer();
    }

    private void tag() {
        combatUntil = System.currentTimeMillis() + Config.combatSeconds() * 1000L;
    }

    private void reset() {
        combatUntil = 0;
        wasInCombat = false;
        wasHurt = false;
        pauseArmedUntil = 0;
        closeArmedUntil = 0;
    }

    private void onMessage(Component message) {
        if (!enabled()) return;
        String text = message.getString();
        if (combatEnd().matcher(text).find()) {
            combatUntil = 0;                // the server says it is over; the tick prints the "safe to quit" line
        } else if (pvpMode().matcher(text).find()) {
            tag();
        }
    }

    private void tick(Minecraft mc) {
        installCloseHook();

        if (mc.player == null || mc.level == null) {
            reset();
            return;
        }
        if (!mc.player.isAlive()) combatUntil = 0;

        // you were hit with another player close by
        boolean hurt = mc.player.hurtTime > 0;
        if (hurt && !wasHurt && enabled() && playerNearby(mc)) tag();
        wasHurt = hurt;

        boolean now = inCombat();
        if (enabled() && now != wasInCombat && !mc.isLocalServer()) {
            say(mc, now ? "In combat - quitting is guarded for " + Config.combatSeconds() + "s after the last hit"
                        : "Combat over - safe to quit", now ? ChatFormatting.RED : ChatFormatting.GREEN);
        }
        wasInCombat = now;
        if (!now) { pauseArmedUntil = 0; closeArmedUntil = 0; }

        syncPauseGuard(mc);
    }

    private static boolean playerNearby(Minecraft mc) {
        for (Player other : mc.level.players()) {
            if (other != mc.player && !other.isSpectator() && other.distanceToSqr(mc.player) <= NEARBY_PLAYER_SQR) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- pause menu guard

    private static boolean isQuitLabel(String label) {
        return label.equals(Component.translatable("menu.disconnect").getString())
                || label.equals(Component.translatable("menu.returnToMenu").getString());
    }

    /** Swaps the Disconnect button for a look-alike at the same spot; syncPauseGuard decides which of the two shows. */
    private void installGuard(Screen screen) {
        guard = null;
        List<AbstractWidget> buttons = Screens.getWidgets(screen);
        Button original = null;
        for (AbstractWidget widget : buttons) {
            if (widget instanceof Button button && isQuitLabel(button.getMessage().getString())) { original = button; break; }
        }
        if (original == null) return;                       // unknown pause menu layout: leave it alone

        Button fake = Button.builder(original.getMessage(), button -> onFakePressed())
                .bounds(original.getX(), original.getY(), original.getWidth(), original.getHeight()).build();
        buttons.add(fake);
        Guard g = new Guard(screen, original, fake);
        guard = g;
        ScreenEvents.remove(screen).register(removed -> { if (guard == g) guard = null; });
        syncPauseGuard(Minecraft.getInstance());
    }

    /** The look-alike was clicked: in warn mode this arms the real button for a few seconds. */
    private void onFakePressed() {
        Minecraft mc = Minecraft.getInstance();
        if (!guarding(mc) || Config.safeLogoutMode().equals("block")) return;
        pauseArmedUntil = System.currentTimeMillis() + ARM_MS;
        syncPauseGuard(mc);
    }

    /** Called every tick: shows the real button when it is safe (or armed), the look-alike when it is not. */
    private void syncPauseGuard(Minecraft mc) {
        Guard g = guard;
        if (g == null || mc.gui.screen() != g.screen) return;

        long now = System.currentTimeMillis();
        boolean guarded = guarding(mc);
        boolean block = Config.safeLogoutMode().equals("block");
        boolean armed = guarded && !block && pauseArmedUntil > now;
        long secs = Math.max(0, (combatUntil - now + 999) / 1000);

        if (!guarded) {                                     // free to leave: the real button, untouched
            pauseArmedUntil = 0;
            g.original.setMessage(g.originalLabel);
            show(g.original, true, true);
            show(g.fake, false, false);
        } else if (armed) {                                 // second click goes through to the real button
            g.original.setMessage(Component.literal("Quit anyway! (" + secs + "s in combat)").withStyle(ChatFormatting.RED));
            show(g.original, true, true);
            show(g.fake, false, false);
        } else {
            g.original.setMessage(g.originalLabel);
            show(g.original, false, false);
            g.fake.setMessage(Component.literal(block ? "Can't quit: in combat (" + secs + "s)" : "In combat! Click to unlock (" + secs + "s)")
                    .withStyle(ChatFormatting.RED));
            show(g.fake, true, !block);
        }
    }

    private static void show(Button button, boolean visible, boolean active) {
        button.visible = visible;
        button.active = active;
    }

    // ---------------------------------------------------------------- window close (X button / Alt+F4)

    /** Hooks the window's close request once, on the render thread. A cancelled request is undone the way GLFW intends. */
    private void installCloseHook() {
        if (closeHookInstalled) return;
        long window = GLFW.glfwGetCurrentContext();
        if (window == 0L) return;                           // not ready yet, try again next tick
        closeHookInstalled = true;
        GLFWWindowCloseCallback previous = GLFW.glfwSetWindowCloseCallback(window, null);
        closeCallback = GLFWWindowCloseCallback.create(w -> {
            if (previous != null) previous.invoke(w);
            Minecraft mc = Minecraft.getInstance();
            if (!guarding(mc)) return;
            long now = System.currentTimeMillis();
            boolean block = Config.safeLogoutMode().equals("block");
            if (!block && closeArmedUntil > now) return;    // second try in time: let it close
            GLFW.glfwSetWindowShouldClose(w, false);        // cancel this close request
            long secs = Math.max(0, (combatUntil - now + 999) / 1000);
            if (block) {
                say(mc, "You're in combat - can't close the game for another " + secs + "s", ChatFormatting.RED);
            } else {
                closeArmedUntil = now + ARM_MS;
                say(mc, "You're in combat (" + secs + "s)! Close again within " + (ARM_MS / 1000) + "s to quit anyway", ChatFormatting.RED);
            }
        });
        GLFW.glfwSetWindowCloseCallback(window, closeCallback);
    }

    // ---------------------------------------------------------------- HUD + helpers

    private void drawHud(GuiGraphicsExtractor g) {
        if (MineBerryEssentials.hudHidden() || !enabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.isLocalServer() || !inCombat()) return;
        var font = mc.font;
        long secs = Math.max(0, (combatUntil - System.currentTimeMillis() + 999) / 1000);
        String title = "IN COMBAT: " + secs + "s";
        String subtitle = Config.safeLogoutMode().equals("block") ? "Quitting is blocked" : "Quitting needs a second click";
        int w = Math.max(font.width(title), font.width(subtitle)) + 18;
        int x = mc.getWindow().getGuiScaledWidth() - w - 6;
        int y = 108;
        g.fill(x - 1, y - 1, x + w + 1, y + 30, 0x66000000);
        g.fill(x, y, x + w, y + 29, 0xE0341212);
        g.fill(x + w - 3, y, x + w, y + 29, 0xFFFF4D4D);
        g.text(font, title, x + 8, y + 5, 0xFFFF7A7A, true);
        g.text(font, subtitle, x + 8, y + 17, 0xFFE0E0E0, false);
    }

    private static void say(Minecraft mc, String text, ChatFormatting color) {
        mc.execute(() -> {
            if (mc.player == null) return;
            mc.player.sendSystemMessage(Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal(text).withStyle(color)));
        });
    }

    private Pattern pvpMode() {
        String src = Config.pvpModePattern();
        if (pvpMode == null || !src.equals(pvpModeSource)) {
            pvpMode = compile(src, Config.DEFAULT_PVP_MODE);
            pvpModeSource = src;
        }
        return pvpMode;
    }

    private Pattern combatEnd() {
        String src = Config.combatEndPattern();
        if (combatEnd == null || !src.equals(combatEndSource)) {
            combatEnd = compile(src, Config.DEFAULT_COMBAT_END);
            combatEndSource = src;
        }
        return combatEnd;
    }

    private static Pattern compile(String source, String fallback) {
        try {
            return Pattern.compile(source, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return Pattern.compile(fallback, Pattern.CASE_INSENSITIVE);
        }
    }

    @Override
    protected void onToggled(boolean nowEnabled) {
        if (!nowEnabled) reset();
    }
}
