package dev.imb11.mixins.compat;

import dev.imb11.client.renderer.projection.ProjectionRenderContext;
import dev.imb11.client.renderer.projection.ProjectionRenderManager;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
abstract class SodiumWorldRendererMixin {
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private void glass$projectionEntityVisibility(Entity entity, CallbackInfoReturnable<Boolean> callbackInfo) {
        if (ProjectionRenderContext.isActive()) {
            callbackInfo.setReturnValue(true);
        }
    }

    @Inject(method = "scheduleRebuildForChunk", at = @At("TAIL"))
    private void glass$mirrorSectionRebuild(int x, int y, int z, boolean important, CallbackInfo callbackInfo) {
        if ((Object) this == SodiumWorldRenderer.instanceNullable()) {
            ProjectionRenderManager.onMainSectionDirty(Minecraft.getInstance().levelRenderer, x, y, z);
        }
    }
}
