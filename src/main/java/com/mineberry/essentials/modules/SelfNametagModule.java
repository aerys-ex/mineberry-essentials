package com.mineberry.essentials.modules;

import com.mineberry.essentials.MineBerryModule;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

/**
 * Self Nametag: shows your own nametag above your head in third person, with the same prefix/suffix other players
 * see (the server's team prefix is part of the display name). Vanilla never draws it because you are the camera.
 * The work is done by LivingEntityRendererMixin; this class only decides when it applies.
 */
public class SelfNametagModule extends MineBerryModule {

    public SelfNametagModule() {
        super("self_nametag", "Self Nametag", "See your own name and prefix in third person");
    }

    @Override
    public void init() {
        // nothing to register: the mixin asks shouldShow()
    }

    private static SelfNametagModule instance;

    /** Lazily finds the registered module so the mixin can ask whether it is switched on. */
    private static boolean on() {
        if (instance == null) {
            for (MineBerryModule m : com.mineberry.essentials.MineBerryEssentials.modules()) {
                if (m instanceof SelfNametagModule s) { instance = s; break; }
            }
        }
        return instance != null && instance.enabled();
    }

    public static boolean shouldShow(Entity entity) {
        Minecraft mc = Minecraft.getInstance();
        return entity == mc.player
                && !mc.gui.hud.isHidden()                // F1: hidden GUI hides names too (26.2: Options.hideGui -> Hud#isHidden)
                && !entity.isInvisible() && !entity.isSpectator()
                && mc.options.getCameraType() != CameraType.FIRST_PERSON
                && on();
    }
}
