package dev.imb11.mixins;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.imb11.client.renderer.projection.ProjectionRenderContext;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void glass$projectionMainTarget(CallbackInfoReturnable<RenderTarget> callbackInfo) {
        RenderTarget projectionTarget = ProjectionRenderContext.target();
        if (projectionTarget != null) {
            callbackInfo.setReturnValue(projectionTarget);
        }
    }

    @Inject(method = "useShaderTransparency", at = @At("HEAD"), cancellable = true)
    private static void glass$useForwardTransparency(CallbackInfoReturnable<Boolean> callbackInfo) {
        if (ProjectionRenderContext.isActive()) {
            callbackInfo.setReturnValue(false);
        }
    }
}
