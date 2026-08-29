package dev.imb11.client.renderer.block;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.imb11.blocks.TerminalBlock;
import dev.imb11.blocks.entity.TerminalBlockEntity;
import net.minecraft.client.Minecraft;
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
import org.apache.commons.lang3.ArrayUtils;
import org.joml.Quaternionf;

import java.util.Objects;

public class TerminalBlockEntityRenderer implements BlockEntityRenderer<TerminalBlockEntity> {

    public TerminalBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public void render(TerminalBlockEntity entity, float tickDelta, PoseStack matrices, MultiBufferSource vertexConsumers, int light, int overlay) {
        Direction facing = entity.getBlockState().getValue(TerminalBlock.FACING);

        matrices.pushPose();

        float scale = 0.25f;

        matrices.translate(0.5, 0.5, 0.5);

        var e = new float[] {45, facing.getStepX(), facing.getStepY(), facing.getStepZ() };

        boolean needBreak = false;
        for (float v : e) {
            if (needBreak) {
                break;
            }
            if (v == e[0]) {
                continue;
            }
            if (v != 0) {
                int axis = ArrayUtils.indexOf(e, v) - 1;
                switch (axis) {
                    case 0 -> {
                        matrices.mulPose(new Quaternionf().rotationX((float) Math.toRadians(45)));
                        needBreak = true;
                    }
                    case 1 -> {
                        matrices.mulPose(new Quaternionf().rotationY((float) Math.toRadians(45)));
                        needBreak = true;
                    }
                    case 2 -> {
                        matrices.mulPose(new Quaternionf().rotationZ((float) Math.toRadians(45)));
                        needBreak = true;
                    }
                }
            }
        }

        matrices.translate(-0.5, -0.5, -0.5);
        matrices.translate(0.375, 0.375, 0.375);
        matrices.translate(facing.getStepX() * -0.4D, facing.getStepY() * -0.4D, facing.getStepZ() * -0.4D);

        matrices.scale(scale, scale, scale);

        BlockRenderDispatcher blockRenderManager = Minecraft.getInstance().getBlockRenderer();
        ModelBlockRenderer blockModelRenderer = blockRenderManager.getModelRenderer();

        int lightAbove = LevelRenderer.getLightColor(Objects.requireNonNull(entity.getLevel()), entity.getBlockPos().above());

        blockModelRenderer.renderModel(matrices.last(),
                vertexConsumers.getBuffer(ItemBlockRenderTypes.getChunkRenderType(Blocks.GLASS.defaultBlockState())),
                Blocks.GLASS.defaultBlockState(),
                blockRenderManager.getBlockModel(Blocks.GLASS.defaultBlockState()),
                1f,
                1f,
                1f,
                lightAbove,
                OverlayTexture.NO_OVERLAY);

        matrices.popPose();
    }
}
