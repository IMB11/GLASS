package dev.imb11.mixins;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class SectionRenderDispatcherRenderSectionMixin {
    @Unique
    private SectionRenderDispatcher glass$dispatcher;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void glass$captureDispatcher(SectionRenderDispatcher dispatcher, int index, int x, int y, int z, CallbackInfo callbackInfo) {
        glass$dispatcher = dispatcher;
    }

    @Inject(method = "getDistToPlayerSqr", at = @At("HEAD"), cancellable = true)
    private void glass$distanceToRendererCamera(CallbackInfoReturnable<Double> callbackInfo) {
        AABB bounds = ((SectionRenderDispatcher.RenderSection) (Object) this).getBoundingBox();
        Vec3 camera = glass$dispatcher.getCameraPosition();
        double x = bounds.minX + 8.0D - camera.x;
        double y = bounds.minY + 8.0D - camera.y;
        double z = bounds.minZ + 8.0D - camera.z;
        callbackInfo.setReturnValue(x * x + y * y + z * z);
    }
}
