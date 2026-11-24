package com.mineberry.essentials.mixin;

import com.mineberry.essentials.modules.SelfNametagModule;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets the renderer draw the local player's own nametag (vanilla skips the camera entity). */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z", at = @At("HEAD"), cancellable = true)
    private void mineberry$showOwnName(LivingEntity entity, double distanceToCameraSq, CallbackInfoReturnable<Boolean> cir) {
        if (SelfNametagModule.shouldShow(entity)) cir.setReturnValue(true);
    }
}
