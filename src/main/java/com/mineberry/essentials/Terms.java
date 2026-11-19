package com.mineberry.essentials;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The agreement ("I Agree / I Disagree") the player has to accept before the mod does anything.
 *
 * How it works:
 * - The first time the title screen shows, TermsScreen opens once (see tick). Existing players get it too, after updating.
 * - Until the player agrees, accepted() is false. MineBerryModule.enabled() uses it, so every module is off, and the HUD,
 *   the update check and the What's New popup wait as well. Nothing is drawn, blocked, recorded or sent.
 * - Pressing any MineBerry key (they are all registered through registerKey) opens TermsScreen again instead of doing
 *   anything, and so does the Configure button in Mod Menu (see gate).
 * - Agreeing is saved in the config (termsAcceptedVersion). Raise VERSION when the text changes in a way that needs
 *   everybody to agree again.
 */
public final class Terms {

    /** Version of the terms text. Players who agreed to a lower number are asked again. */
    public static final int VERSION = 1;

    private static final List<KeyMapping> KEYS = new ArrayList<>();
    private static volatile Screen title;       // the title screen currently open, if any

    private Terms() {}

    /** True once the player has agreed to the current terms. */
    public static boolean accepted() {
        return Config.termsAcceptedVersion() >= VERSION;
    }

    /** Registers one of our key binds and remembers it, so a press can open the agreement while it is not accepted. */
    public static KeyMapping registerKey(KeyMapping key) {
        KeyMapping registered = KeyMappingHelper.registerKeyMapping(key);
        KEYS.add(registered);
        return registered;
    }

    /** The screen to open for a feature: {@code target} when the terms are accepted, otherwise the agreement, which continues to {@code target} on "I Agree". */
    public static Screen gate(Screen target, Screen parent) {
        return accepted() ? target : new TermsScreen(parent, target);
    }

    /** Call once from the client initializer, after Config.load() and before Changelog.init(). */
    public static void init() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof TitleScreen && title != screen) {
                title = screen;
                ScreenEvents.remove(screen).register(removed -> { if (title == removed) title = null; });
            }
        });
        // START of the tick, so we see a key press before any module does
        ClientTickEvents.START_CLIENT_TICK.register(Terms::tick);
    }

    private static void tick(Minecraft mc) {
        if (accepted()) return;

        boolean pressed = false;
        for (KeyMapping key : KEYS) {
            while (key.consumeClick()) pressed = true;     // swallowed: the feature behind the key must not run
        }
        if (pressed && mc.gui.screen() == null) {
            mc.gui.setScreen(new TermsScreen(null, null));
            return;
        }

        // the first-launch prompt: once per terms version, on the title screen
        Screen t = title;
        if (t != null && mc.gui.screen() == t && Config.termsPromptedVersion() < VERSION) {
            Config.setTermsPromptedVersion(VERSION);
            title = null;
            mc.gui.setScreen(new TermsScreen(t, null));
        }
    }

    /** Called by TermsScreen on "I Agree". */
    static void accept(Minecraft mc) {
        Config.setTermsAcceptedVersion(VERSION);
        Config.flushNow();                                  // saved right away, even if the game closes a moment later
        if (mc.player != null) {
            mc.player.sendSystemMessage(Component.literal("[MineBerry] ").withStyle(ChatFormatting.LIGHT_PURPLE)
                    .append(Component.literal("Thanks! The mod is ready. Press the open control panel key (Right Control by default) to start.")
                            .withStyle(ChatFormatting.GREEN)));
        }
    }
}
