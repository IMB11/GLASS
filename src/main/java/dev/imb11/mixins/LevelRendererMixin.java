package dev.imb11.mixins;

import dev.imb11.client.renderer.block.ProjectorBlockEntityRenderer;
import dev.imb11.client.renderer.projection.ProjectionRenderContext;
import dev.imb11.client.renderer.projection.ProjectionRenderManager;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Redirect(
            method = "renderSectionLayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;resortTransparency(Lnet/minecraft/client/renderer/RenderType;Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;)Z")
    )
    private boolean glass$sharedTerrainSort(SectionRenderDispatcher.RenderSection section, RenderType layer, SectionRenderDispatcher dispatcher) {
        return !ProjectionRenderManager.usesSharedTerrain((LevelRenderer) (Object) this)
                && section.resortTransparency(layer, dispatcher);
    }

    @Redirect(
            method = "renderSky",
            at = @At(
                    value = "FIELD",
                    target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;"
            )
    )
    private ClientLevel glass$projectionSkyLevel(Minecraft minecraft) {
        return ProjectionRenderContext.level((LevelRenderer) (Object) this, minecraft.level);
    }

    @Redirect(
            method = "renderSky",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;getMainCamera()Lnet/minecraft/client/Camera;"
            )
    )
    private Camera glass$projectionSkyCamera(GameRenderer gameRenderer) {
        return ProjectionRenderContext.camera((LevelRenderer) (Object) this, gameRenderer.getMainCamera());
    }

    @Redirect(
            method = "renderSky",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;getEyePosition(F)Lnet/minecraft/world/phys/Vec3;"
            )
    )
    private Vec3 glass$projectionSkyEyePosition(LocalPlayer player, float partialTick) {
        Camera camera = ProjectionRenderContext.camera((LevelRenderer) (Object) this, null);
        return camera == null ? player.getEyePosition(partialTick) : camera.getPosition();
    }

    @Redirect(
            method = "setupRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getX()D")
    )
    private double glass$projectionCameraX(LocalPlayer player) {
        return ProjectionRenderContext.cameraX((LevelRenderer) (Object) this, player);
    }

    @Redirect(
            method = "setupRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getY()D")
    )
    private double glass$projectionCameraY(LocalPlayer player) {
        return ProjectionRenderContext.cameraY((LevelRenderer) (Object) this, player);
    }

    @Redirect(
            method = "setupRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D")
    )
    private double glass$projectionCameraZ(LocalPlayer player) {
        return ProjectionRenderContext.cameraZ((LevelRenderer) (Object) this, player);
    }

    @Redirect(
            method = "allChanged",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getX()D")
    )
    private double glass$projectionInitialCameraX(Entity entity) {
        return ProjectionRenderContext.cameraX((LevelRenderer) (Object) this, entity);
    }

    @Redirect(
            method = "allChanged",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getZ()D")
    )
    private double glass$projectionInitialCameraZ(Entity entity) {
        return ProjectionRenderContext.cameraZ((LevelRenderer) (Object) this, entity);
    }

    @Redirect(
            method = "allChanged",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I")
    )
    private int glass$projectionInitialRenderDistance(Options options) {
        return ProjectionRenderContext.renderDistance(
                (LevelRenderer) (Object) this,
                options.getEffectiveRenderDistance()
        );
    }

    @Redirect(
            method = "setupRender",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;getEffectiveRenderDistance()I")
    )
    private int glass$projectionRenderDistance(Options options) {
        return ProjectionRenderContext.renderDistance(
                (LevelRenderer) (Object) this,
                options.getEffectiveRenderDistance()
        );
    }

    @Redirect(
            method = "compileSections",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;")
    )
    private Object glass$projectionChunkUpdatePriority(OptionInstance<?> option) {
        Minecraft minecraft = Minecraft.getInstance();
        if (ProjectionRenderContext.isActiveRenderer((LevelRenderer) (Object) this)
                && option == minecraft.options.prioritizeChunkUpdates()) {
            return PrioritizeChunkUpdates.NONE;
        }
        return option.get();
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
        ProjectorBlockEntityRenderer.onMainRendererRebuilt((LevelRenderer) (Object) this);
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
