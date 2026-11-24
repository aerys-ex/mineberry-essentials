package com.mineberry.essentials.mixin;

import com.mineberry.essentials.modules.FreelookModule;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** While freelook is held, the camera uses the freelook angles instead of the player's. */
@Mixin(Camera.class)
public abstract class CameraMixin {

    @ModifyVariable(method = "setRotation", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float mineberry$freelookYaw(float yRot) {
        return FreelookModule.active() ? FreelookModule.yaw() : yRot;
    }

    @ModifyVariable(method = "setRotation", at = @At("HEAD"), argsOnly = true, ordinal = 1)
    private float mineberry$freelookPitch(float xRot) {
        return FreelookModule.active() ? FreelookModule.pitch() : xRot;
    }
}
