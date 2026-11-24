package com.mineberry.essentials.mixin;

import com.mineberry.essentials.modules.FreelookModule;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While freelook is held, mouse movement turns the camera instead of the player. */
@Mixin(Entity.class)
public abstract class EntityTurnMixin {

    @Inject(method = "turn(DD)V", at = @At("HEAD"), cancellable = true)
    private void mineberry$freelookTurn(double yRot, double xRot, CallbackInfo ci) {
        if (FreelookModule.active() && (Object) this == Minecraft.getInstance().player) {
            FreelookModule.turn(yRot, xRot);
            ci.cancel();
        }
    }
}
