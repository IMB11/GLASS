package dev.imb11.mixins;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LevelRenderer.class)
public interface LevelRendererInvoker {
    @Invoker("setupRender")
    void glass$setupRender(Camera camera, Frustum frustum, boolean hasCapturedFrustum, boolean spectator);

    @Invoker("compileSections")
    void glass$compileSections(Camera camera);

    @Invoker("renderEntity")
    void glass$renderEntity(Entity entity, double x, double y, double z, float partialTick, PoseStack poses, MultiBufferSource buffers);

    @Invoker("renderSectionLayer")
    void glass$renderSectionLayer(
            RenderType renderType,
            double cameraX,
            double cameraY,
            double cameraZ,
            Matrix4f modelViewMatrix,
            Matrix4f projectionMatrix
    );
}
