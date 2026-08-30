package dev.imb11.mixins;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionRenderDispatcher.RenderSection.class)
abstract class SectionRenderDispatcherRenderSectionMixin {
    @Shadow
    @Final
    SectionRenderDispatcher field_20833;

    @Inject(method = "getDistToPlayerSqr", at = @At("HEAD"), cancellable = true)
    private void glass$distanceToRendererCamera(CallbackInfoReturnable<Double> callbackInfo) {
        AABB bounds = ((SectionRenderDispatcher.RenderSection) (Object) this).getBoundingBox();
        Vec3 camera = field_20833.getCameraPosition();
        double x = bounds.minX + 8.0D - camera.x;
        double y = bounds.minY + 8.0D - camera.y;
        double z = bounds.minZ + 8.0D - camera.z;
        callbackInfo.setReturnValue(x * x + y * y + z * z);
    }
}
