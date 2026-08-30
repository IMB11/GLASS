package dev.imb11.mixins;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.imb11.client.renderer.projection.ProjectionRenderContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VertexBuffer.class)
abstract class VertexBufferMixin {
    @Shadow
    private int vertexBufferId;

    @Shadow
    private int indexBufferId;

    @Shadow
    private int arrayObjectId;

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenBuffers()I")
    )
    private int glass$deferProjectionBuffer() {
        return ProjectionRenderContext.isActive() ? 0 : GlStateManager._glGenBuffers();
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/GlStateManager;_glGenVertexArrays()I")
    )
    private int glass$deferProjectionArray() {
        return ProjectionRenderContext.isActive() ? 0 : GlStateManager._glGenVertexArrays();
    }

    @Inject(method = "bind", at = @At("HEAD"))
    private void glass$allocateProjectionBuffer(CallbackInfo callbackInfo) {
        if (arrayObjectId != 0) {
            return;
        }

        RenderSystem.assertOnRenderThread();
        vertexBufferId = GlStateManager._glGenBuffers();
        indexBufferId = GlStateManager._glGenBuffers();
        arrayObjectId = GlStateManager._glGenVertexArrays();
    }
}
