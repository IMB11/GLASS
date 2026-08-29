package dev.imb11.mixins;

import dev.imb11.client.renderer.projection.ProjectionRenderManager;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LightTexture;updateLightTexture(F)V",
                    shift = At.Shift.AFTER
            )
    )
    private void glass$renderProjectionBeforeMain(DeltaTracker deltaTracker, CallbackInfo callbackInfo) {
        ProjectionRenderManager.renderBeforeMain((GameRenderer) (Object) this, deltaTracker);
    }

    @Inject(method = "resetData", at = @At("HEAD"))
    private void glass$resetProjectionRenderer(CallbackInfo callbackInfo) {
        ProjectionRenderManager.reset();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void glass$closeProjectionRenderer(CallbackInfo callbackInfo) {
        ProjectionRenderManager.reset();
    }
}
