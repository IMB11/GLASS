package dev.imb11.mixins;

import dev.imb11.client.renderer.world.IdentifiableCamera;
import dev.imb11.client.renderer.world.ProjectorRenderingHelper;
import dev.imb11.client.renderer.world.TemporaryCameraHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin implements TemporaryCameraHelper {
    @Shadow public Camera camera;
    @Shadow @Final private MinecraftClient client;

    @Shadow public abstract Matrix4f getBasicProjectionMatrix(double fov);

    @Shadow protected abstract double getFov(Camera camera, float tickDelta, boolean changingFov);

    @Unique
    private Camera oldCamera;
    @Unique
    private boolean shouldFixFrustrum;

    @Override
    public void gLASS$setOldCamera(Camera camera) {
        if(camera == null) {
            this.shouldFixFrustrum = true;
            return;
        }
        this.oldCamera = camera;
    }

    @Inject(method = "renderWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;setupFrustum(Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/util/math/Vec3d;Lorg/joml/Matrix4f;)V"), cancellable = true)
    public void renderFrustrumFix(float tickDelta, long limitTime, MatrixStack matrices, CallbackInfo ci) {
        if(this.shouldFixFrustrum) {
            ci.cancel();
            this.shouldFixFrustrum = false;
            this.client.worldRenderer.setupFrustum(matrices, oldCamera.getPos(), this.getBasicProjectionMatrix(Math.max(this.getFov(oldCamera, tickDelta, true), (double) this.client.options.getFov().getValue())));
            this.oldCamera = null;
        }
    }

    @Inject(method = "getCamera", at = @At("HEAD"), cancellable = true)
    private void onGetCamera(CallbackInfoReturnable<Camera> cir) {
        if (this.oldCamera != null && ProjectorRenderingHelper.isRendering && this.camera instanceof IdentifiableCamera) {
            cir.setReturnValue(this.oldCamera);
        }
    }
}
