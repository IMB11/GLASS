package dev.imb11.client.renderer.block;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.imb11.blocks.ProjectorBlock;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.client.ClientProjectionSourceRegistry;
import dev.imb11.client.renderer.projection.ProjectionRenderManager;
import dev.imb11.client.renderer.projection.ProjectionSurfaceRenderer;
import dev.imb11.projection.ProjectionSurface;
import dev.imb11.sync.ProjectionSource;
import dev.imb11.sync.remote.RemoteSceneServerManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class ProjectorBlockEntityRenderer implements BlockEntityRenderer<ProjectorBlockEntity> {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int BEACON_BLUR_SAMPLES = 10;
    private static final float BEACON_BLUR_SHUTTER_TICKS = 0.5F;
    private static final RenderType BEACON_BLUR_TYPE = RenderType.create(
            "glass_projector_beacon_blur",
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
    private static final Map<ProjectorBlockEntity, ActivationDiagnostics> DIAGNOSTICS = new IdentityHashMap<>();
    private static final Map<ProjectorBlockEntity, Long> LOADING_START_TIMES = new IdentityHashMap<>();
    private static final long LOADING_FADE_NANOS = 500_000_000L;
    private static final float LOADING_MAX_OPACITY = 0.35F;
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
        if (previous != null && previous != entity) {
            DIAGNOSTICS.remove(previous);
            LOADING_START_TIMES.remove(previous);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == level) {
            minecraft.levelRenderer.updateGlobalBlockEntities(
                    previous == null || previous == entity ? List.of() : List.of(previous),
                    List.of(entity)
            );
        }
    }

    public static void unregisterLoaded(ClientLevel level, ProjectorBlockEntity entity) {
        DIAGNOSTICS.remove(entity);
        LOADING_START_TIMES.remove(entity);
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
        DIAGNOSTICS.clear();
        LOADING_START_TIMES.clear();
    }

    public static void prepareNearby(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        Map<BlockPos, ProjectorBlockEntity> projectors = LOADED_PROJECTORS.get(level);
        if (projectors == null || minecraft.player == null) {
            return;
        }
        Vec3 viewer = minecraft.gameRenderer.getMainCamera().getPosition();
        List<ProjectorBlockEntity> nearby = new ArrayList<>();
        for (ProjectorBlockEntity projector : projectors.values()) {
            projector.setClientProjectionReady(false);
            if (!projector.isActive() || projector.isRemoved()) {
                LOADING_START_TIMES.remove(projector);
            }
            if (projector.isRemoved()) {
                continue;
            }
            if (ClientProjectionSourceRegistry.resolve(projector.getChannel()) == null) {
                reportActivation(projector, "missing-channel-source", null);
                continue;
            }
            if (distanceToSqr(new AABB(projector.getBlockPos()).inflate(ProjectionSurface.MAX_RADIUS), viewer)
                    > VIEW_DISTANCE_SQUARED) {
                reportActivation(projector, "outside-preload-range", null);
                continue;
            }
            projector.prepareProjectionSurface();
            ProjectionSurface surface = projector.getProjectionSurface();
            if (surface != null && distanceToSqr(surface.renderBounds(), viewer) <= VIEW_DISTANCE_SQUARED) {
                nearby.add(projector);
            } else {
                reportActivation(projector, surface == null ? "missing-surface" : "outside-surface-range", null);
            }
        }
        nearby.sort(Comparator.comparing((ProjectorBlockEntity projector) -> !projector.isProjectionVisible())
                .thenComparingDouble(projector -> distanceToSqr(projector.getProjectionSurface().renderBounds(), viewer))
                .thenComparingLong(projector -> projector.getBlockPos().asLong()));
        int selectedCount = Math.min(nearby.size(), RemoteSceneServerManager.MAX_SUBSCRIPTIONS_PER_PLAYER);
        ProjectionRenderManager.prepareRequests(nearby.subList(0, selectedCount));
        for (int i = 0; i < selectedCount; i++) {
            ProjectorBlockEntity projector = nearby.get(i);
            ProjectionRenderManager.ProjectionFeed feed = ProjectionRenderManager.requestFeed(
                    ClientProjectionSourceRegistry.resolve(projector.getChannel()),
                    projector.getBlockPos(),
                    projector.getProjectionSurface()
            );
            projector.setClientProjectionReady(ProjectionRenderManager.isReady(feed));
            reportActivation(projector, feed == null ? "feed-request-rejected" : feed.diagnosticStage(), feed);
        }
        for (int i = RemoteSceneServerManager.MAX_SUBSCRIPTIONS_PER_PLAYER; i < nearby.size(); i++) {
            reportActivation(nearby.get(i), "subscription-limit", null);
        }
    }

    public static void prepareSurfaceBlending(ClientLevel level) {
        List<ProjectionSurfaceRenderer.Projection> projections = new ArrayList<>();
        Map<BlockPos, ProjectorBlockEntity> projectors = LOADED_PROJECTORS.get(level);
        if (projectors != null) {
            for (ProjectorBlockEntity projector : projectors.values()) {
                ProjectionSurface surface = projector.getProjectionSurface();
                if (projector.isRemoved() || !projector.isProjectionVisible() || surface == null) {
                    continue;
                }
                ProjectionRenderManager.ProjectionFeed feed = ProjectionRenderManager.preparedFeed(
                        ClientProjectionSourceRegistry.resolve(projector.getChannel()),
                        projector.getBlockPos(),
                        surface
                );
                if (ProjectionRenderManager.isReady(feed)) {
                    projections.add(new ProjectionSurfaceRenderer.Projection(
                            projector.getBlockPos(), surface, feed, projector.getRevealDistance()
                    ));
                }
            }
        }
        ProjectionSurfaceRenderer.prepare(level, projections);
    }

    private static void reportActivation(ProjectorBlockEntity projector, String stage, ProjectionRenderManager.ProjectionFeed feed) {
        ActivationDiagnostics diagnostic = DIAGNOSTICS.computeIfAbsent(projector, ignored -> new ActivationDiagnostics());
        long now = System.nanoTime();
        boolean powered = projector.isActive();
        boolean powerChanged = diagnostic.powered != powered;
        if (powerChanged) {
            diagnostic.powered = powered;
            diagnostic.poweredSince = now;
        }
        boolean ready = ProjectionRenderManager.isReady(feed);
        long sinceLog = now - diagnostic.lastLog;
        boolean stalled = powered && !ready && now - diagnostic.poweredSince >= 5_000_000_000L;
        boolean changed = !stage.equals(diagnostic.stage) || ready != diagnostic.ready;
        if (diagnostic.stage != null && !powerChanged && !(changed && sinceLog >= 1_000_000_000L)
                && !(stalled && sinceLog >= 5_000_000_000L)) {
            return;
        }
        diagnostic.stage = stage;
        diagnostic.ready = ready;
        diagnostic.lastLog = now;
        ProjectionSurface surface = projector.getProjectionSurface();
        String message = "[GLASS projector] activation dimension={} pos={} channel={} powered={} poweredMs={} stage={} ready={} reveal={} surfaceVersion={} bounds={} {}";
        Object[] details = {
                projector.getLevel().dimension().location(), projector.getBlockPos().toShortString(), projector.getChannel(),
                powered, powered ? (now - diagnostic.poweredSince) / 1_000_000L : 0L, stage, ready, projector.getRevealDistance(),
                surface == null ? -1L : surface.version(), surface == null ? "none" : surface.renderBounds(),
                feed == null ? "feed=none" : feed.diagnostics()
        };
        if (stalled) {
            LOGGER.warn(message, details);
        } else {
            LOGGER.info(message, details);
        }
    }

    private static final class ActivationDiagnostics {
        private String stage;
        private boolean powered;
        private boolean ready;
        private long poweredSince;
        private long lastLog;
    }

    public static void releaseLevel(ClientLevel level) {
        DIAGNOSTICS.keySet().removeIf(projector -> projector.getLevel() == level);
        LOADING_START_TIMES.keySet().removeIf(projector -> projector.getLevel() == level);
        RENDERED_FRAMES.remove(level);
        LOADED_PROJECTORS.remove(level);
        if (frustumLevel == level) {
            clearFrustum();
        }
    }

    public static void reset() {
        DIAGNOSTICS.clear();
        LOADING_START_TIMES.clear();
        RENDERED_FRAMES.clear();
        clearFrustum();
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

        Direction direction = entity.getBlockState().getValue(ProjectorBlock.FACING);
        if (direction == Direction.DOWN) {
            matrices.mulPose(new Quaternionf().rotationXYZ((float) Math.toRadians(180.0f), 0.0f, 0.0f));
        } else if (direction.get2DDataValue() >= 0) {
            int horizontalIndex = direction.get2DDataValue();
            matrices.mulPose(new Quaternionf().rotationY((float) Math.toRadians(-horizontalIndex * 90f)));
            matrices.mulPose(new Quaternionf().rotationX((float) Math.toRadians(90f)));
        }

        float rotation = entity.getBeaconRotation(tickDelta);
        float speed = entity.getBeaconSpeed(tickDelta);
        float blur = Mth.clamp((speed / ProjectorBlockEntity.BEACON_MAX_SPEED - 0.25F) / 0.75F, 0.0F, 1.0F);
        blur = blur * blur * (3.0F - 2.0F * blur);
        BlockState beaconState = Blocks.BEACON.defaultBlockState();
        BakedModel beaconModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(beaconState);
        RandomSource random = RandomSource.create();
        List<BakedQuad> quads = new ArrayList<>();
        for (Direction face : Direction.values()) {
            random.setSeed(42L);
            quads.addAll(beaconModel.getQuads(beaconState, face, random));
        }
        random.setSeed(42L);
        quads.addAll(beaconModel.getQuads(beaconState, null, random));
        int lightAbove = LevelRenderer.getLightColor(clientLevel, entity.getBlockPos().above());
        renderBeaconSample(matrices, vertexConsumers.getBuffer(ItemBlockRenderTypes.getChunkRenderType(beaconState)),
                quads, rotation, 1.0F, lightAbove);
        if (blur > 0.0F) {
            VertexConsumer blurVertices = vertexConsumers.getBuffer(BEACON_BLUR_TYPE);
            for (int sample = BEACON_BLUR_SAMPLES; sample > 0; sample--) {
                float trail = (float) sample / BEACON_BLUR_SAMPLES;
                float opacity = blur * Mth.lerp(trail, 0.14F, 0.035F);
                renderBeaconSample(matrices, blurVertices, quads,
                        rotation - speed * BEACON_BLUR_SHUTTER_TICKS * trail, opacity, lightAbove);
            }
        }

        matrices.popPose();

        if (!entity.isProjectionVisible() || surface == null) {
            LOADING_START_TIMES.remove(entity);
            return;
        }

        ProjectionSource source = ClientProjectionSourceRegistry.resolve(entity.getChannel());
        ProjectionRenderManager.ProjectionFeed feed = ProjectionRenderManager.visibleFeed(
                source,
                entity.getBlockPos(),
                surface
        );
        boolean loading = entity.isActive() && source != null && !ProjectionRenderManager.isReady(feed);
        ProjectionSurfaceRenderer.render(
                clientLevel,
                entity.getBlockPos(),
                surface,
                feed,
                matrices,
                loadingOpacity(entity, loading)
        );
    }

    private static void renderBeaconSample(PoseStack matrices, VertexConsumer vertices, List<BakedQuad> quads,
                                           float rotation, float opacity, int light) {
        matrices.pushPose();
        matrices.mulPose(new Quaternionf().rotationY(rotation * Mth.DEG_TO_RAD));
        matrices.scale(0.5F, 0.5F, 0.5F);
        matrices.translate(-0.5D, -0.5D, -0.5D);
        for (BakedQuad quad : quads) {
            vertices.putBulkData(matrices.last(), quad, 1.0F, 1.0F, 1.0F, opacity, light, OverlayTexture.NO_OVERLAY);
        }
        matrices.popPose();
    }

    private static float loadingOpacity(ProjectorBlockEntity entity, boolean loading) {
        if (!loading) {
            LOADING_START_TIMES.remove(entity);
            return -1.0F;
        }
        long now = System.nanoTime();
        long start = LOADING_START_TIMES.computeIfAbsent(entity, ignored -> now);
        long elapsed = (now - start) % (LOADING_FADE_NANOS * 2L);
        double phase = (double) elapsed / LOADING_FADE_NANOS;
        return LOADING_MAX_OPACITY * (float) (0.5D - 0.5D * Math.cos(Math.PI * phase));
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
