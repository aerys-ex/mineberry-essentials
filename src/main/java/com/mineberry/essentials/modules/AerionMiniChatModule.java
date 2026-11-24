package com.mineberry.essentials.modules;

import com.mojang.blaze3d.platform.InputConstants;
import com.mineberry.essentials.AerionMiniScreen;
import com.mineberry.essentials.AerionTalk;
import com.mineberry.essentials.Clock;
import com.mineberry.essentials.MineBerryEssentials;
import com.mineberry.essentials.MineBerryModule;
import com.mineberry.essentials.Terms;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Supplier;

/**
 * Aerion mini chat: press ` (rebindable) to open a small chat with Aerion on the left of the HUD, press it again (or Esc)
 * to close it. Enter sends. It is the same conversation as the Aerion tab and {@code !aerion}.
 *
 * After you close it, Aerion's last answer stays on the HUD for a few seconds so you can read it while you play.
 * The radial menu has an Aerion slice that opens the same chat.
 */
public class AerionMiniChatModule extends MineBerryModule {

    private static final long TOAST_MS = 10_000;
    private static final int TOAST_MAX_W = 240, TOAST_MAX_LINES = 6;

    private static AerionMiniChatModule instance;
    private KeyMapping key;
    private String toast;
    private long toastAt;
    private Supplier<String> pinned;   // a message that stays up until it is taken down (the airdrop question)

    public AerionMiniChatModule() {
        super("aerion_mini", "Aerion Mini Chat", "Press ` to chat with Aerion on the HUD");
    }

    @Override
    public void init() {
        instance = this;
        key = Terms.registerKey(new KeyMapping(
                "key.mineberry_essentials.aerion_chat", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_GRAVE_ACCENT, MineBerryEssentials.CATEGORY));
        HudElementRegistry.addLast(MineBerryEssentials.id("aerion_toast"), (g, delta) -> drawToast(g));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (key.consumeClick()) {
                if (enabled() && mc.player != null && mc.level != null && mc.gui.screen() == null) open(mc);
            }
        });
    }

    /** Opens the mini chat (also used by the radial menu's Aerion slice). */
    public static void open(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        mc.gui.setScreen(new AerionMiniScreen());
    }

    /** The GLFW key code that closes the chat again: the open key, wherever the player bound it. */
    public static int closeKeyCode() {
        AerionMiniChatModule m = instance;
        if (m == null) return GLFW.GLFW_KEY_GRAVE_ACCENT;
        InputConstants.Key bound = KeyMappingHelper.getBoundKeyOf(m.key);
        return bound.getType() == InputConstants.Type.KEYSYM && bound.getValue() >= 0 ? bound.getValue() : GLFW.GLFW_KEY_GRAVE_ACCENT;
    }

    /**
     * True when pressing the open key may close the chat. A key that types a character (a letter, a digit...) must not:
     * typing it would close the chat. The chat then closes with Esc only.
     */
    public static boolean closesWithKey() {
        int code = closeKeyCode();
        boolean typesCharacter = (code >= GLFW.GLFW_KEY_SPACE && code < GLFW.GLFW_KEY_GRAVE_ACCENT)
                || code == GLFW.GLFW_KEY_WORLD_1 || code == GLFW.GLFW_KEY_WORLD_2
                || (code >= GLFW.GLFW_KEY_KP_0 && code <= GLFW.GLFW_KEY_KP_EQUAL);
        return !typesCharacter;
    }

    /** The open key as the player sees it, for hints. */
    public static String keyName() {
        AerionMiniChatModule m = instance;
        return m == null ? "`" : m.key.getTranslatedKeyMessage().getString();
    }

    /** Shows Aerion's answer on the HUD for a few seconds (when the mini chat is not open, which already shows it). */
    public static void toast(String text) {
        AerionMiniChatModule m = instance;
        if (m == null || text == null || text.isBlank()) return;
        if (Minecraft.getInstance().gui.screen() instanceof AerionMiniScreen) return;   // the open chat already shows it
        m.toast = text;
        m.toastAt = Clock.ms();
    }

    /** True when this module is on, so Aerion's HUD messages can be shown here. */
    public static boolean active() {
        AerionMiniChatModule m = instance;
        return m != null && m.enabled();
    }

    /**
     * Keeps a message from Aerion on the HUD, in the same place as her answers, until {@link #unpin} is called.
     * The text is asked again every frame, so it can hold a live countdown. Not drawn while the mini chat is open
     * (the same message is in the conversation then).
     */
    public static void pin(Supplier<String> text) {
        AerionMiniChatModule m = instance;
        if (m != null) m.pinned = text;
    }

    public static void unpin() {
        AerionMiniChatModule m = instance;
        if (m != null) m.pinned = null;
    }

    private void drawToast(GuiGraphicsExtractor g) {
        if (!enabled()) return;
        Supplier<String> pin = pinned;
        String shown = pin != null ? pin.get() : (toast != null && Clock.ms() - toastAt <= TOAST_MS ? toast : null);
        if (shown == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.screen() instanceof AerionMiniScreen) return;
        var font = mc.font;

        List<String> lines = AerionTalk.wrap(font, shown, TOAST_MAX_W);
        if (lines.size() > TOAST_MAX_LINES) lines = lines.subList(0, TOAST_MAX_LINES);
        int textW = 0;
        for (String s : lines) textW = Math.max(textW, font.width(s));
        int w = Math.max(textW, font.width("Aerion")) + 24;
        int h = 20 + lines.size() * 10 + 6;
        int x = 6, y = (mc.getWindow().getGuiScaledHeight() - h) / 2;
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x66000000);
        g.fill(x, y, x + w, y + h, 0xE0241024);
        g.fill(x, y, x + 4, y + h, 0xFFE65B93);
        g.text(font, "Aerion", x + 12, y + 6, 0xFFFF8FBF, true);
        int ty = y + 20;
        for (String s : lines) { g.text(font, s, x + 12, ty, 0xFFFFFFFF, false); ty += 10; }
    }
}
