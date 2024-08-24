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

    public static void renderBackground(float r, float g, float b, MatrixStack matrices, Direction direction, ArrayList<Pair<BlockPos, Integer>> neighbouringGlassBlocks, int targetDistance, int maxDistance) {
        // Draw a full screen white quad to prevent behind the block showing through

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

                // Push matrix to manipulate position
                matrices.push();
                matrices.translate(relativePos.getX(), relativePos.getY(), relativePos.getZ());

                Matrix4f positionMatrix = matrices.peek().getPositionMatrix();
                Tessellator tessellator = Tessellator.getInstance();
                BufferBuilder backgroundBuffer = tessellator.getBuffer();
                backgroundBuffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

                // Check if block is within 3 of the target distance.
                float oldR = r;
                float oldG = g;
                float oldB = b;
                if (distance >= targetDistance - 3) {
                    // Lerp between white and provided color depending on distance to target
                    float lerp = (float) (targetDistance - distance) / 3f;
                    r = r * lerp + (1 - lerp);
                    g = g * lerp + (1 - lerp);
                    b = b * lerp + (1 - lerp);
                }


                switch (direction) {
                    case UP:
                        backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 0).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 1.01f).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 1.01f).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 0).color(r, g, b, 1f).next();
                        break;
                    case DOWN:
                        backgroundBuffer.vertex(positionMatrix, 0, 0, 0).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 0).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 1.01f).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 0, 0, 1.01f).color(r, g, b, 1f).next();
                        break;
                    case NORTH:
                        backgroundBuffer.vertex(positionMatrix, 0, 0, 0).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 0).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 0).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 0).color(r, g, b, 1f).next();
                        break;
                    case SOUTH:
                        backgroundBuffer.vertex(positionMatrix, 0, 0, 1.01f).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 1.01f).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 1.01f).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 1.01f).color(r, g, b, 1f).next();
                        break;
                    case WEST:
                        backgroundBuffer.vertex(positionMatrix, 0, 0, 0).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 0, 0, 1.01f).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 1.01f).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 0).color(r, g, b, 1f).next();
                        break;
                    case EAST:
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 0).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 0).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 1.01f).color(r, g, b, 1f).next();
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 1).color(r, g, b, 1f).next();
                        break;
                }

                r = oldR;
                g = oldG;
                b = oldB;

                RenderSystem.setShader(GameRenderer::getPositionColorProgram);
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                RenderSystem.enableDepthTest();
                RenderSystem.disableCull();
                tessellator.draw();
                RenderSystem.disableDepthTest();
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                RenderSystem.enableCull();

                // Pop matrix to restore original state
                matrices.pop();
            }
        }
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
