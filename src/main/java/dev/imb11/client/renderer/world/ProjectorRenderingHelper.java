package dev.imb11.client.renderer.world;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Pair;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;

public class ProjectorRenderingHelper {
    public static void renderEdgePanels(Vec3d rootPos, MatrixStack matrices, Direction direction, ArrayList<Pair<BlockPos, Integer>> neighbouringGlassBlocks, int targetDistance, int maxDistance) {
        // Draw a full screen white quad to prevent behind the block showing through
        float r = 1f, g = 1f, b = 1f;

        // Iterate over neighbouring blocks and render if within target distance
        for (Pair<BlockPos, Integer> pair : neighbouringGlassBlocks) {
            BlockPos blockPos = pair.getLeft();
            int distance = pair.getRight();

            if (distance >= targetDistance - 2 && distance <= targetDistance) {
                Vec3d relativePos = new Vec3d(blockPos.getX() - rootPos.getX(), blockPos.getY() - rootPos.getY(), blockPos.getZ() - rootPos.getZ());

                float oldR = r;
                float oldG = g;
                float oldB = b;
                float lerp = (float) (targetDistance - distance) / 3f;
                r = r * (1 - lerp);
                g = g * (1 - lerp);
                b = b * (1 - lerp);

                // Push matrix to manipulate position
                matrices.push();

                matrices.translate(relativePos.getX(), relativePos.getY(), relativePos.getZ());
                Vec3d zFightFix = new Vec3d(0.001, 0.001, 0.001).multiply(Vec3d.of(direction.getVector()));
                matrices.translate(zFightFix.getX(), zFightFix.getY(), zFightFix.getZ());

                Matrix4f positionMatrix = matrices.peek().getPositionMatrix();
                Tessellator tessellator = Tessellator.getInstance();
                BufferBuilder backgroundBuffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

                switch (direction) {
                    case UP:
                        backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 0).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 1.01f).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 1.01f).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 0).color(r, g, b, 1f);
                        break;
                    case DOWN:
                        backgroundBuffer.vertex(positionMatrix, 0, 0, 0).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 0).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 1.01f).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 0, 0, 1.01f).color(r, g, b, 1f);
                        break;
                    case NORTH:
                        backgroundBuffer.vertex(positionMatrix, 0, 0, 0).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 0).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 0).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 0).color(r, g, b, 1f);
                        break;
                    case SOUTH:
                        backgroundBuffer.vertex(positionMatrix, 0, 0, 1.01f).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 1.01f).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 1.01f).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 1.01f).color(r, g, b, 1f);
                        break;
                    case WEST:
                        backgroundBuffer.vertex(positionMatrix, 0, 0, 0).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 0, 0, 1.01f).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 1.01f).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 0, 1.01f, 0).color(r, g, b, 1f);
                        break;
                    case EAST:
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 0).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 0).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 1.01f, 1.01f).color(r, g, b, 1f);
                        backgroundBuffer.vertex(positionMatrix, 1.01f, 0, 1).color(r, g, b, 1f);
                        break;
                }

                r = oldR;
                g = oldG;
                b = oldB;

                RenderSystem.setShader(GameRenderer::getPositionColorProgram);
                RenderSystem.setShaderColor(1f, 1f, 1f, lerp);
                RenderSystem.enableDepthTest();
                RenderSystem.disableCull();
                BufferRenderer.drawWithGlobalProgram(backgroundBuffer.end());
                RenderSystem.disableDepthTest();
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                RenderSystem.enableCull();

                // Pop matrix to restore original state
                matrices.pop();
            }
        }
    }
}
