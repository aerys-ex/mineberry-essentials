package com.mineberry.essentials.modules;

import com.mineberry.essentials.MineBerryEssentials;
import com.mineberry.essentials.MineBerryModule;
import com.mineberry.essentials.Terms;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Freelook: hold Alt and move the mouse to look around your character without turning it (like Lunar Client).
 * While held the camera goes to third person; on release it goes back to the view you had and the camera snaps back.
 *
 * Purely visual: your real rotation (and so everything sent to the server) never changes.
 * The work is done by two mixins: EntityTurnMixin (mouse movement goes to the freelook angles instead of the player)
 * and CameraMixin (the camera uses the freelook angles).
 */
public class FreelookModule extends MineBerryModule {

    private static boolean active;
    private static float yaw;
    private static float pitch;
    private static CameraType previousView;

    private KeyMapping key;

    public FreelookModule() {
        super("freelook", "Freelook", "Hold Alt to look around without turning your character");
    }

    @Override
    public void init() {
        key = Terms.registerKey(new KeyMapping(
                "key.mineberry_essentials.freelook",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_LEFT_ALT,
                MineBerryEssentials.CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(Minecraft mc) {
        boolean wanted = enabled() && mc.player != null && mc.level != null
                && mc.gui.screen() == null && key.isDown();
        if (wanted && !active) start(mc);
        else if (!wanted && active) stop(mc);
    }

    private static void start(Minecraft mc) {
        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
        previousView = mc.options.getCameraType();
        // the camera hook assumes the normal back view: the front view flips the camera round, which the hook would undo
        if (previousView != CameraType.THIRD_PERSON_BACK) mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        active = true;
    }

    private static void stop(Minecraft mc) {
        active = false;
        if (previousView != null && mc.options.getCameraType() != previousView) {
            mc.options.setCameraType(previousView);
        }
        previousView = null;
    }

    // ---- used by the mixins ----

    public static boolean active() { return active; }
    public static float yaw() { return yaw; }
    public static float pitch() { return pitch; }

    /** Same maths as Entity#turn, but on the freelook angles. */
    public static void turn(double dx, double dy) {
        yaw += (float) dx * 0.15F;
        pitch = Math.max(-90.0F, Math.min(90.0F, pitch + (float) dy * 0.15F));
    }
}
