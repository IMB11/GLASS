package dev.imb11.client.renderer.block;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.imb11.blocks.ProjectorBlock;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.client.ClientProjectionSourceRegistry;
import dev.imb11.client.renderer.projection.ProjectionRenderManager;
import dev.imb11.client.renderer.projection.ProjectionSurfaceRenderer;
import dev.imb11.projection.ProjectionSurface;
import dev.imb11.sync.ProjectionSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import org.joml.Quaternionf;

import java.util.Objects;

public class ProjectorBlockEntityRenderer implements BlockEntityRenderer<ProjectorBlockEntity> {
    public ProjectorBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

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
    public void render(ProjectorBlockEntity entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
        matrices.pushPose();

        matrices.translate(0.5D, 0.5D, 0.5D);

        float scale = 0.5f;

        Direction direction = entity.getBlockState().getValue(ProjectorBlock.FACING);
        if (direction == Direction.DOWN) {
            matrices.mulPose(new Quaternionf().rotationXYZ((float) Math.toRadians(180.0f), 0.0f, 0.0f));
        } else if (direction.get2DDataValue() >= 0) {
            int horizontalIndex = direction.get2DDataValue();
            matrices.mulPose(new Quaternionf().rotationY((float) Math.toRadians(-horizontalIndex * 90f)));
            matrices.mulPose(new Quaternionf().rotationX((float) Math.toRadians(90f)));
        }

        float rot = interpolateRotation(entity.rotationBeacon, entity.rotationBeaconPrev, tickDelta);
        matrices.mulPose(new Quaternionf().rotationY((float) Math.toRadians(rot)));
        matrices.translate(-0.25D, -0.25D, -0.25D);
        matrices.scale(scale, scale, scale);

        BlockRenderDispatcher blockRenderManager = Minecraft.getInstance().getBlockRenderer();
        ModelBlockRenderer blockModelRenderer = blockRenderManager.getModelRenderer();

        int lightAbove = LevelRenderer.getLightColor(Objects.requireNonNull(entity.getLevel()), entity.getBlockPos().above());

        blockModelRenderer.renderModel(matrices.last(),
                vertexConsumers.getBuffer(ItemBlockRenderTypes.getChunkRenderType(Blocks.BEACON.defaultBlockState())),
                Blocks.BEACON.defaultBlockState(),
                blockRenderManager.getBlockModel(Blocks.BEACON.defaultBlockState()),
                1f,
                1f,
                1f,
                lightAbove,
                OverlayTexture.NO_OVERLAY);

        matrices.popPose();

        ProjectionSurface surface = entity.getProjectionSurface();
        if (!entity.isProjectionVisible() || surface == null) {
            return;
        }

        if (entity.getLevel() instanceof ClientLevel clientLevel) {
            ProjectionSource source = ClientProjectionSourceRegistry.resolve(entity.getChannel());
            ProjectionRenderManager.ProjectionFeed feed = ProjectionRenderManager.requestFeed(
                    source,
                    entity.getBlockPos(),
                    surface
            );
            if (feed == null || !feed.isReady()) {
                return;
            }
            ProjectionSurfaceRenderer.render(
                    clientLevel,
                    entity.getBlockPos(),
                    surface,
                    feed,
                    matrices,
                    entity.getRevealDistance()
            );
        }
    }
}
