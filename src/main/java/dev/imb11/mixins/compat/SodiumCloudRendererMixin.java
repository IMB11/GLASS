package dev.imb11.mixins.compat;

import dev.imb11.client.renderer.projection.ProjectionRenderContext;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.immediate.CloudRenderer", remap = false)
abstract class SodiumCloudRendererMixin {
    @ModifyVariable(method = "render", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Camera glass$projectionCloudCamera(Camera camera) {
        Camera projectionCamera = ProjectionRenderContext.camera();
        return projectionCamera == null ? camera : projectionCamera;
    }
}
