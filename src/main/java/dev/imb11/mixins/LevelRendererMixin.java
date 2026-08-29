package dev.imb11.mixins;

import dev.imb11.client.renderer.projection.ProjectionRenderContext;
import dev.imb11.client.renderer.projection.ProjectionRenderManager;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Redirect(
            method = "setupRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getX()D")
    )
    private double glass$projectionCameraX(LocalPlayer player) {
        return ProjectionRenderContext.cameraX((LevelRenderer) (Object) this, player.getX());
    }

    @Redirect(
            method = "setupRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getY()D")
    )
    private double glass$projectionCameraY(LocalPlayer player) {
        return ProjectionRenderContext.cameraY((LevelRenderer) (Object) this, player.getY());
    }

    @Redirect(
            method = "setupRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D")
    )
    private double glass$projectionCameraZ(LocalPlayer player) {
        return ProjectionRenderContext.cameraZ((LevelRenderer) (Object) this, player.getZ());
    }

    @Inject(method = "graphicsChanged", at = @At("HEAD"), cancellable = true)
    private void glass$skipProjectionPostChains(CallbackInfo callbackInfo) {
        if (ProjectionRenderManager.isProjectionRenderer((LevelRenderer) (Object) this)) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void glass$mirrorMainRendererTick(CallbackInfo callbackInfo) {
        ProjectionRenderManager.onMainRendererTick((LevelRenderer) (Object) this);
    }

    @Inject(method = "allChanged", at = @At("TAIL"))
    private void glass$mirrorMainRendererRebuild(CallbackInfo callbackInfo) {
        ProjectionRenderManager.onMainRendererRebuilt((LevelRenderer) (Object) this);
    }

    @Inject(method = "setSectionDirty(IIIZ)V", at = @At("TAIL"))
    private void glass$mirrorSectionDirty(
            int sectionX,
            int sectionY,
            int sectionZ,
            boolean playerChanged,
            CallbackInfo callbackInfo
    ) {
        ProjectionRenderManager.onMainSectionDirty(
                (LevelRenderer) (Object) this,
                sectionX,
                sectionY,
                sectionZ
        );
    }

    @Inject(method = "onChunkLoaded", at = @At("TAIL"))
    private void glass$mirrorChunkLoaded(ChunkPos chunkPos, CallbackInfo callbackInfo) {
        ProjectionRenderManager.onMainChunkLoaded((LevelRenderer) (Object) this, chunkPos);
    }
}
