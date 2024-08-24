package dev.imb11.client.renderer.world;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.joml.Matrix4f;

import java.util.ArrayList;

public class ProjectorRenderingHelper {
    public static boolean isRendering = false;

    public static void renderBackground(MatrixStack matrices, Direction direction, ArrayList<Pair<BlockPos, Integer>> neighbouringGlassBlocks, int targetDistance) {
        Matrix4f positionMatrix = matrices.peek().getPositionMatrix();
        Tessellator tessellator = Tessellator.getInstance();

        // Draw a full screen white quad to prevent behind the block showing through
        float backgroundR = BackgroundRenderer.red;
        float backgroundG = BackgroundRenderer.green;
        float backgroundB = BackgroundRenderer.blue;

        BufferBuilder backgroundBuffer = tessellator.getBuffer();
        backgroundBuffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        switch (direction) {
            case UP:
                backgroundBuffer.vertex(positionMatrix, 0, 1, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 0, 1, 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 1, 1, 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 1, 1, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
                break;
            case DOWN:
                backgroundBuffer.vertex(positionMatrix, 0, 0, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 1, 0, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 1, 0, 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 0, 0, 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                break;
            case NORTH:
                backgroundBuffer.vertex(positionMatrix, 0, 0, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 0, 1, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 1, 1, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 1, 0, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
                break;
            case SOUTH:
                backgroundBuffer.vertex(positionMatrix, 0, 0, 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 1, 0, 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 1, 1, 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 0, 1, 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                break;
            case WEST:
                backgroundBuffer.vertex(positionMatrix, 0, 0, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 0, 0, 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 0, 1, 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 0, 1, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
                break;
            case EAST:
                backgroundBuffer.vertex(positionMatrix, 1, 0, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 1, 1, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 1, 1, 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                backgroundBuffer.vertex(positionMatrix, 1, 0, 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                break;
        }

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableCull();
        tessellator.draw();
        RenderSystem.enableCull();
    }

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
