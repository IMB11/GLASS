package dev.imb11.client.renderer.block;

import com.mojang.blaze3d.systems.RenderSystem;
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
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class ProjectorBlockEntityRenderer implements BlockEntityRenderer<ProjectorBlockEntity> {
    private static final int VIEW_DISTANCE = 64;
    private static final double VIEW_DISTANCE_SQUARED = VIEW_DISTANCE * VIEW_DISTANCE;
    private static final Map<ClientLevel, Map<BlockPos, Long>> RENDERED_FRAMES = new IdentityHashMap<>();
    private static final Map<ClientLevel, Map<BlockPos, ProjectorBlockEntity>> LOADED_PROJECTORS = new IdentityHashMap<>();
    private static ClientLevel frustumLevel;
    private static long frustumFrame = Long.MIN_VALUE;
    private static Frustum frustum;

    public ProjectorBlockEntityRenderer(BlockEntityRendererProvider.Context ctx) {}

    @Override
    public boolean shouldRenderOffScreen(ProjectorBlockEntity entity) {
        return true;
    }

    @Override
    public int getViewDistance() {
        return VIEW_DISTANCE;
    }

    @Override
    public boolean shouldRender(ProjectorBlockEntity entity, Vec3 cameraPosition) {
        if (!(entity.getLevel() instanceof ClientLevel clientLevel) || entity.isRemoved()) {
            return false;
        }
        ProjectionSurface surface = entity.getProjectionSurface();
        if (surface != null) {
            ProjectionRenderManager.trackViewer(clientLevel, entity.getBlockPos(), surface, cameraPosition);
        }
        return distanceToSqr(renderBounds(entity, surface), cameraPosition) <= VIEW_DISTANCE_SQUARED;
    }

    public static void release(ClientLevel level, BlockPos projectorPos) {
        Map<BlockPos, Long> renderedFrames = RENDERED_FRAMES.get(level);
        if (renderedFrames == null) {
            return;
        }
        renderedFrames.remove(projectorPos);
        if (renderedFrames.isEmpty()) {
            RENDERED_FRAMES.remove(level);
        }
    }

    public static void registerLoaded(ClientLevel level, ProjectorBlockEntity entity) {
        Map<BlockPos, ProjectorBlockEntity> projectors = LOADED_PROJECTORS.computeIfAbsent(
                level,
                ignored -> new HashMap<>()
        );
        ProjectorBlockEntity previous = projectors.put(entity.getBlockPos().immutable(), entity);
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == level) {
            minecraft.levelRenderer.updateGlobalBlockEntities(
                    previous == null || previous == entity ? List.of() : List.of(previous),
                    List.of(entity)
            );
        }
    }

    public static void unregisterLoaded(ClientLevel level, ProjectorBlockEntity entity) {
        Map<BlockPos, ProjectorBlockEntity> projectors = LOADED_PROJECTORS.get(level);
        if (projectors != null) {
            projectors.remove(entity.getBlockPos(), entity);
            if (projectors.isEmpty()) {
                LOADED_PROJECTORS.remove(level);
            }
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == level) {
            minecraft.levelRenderer.updateGlobalBlockEntities(List.of(entity), List.of());
        }
    }

    public static void onMainRendererRebuilt(LevelRenderer renderer) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (renderer != minecraft.levelRenderer || level == null) {
            return;
        }
        Map<BlockPos, ProjectorBlockEntity> projectors = LOADED_PROJECTORS.get(level);
        if (projectors != null && !projectors.isEmpty()) {
            renderer.updateGlobalBlockEntities(List.of(), List.copyOf(projectors.values()));
        }
    }

    public static void clearLoaded() {
        LOADED_PROJECTORS.clear();
    }

    public static void releaseLevel(ClientLevel level) {
        RENDERED_FRAMES.remove(level);
        LOADED_PROJECTORS.remove(level);
        if (frustumLevel == level) {
            clearFrustum();
        }
    }

    public static void reset() {
        RENDERED_FRAMES.clear();
        clearFrustum();
    }

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
        if (!(entity.getLevel() instanceof ClientLevel clientLevel)) {
            return;
        }

        ProjectionSurface surface = entity.getProjectionSurface();
        AABB renderBounds = renderBounds(entity, surface);
        long frame = ProjectionRenderManager.currentFrameSequence();
        if (!claimRender(clientLevel, entity.getBlockPos(), frame)) {
            return;
        }
        if (!currentFrustum(clientLevel, frame).isVisible(renderBounds)) {
            return;
        }

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

        int lightAbove = LevelRenderer.getLightColor(clientLevel, entity.getBlockPos().above());

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

        if (!entity.isProjectionVisible() || surface == null) {
            return;
        }

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

    private static AABB renderBounds(ProjectorBlockEntity entity, ProjectionSurface surface) {
        return surface == null || !entity.isProjectionVisible()
                ? new AABB(entity.getBlockPos())
                : surface.renderBounds();
    }

    private static double distanceToSqr(AABB bounds, Vec3 position) {
        double x = Math.max(Math.max(bounds.minX - position.x, 0.0D), position.x - bounds.maxX);
        double y = Math.max(Math.max(bounds.minY - position.y, 0.0D), position.y - bounds.maxY);
        double z = Math.max(Math.max(bounds.minZ - position.z, 0.0D), position.z - bounds.maxZ);
        return x * x + y * y + z * z;
    }

    private static boolean claimRender(ClientLevel level, BlockPos projectorPos, long frame) {
        Map<BlockPos, Long> renderedFrames = RENDERED_FRAMES.computeIfAbsent(level, ignored -> new HashMap<>());
        Long previousFrame = renderedFrames.put(projectorPos.immutable(), frame);
        return previousFrame == null || previousFrame != frame;
    }

    private static Frustum currentFrustum(ClientLevel level, long frame) {
        if (frustum == null || frustumLevel != level || frustumFrame != frame) {
            Minecraft minecraft = Minecraft.getInstance();
            Vec3 cameraPosition = minecraft.gameRenderer.getMainCamera().getPosition();
            frustum = new Frustum(
                    new Matrix4f(RenderSystem.getModelViewMatrix()),
                    new Matrix4f(RenderSystem.getProjectionMatrix())
            );
            frustum.prepare(cameraPosition.x, cameraPosition.y, cameraPosition.z);
            frustumLevel = level;
            frustumFrame = frame;
        }
        return frustum;
    }

    private static void clearFrustum() {
        frustumLevel = null;
        frustumFrame = Long.MIN_VALUE;
        frustum = null;
    }
}
