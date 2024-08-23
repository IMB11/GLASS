package dev.imb11.client.renderer.world;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.Camera;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import org.joml.Matrix4f;

public class ProjectorRenderingHelper {
    public static boolean isRendering = false;
    public static void captureWorld(BlockPos cameraPosition, Framebuffer framebuffer, MinecraftClient client) {
        IdentifiableCamera newCamera = new IdentifiableCamera();
        newCamera.reset();
        Camera oldCamera = client.gameRenderer.getCamera();
        client.gameRenderer.renderingPanorama = true;
        client.worldRenderer.reloadTransparencyPostProcessor();
        client.getFramebuffer().endWrite();

        Matrix4f old = new Matrix4f(RenderSystem.getProjectionMatrix());
        framebuffer.beginWrite(true);
        client.gameRenderer.setBlockOutlineEnabled(false);

        isRendering = true;
        newCamera.setPos(cameraPosition.getX(), cameraPosition.getY(), cameraPosition.getZ());
        ((TemporaryCameraHelper) client.gameRenderer).gLASS$setOldCamera(oldCamera);
        client.gameRenderer.camera = newCamera;
        client.gameRenderer.renderWorld(0F, 0L, new MatrixStack());
        client.gameRenderer.camera = oldCamera;
        ((TemporaryCameraHelper) client.gameRenderer).gLASS$setOldCamera(null);
        framebuffer.endWrite();
        isRendering = false;

        client.gameRenderer.setBlockOutlineEnabled(true);


        RenderSystem.setProjectionMatrix(old, RenderSystem.getVertexSorting());
        client.gameRenderer.renderingPanorama = false;
        client.worldRenderer.reloadTransparencyPostProcessor();
        client.getFramebuffer().beginWrite(true);
    }
}
