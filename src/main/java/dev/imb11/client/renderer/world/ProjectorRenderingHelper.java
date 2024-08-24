package dev.imb11.client.renderer.world;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.apache.commons.lang3.Validate;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.ducks.IEMinecraftClient;
import qouteall.imm_ptl.core.render.GuiPortalRendering;
import qouteall.imm_ptl.core.render.MyGameRenderer;
import qouteall.imm_ptl.core.render.MyRenderHelper;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

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

    // For now, to test, render world framebuffer to projector block face instead of across all the faces.
    public static void renderWorldFramebuffer(BlockPos cameraPosition, Framebuffer framebuffer, MatrixStack matrices, Direction direction, ArrayList<Pair<BlockPos, Integer>> neighbouringGlassBlocks, int targetDistance, int maxDistance) {
        // Render framebuffer to projector block face, nothing else.

        var client = MinecraftClient.getInstance();
        Matrix4f cameraTransformation = new Matrix4f();
        cameraTransformation.identity();
        // Rotate in the direction of the projector block facing
        switch (direction) {
            case UP:
                cameraTransformation.rotateX((float) Math.toRadians(90));
                break;
            case DOWN:
                cameraTransformation.rotateX((float) Math.toRadians(-90));
                break;
            case NORTH:
                cameraTransformation.rotateY((float) Math.toRadians(180));
                break;
            case SOUTH:
                break;
            case WEST:
                cameraTransformation.rotateY((float) Math.toRadians(90));
                break;
            case EAST:
                cameraTransformation.rotateY((float) Math.toRadians(-90));
                break;
        }

        if (client.player == null) return;

        WorldRenderInfo worldRenderInfo = new WorldRenderInfo.Builder()
                .setWorld(client.world)
                .setCameraPos(cameraPosition.toCenterPos())
                .setCameraTransformation(cameraTransformation)
                .setOverwriteCameraTransformation(true)
                .setDescription(null)
                .setRenderDistance(client.options.getClampedViewDistance())
                .setDoRenderHand(false)
                .setEnableViewBobbing(false)
                .setDoRenderSky(false)
                .setHasFog(false)
                .build();

        try {
            GuiPortalRendering.submitNextFrameRendering(worldRenderInfo, framebuffer);
        } catch (IllegalArgumentException ignored) {

        }

        var positionMatrix = matrices.peek().getPositionMatrix();
        BufferBuilder backgroundBuffer = Tessellator.getInstance().getBuffer();
        backgroundBuffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);

        switch (direction) {
            case UP:
                backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 0).texture(0, 0).next();
                backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 1.01f).texture(1, 0).next();
                backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 1.01f).texture(1, 1).next();
                backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 0).texture(0, 1).next();
                break;
            case DOWN:
                backgroundBuffer.vertex(positionMatrix, 0, 0, 0).texture(0, 0).next();
                backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 0).texture(1, 0).next();
                backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 1.01f).texture(1, 1).next();
                backgroundBuffer.vertex(positionMatrix, 0, 0, 1.01f).texture(0, 1).next();
                break;
            case NORTH:
                backgroundBuffer.vertex(positionMatrix, 0, 0, 0).texture(0, 0).next();
                backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 0).texture(1, 0).next();
                backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 0).texture(1, 1).next();
                backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 0).texture(0, 1).next();
                break;
            case SOUTH:
                backgroundBuffer.vertex(positionMatrix, 0, 0, 1.01f).texture(0, 0).next();
                backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 1.01f).texture(1, 0).next();
                backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 1.01f).texture(1, 1).next();
                backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 1.01f).texture(0, 1).next();
                break;
            case WEST:
                backgroundBuffer.vertex(positionMatrix, 0, 0, 0).texture(0, 0).next();
                backgroundBuffer.vertex(positionMatrix, 0, 0, 1.01f).texture(1, 0).next();
                backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 1.01f).texture(1, 1).next();
                backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 0).texture(0, 1).next();
                break;
            case EAST:
                backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 0).texture(0, 0).next();
                backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 0).texture(1, 0).next();
                backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 1.01f).texture(1, 1).next();
                backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 1).texture(0, 1).next();
                break;
        }

        RenderSystem.enableBlend();
        RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderTexture(0, framebuffer.getColorAttachment());
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        Tessellator.getInstance().draw();
        RenderSystem.disableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
