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

        // Get the root BlockPos
        BlockPos rootPos = null;
        for (Pair<BlockPos, Integer> pair : neighbouringGlassBlocks) {
            if (pair.getRight() == 0) {
                rootPos = pair.getLeft();
                break;
            }
        }

        if (rootPos == null) {
            return; // No root position found, exit the function
        }

        // Iterate over neighbouring blocks and render if within target distance
        for (Pair<BlockPos, Integer> pair : neighbouringGlassBlocks) {
            BlockPos blockPos = pair.getLeft();
            int distance = pair.getRight();

            if (distance <= targetDistance) {
                BlockPos relativePos = blockPos.subtract(rootPos);

                switch (direction) {
                    case UP:
                        backgroundBuffer.vertex(positionMatrix, relativePos.getX(), 1, relativePos.getZ()).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, relativePos.getX(), 1, relativePos.getZ() + 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, relativePos.getX() + 1, 1, relativePos.getZ() + 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, relativePos.getX() + 1, 1, relativePos.getZ()).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        break;
                    case DOWN:
                        backgroundBuffer.vertex(positionMatrix, relativePos.getX(), 0, relativePos.getZ()).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, relativePos.getX() + 1, 0, relativePos.getZ()).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, relativePos.getX() + 1, 0, relativePos.getZ() + 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, relativePos.getX(), 0, relativePos.getZ() + 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        break;
                    case NORTH:
                        backgroundBuffer.vertex(positionMatrix, relativePos.getX(), relativePos.getY(), 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, relativePos.getX(), relativePos.getY() + 1, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, relativePos.getX() + 1, relativePos.getY() + 1, 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, relativePos.getX() + 1, relativePos.getY(), 0).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        break;
                    case SOUTH:
                        backgroundBuffer.vertex(positionMatrix, relativePos.getX(), relativePos.getY(), 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, relativePos.getX() + 1, relativePos.getY(), 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, relativePos.getX() + 1, relativePos.getY() + 1, 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, relativePos.getX(), relativePos.getY() + 1, 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        break;
                    case WEST:
                        backgroundBuffer.vertex(positionMatrix, 0, relativePos.getY(), relativePos.getZ()).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 0, relativePos.getY(), relativePos.getZ() + 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 0, relativePos.getY() + 1, relativePos.getZ() + 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 0, relativePos.getY() + 1, relativePos.getZ()).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        break;
                    case EAST:
                        backgroundBuffer.vertex(positionMatrix, 1, relativePos.getY(), relativePos.getZ()).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 1, relativePos.getY() + 1, relativePos.getZ()).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 1, relativePos.getY() + 1, relativePos.getZ() + 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 1, relativePos.getY(), relativePos.getZ() + 1).color(backgroundR, backgroundG, backgroundB, 1f).next();
                        break;
                }
            }
        }


        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableCull();
        BufferRenderer.draw(backgroundBuffer.end());
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
