package dev.imb11.client.renderer.block;

import dev.imb11.blocks.ProjectorBlock;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.client.renderer.world.ProjectorRenderingHelper;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.BlockModelRenderer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;

import java.util.Objects;

public class ProjectorBlockEntityRenderer implements BlockEntityRenderer<ProjectorBlockEntity> {

    public Framebuffer framebuffer;

    public ProjectorBlockEntityRenderer(BlockEntityRendererFactory.Context ctx) {}

    private static float interpolateRotation(float prevRotation, float nextRotation, float partialTick) {
        float f3;

        f3 = nextRotation - prevRotation;
        while (f3 < -180.0F) {
            f3 += 360.0F;
        }

        while(f3 >= 180.0F)
        {
            f3 -= 360.0F;
        }

        return prevRotation + partialTick * f3;
    }


    @Override
    public void render(ProjectorBlockEntity entity, float tickDelta, MatrixStack matrices, VertexConsumerProvider vertexConsumers, int light, int overlay) {
        matrices.push();

        matrices.translate(0.5D, 0.5D, 0.5D);

        float scale = 0.5f;

        Direction direction = entity.getCachedState().get(ProjectorBlock.FACING);
        if (direction == Direction.DOWN) {
            matrices.multiply(new Quaternionf().rotationXYZ((float) Math.toRadians(180.0f), 0.0f, 0.0f));
        } else if (direction.getHorizontal() >= 0) {
            int horizontalIndex = direction.getHorizontal();
            matrices.multiply(new Quaternionf().rotationY((float) Math.toRadians(-horizontalIndex * 90f)));
            matrices.multiply(new Quaternionf().rotationX((float) Math.toRadians(90f)));
        }

        float rot = interpolateRotation(entity.rotationBeacon, entity.rotationBeaconPrev, tickDelta);
        matrices.multiply(new Quaternionf().rotationY((float) Math.toRadians(rot)));
        matrices.translate(-0.25D, -0.25D, -0.25D);
        matrices.scale(scale, scale, scale);

        BlockRenderManager blockRenderManager = MinecraftClient.getInstance().getBlockRenderManager();
        BlockModelRenderer blockModelRenderer = blockRenderManager.getModelRenderer();

        int lightAbove = WorldRenderer.getLightmapCoordinates(Objects.requireNonNull(entity.getWorld()), entity.getPos().up());

        blockModelRenderer.render(matrices.peek(),
                vertexConsumers.getBuffer(RenderLayers.getBlockLayer(Blocks.BEACON.getDefaultState())),
                Blocks.BEACON.getDefaultState(),
                blockRenderManager.getModel(Blocks.BEACON.getDefaultState()),
                1f,
                1f,
                1f,
                lightAbove,
                OverlayTexture.DEFAULT_UV);

        matrices.pop();

        int maxDistance = entity.furthestBlock + 3;

        // Render the world onto the facing direction.
        if (framebuffer == null) {
            framebuffer = new SimpleFramebuffer(512, 512, true, MinecraftClient.IS_SYSTEM_MAC);
        }

        matrices.push();
        ProjectorRenderingHelper.renderEdgePanels(Vec3d.of(entity.getPos()), matrices, direction, entity.neighbouringGlassBlocks, entity.targetDistance, maxDistance);
        matrices.pop();

//        matrices.push();
//        ProjectorRenderingHelper.renderWorldFramebuffer(new BlockPos(20, -58, 4), framebuffer, matrices, facing, entity.neighbouringGlassBlocks, entity.targetDistance, maxDistance);
//        matrices.pop();
    }
}