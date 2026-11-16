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
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWWindowCloseCallback;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Stops accidental combat-logging.
 *
 * The server tells us when PvP mode starts ("You've entered PvP mode!...") and when it ends ("You're out of PvP mode!..."),
 * so nothing is counted here: you are "in combat" from the first line until the second one (or until you die / disconnect).
 *
 * While in combat, on a multiplayer server, the quit paths are guarded:
 *  - the pause menu's Disconnect button,
 *  - the window's close button / Alt+F4.
 * Mode "warn": the first try is refused and arms the button for a few seconds, a second try goes through.
 * Mode "block": quitting is refused until the server says PvP mode is over. (Switch with the gear on the module card.)
 */
public class SafeLogoutModule extends MineBerryModule {

    private static final long ARM_MS = 4_000;           // how long the second click is accepted
    /** Always treated as "PvP mode is over", even if an old config file still has an older end pattern. */
    private static final Pattern BUILTIN_END = Pattern.compile("out of pvp mode", Pattern.CASE_INSENSITIVE);

    /**
     * Broadcasts about OTHER players ("Player X has left the game while in PvP mode and was killed") mention PvP mode
     * too, but they say nothing about us. They must never start the guard, whatever the configured pattern is.
     */
    private static final Pattern OTHER_PLAYER_NOTICE = Pattern.compile(
            "left the game|has quit|logged out|disconnected|was killed|were killed|killed by|was slain|slain by|combat[- ]?logg",
            Pattern.CASE_INSENSITIVE);

    /** True for server announcements about someone else's PvP mode (leaving, dying, combat logging). */
    public static boolean isOtherPlayerNotice(String text) {
        return OTHER_PLAYER_NOTICE.matcher(text).find();
    }

    /** Chat separators: what comes before a player's own words, like "<Steve> ..." or "Steve: ..." or "Steve > ...". */
    private static final Pattern CHAT_SEPARATOR = Pattern.compile("[<>:\u00bb\u203a\u2192]");

    /**
     * True only if the pattern matches AND the line is a server notice, not a player typing the same words.
     * A player's line has a chat separator before the matched words ("Steve: entered pvp mode"), a server notice
     * ("You've entered PvP mode!") does not. Without this, anyone could switch the PvP guard on or off from chat.
     */
    public static boolean matchesServerNotice(Pattern pattern, String text) {
        java.util.regex.Matcher m = pattern.matcher(text);
        while (m.find()) {
            if (!CHAT_SEPARATOR.matcher(text.substring(0, m.start())).find()) return true;
        }
        return false;
    }

    private static SafeLogoutModule instance;
    private boolean inCombat;                           // set and cleared only by the server's PvP mode messages
    private long pvpSince;                              // last time the server put us in PvP mode
    private boolean wasInCombat;
    private long pauseArmedUntil, closeArmedUntil;

    private Pattern pvpMode, combatEnd;
    private String pvpModeSource, combatEndSource;

    private boolean closeHookInstalled;
    private GLFWWindowCloseCallback closeCallback;      // kept so it is never garbage collected
    private Guard guard;                                // the pause menu currently open, if any

    /** The vanilla Disconnect button and the look-alike that stands in front of it. */
    private static final class Guard {
        final Screen screen;
        final Button original, fake, unlock;
        final Component originalLabel;
        Guard(Screen screen, Button original, Button fake, Button unlock) {
            this.screen = screen; this.original = original; this.fake = fake; this.unlock = unlock; this.originalLabel = original.getMessage();
        }
    }

    public SafeLogoutModule() {
        super("safe_logout", "Safe Logout", "Warns or blocks quitting while you're in combat");
    }

    @Override
    public void init() {
        instance = this;
        HudElementRegistry.addLast(MineBerryEssentials.id("safe_logout_status"), (g, delta) -> drawHud(g));

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onMessage(message, overlay));
        // Signed player chat is deliberately NOT listened to: a player typing "entered pvp mode" must not start the guard.

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
        return inCombat && System.currentTimeMillis() - pvpSince < failsafeMs();
    }

    /** If the server's "out of PvP mode" line is ever missed, everything lets go after this long (config: pvpFailsafeSeconds). */
    private static long failsafeMs() {
        return Config.pvpFailsafeSeconds() * 1000L;
    }

    /** The manual "I'm safe, unlock" override: ends the guard right now, without waiting for the server. */
    private void unlockManually() {
        Minecraft mc = Minecraft.getInstance();
        inCombat = false;
        wasInCombat = false;
        pauseArmedUntil = 0;
        closeArmedUntil = 0;
        say(mc, "Unlocked by you - quitting and commands work again", ChatFormatting.GREEN);
        syncPauseGuard(mc);
    }

    private boolean guarding(Minecraft mc) {
        return enabled() && inCombat() && mc.player != null && !mc.isLocalServer();
    }

    private void tag() {
        inCombat = true;
        pvpSince = System.currentTimeMillis();
    }

    /** True while the server says we are in PvP mode (used by PvP Command Lock). Works even if Safe Logout itself is switched off. */
    public static boolean inPvpMode() {
        SafeLogoutModule m = instance;
        return m != null && m.inCombat();
    }

    private void reset() {
        inCombat = false;
        wasInCombat = false;
        pauseArmedUntil = 0;
        closeArmedUntil = 0;
    }

    /** Action bar lines (overlay) can only end PvP mode, never start it: a countdown there would keep re-arming the guard. */
    private void onMessage(Component message, boolean overlay) {
        String text = message.getString();
        // the end message is checked first: "out of PvP mode" also contains "PvP mode"
        if (matchesServerNotice(BUILTIN_END, text) || matchesServerNotice(combatEnd(), text)) {
            inCombat = false;               // the server says it is over; the tick prints the "safe to quit" line
        } else if (!overlay && matchesServerNotice(pvpMode(), text) && !isOtherPlayerNotice(text)) {
            tag();
        }
    }

    private void tick(Minecraft mc) {
        installCloseHook();

        if (mc.player == null || mc.level == null) {
            reset();
            return;
        }
        if (!mc.player.isAlive()) inCombat = false;
        if (inCombat && !inCombat()) {                      // the end message never came: let go
            inCombat = false;
            if (enabled() && !mc.isLocalServer()) {
                say(mc, "No PvP mode message for " + Config.pvpFailsafeSeconds() + "s - unlocked", ChatFormatting.YELLOW);
            }
            wasInCombat = false;
        }

        boolean now = inCombat();
        if (enabled() && now != wasInCombat && !mc.isLocalServer()) {
            say(mc, now ? "In PvP mode - quitting is guarded until the server says you're out"
                        : "Out of PvP mode - safe to quit", now ? ChatFormatting.RED : ChatFormatting.GREEN);
        }
        wasInCombat = now;
        if (!now) { pauseArmedUntil = 0; closeArmedUntil = 0; }

        syncPauseGuard(mc);
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
        Button unlock = Button.builder(Component.literal("I'm safe - unlock").withStyle(ChatFormatting.GREEN), button -> unlockManually())
                .bounds(original.getX(), original.getY() + original.getHeight() + 4, original.getWidth(), original.getHeight()).build();
        unlock.visible = false;
        unlock.active = false;
        buttons.add(unlock);
        Guard g = new Guard(screen, original, fake, unlock);
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
        show(g.unlock, guarded, guarded);                   // the manual override is only there while a guard is
        boolean block = Config.safeLogoutMode().equals("block");
        boolean armed = guarded && !block && pauseArmedUntil > now;

        if (!guarded) {                                     // free to leave: the real button, untouched
            pauseArmedUntil = 0;
            g.original.setMessage(g.originalLabel);
            show(g.original, true, true);
            show(g.fake, false, false);
        } else if (armed) {                                 // second click goes through to the real button
            g.original.setMessage(Component.literal("Quit anyway! (in PvP mode)").withStyle(ChatFormatting.RED));
            show(g.original, true, true);
            show(g.fake, false, false);
        } else {
            g.original.setMessage(g.originalLabel);
            show(g.original, false, false);
            g.fake.setMessage(Component.literal(block ? "Can't quit: in PvP mode" : "In PvP mode! Click to unlock")
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
            if (block) {
                say(mc, "You're in PvP mode - can't close the game until the server says you're out (or press Esc and click \"I'm safe - unlock\")", ChatFormatting.RED);
            } else {
                closeArmedUntil = now + ARM_MS;
                say(mc, "You're in PvP mode! Close again within " + (ARM_MS / 1000) + "s to quit anyway", ChatFormatting.RED);
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
        String title = "IN PVP MODE";
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
