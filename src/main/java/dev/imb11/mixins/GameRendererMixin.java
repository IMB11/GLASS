package dev.imb11.mixins;

import dev.imb11.client.renderer.block.ProjectorBlockEntityRenderer;
import dev.imb11.client.renderer.projection.ProjectionRenderContext;
import dev.imb11.client.renderer.projection.ProjectionRenderManager;
import dev.imb11.client.renderer.projection.ProjectionSurfaceRenderer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Inject(method = "lightTexture", at = @At("HEAD"), cancellable = true)
    private void glass$projectionLightTexture(CallbackInfoReturnable<LightTexture> callbackInfo) {
        LightTexture lightTexture = ProjectionRenderContext.lightTexture();
        if (lightTexture != null) {
            callbackInfo.setReturnValue(lightTexture);
        }
    }

    @Inject(
            method = "renderLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;resetProjectionMatrix(Lorg/joml/Matrix4f;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void glass$renderProjectionBeforeMain(DeltaTracker deltaTracker, CallbackInfo callbackInfo) {
        ProjectionRenderManager.renderBeforeMain((GameRenderer) (Object) this, deltaTracker);
    }

    @Inject(method = "resetData", at = @At("HEAD"))
    private void glass$resetProjectionRenderer(CallbackInfo callbackInfo) {
        ProjectionRenderManager.reset();
        ProjectionSurfaceRenderer.reset();
        ProjectorBlockEntityRenderer.reset();
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void glass$closeProjectionRenderer(CallbackInfo callbackInfo) {
        ProjectionRenderManager.reset();
        ProjectionSurfaceRenderer.reset();
        ProjectorBlockEntityRenderer.reset();
        ProjectorBlockEntityRenderer.clearLoaded();
    }
}
