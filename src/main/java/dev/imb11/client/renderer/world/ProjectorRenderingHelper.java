package dev.imb11.client.renderer.world;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.Util;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Tuple;

import java.util.List;
import java.util.function.Function;

public final class ProjectorRenderingHelper {
    private static final float SURFACE_OFFSET = 0.002F;
    private static final int REVEAL_EDGE_WIDTH = 2;
    private static final RenderStateShard.ShaderStateShard PROJECTION_SURFACE_SHADER =
            new RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader);
    private static final Function<ResourceLocation, RenderType> PROJECTION_SURFACE_TYPES = Util.memoize(texture ->
            RenderType.create(
                    "glass_projection_surface",
                    DefaultVertexFormat.POSITION_TEX_COLOR,
                    VertexFormat.Mode.QUADS,
                    1536,
                    false,
                    false,
                    RenderType.CompositeState.builder()
                            .setShaderState(PROJECTION_SURFACE_SHADER)
                            .setTextureState(new RenderStateShard.TextureStateShard(texture, true, false))
                            .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                            .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                            .setCullState(RenderStateShard.NO_CULL)
                            .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                            .setOverlayState(RenderStateShard.NO_OVERLAY)
                            .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                            .createCompositeState(false)
            )
    );

    private ProjectorRenderingHelper() {
    }

    public static void renderProjectionSurface(
            PoseStack matrices,
            MultiBufferSource buffers,
            Direction direction,
            List<Tuple<BlockPos, Integer>> connectedBlocks,
            int targetDistance,
            ResourceLocation projectionTexture
    ) {
        BlockPos root = findRoot(connectedBlocks);
        PlaneBounds bounds = findBounds(direction, connectedBlocks);
        if (root == null || bounds == null || targetDistance < 0) {
            return;
        }

        VertexConsumer vertices = buffers.getBuffer(PROJECTION_SURFACE_TYPES.apply(projectionTexture));
        for (Tuple<BlockPos, Integer> pair : connectedBlocks) {
            int distance = pair.getB();
            if (distance > targetDistance) {
                continue;
            }

            BlockPos blockPos = pair.getA();
            BlockPos relativePos = blockPos.subtract(root);
            float u0 = bounds.u(direction, blockPos, false);
            float u1 = bounds.u(direction, blockPos, true);
            float v0 = bounds.v(direction, blockPos, false);
            float v1 = bounds.v(direction, blockPos, true);
            int distanceBehindEdge = targetDistance - distance;
            int revealShade = distanceBehindEdge >= 0 && distanceBehindEdge <= REVEAL_EDGE_WIDTH
                    ? Math.round(166.0F * distanceBehindEdge / REVEAL_EDGE_WIDTH)
                    : 0;
            int tint = 255 - revealShade;

            matrices.pushPose();
            matrices.translate(relativePos.getX(), relativePos.getY(), relativePos.getZ());
            emitTexturedFace(
                    matrices.last(), vertices, direction, u0, u1, v0, v1,
                    SURFACE_OFFSET, tint, tint, tint, 255
            );
            matrices.popPose();
        }
    }

    private static BlockPos findRoot(List<Tuple<BlockPos, Integer>> connectedBlocks) {
        for (Tuple<BlockPos, Integer> pair : connectedBlocks) {
            if (pair.getB() == 0) {
                return pair.getA();
            }
        }
        return null;
    }

    private static PlaneBounds findBounds(Direction direction, List<Tuple<BlockPos, Integer>> connectedBlocks) {
        if (connectedBlocks.isEmpty()) {
            return null;
        }

        int minU = Integer.MAX_VALUE;
        int minV = Integer.MAX_VALUE;
        int maxU = Integer.MIN_VALUE;
        int maxV = Integer.MIN_VALUE;
        for (Tuple<BlockPos, Integer> pair : connectedBlocks) {
            BlockPos blockPos = pair.getA();
            int u = planeU(direction, blockPos);
            int v = planeV(direction, blockPos);
            minU = Math.min(minU, u);
            minV = Math.min(minV, v);
            maxU = Math.max(maxU, u + 1);
            maxV = Math.max(maxV, v + 1);
        }
        return new PlaneBounds(minU, minV, maxU, maxV);
    }

    private static int planeU(Direction direction, BlockPos blockPos) {
        return switch (direction.getAxis()) {
            case X -> blockPos.getZ();
            case Y, Z -> blockPos.getX();
        };
    }

    private static int planeV(Direction direction, BlockPos blockPos) {
        return switch (direction.getAxis()) {
            case Y -> blockPos.getZ();
            case X, Z -> blockPos.getY();
        };
    }

    private static void emitTexturedFace(
            PoseStack.Pose pose,
            VertexConsumer vertices,
            Direction direction,
            float u0,
            float u1,
            float v0,
            float v1,
            float surfaceOffset,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        float faceX = direction == Direction.EAST ? 1.0F + surfaceOffset
                : direction == Direction.WEST ? -surfaceOffset : 0.0F;
        float faceY = direction == Direction.UP ? 1.0F + surfaceOffset
                : direction == Direction.DOWN ? -surfaceOffset : 0.0F;
        float faceZ = direction == Direction.SOUTH ? 1.0F + surfaceOffset
                : direction == Direction.NORTH ? -surfaceOffset : 0.0F;

        switch (direction.getAxis()) {
            case X -> {
                if (direction == Direction.WEST) {
                    projectionVertex(pose, vertices, faceX, 0.0F, 1.0F, u1, v0, red, green, blue, alpha);
                    projectionVertex(pose, vertices, faceX, 1.0F, 1.0F, u1, v1, red, green, blue, alpha);
                    projectionVertex(pose, vertices, faceX, 1.0F, 0.0F, u0, v1, red, green, blue, alpha);
                    projectionVertex(pose, vertices, faceX, 0.0F, 0.0F, u0, v0, red, green, blue, alpha);
                } else {
                    projectionVertex(pose, vertices, faceX, 0.0F, 0.0F, u0, v0, red, green, blue, alpha);
                    projectionVertex(pose, vertices, faceX, 1.0F, 0.0F, u0, v1, red, green, blue, alpha);
                    projectionVertex(pose, vertices, faceX, 1.0F, 1.0F, u1, v1, red, green, blue, alpha);
                    projectionVertex(pose, vertices, faceX, 0.0F, 1.0F, u1, v0, red, green, blue, alpha);
                }
            }
            case Y -> {
                if (direction == Direction.DOWN) {
                    projectionVertex(pose, vertices, 1.0F, faceY, 0.0F, u1, v0, red, green, blue, alpha);
                    projectionVertex(pose, vertices, 1.0F, faceY, 1.0F, u1, v1, red, green, blue, alpha);
                    projectionVertex(pose, vertices, 0.0F, faceY, 1.0F, u0, v1, red, green, blue, alpha);
                    projectionVertex(pose, vertices, 0.0F, faceY, 0.0F, u0, v0, red, green, blue, alpha);
                } else {
                    projectionVertex(pose, vertices, 0.0F, faceY, 0.0F, u0, v0, red, green, blue, alpha);
                    projectionVertex(pose, vertices, 0.0F, faceY, 1.0F, u0, v1, red, green, blue, alpha);
                    projectionVertex(pose, vertices, 1.0F, faceY, 1.0F, u1, v1, red, green, blue, alpha);
                    projectionVertex(pose, vertices, 1.0F, faceY, 0.0F, u1, v0, red, green, blue, alpha);
                }
            }
            case Z -> {
                if (direction == Direction.SOUTH) {
                    projectionVertex(pose, vertices, 1.0F, 0.0F, faceZ, u1, v0, red, green, blue, alpha);
                    projectionVertex(pose, vertices, 1.0F, 1.0F, faceZ, u1, v1, red, green, blue, alpha);
                    projectionVertex(pose, vertices, 0.0F, 1.0F, faceZ, u0, v1, red, green, blue, alpha);
                    projectionVertex(pose, vertices, 0.0F, 0.0F, faceZ, u0, v0, red, green, blue, alpha);
                } else {
                    projectionVertex(pose, vertices, 0.0F, 0.0F, faceZ, u0, v0, red, green, blue, alpha);
                    projectionVertex(pose, vertices, 0.0F, 1.0F, faceZ, u0, v1, red, green, blue, alpha);
                    projectionVertex(pose, vertices, 1.0F, 1.0F, faceZ, u1, v1, red, green, blue, alpha);
                    projectionVertex(pose, vertices, 1.0F, 0.0F, faceZ, u1, v0, red, green, blue, alpha);
                }
            }
        }
    }

    private static void projectionVertex(
            PoseStack.Pose pose,
            VertexConsumer vertices,
            float x,
            float y,
            float z,
            float u,
            float v,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        vertices.addVertex(pose, x, y, z)
                .setUv(u, v)
                .setColor(red, green, blue, alpha);
    }

    private record PlaneBounds(int minU, int minV, int maxU, int maxV) {
        private float u(Direction direction, BlockPos blockPos, boolean high) {
            float value = (planeU(direction, blockPos) + (high ? 1.0F : 0.0F) - minU) / (maxU - minU);
            return direction == Direction.NORTH || direction == Direction.EAST ? 1.0F - value : value;
        }

        private float v(Direction direction, BlockPos blockPos, boolean high) {
            float value = (planeV(direction, blockPos) + (high ? 1.0F : 0.0F) - minV) / (maxV - minV);

            return direction == Direction.UP ? 1.0F - value : value;
        }
    }
}
