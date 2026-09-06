package dev.imb11.client.renderer.block;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.imb11.blocks.ProjectorBlock;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.items.GItems;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

public final class ProjectorBlockModel {
    private static final int ROTOR = 1;
    private static final int LENS = 2;
    private static final int CORE = 3;
    private static final int GLASS_CASING = 4;
    private static final int BLUR_SAMPLES = 6;
    private static final float BLUR_SHUTTER_TICKS = 0.5F;
    private static final RenderType MODEL_TYPE = RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS);
    private static final RenderType BLUR_TYPE = RenderType.create(
            "glass_projector_rotor_blur",
            DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(TextureAtlas.LOCATION_BLOCKS, false, true))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setOverlayState(RenderStateShard.OVERLAY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false)
    );

    private BakedModel bakedModel;
    private final List<BakedQuad> housing = new ArrayList<>();
    private final List<BakedQuad> rotor = new ArrayList<>();

    public void render(ProjectorBlockEntity entity, float tickDelta, PoseStack matrices,
                       MultiBufferSource buffers, int light, int overlay) {
        BakedModel model = Minecraft.getInstance().getItemRenderer().getItemModelShaper().getItemModel(GItems.PROJECTOR);
        if (model == null) {
            return;
        }
        if (bakedModel != model) {
            rebuild(model);
        }

        matrices.pushPose();
        matrices.translate(0.5D, 0.5D, 0.5D);
        Direction facing = entity.getBlockState().getValue(ProjectorBlock.FACING);
        if (facing == Direction.DOWN) {
            matrices.mulPose(new Quaternionf().rotationX(Mth.PI));
        } else if (facing.get2DDataValue() >= 0) {
            matrices.mulPose(new Quaternionf().rotationY(-facing.get2DDataValue() * Mth.HALF_PI));
            matrices.mulPose(new Quaternionf().rotationX(Mth.HALF_PI));
        }

        VertexConsumer vertices = buffers.getBuffer(MODEL_TYPE);
        renderPart(matrices, vertices, housing, 0.0F, 1.0F, light, overlay);
        float rotation = entity.getBeaconRotation(tickDelta);
        float speed = entity.getBeaconSpeed(tickDelta);
        renderPart(matrices, vertices, rotor, rotation, 1.0F, light, overlay);

        float blur = Mth.clamp((speed / ProjectorBlockEntity.BEACON_MAX_SPEED - 0.25F) / 0.75F, 0.0F, 1.0F);
        blur = blur * blur * (3.0F - 2.0F * blur);
        if (blur > 0.0F) {
            VertexConsumer blurVertices = buffers.getBuffer(BLUR_TYPE);
            for (int sample = BLUR_SAMPLES; sample > 0; sample--) {
                float trail = (float) sample / BLUR_SAMPLES;
                float opacity = blur * Mth.lerp(trail, 0.14F, 0.035F);
                renderPart(matrices, blurVertices, rotor,
                        rotation - speed * BLUR_SHUTTER_TICKS * trail, opacity, light, overlay);
            }
        }
        matrices.popPose();
    }

    private void rebuild(BakedModel model) {
        housing.clear();
        rotor.clear();
        RandomSource random = RandomSource.create(42L);
        for (Direction face : Direction.values()) {
            random.setSeed(42L);
            addQuads(model.getQuads(null, face, random));
        }
        random.setSeed(42L);
        addQuads(model.getQuads(null, null, random));
        bakedModel = model;
    }

    private void addQuads(List<BakedQuad> quads) {
        for (BakedQuad quad : quads) {
            switch (quad.getTintIndex()) {
                case ROTOR, CORE -> rotor.add(quad);
                case GLASS_CASING -> {}
                default -> housing.add(quad);
            }
        }
    }

    private static void renderPart(PoseStack matrices, VertexConsumer vertices, List<BakedQuad> quads,
                                   float rotation, float opacity, int light, int overlay) {
        matrices.pushPose();
        matrices.mulPose(new Quaternionf().rotationY(rotation * Mth.DEG_TO_RAD));
        matrices.translate(-0.5D, -0.5D, -0.5D);
        for (BakedQuad quad : quads) {
            int part = quad.getTintIndex();
            int partLight = part == LENS || part == CORE ? LightTexture.FULL_BRIGHT : light;
            vertices.putBulkData(matrices.last(), quad, 1.0F, 1.0F, 1.0F, opacity, partLight, overlay);
        }
        matrices.popPose();
    }
}
