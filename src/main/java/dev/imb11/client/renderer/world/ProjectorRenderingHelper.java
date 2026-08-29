package dev.imb11.client.renderer.world;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Tuple;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;

public class ProjectorRenderingHelper {
    public static void renderEdgePanels(Vec3 rootPos, PoseStack matrices, Direction direction, ArrayList<Tuple<BlockPos, Integer>> neighbouringGlassBlocks, int targetDistance, int maxDistance) {
        // Draw a full screen white quad to prevent behind the block showing through
        float r = 1f, g = 1f, b = 1f;

        // Iterate over neighbouring blocks and render if within target distance
        for (Tuple<BlockPos, Integer> pair : neighbouringGlassBlocks) {
            BlockPos blockPos = pair.getA();
            int distance = pair.getB();

            if (distance >= targetDistance - 2 && distance <= targetDistance) {
                Vec3 relativePos = new Vec3(blockPos.getX() - rootPos.x(), blockPos.getY() - rootPos.y(), blockPos.getZ() - rootPos.z());

                float oldR = r;
                float oldG = g;
                float oldB = b;
                float lerp = (float) (targetDistance - distance) / 3f;
                r = r * (1 - lerp);
                g = g * (1 - lerp);
                b = b * (1 - lerp);

                // Push matrix to manipulate position
                matrices.pushPose();

                matrices.translate(relativePos.x(), relativePos.y(), relativePos.z());
                Vec3 zFightFix = new Vec3(0.01, 0.01, 0.01).multiply(Vec3.atLowerCornerOf(direction.getNormal()));
                matrices.translate(zFightFix.x(), zFightFix.y(), zFightFix.z());

                Matrix4f positionMatrix = matrices.last().pose();
                Tesselator tessellator = Tesselator.getInstance();
                BufferBuilder backgroundBuffer = tessellator.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

                switch (direction) {
                    case UP:
                        backgroundBuffer.addVertex(positionMatrix, 0, 1.01f, 0).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 0, 1.01f, 1.01f).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 1.01f, 1.01f, 1.01f).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 1.01f, 1.01f, 0).setColor(r, g, b, 1f);
                        break;
                    case DOWN:
                        backgroundBuffer.addVertex(positionMatrix, 0, 0, 0).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 1.01f, 0, 0).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 1.01f, 0, 1.01f).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 0, 0, 1.01f).setColor(r, g, b, 1f);
                        break;
                    case NORTH:
                        backgroundBuffer.addVertex(positionMatrix, 0, 0, 0).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 0, 1.01f, 0).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 1.01f, 1.01f, 0).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 1.01f, 0, 0).setColor(r, g, b, 1f);
                        break;
                    case SOUTH:
                        backgroundBuffer.addVertex(positionMatrix, 0, 0, 1.01f).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 1.01f, 0, 1.01f).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 1.01f, 1.01f, 1.01f).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 0, 1.01f, 1.01f).setColor(r, g, b, 1f);
                        break;
                    case WEST:
                        backgroundBuffer.addVertex(positionMatrix, 0, 0, 0).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 0, 0, 1.01f).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 0, 1.01f, 1.01f).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 0, 1.01f, 0).setColor(r, g, b, 1f);
                        break;
                    case EAST:
                        backgroundBuffer.addVertex(positionMatrix, 1.01f, 0, 0).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 1.01f, 1.01f, 0).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 1.01f, 1.01f, 1.01f).setColor(r, g, b, 1f);
                        backgroundBuffer.addVertex(positionMatrix, 1.01f, 0, 1).setColor(r, g, b, 1f);
                        break;
                }

                r = oldR;
                g = oldG;
                b = oldB;

                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                RenderSystem.setShaderColor(1f, 1f, 1f, lerp);
                RenderSystem.enableDepthTest();
                RenderSystem.disableCull();
                BufferUploader.drawWithShader(backgroundBuffer.buildOrThrow());
                RenderSystem.disableDepthTest();
                RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
                RenderSystem.enableCull();

                // Pop matrix to restore original state
                matrices.popPose();
            }
        }
    }
}
