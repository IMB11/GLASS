package dev.imb11.client.renderer.projection;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.imb11.client.remote.RemoteSceneClientManager;
import dev.imb11.client.remote.ProjectionChunkStorage;
import dev.imb11.client.remote.RemoteSceneHandle;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.client.renderer.block.ProjectorBlockEntityRenderer;
import dev.imb11.mixins.BufferSourceAccessor;
import dev.imb11.mixins.CompiledSectionAccessor;
import dev.imb11.mixins.LevelRendererBufferAccessor;
import dev.imb11.mixins.LevelRendererInvoker;
import dev.imb11.mixins.SectionRenderDispatcherAccessor;
import dev.imb11.mixins.ViewAreaInvoker;
import dev.imb11.projection.ProjectionSurface;
import dev.imb11.projection.ProjectionChunkRegion;
import dev.imb11.sync.ProjectionSource;
import dev.imb11.sync.remote.RemoteSubscriptionId;
import dev.imb11.sync.remote.RemoteSceneServerManager;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class ProjectionRenderManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("glass/projection-renderer");
    private static final int REQUEST_RENDER_GRACE_FRAMES = 2;
    private static final long FEED_RETENTION_NANOS = TimeUnit.SECONDS.toNanos(10L);
    private static final int FAILURE_RETRY_FRAMES = 60;
    private static final int FEED_BUILD_BUFFERS = Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    private static final long BUILD_PREPARATION_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(2L);
    private static final long UPLOAD_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(2L);
    private static final long SLOW_STAGE_NANOS = TimeUnit.MILLISECONDS.toNanos(20L);
    private static final float CAMERA_FACE_OFFSET = 0.5625F;
    private static final double PROJECTOR_PLANE_OFFSET = 0.502D;
    private static final double SIDE_EPSILON = 1.0E-7D;
    private static final double DISTANCE_EPSILON = SIDE_EPSILON * SIDE_EPSILON;
    private static final double CROSSING_EPSILON = 1.0E-6D;
    private static final RenderType[] TERRAIN_LAYERS = {
            RenderType.solid(),
            RenderType.cutoutMipped(),
            RenderType.cutout(),
            RenderType.translucent(),
            RenderType.tripwire()
    };
    private static final Map<FeedKey, ProjectionFeed> FEEDS = new LinkedHashMap<>();
    private static final Map<TerrainKey, TerrainResources> TERRAINS = new HashMap<>();
    private static final Map<ProjectionOwner, PortalSideState> PORTAL_SIDES = new HashMap<>();
    private static final List<RetiredBufferPool> RETIRED_BUFFER_POOLS = new ArrayList<>();
    private static Map<String, ProjectionSource> registrySources = Map.of();
    private static ClientLevel activeLevel;
    private static long frameSequence;
    private static long textureSequence;
    private static long subscriptionSequence;
    private static long buildPreparationNanos;
    private static long uploadNanos;
    private static volatile boolean retiredDrainScheduled;

    private ProjectionRenderManager() {
    }

    @Nullable
    public static ProjectionFeed requestFeed(
            @Nullable ProjectionSource source,
            BlockPos projectorPos,
            ProjectionSurface surface
    ) {
        RenderSystem.assertOnRenderThread();
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel currentLevel = minecraft.level;
        if (source == null || currentLevel == null || !isDescriptorValid(source)) {
            return null;
        }
        if (activeLevel != currentLevel) {
            changeLevelNow(currentLevel);
        }
        if (!source.equals(registrySources.get(source.channel()))) {
            return null;
        }

        ProjectionView view = ProjectionView.create(currentLevel.dimension(), projectorPos, surface);
        PortalSideState sideState = portalSideState(view);
        preparePortalSide(sideState, view, minecraft.gameRenderer.getMainCamera().getPosition());
        updatePortalSide(sideState, minecraft.gameRenderer.getMainCamera().getPosition());
        FeedKey key = new FeedKey(source.key(), view.dimension(), view.projectorPos());
        for (Map.Entry<FeedKey, ProjectionFeed> entry : List.copyOf(FEEDS.entrySet())) {
            if (!entry.getKey().equals(key)
                    && entry.getKey().projectorDimension().equals(key.projectorDimension())
                    && entry.getKey().projectorPos().equals(key.projectorPos())) {
                FEEDS.remove(entry.getKey());
                closeFeed(entry.getValue());
            }
        }
        ProjectionFeed feed = FEEDS.get(key);
        if (feed != null && !feed.source.equals(source)) {
            FEEDS.remove(key);
            closeFeed(feed);
            feed = null;
        }
        if (feed == null) {
            feed = new ProjectionFeed(key, source, view, sideState, nextTextureLocation());
            FEEDS.put(key, feed);
        } else if (!feed.view.equals(view)) {
            feed.view = view;
            feed.ready = false;
            feed.diagnosticStage = "surface-changed";
        }
        feed.lastRequestFrame = frameSequence;
        feed.lastRequestNanos = System.nanoTime();
        return feed;
    }

    public static void prepareRequests(List<ProjectorBlockEntity> projectors) {
        Set<FeedKey> requested = new LinkedHashSet<>();
        for (ProjectorBlockEntity projector : projectors) {
            ProjectionSource source = registrySources.get(projector.getChannel());
            if (source != null && activeLevel != null) {
                requested.add(new FeedKey(source.key(), activeLevel.dimension(), projector.getBlockPos()));
            }
        }
        long newFeeds = requested.stream().filter(key -> !FEEDS.containsKey(key)).count();
        List<ProjectionFeed> retained = FEEDS.values().stream()
                .filter(feed -> !requested.contains(feed.key))
                .sorted(Comparator.comparingLong(feed -> feed.lastRequestNanos))
                .toList();
        for (ProjectionFeed feed : retained) {
            if (FEEDS.size() + newFeeds <= RemoteSceneServerManager.MAX_SUBSCRIPTIONS_PER_PLAYER) {
                break;
            }
            FEEDS.remove(feed.key);
            closeFeed(feed);
        }
    }

    public static boolean isReady(@Nullable ProjectionFeed feed) {
        return feed != null
                && FEEDS.get(feed.key) == feed
                && feed.ready
                && feed.target != null
                && feed.target.getColorTextureId() > 0;
    }

    public static boolean usesSharedTerrain(LevelRenderer renderer) {
        for (TerrainResources terrain : TERRAINS.values()) {
            if (terrain.renderer == renderer) {
                return terrain.references > 1;
            }
        }
        return false;
    }

    @Nullable
    public static ProjectionFeed visibleFeed(@Nullable ProjectionSource source, BlockPos projectorPos, ProjectionSurface surface) {
        ProjectionFeed feed = preparedFeed(source, projectorPos, surface);
        if (feed != null) {
            markVisible(feed);
        }
        return feed;
    }

    @Nullable
    public static ProjectionFeed preparedFeed(@Nullable ProjectionSource source, BlockPos projectorPos, ProjectionSurface surface) {
        if (source == null || activeLevel == null) {
            return null;
        }
        ProjectionFeed feed = FEEDS.get(new FeedKey(source.key(), activeLevel.dimension(), projectorPos));
        if (feed == null || feed.lastRequestFrame != frameSequence
                || !feed.source.equals(source)
                || !feed.view.equals(ProjectionView.create(activeLevel.dimension(), projectorPos, surface))) {
            return null;
        }
        return feed;
    }

    static void markVisible(ProjectionFeed feed) {
        feed.lastVisibleFrame = frameSequence;
    }

    public static long currentFrameSequence() {
        return frameSequence;
    }

    public static boolean hasVisibleProjection() {
        for (ProjectionFeed feed : FEEDS.values()) {
            if (feed.ready && frameSequence - feed.lastRequestFrame <= REQUEST_RENDER_GRACE_FRAMES) {
                return true;
            }
        }
        return false;
    }

    public static void trackViewer(
            ClientLevel level,
            BlockPos projectorPos,
            ProjectionSurface surface,
            Vec3 viewerPosition
    ) {
        RenderSystem.assertOnRenderThread();
        if (activeLevel != level) {
            changeLevelNow(level);
        }
        ProjectionView view = ProjectionView.create(level.dimension(), projectorPos, surface);
        PortalSideState sideState = portalSideState(view);
        preparePortalSide(sideState, view, viewerPosition);
        updatePortalSide(sideState, viewerPosition);
    }

    public static void releaseProjector(ClientLevel level, BlockPos projectorPos) {
        BlockPos immutablePos = projectorPos.immutable();
        ResourceKey<Level> dimension = level.dimension();
        runOnRenderThread(() -> releaseProjectorNow(dimension, immutablePos));
    }

    public static boolean isProjectionRenderer(LevelRenderer candidate) {
        if (candidate == null) {
            return false;
        }
        for (ProjectionFeed feed : FEEDS.values()) {
            if (feed.renderer == candidate) {
                return true;
            }
        }
        return false;
    }

    public static void onRegistryReplaced(Map<String, ProjectionSource> sources) {
        Map<String, ProjectionSource> replacement = new HashMap<>();
        sources.forEach((channel, source) -> {
            if (channel != null && source != null && channel.equals(source.channel())) {
                replacement.put(channel, source);
            }
        });
        Map<String, ProjectionSource> immutableReplacement = Map.copyOf(replacement);
        runOnRenderThread(() -> replaceRegistryNow(immutableReplacement));
    }

    public static void onClientChunkUnloaded(ClientLevel level, ChunkPos chunkPos) {
        runOnRenderThread(() -> chunkUnloadedNow(level, chunkPos));
    }

    public static void onClientLevelChanged(@Nullable ClientLevel level) {
        runOnRenderThread(() -> {
            if (activeLevel != level) {
                changeLevelNow(level);
            }
        });
    }

    public static void onMainRendererRebuilt(LevelRenderer candidate) {
        Minecraft minecraft = Minecraft.getInstance();
        if (candidate != minecraft.levelRenderer) {
            return;
        }
        runOnRenderThread(() -> {
            for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
                disposeFeedResources(feed);
            }
            drainRetiredBufferPools();
        });
    }

    public static void onMainSectionDirty(LevelRenderer candidate, int sectionX, int sectionY, int sectionZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (candidate != minecraft.levelRenderer) {
            return;
        }
        for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
            if (feed.level == activeLevel
                    && feed.renderer != null
                    && sectionInFeedView(feed, sectionX, sectionY, sectionZ)) {
                SectionRenderDispatcher.RenderSection section = ProjectionSections.find(feed.renderer, sectionX, sectionY, sectionZ);
                if (section != null) {
                    section.setDirty(false);
                }
            }
        }
    }

    public static void onMainChunkLoaded(LevelRenderer candidate, ChunkPos chunkPos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (candidate != minecraft.levelRenderer) {
            return;
        }
        for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
            if (feed.level == activeLevel && feed.renderer != null) {
                feed.renderer.onChunkLoaded(chunkPos);
                dirtyChunkSections(feed, chunkPos);
            }
        }
    }

    public static void onMainRendererTick(LevelRenderer candidate) {
        Minecraft minecraft = Minecraft.getInstance();
        if (candidate != minecraft.levelRenderer) {
            return;
        }
        Set<LevelRenderer> ticked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
            if (feed.renderer != null && ticked.add(feed.renderer)) {
                feed.renderer.tick();
            }
        }
        drainRetiredBufferPools();
    }

    public static void renderBeforeMain(GameRenderer gameRenderer, DeltaTracker deltaTracker) {
        if (ProjectionRenderContext.isActive()) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        frameSequence++;
        buildPreparationNanos = 0L;
        uploadNanos = 0L;

        Minecraft minecraft = gameRenderer.getMinecraft();
        ClientLevel currentLevel = minecraft.level;
        if (currentLevel == null || minecraft.player == null) {
            if (activeLevel != null || !FEEDS.isEmpty()) {
                changeLevelNow(null);
            }
            drainRetiredBufferPools();
            return;
        }
        if (activeLevel != currentLevel) {
            changeLevelNow(currentLevel);
        }

        ProjectorBlockEntityRenderer.prepareNearby(minecraft);
        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        Camera viewerCamera = gameRenderer.getMainCamera();
        Vec3 viewerPosition = viewerCamera.getPosition();
        Matrix4f mainProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4f mainModelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        boolean initializedFeedThisFrame = false;
        for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
            if (FEEDS.get(feed.key) != feed) {
                continue;
            }
            updatePortalSide(feed.sideState, viewerPosition);
            long requestAge = frameSequence - feed.lastRequestFrame;
            if (feed.lastRequestFrame < 0L || System.nanoTime() - feed.lastRequestNanos > FEED_RETENTION_NANOS) {
                FEEDS.remove(feed.key);
                closeFeed(feed);
                continue;
            }
            if (requestAge > 0L) {
                feed.diagnosticStage = "retained";
                if (feed.remoteScene != null && feed.remoteScene.isTerminalFailure()) {
                    releaseRemoteFeed(feed);
                }
                continue;
            }
            if (feed.remoteScene != null && feed.remoteScene.isTerminalFailure()) {
                feed.diagnosticStage = "subscription-failed";
                releaseRemoteFeed(feed);
                feed.nextRetryFrame = frameSequence + FAILURE_RETRY_FRAMES;
                continue;
            }
            if (frameSequence < feed.nextRetryFrame) {
                feed.diagnosticStage = "retry-delay";
                continue;
            }

            try {
                PortalView portalView = configurePortalCamera(
                        feed,
                        viewerCamera,
                        mainProjection,
                        mainTarget
                );
                ChunkPos cameraCenter = viewCenterChunk(feed);
                int requestedRadius = ProjectionRenderContext.feedRenderDistance(
                        minecraft.options.getEffectiveRenderDistance()
                );
                RemoteSceneHandle remoteScene = ensureRemoteScene(feed, cameraCenter, requestedRadius);
                ClientLevel remoteLevel = remoteScene.level();
                int grantedRadius = remoteScene.grantedRadius();
                ClientLevel sceneLevel = remoteLevel;
                LightTexture sceneLight = remoteScene.lightTexture();
                if (sceneLevel == null || grantedRadius < 1 || remoteScene.isUnavailable()
                        || (!feed.ready && !remoteScene.isReady(cameraCenter))) {
                    feed.diagnosticStage = remoteLevel == null || grantedRadius < 1 ? "waiting-for-grant" : "waiting-for-camera-chunks";
                    markUnavailable(feed, mainTarget);
                    continue;
                }
                applyCamera(feed.camera, sceneLevel, minecraft, portalView, partialTick);
                boolean requiresInitialization = requiresResourceInitialization(feed, sceneLevel, grantedRadius);
                if (requiresInitialization && initializedFeedThisFrame) {
                    feed.diagnosticStage = "waiting-for-renderer-slot";
                    continue;
                }
                if (requiresInitialization) {
                    initializedFeedThisFrame = true;
                }
                long resourceStart = System.nanoTime();
                boolean resourcesReady = ensureResources(
                        minecraft,
                        sceneLevel,
                        sceneLight,
                        grantedRadius,
                        feed,
                        portalView,
                        partialTick
                );
                reportSlowStage(feed, "resources", resourceStart);
                if (!resourcesReady) {
                    feed.diagnosticStage = "initializing-renderer";
                    continue;
                }
                long prepareStart = System.nanoTime();
                prepareTerrain(feed, portalView);
                reportSlowStage(feed, "terrain-preparation", prepareStart);
                if (feed.ready && (feed.lastVisibleFrame < 0L || frameSequence - feed.lastVisibleFrame > REQUEST_RENDER_GRACE_FRAMES)) {
                    feed.diagnosticStage = "preloaded";
                    continue;
                }
                long drawStart = System.nanoTime();
                renderFeed(minecraft, gameRenderer, partialTick, feed, portalView, mainTarget);
                reportSlowStage(feed, "draw", drawStart);
                feed.diagnosticStage = feed.ready ? (feed.terrainReadyFrames < 2 ? "refreshing-terrain" : "ready") : "waiting-for-terrain";
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "[GLASS projector] render failed projector={} source={} stage={}; retrying in {} frames",
                        feed.key,
                        feed.source.key(),
                        feed.diagnosticStage,
                        FAILURE_RETRY_FRAMES,
                        exception
                );
                disposeFeedResources(feed);
                feed.failed = true;
                feed.diagnosticStage = "render-failed";
                feed.nextRetryFrame = frameSequence + FAILURE_RETRY_FRAMES;
            } finally {
                restoreMainRenderState(mainTarget, gameRenderer, mainProjection, mainModelView);
            }
        }
        drainRetiredBufferPools();
        restoreMainRenderState(mainTarget, gameRenderer, mainProjection, mainModelView);
        ProjectorBlockEntityRenderer.prepareSurfaceBlending(currentLevel);
    }

    public static void reset() {
        runOnRenderThread(ProjectionRenderManager::resetNow);
    }

    private static boolean requiresResourceInitialization(ProjectionFeed feed, ClientLevel level, int grantedRadius) {
        return feed.renderer == null
                || feed.level != level
                || feed.rendererRadius != grantedRadius
                || feed.target == null
                || feed.renderBuffers == null
                || !feed.terrain.key.equals(new TerrainKey(level, viewCenterChunk(feed), grantedRadius, feed.source.pos()));
    }

    private static boolean localChunksReady(ClientLevel level, ChunkPos center, int radius) {
        for (int x = center.x - radius; x <= center.x + radius; x++) {
            for (int z = center.z - radius; z <= center.z + radius; z++) {
                if (!level.getChunkSource().hasChunk(x, z)
                        || !level.getLightEngine().lightOnInSection(SectionPos.of(x, level.getMinSection(), z))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean chunkReady(ProjectionFeed feed, ChunkPos pos) {
        return feed.level == activeLevel && feed.level != null
                ? localChunksReady(feed.level, pos, ProjectionChunkRegion.NEIGHBOR_PADDING)
                : feed.remoteScene != null && feed.remoteScene.isReady(pos);
    }

    private static void reportSlowStage(ProjectionFeed feed, String stage, long started) {
        long now = System.nanoTime();
        long elapsed = now - started;
        if (elapsed >= SLOW_STAGE_NANOS && now - feed.lastSlowStageLog >= TimeUnit.SECONDS.toNanos(5L)) {
            feed.lastSlowStageLog = now;
            LOGGER.warn("[GLASS projector] slow stage={} durationMs={} projector={} source={} terrainSource={} buildQueue={}",
                    stage, elapsed / 1_000_000.0D, feed.key.projectorPos(), feed.source.key(),
                    feed.level == activeLevel ? "local" : "remote",
                    feed.renderer == null ? "none" : feed.renderer.getSectionRenderDispatcher().getStats());
        }
    }

    private static RemoteSceneHandle ensureRemoteScene(
            ProjectionFeed feed,
            ChunkPos cameraCenter,
            int requestedRadius
    ) {
        RemoteSceneHandle remoteScene = feed.remoteScene;
        if (remoteScene == null) {
            RemoteSubscriptionId subscription = new RemoteSubscriptionId(
                    feed.key.projectorDimension(),
                    feed.key.projectorPos(),
                    feed.source,
                    ++subscriptionSequence
            );
            remoteScene = RemoteSceneClientManager.acquire(subscription, cameraCenter, requestedRadius);
            feed.remoteScene = remoteScene;
        } else {
            RemoteSceneClientManager.update(remoteScene, cameraCenter, requestedRadius);
        }
        return remoteScene;
    }

    private static void replaceRegistryNow(Map<String, ProjectionSource> replacement) {
        registrySources = replacement;
        for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
            if (!feed.source.equals(replacement.get(feed.source.channel()))) {
                FEEDS.remove(feed.key);
                closeFeed(feed);
            }
        }
        drainRetiredBufferPools();
    }

    private static void chunkUnloadedNow(ClientLevel level, ChunkPos chunkPos) {
        if (level != activeLevel || ProjectionChunkStorage.of(level).retained(chunkPos)) {
            return;
        }
        for (ProjectionFeed feed : FEEDS.values()) {
            if (feed.level != level) {
                continue;
            }
            ChunkPos cameraChunk = new ChunkPos(BlockPos.containing(feed.cameraPosition));
            resetUnloadedChunkSections(feed, chunkPos);
            if (chunkPos.equals(new ChunkPos(feed.source.pos()))
                    || chunkPos.equals(cameraChunk)) {
                feed.ready = false;
            }
        }
    }

    private static void dirtyChunkSections(ProjectionFeed feed, ChunkPos chunkPos) {
        ClientLevel level = feed.level;
        LevelRenderer renderer = feed.renderer;
        if (level == null || renderer == null) {
            return;
        }
        if (!chunkInFeedView(feed, chunkPos.x, chunkPos.z)) {
            return;
        }
        for (int sectionY = level.getMinSection(); sectionY < level.getMaxSection(); sectionY++) {
            SectionRenderDispatcher.RenderSection section = ProjectionSections.find(renderer, chunkPos.x, sectionY, chunkPos.z);
            if (section != null) {
                section.setDirty(false);
            }
        }
    }

    private static void resetUnloadedChunkSections(ProjectionFeed feed, ChunkPos chunkPos) {
        ClientLevel level = feed.level;
        LevelRenderer renderer = feed.renderer;
        if (level == null || renderer == null || !chunkInFeedView(feed, chunkPos.x, chunkPos.z)) {
            return;
        }
        ViewArea viewArea = ((LevelRendererBufferAccessor) renderer).glass$getViewArea();
        if (viewArea == null) {
            return;
        }
        ViewAreaInvoker invoker = (ViewAreaInvoker) viewArea;
        int originX = SectionPos.sectionToBlockCoord(chunkPos.x);
        int originZ = SectionPos.sectionToBlockCoord(chunkPos.z);
        boolean reset = false;
        for (int sectionY = level.getMinSection(); sectionY < level.getMaxSection(); sectionY++) {
            int originY = SectionPos.sectionToBlockCoord(sectionY);
            BlockPos origin = new BlockPos(originX, originY, originZ);
            SectionRenderDispatcher.RenderSection section = invoker.glass$getRenderSectionAt(origin);
            if (section != null && section.getOrigin().equals(origin)) {
                section.setOrigin(originX, originY, originZ);
                reset = true;
            }
        }
        if (reset) {
            renderer.needsUpdate();
        }
    }

    private static boolean sectionInFeedView(
            ProjectionFeed feed,
            int sectionX,
            int sectionY,
            int sectionZ
    ) {
        ClientLevel level = feed.level;
        return level != null
                && sectionY >= level.getMinSection()
                && sectionY < level.getMaxSection()
                && chunkInFeedView(feed, sectionX, sectionZ);
    }

    private static boolean chunkInFeedView(ProjectionFeed feed, int sectionX, int sectionZ) {
        ChunkPos center = viewCenterChunk(feed);
        int renderDistance = feedViewDistance(feed);
        return new ProjectionChunkRegion(center, renderDistance).contains(sectionX, sectionZ);
    }

    private static int feedViewDistance(ProjectionFeed feed) {
        LevelRenderer renderer = feed.renderer;
        if (renderer != null) {
            ViewArea viewArea = ((LevelRendererBufferAccessor) renderer).glass$getViewArea();
            if (viewArea != null) {
                return viewArea.getViewDistance();
            }
        }
        int grantedRadius = feed.remoteScene == null
                ? ProjectionRenderContext.feedRenderDistance(Minecraft.getInstance().options.getEffectiveRenderDistance())
                : feed.remoteScene.grantedRadius();
        return ProjectionRenderContext.feedRenderDistance(
                Minecraft.getInstance().options.getEffectiveRenderDistance(),
                grantedRadius
        );
    }

    private static ChunkPos viewCenterChunk(ProjectionFeed feed) {
        return feed.camera.retainGridCenter(feed.cameraPosition);
    }

    private static void releaseProjectorNow(ResourceKey<Level> dimension, BlockPos projectorPos) {
        for (Map.Entry<FeedKey, ProjectionFeed> entry : List.copyOf(FEEDS.entrySet())) {
            FeedKey key = entry.getKey();
            if (key.projectorDimension().equals(dimension) && key.projectorPos().equals(projectorPos)) {
                FEEDS.remove(entry.getKey());
                closeFeed(entry.getValue());
            }
        }
        PORTAL_SIDES.remove(new ProjectionOwner(dimension, projectorPos));
        drainRetiredBufferPools();
    }

    private static void changeLevelNow(@Nullable ClientLevel level) {
        for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
            FEEDS.remove(feed.key);
            closeFeed(feed);
        }
        FEEDS.clear();
        PORTAL_SIDES.clear();
        activeLevel = level;
        drainRetiredBufferPools();
    }

    private static void resetNow() {
        for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
            FEEDS.remove(feed.key);
            closeFeed(feed);
        }
        FEEDS.clear();
        PORTAL_SIDES.clear();
        activeLevel = null;
        frameSequence = 0L;
        drainRetiredBufferPools();
    }

    private static boolean ensureResources(
            Minecraft minecraft,
            ClientLevel level,
            @Nullable LightTexture lightTexture,
            int grantedRadius,
            ProjectionFeed feed,
            PortalView portalView,
            float partialTick
    ) {
        if (lightTexture == null) {
            return false;
        }
        TerrainKey terrainKey = new TerrainKey(level, viewCenterChunk(feed), grantedRadius, feed.source.pos());
        if (feed.terrain != null && feed.terrain.key.equals(terrainKey) && feed.target != null) {
            resizeTarget(feed, portalView.targetWidth(), portalView.targetHeight());
            return true;
        }
        if (feed.terrain != null && feed.terrain.references == 1 && feed.level == level
                && feed.rendererRadius == grantedRadius && !TERRAINS.containsKey(terrainKey)) {
            TERRAINS.remove(feed.terrain.key, feed.terrain);
            feed.terrain.key = terrainKey;
            TERRAINS.put(terrainKey, feed.terrain);
            resizeTarget(feed, portalView.targetWidth(), portalView.targetHeight());
            return true;
        }
        releaseTerrain(feed);
        feed.ready = false;
        feed.terrainReadyFrames = 0;
        applyCamera(
                feed.camera,
                level,
                minecraft,
                portalView,
                partialTick
        );
        if (feed.target == null) {
            feed.target = new TextureTarget(portalView.targetWidth(), portalView.targetHeight(), true, Minecraft.ON_OSX);
            feed.target.setFilterMode(GL11.GL_LINEAR);
        } else {
            resizeTarget(feed, portalView.targetWidth(), portalView.targetHeight());
        }
        TerrainResources terrain = TERRAINS.get(terrainKey);
        boolean created = terrain == null;
        if (created) {
            terrain = new TerrainResources(terrainKey);
            TERRAINS.put(terrainKey, terrain);
        }
        terrain.references++;
        feed.terrain = terrain;
        feed.level = level;
        feed.lightTexture = lightTexture;
        feed.rendererRadius = grantedRadius;
        if (created) {
            terrain.buffers = new ProjectionRenderBuffers(FEED_BUILD_BUFFERS);
            terrain.bufferCount = terrain.buffers.sectionBufferPool().getFreeBufferCount();
            feed.renderBuffers = terrain.buffers;
            feed.compileBufferCount = terrain.bufferCount;
            feed.renderer = terrain.renderer = new ProjectionLevelRenderer(minecraft, terrain.buffers, grantedRadius);
            try (ProjectionRenderContext.Scope ignored = ProjectionRenderContext.enter(
                    feed.renderer, feed.camera, feed.target, feed.source.pos(), level, lightTexture, grantedRadius
            )) {
                feed.renderer.setLevel(level);
            } finally {
                ClientLevel mainLevel = minecraft.level;
                if (mainLevel != null) {
                    minecraft.getEntityRenderDispatcher().setLevel(mainLevel);
                }
            }
        } else {
            feed.renderer = terrain.renderer;
            feed.renderBuffers = terrain.buffers;
            feed.compileBufferCount = terrain.bufferCount;
        }
        if (feed.remoteScene != null && level == feed.remoteScene.level()) {
            RemoteSceneClientManager.attachRenderer(feed.remoteScene, feed.renderer);
        }
        if (feed.textureProxy == null) {
            feed.textureProxy = new ProjectionTargetTexture(feed);
            feed.textureManager = minecraft.getTextureManager();
            feed.textureManager.register(feed.textureLocation, feed.textureProxy);
        }
        return !created;
    }

    private static void prepareTerrain(ProjectionFeed feed, PortalView portalView) {
        Vec3 position = feed.camera.getPosition();
        Matrix4f modelView = new Matrix4f().rotation(feed.camera.rotation().conjugate(new Quaternionf()));
        Frustum frustum = new Frustum(modelView, portalView.projection());
        frustum.prepare(position.x, position.y, position.z);
        try (ProjectionRenderContext.Scope ignored = ProjectionRenderContext.enter(
                feed.renderer, feed.camera, feed.target, feed.source.pos(), feed.level, feed.lightTexture, feed.rendererRadius
        )) {
            feed.renderer.prepareCamera(feed.camera);
            LevelRendererBufferAccessor accessor = (LevelRendererBufferAccessor) feed.renderer;
            SectionRenderDispatcher dispatcher = feed.renderer.getSectionRenderDispatcher();
            uploadTerrain(dispatcher);
            List<SectionRenderDispatcher.RenderSection> visible = accessor.glass$getVisibleSections();
            visible.clear();
            for (SectionRenderDispatcher.RenderSection section : accessor.glass$getViewArea().sections) {
                BlockPos origin = section.getOrigin();
                if (!feed.level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(origin.getX()), SectionPos.blockToSectionCoord(origin.getZ()))) {
                    continue;
                }
                if (frustum.isVisible(section.getBoundingBox())) {
                    visible.add(section);
                }
            }
            int buildSlots = Math.max(0, feed.compileBufferCount * 2 - dispatcher.getToBatchCount()
                    - (feed.compileBufferCount - dispatcher.getFreeBufferCount()));
            if (feed.terrain.lastBuildFrame != frameSequence) {
                feed.terrain.lastBuildFrame = frameSequence;
                feed.terrain.scheduledBuilds = 0;
            }
            buildSlots = Math.min(buildSlots, Math.max(0, feed.compileBufferCount * 2 - feed.terrain.scheduledBuilds));
            Map<Long, Boolean> chunkReadiness = new HashMap<>();
            List<SectionRenderDispatcher.RenderSection> builds = new ArrayList<>();
            boolean visibleCompiled = true;
            for (SectionRenderDispatcher.RenderSection section : visible) {
                if (section.getCompiled() == SectionRenderDispatcher.CompiledSection.UNCOMPILED) {
                    visibleCompiled = false;
                }
                if (buildSlots > 0 && canCompile(feed, section, chunkReadiness)) {
                    builds.add(section);
                }
            }
            if (visibleCompiled && builds.isEmpty() && buildSlots > 0) {
                for (SectionRenderDispatcher.RenderSection section : accessor.glass$getViewArea().sections) {
                    if (canCompile(feed, section, chunkReadiness)) {
                        builds.add(section);
                    }
                }
            }
            if (!builds.isEmpty()) {
                builds.sort(Comparator.comparing((SectionRenderDispatcher.RenderSection section) ->
                                section.getCompiled() != SectionRenderDispatcher.CompiledSection.UNCOMPILED)
                        .thenComparingDouble(section -> section.getOrigin().distToCenterSqr(position)));
                RenderRegionCache regions = new RenderRegionCache();
                for (int i = 0; i < Math.min(buildSlots, builds.size()) && buildPreparationNanos < BUILD_PREPARATION_BUDGET_NANOS; i++) {
                    SectionRenderDispatcher.RenderSection section = builds.get(i);
                    long started = System.nanoTime();
                    section.rebuildSectionAsync(dispatcher, regions);
                    section.setNotDirty();
                    buildPreparationNanos += System.nanoTime() - started;
                    feed.terrain.scheduledBuilds++;
                }
            }
        }
        feed.terrainReadyFrames = terrainReady(feed) ? Math.min(2, feed.terrainReadyFrames + 1) : 0;
    }

    private static void uploadTerrain(SectionRenderDispatcher dispatcher) {
        var pending = ((SectionRenderDispatcherAccessor) dispatcher).glass$getPendingUploads();
        while (uploadNanos < UPLOAD_BUDGET_NANOS) {
            Runnable upload = pending.poll();
            if (upload == null) {
                break;
            }
            long started = System.nanoTime();
            upload.run();
            uploadNanos += System.nanoTime() - started;
        }
    }

    private static boolean canCompile(ProjectionFeed feed, SectionRenderDispatcher.RenderSection section, Map<Long, Boolean> chunkReadiness) {
        BlockPos origin = section.getOrigin();
        return section.isDirty()
                && chunkReadiness.computeIfAbsent(ChunkPos.asLong(SectionPos.blockToSectionCoord(origin.getX()), SectionPos.blockToSectionCoord(origin.getZ())),
                packed -> chunkReady(feed, new ChunkPos(packed)))
                && section.hasAllNeighbors()
                && feed.level.getLightEngine().lightOnInSection(SectionPos.of(origin));
    }

    private static boolean terrainReady(ProjectionFeed feed) {
        if (feed.level == activeLevel ? !localChunksReady(feed.level, viewCenterChunk(feed), feed.rendererRadius + ProjectionChunkRegion.NEIGHBOR_PADDING)
                : !feed.remoteScene.isComplete()) {
            return false;
        }
        List<SectionRenderDispatcher.RenderSection> sections = ((LevelRendererBufferAccessor) feed.renderer).glass$getVisibleSections();
        if (sections.isEmpty()) {
            return false;
        }
        for (SectionRenderDispatcher.RenderSection section : sections) {
            if (section.getCompiled() == SectionRenderDispatcher.CompiledSection.UNCOMPILED) {
                return false;
            }
        }
        return true;
    }

    private static void renderFeed(
            Minecraft minecraft,
            GameRenderer gameRenderer,
            float partialTick,
            ProjectionFeed feed,
            PortalView portalView,
            RenderTarget mainTarget
    ) {
        ClientLevel level = feed.level;
        LevelRenderer renderer = feed.renderer;
        TextureTarget target = feed.target;
        LightTexture lightTexture = feed.lightTexture;
        RemoteSceneHandle remoteScene = feed.remoteScene;
        if (level == null
                || renderer == null
                || target == null
                || lightTexture == null
                || remoteScene == null
                || minecraft.player == null) {
            return;
        }
        if (!feed.ready && feed.terrainReadyFrames < 2) {
            return;
        }

        Vec3 cameraPosition = feed.camera.getPosition();
        int renderDistanceChunks = ProjectionRenderContext.feedRenderDistance(
                minecraft.options.getEffectiveRenderDistance(),
                feed.rendererRadius
        );
        float renderDistanceBlocks = renderDistanceChunks * 16.0F;
        Matrix4f projection = new Matrix4f(portalView.projection());
        Quaternionf inverseRotation = feed.camera.rotation().conjugate(new Quaternionf());
        Matrix4f modelView = new Matrix4f().rotation(inverseRotation);

        boolean worldFog = level.effects().isFoggyAt(Mth.floor(cameraPosition.x), Mth.floor(cameraPosition.y));
        FogRenderer.setupColor(
                feed.camera,
                partialTick,
                level,
                renderDistanceChunks,
                0.0F
        );
        FogRenderer.levelFogColor();

        target.setClearColor(FogRenderer.fogRed, FogRenderer.fogGreen, FogRenderer.fogBlue, 1.0F);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        target.clear(Minecraft.ON_OSX);
        target.bindWrite(true);
        RenderSystem.setShaderGameTime(level.getGameTime(), partialTick);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        try (ProjectionRenderContext.Scope ignored = ProjectionRenderContext.enter(
                renderer,
                feed.camera,
                target,
                feed.source.pos(),
                level,
                lightTexture,
                feed.rendererRadius
        )) {
            lightTexture.updateLightTexture(partialTick);
            lightTexture.turnOnLightLayer();
            RenderSystem.setShader(GameRenderer::getPositionShader);
            renderer.renderSky(
                    modelView,
                    projection,
                    partialTick,
                    feed.camera,
                    worldFog,
                    () -> FogRenderer.setupFog(
                            feed.camera,
                            FogRenderer.FogMode.FOG_SKY,
                            renderDistanceBlocks,
                            worldFog,
                            partialTick
                    )
            );
            restoreProjectionTarget(target);
            FogRenderer.setupFog(
                    feed.camera,
                    FogRenderer.FogMode.FOG_TERRAIN,
                    Math.max(renderDistanceBlocks, 32.0F),
                    worldFog,
                    partialTick
            );

            for (RenderType layer : TERRAIN_LAYERS) {
                if (layer == RenderType.translucent()) {
                    renderEntities(minecraft, gameRenderer, feed, portalView, modelView, projection, partialTick);
                    if (feed.terrain.references > 1) {
                        sortSharedTransparency(feed);
                    }
                }
                renderLayer(feed.renderer, layer, cameraPosition, modelView, projection, target);
            }

            if (minecraft.options.getCloudsType() != CloudStatus.OFF) {
                renderer.renderClouds(
                        new PoseStack(),
                        modelView,
                        projection,
                        partialTick,
                        cameraPosition.x,
                        cameraPosition.y,
                        cameraPosition.z
                );
                restoreProjectionTarget(target);
            }

            feed.ready = true;
            feed.available = true;
            feed.failed = false;
            feed.nextRetryFrame = 0L;
        } finally {
            mainTarget.bindWrite(true);
            gameRenderer.lightTexture().turnOnLightLayer();
        }
    }

    private static void renderEntities(Minecraft minecraft, GameRenderer gameRenderer, ProjectionFeed feed,
                                       PortalView portalView, Matrix4f modelView, Matrix4f projection, float partialTick) {
        Vec3 position = feed.camera.getPosition();
        Frustum frustum = new Frustum(modelView, portalView.projection());
        frustum.prepare(position.x, position.y, position.z);
        var dispatcher = minecraft.getEntityRenderDispatcher();
        var blockDispatcher = minecraft.getBlockEntityRenderDispatcher();
        var buffers = feed.renderBuffers.bufferSource();
        PoseStack poses = new PoseStack();
        RenderSystem.getModelViewStack().pushMatrix();
        try {
            setupEntityLighting(feed.level);
            RenderSystem.getModelViewStack().set(modelView);
            RenderSystem.applyModelViewMatrix();
            gameRenderer.resetProjectionMatrix(projection);
            dispatcher.prepare(feed.level, feed.camera, null);
            blockDispatcher.prepare(feed.level, feed.camera, null);
            LevelRendererInvoker invoker = (LevelRendererInvoker) feed.renderer;
            for (Entity entity : feed.level.entitiesForRendering()) {
                if (!entity.isRemoved() && dispatcher.shouldRender(entity, frustum, position.x, position.y, position.z)) {
                    invoker.glass$renderEntity(entity, position.x, position.y, position.z, partialTick, poses, buffers);
                }
            }
            LevelRendererBufferAccessor accessor = (LevelRendererBufferAccessor) feed.renderer;
            Set<BlockEntity> blockEntities = new LinkedHashSet<>();
            for (SectionRenderDispatcher.RenderSection section : accessor.glass$getVisibleSections()) {
                blockEntities.addAll(section.getCompiled().getRenderableBlockEntities());
            }
            synchronized (accessor.glass$getGlobalBlockEntities()) {
                blockEntities.addAll(accessor.glass$getGlobalBlockEntities());
            }
            for (BlockEntity blockEntity : blockEntities) {
                if (blockEntity.isRemoved() || blockEntity instanceof ProjectorBlockEntity
                        || blockEntity.getBlockPos().equals(feed.source.pos())) {
                    continue;
                }
                BlockPos pos = blockEntity.getBlockPos();
                poses.pushPose();
                try {
                    poses.translate(pos.getX() - position.x, pos.getY() - position.y, pos.getZ() - position.z);
                    blockDispatcher.render(blockEntity, partialTick, poses, buffers);
                } finally {
                    poses.popPose();
                }
            }
            buffers.endBatch();
        } finally {
            dispatcher.prepare(minecraft.level, gameRenderer.getMainCamera(), minecraft.crosshairPickEntity);
            blockDispatcher.prepare(minecraft.level, gameRenderer.getMainCamera(), minecraft.hitResult);
            RenderSystem.getModelViewStack().popMatrix();
            RenderSystem.applyModelViewMatrix();
            gameRenderer.resetProjectionMatrix(portalView.projection());
            restoreProjectionTarget(feed.target);
        }
    }

    private static void sortSharedTransparency(ProjectionFeed feed) {
        TerrainResources terrain = feed.terrain;
        if (terrain.sortBuffer == null) {
            terrain.sortBuffer = new ByteBufferBuilder(1536);
        }
        Vec3 camera = feed.camera.getPosition();
        try {
            for (SectionRenderDispatcher.RenderSection section : ((LevelRendererBufferAccessor) terrain.renderer).glass$getVisibleSections()) {
                MeshData.SortState sort = ((CompiledSectionAccessor) section.getCompiled()).glass$getTransparencyState();
                if (sort == null) {
                    continue;
                }
                BlockPos origin = section.getOrigin();
                VertexBuffer buffer = section.getBuffer(RenderType.translucent());
                buffer.bind();
                ByteBufferBuilder.Result indices = sort.buildSortedIndexBuffer(terrain.sortBuffer, VertexSorting.byDistance(
                        (float) (camera.x - origin.getX()), (float) (camera.y - origin.getY()), (float) (camera.z - origin.getZ())));
                if (indices != null) {
                    buffer.uploadIndexBuffer(indices);
                }
            }
        } finally {
            VertexBuffer.unbind();
            terrain.sortBuffer.clear();
        }
    }

    private static PortalView configurePortalCamera(
            ProjectionFeed feed,
            Camera viewerCamera,
            Matrix4f mainProjection,
            RenderTarget mainTarget
    ) {
        ProjectionView view = feed.view;
        Vec3 surfaceNormal = directionVector(view.facing());
        Vec3 projectorAnchor = Vec3.atCenterOf(view.projectorPos())
                .add(surfaceNormal.scale(PROJECTOR_PLANE_OFFSET));
        Vec3 viewerOffset = viewerCamera.getPosition().subtract(projectorAnchor);
        double side = feed.sideState.portalSide == 0.0D
                ? classifyPortalSide(view, viewerCamera.getPosition())
                : feed.sideState.portalSide;
        Vec3 sourceRight = directionVector(view.uDirection()).scale(side);
        Vec3 sourceUp = directionVector(view.vDirection());
        Vec3 sourceBack = surfaceNormal.scale(side);

        Vec3 destinationLook = directionVector(feed.source.facing().getOpposite());
        Vec3 destinationUp = feed.source.facing().getAxis().isHorizontal()
                ? directionVector(Direction.UP)
                : directionVector(Direction.NORTH);
        Vec3 destinationRight = destinationLook.cross(destinationUp);
        Vec3 destinationBack = destinationLook.scale(-1.0D);

        Quaternionf sourceFrame = frameRotation(sourceRight, sourceUp, sourceBack);
        Quaternionf destinationFrame = frameRotation(destinationRight, destinationUp, destinationBack);
        Quaternionf portalRotation = new Quaternionf(destinationFrame)
                .mul(new Quaternionf(sourceFrame).conjugate());
        Quaternionf cameraRotation = portalRotation
                .mul(new Quaternionf(viewerCamera.rotation()))
                .normalize();

        double horizontalOffset = viewerOffset.dot(sourceRight);
        double verticalOffset = viewerOffset.dot(sourceUp);
        double normalOffset = viewerOffset.dot(sourceBack);
        Vec3 destinationAnchor = cameraPosition(feed.source);
        Vec3 dynamicPosition = destinationAnchor
                .add(destinationRight.scale(horizontalOffset))
                .add(destinationUp.scale(verticalOffset))
                .add(destinationBack.scale(normalOffset));
        TargetSize targetSize = targetSize(mainTarget);
        PortalView portalView = new PortalView(
                dynamicPosition,
                cameraRotation,
                mainProjection,
                targetSize.width(),
                targetSize.height()
        );
        feed.camera.setPose(portalView.cameraPosition(), portalView.cameraRotation());
        feed.camera.setProjection(portalView.projection());
        feed.cameraPosition = dynamicPosition;
        return portalView;
    }

    private static void applyCamera(
            ProjectionCamera camera,
            ClientLevel level,
            Minecraft minecraft,
            PortalView portalView,
            float partialTick
    ) {
        camera.setup(level, minecraft.player, false, false, partialTick);
        camera.setPose(portalView.cameraPosition(), portalView.cameraRotation());
        camera.setProjection(portalView.projection());
    }

    private static Quaternionf frameRotation(Vec3 right, Vec3 up, Vec3 back) {
        return new Quaternionf().setFromNormalized(new Matrix3f(
                (float) right.x, (float) right.y, (float) right.z,
                (float) up.x, (float) up.y, (float) up.z,
                (float) back.x, (float) back.y, (float) back.z
        ));
    }

    private static Vec3 directionVector(Direction direction) {
        return new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    private static PortalSideState portalSideState(ProjectionView view) {
        ProjectionOwner owner = new ProjectionOwner(view.dimension(), view.projectorPos());
        return PORTAL_SIDES.computeIfAbsent(owner, ignored -> new PortalSideState());
    }

    private static void preparePortalSide(
            PortalSideState sideState,
            ProjectionView view,
            Vec3 viewerPosition
    ) {
        ProjectionView previousView = sideState.view;
        if (view.equals(previousView)) {
            return;
        }
        boolean rootFrameChanged = previousView != null
                && (previousView.facing() != view.facing() || !previousView.origin().equals(view.origin()));
        sideState.view = view;
        sideState.lastViewerPosition = pointOnSurface(view, viewerPosition) ? null : viewerPosition;
        sideState.lastUpdateFrame = Long.MIN_VALUE;
        if (rootFrameChanged) {
            sideState.portalSide = 0.0D;
            sideState.lastViewerPosition = null;
        }
    }

    private static void updatePortalSide(PortalSideState sideState, Vec3 viewerPosition) {
        if (sideState.lastUpdateFrame == frameSequence) {
            return;
        }
        sideState.lastUpdateFrame = frameSequence;
        ProjectionView view = sideState.view;
        if (view == null) {
            return;
        }
        if (sideState.portalSide == 0.0D) {
            if (pointOnSurface(view, viewerPosition)) {
                sideState.lastViewerPosition = null;
                return;
            }
            sideState.portalSide = classifyPortalSide(view, viewerPosition);
            sideState.lastViewerPosition = viewerPosition;
            return;
        }

        if (pointOnSurface(view, viewerPosition)) {
            return;
        }
        Vec3 previousPosition = sideState.lastViewerPosition;
        sideState.lastViewerPosition = viewerPosition;
        if (previousPosition == null || previousPosition.distanceToSqr(viewerPosition) <= SIDE_EPSILON * SIDE_EPSILON) {
            return;
        }
        if (!segmentIntersectsBounds(view.bounds(), previousPosition, viewerPosition)) {
            return;
        }
        if ((countSurfaceCrossings(view, previousPosition, viewerPosition) & 1) != 0) {
            sideState.portalSide = -sideState.portalSide;
        }
    }

    private static double classifyPortalSide(ProjectionView view, Vec3 viewerPosition) {
        double closestDistance = Double.POSITIVE_INFINITY;
        double closestSignedDistance = 0.0D;
        for (ProjectionSurface.Face face : view.faces()) {
            double distance = distanceToFaceSquared(face, viewerPosition);
            double signedDistance = signedFaceDistance(face, viewerPosition);
            if (distance < closestDistance - DISTANCE_EPSILON
                    || (Math.abs(distance - closestDistance) <= DISTANCE_EPSILON
                    && signedDistance > closestSignedDistance)) {
                closestDistance = distance;
                closestSignedDistance = signedDistance;
            }
        }
        if (closestDistance == Double.POSITIVE_INFINITY) {
            Vec3 normal = directionVector(view.facing());
            Vec3 anchor = Vec3.atCenterOf(view.projectorPos()).add(normal.scale(PROJECTOR_PLANE_OFFSET));
            closestSignedDistance = viewerPosition.subtract(anchor).dot(normal);
        }
        return closestSignedDistance < 0.0D ? -1.0D : 1.0D;
    }

    private static boolean pointOnSurface(ProjectionView view, Vec3 point) {
        if (!view.bounds().contains(point)
                || (!nearInteger(point.x) && !nearInteger(point.y) && !nearInteger(point.z))) {
            return false;
        }
        for (ProjectionSurface.Face face : view.faces()) {
            if (Math.abs(signedFaceDistance(face, point)) <= SIDE_EPSILON
                    && pointWithinFace(face, point.x, point.y, point.z)) {
                return true;
            }
        }
        return false;
    }

    private static boolean nearInteger(double value) {
        return Math.abs(value - Math.rint(value)) <= SIDE_EPSILON;
    }

    private static double distanceToFaceSquared(ProjectionSurface.Face face, Vec3 point) {
        BlockPos position = face.position();
        Direction.Axis axis = face.normal().getAxis();
        double x = clamp(point.x, position.getX(), position.getX() + 1.0D);
        double y = clamp(point.y, position.getY(), position.getY() + 1.0D);
        double z = clamp(point.z, position.getZ(), position.getZ() + 1.0D);
        double plane = facePlane(face);
        switch (axis) {
            case X -> x = plane;
            case Y -> y = plane;
            case Z -> z = plane;
        }
        double dx = point.x - x;
        double dy = point.y - y;
        double dz = point.z - z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double signedFaceDistance(ProjectionSurface.Face face, Vec3 point) {
        double coordinate = coordinate(point, face.normal().getAxis());
        double direction = face.normal().getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0D : -1.0D;
        return (coordinate - facePlane(face)) * direction;
    }

    private static int countSurfaceCrossings(ProjectionView view, Vec3 start, Vec3 end) {
        double dx = end.x - start.x;
        double dy = end.y - start.y;
        double dz = end.z - start.z;
        List<Double> parameters = new ArrayList<>();
        for (ProjectionSurface.Face face : view.faces()) {
            Direction.Axis axis = face.normal().getAxis();
            double delta = switch (axis) {
                case X -> dx;
                case Y -> dy;
                case Z -> dz;
            };
            if (Math.abs(delta) <= SIDE_EPSILON) {
                continue;
            }
            double startCoordinate = coordinate(start, axis);
            double parameter = (facePlane(face) - startCoordinate) / delta;
            if (parameter <= SIDE_EPSILON || parameter > 1.0D + SIDE_EPSILON) {
                continue;
            }
            double x = start.x + dx * parameter;
            double y = start.y + dy * parameter;
            double z = start.z + dz * parameter;
            if (pointWithinFace(face, x, y, z)) {
                parameters.add(parameter);
            }
        }
        parameters.sort(Double::compare);
        int crossings = 0;
        double segmentLength = Math.sqrt(start.distanceToSqr(end));
        double parameterOffset = Math.min(
                0.25D,
                Math.max(CROSSING_EPSILON * 4.0D, 1.0E-4D / segmentLength)
        );
        int index = 0;
        while (index < parameters.size()) {
            double parameter = parameters.get(index);
            int next = index + 1;
            while (next < parameters.size() && parameters.get(next) - parameter <= CROSSING_EPSILON) {
                next++;
            }
            Vec3 before = pointAlongSegment(start, end, parameter - parameterOffset);
            Vec3 after = pointAlongSegment(start, end, parameter + parameterOffset);
            if (classifyPortalSide(view, before) != classifyPortalSide(view, after)) {
                crossings++;
            }
            index = next;
        }
        return crossings;
    }

    private static Vec3 pointAlongSegment(Vec3 start, Vec3 end, double parameter) {
        return new Vec3(
                Mth.lerp(parameter, start.x, end.x),
                Mth.lerp(parameter, start.y, end.y),
                Mth.lerp(parameter, start.z, end.z)
        );
    }

    private static boolean pointWithinFace(ProjectionSurface.Face face, double x, double y, double z) {
        BlockPos position = face.position();
        return switch (face.normal().getAxis()) {
            case X -> withinFaceCoordinate(y, position.getY()) && withinFaceCoordinate(z, position.getZ());
            case Y -> withinFaceCoordinate(x, position.getX()) && withinFaceCoordinate(z, position.getZ());
            case Z -> withinFaceCoordinate(x, position.getX()) && withinFaceCoordinate(y, position.getY());
        };
    }

    private static boolean withinFaceCoordinate(double value, int minimum) {
        return value >= minimum - SIDE_EPSILON && value <= minimum + 1.0D + SIDE_EPSILON;
    }

    private static boolean segmentIntersectsBounds(AABB bounds, Vec3 start, Vec3 end) {
        return bounds.contains(start) || bounds.contains(end) || bounds.clip(start, end).isPresent();
    }

    private static double facePlane(ProjectionSurface.Face face) {
        int coordinate = switch (face.normal().getAxis()) {
            case X -> face.position().getX();
            case Y -> face.position().getY();
            case Z -> face.position().getZ();
        };
        return coordinate + (face.normal().getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1.0D : 0.0D);
    }

    private static double coordinate(Vec3 point, Direction.Axis axis) {
        return switch (axis) {
            case X -> point.x;
            case Y -> point.y;
            case Z -> point.z;
        };
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static TargetSize targetSize(RenderTarget mainTarget) {
        return new TargetSize(
                Math.max(1, mainTarget.viewWidth),
                Math.max(1, mainTarget.viewHeight)
        );
    }

    private static void resizeTarget(ProjectionFeed feed, int width, int height) {
        TextureTarget target = feed.target;
        if (target == null || target.viewWidth == width && target.viewHeight == height) {
            return;
        }
        target.resize(width, height, Minecraft.ON_OSX);
        target.setFilterMode(GL11.GL_LINEAR);
        feed.ready = false;
    }

    private static boolean isDescriptorValid(ProjectionSource source) {
        return source.channel() != null
                && !source.channel().isBlank()
                && source.dimension() != null
                && source.pos() != null
                && source.facing() != null
                && source.revision() >= 0L;
    }

    private static void markUnavailable(ProjectionFeed feed, RenderTarget mainTarget) {
        boolean clear = feed.ready || feed.available;
        feed.ready = false;
        feed.available = false;
        if (!clear || feed.target == null) {
            return;
        }
        feed.target.setClearColor(0.0F, 0.0F, 0.0F, 1.0F);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        feed.target.clear(Minecraft.ON_OSX);
        mainTarget.bindWrite(true);
    }

    private static void renderLayer(
            ProjectionLevelRenderer renderer,
            RenderType layer,
            Vec3 cameraPosition,
            Matrix4f modelView,
            Matrix4f projection,
            TextureTarget target
    ) {
        boolean completed = false;
        try {
            renderer.renderTerrainLayer(layer, cameraPosition, modelView, projection);
            completed = true;
        } finally {
            if (!completed) {
                layer.clearRenderState();
            }
            target.bindWrite(false);
        }
    }

    private static void restoreProjectionTarget(TextureTarget target) {
        target.bindWrite(false);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        VertexBuffer.unbind();
    }

    private static void restoreMainRenderState(
            RenderTarget mainTarget,
            GameRenderer gameRenderer,
            Matrix4f mainProjection,
            Matrix4f mainModelView
    ) {
        if (ProjectionRenderContext.isActive()) {
            LOGGER.error("Projection render context escaped its scope");
        }
        Minecraft minecraft = gameRenderer.getMinecraft();
        if (minecraft.level != null) {
            minecraft.getEntityRenderDispatcher().setLevel(minecraft.level);
            setupEntityLighting(minecraft.level);
        }
        gameRenderer.lightTexture().turnOnLightLayer();
        mainTarget.bindWrite(true);
        gameRenderer.resetProjectionMatrix(new Matrix4f(mainProjection));
        RenderSystem.getModelViewStack().set(mainModelView);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableCull();
        RenderSystem.disablePolygonOffset();
        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.resetTextureMatrix();
        FogRenderer.setupNoFog();
        VertexBuffer.unbind();
    }

    private static void setupEntityLighting(ClientLevel level) {
        if (level.effects().constantAmbientLight()) {
            Lighting.setupNetherLevel();
        } else {
            Lighting.setupLevel();
        }
    }

    private static void closeFeed(ProjectionFeed feed) {
        long started = System.nanoTime();
        releaseRemoteFeed(feed);
        ProjectionSurfaceRenderer.releaseTexture(feed.textureLocation);
        reportSlowStage(feed, "release", started);
    }

    private static void releaseRemoteFeed(ProjectionFeed feed) {
        disposeFeedResources(feed);
        RemoteSceneHandle remoteScene = feed.remoteScene;
        feed.remoteScene = null;
        if (remoteScene != null) {
            RemoteSceneClientManager.release(remoteScene);
        }
    }

    private static void disposeFeedResources(ProjectionFeed feed) {
        feed.ready = false;
        feed.terrainReadyFrames = 0;
        feed.available = false;
        feed.failed = false;

        TextureManager textureManager = feed.textureManager;
        feed.textureManager = null;
        ProjectionTargetTexture textureProxy = feed.textureProxy;
        feed.textureProxy = null;
        if (textureManager != null && textureProxy != null) {
            cleanup(feed, "texture registration", () -> textureManager.release(feed.textureLocation));
            cleanup(feed, "texture proxy", textureProxy::close);
        }

        releaseTerrain(feed);

        TextureTarget target = feed.target;
        feed.target = null;
        if (target != null) {
            cleanup(feed, "render target", target::destroyBuffers);
        }
        feed.camera.reset();
    }

    private static void releaseTerrain(ProjectionFeed feed) {
        TerrainResources terrain = feed.terrain;
        if (feed.renderer != null && feed.remoteScene != null && feed.level == feed.remoteScene.level()) {
            cleanup(feed, "remote renderer attachment", () -> RemoteSceneClientManager.detachRenderer(feed.remoteScene, feed.renderer));
        }
        feed.terrain = null;
        feed.renderer = null;
        feed.renderBuffers = null;
        feed.compileBufferCount = 0;
        feed.level = null;
        feed.lightTexture = null;
        feed.rendererRadius = 0;
        if (terrain == null || --terrain.references > 0) {
            return;
        }
        TERRAINS.remove(terrain.key, terrain);
        if (terrain.renderer != null) {
            cleanup(feed, "renderer level", () -> terrain.renderer.setLevel(null));
            cleanup(feed, "renderer global buffers", () -> closeGlobalBuffers(terrain.renderer));
            cleanup(feed, "level renderer", terrain.renderer::close);
            ClientLevel currentLevel = Minecraft.getInstance().level;
            if (currentLevel != null) {
                Minecraft.getInstance().getEntityRenderDispatcher().setLevel(currentLevel);
            }
        }
        if (terrain.buffers != null) {
            cleanup(feed, "render buffers", () -> retireRenderBuffers(terrain.buffers, terrain.bufferCount));
        }
        if (terrain.sortBuffer != null) {
            cleanup(feed, "transparency sort buffer", terrain.sortBuffer::close);
        }
    }

    private static void cleanup(ProjectionFeed feed, String resource, Runnable operation) {
        try {
            operation.run();
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to release projection feed {} {}", feed.source.key(), resource, exception);
        }
    }

    private static void closeGlobalBuffers(LevelRenderer renderer) {
        LevelRendererBufferAccessor buffers = (LevelRendererBufferAccessor) renderer;
        closeBuffer(buffers.glass$getStarBuffer());
        closeBuffer(buffers.glass$getSkyBuffer());
        closeBuffer(buffers.glass$getDarkBuffer());
        closeBuffer(buffers.glass$getCloudBuffer());
    }

    private static void closeBuffer(VertexBuffer buffer) {
        if (buffer != null) {
            buffer.close();
        }
    }

    private static void retireRenderBuffers(RenderBuffers renderBuffers, int bufferCount) {
        Set<ByteBufferBuilder> builders = Collections.newSetFromMap(new IdentityHashMap<>());
        SectionBufferBuilderPack fixedBufferPack = renderBuffers.fixedBufferPack();
        for (RenderType renderType : RenderType.chunkBufferLayers()) {
            builders.add(fixedBufferPack.buffer(renderType));
        }
        addBufferSourceBuilders(builders, renderBuffers.bufferSource());
        addBufferSourceBuilders(builders, renderBuffers.crumblingBufferSource());
        builders.forEach(ByteBufferBuilder::close);
        SectionBufferBuilderPool pool = renderBuffers.sectionBufferPool();
        if (pool.getFreeBufferCount() >= bufferCount) {
            closeAvailablePoolBuffers(pool);
        } else {
            RETIRED_BUFFER_POOLS.add(new RetiredBufferPool(pool, bufferCount));
        }
    }

    private static void addBufferSourceBuilders(
            Set<ByteBufferBuilder> builders,
            net.minecraft.client.renderer.MultiBufferSource.BufferSource source
    ) {
        BufferSourceAccessor accessor = (BufferSourceAccessor) source;
        builders.add(accessor.glass$getSharedBuffer());
        builders.addAll(accessor.glass$getFixedBuffers().values());
    }

    private static void drainRetiredBufferPools() {
        Iterator<RetiredBufferPool> iterator = RETIRED_BUFFER_POOLS.iterator();
        while (iterator.hasNext()) {
            RetiredBufferPool retired = iterator.next();
            if (retired.pool().getFreeBufferCount() >= retired.bufferCount()) {
                closeAvailablePoolBuffers(retired.pool());
                iterator.remove();
            }
        }
        if (!RETIRED_BUFFER_POOLS.isEmpty()) {
            scheduleRetiredBufferDrain();
        }
    }

    private static void scheduleRetiredBufferDrain() {
        if (retiredDrainScheduled) {
            return;
        }
        retiredDrainScheduled = true;
        CompletableFuture.delayedExecutor(10L, TimeUnit.MILLISECONDS).execute(() ->
                RenderSystem.recordRenderCall(() -> {
                    retiredDrainScheduled = false;
                    drainRetiredBufferPools();
                })
        );
    }

    private static void closeAvailablePoolBuffers(SectionBufferBuilderPool pool) {
        SectionBufferBuilderPack pack;
        while ((pack = pool.acquire()) != null) {
            pack.close();
        }
    }

    private static ResourceLocation nextTextureLocation() {
        long sequence = ++textureSequence;
        return ResourceLocation.fromNamespaceAndPath(
                "glass",
                "projection/feed/" + Long.toUnsignedString(sequence, 36)
        );
    }

    private record RetiredBufferPool(SectionBufferBuilderPool pool, int bufferCount) {
    }

    private record TerrainKey(ClientLevel level, ChunkPos center, int radius, BlockPos hiddenBlock) {
    }

    private static final class TerrainResources {
        private TerrainKey key;
        private ProjectionLevelRenderer renderer;
        private RenderBuffers buffers;
        private ByteBufferBuilder sortBuffer;
        private int bufferCount;
        private int references;
        private final long createdNanos = System.nanoTime();
        private long lastBuildFrame = -1L;
        private int scheduledBuilds;

        private TerrainResources(TerrainKey key) {
            this.key = key;
        }
    }

    private static Vec3 cameraPosition(ProjectionSource source) {
        Direction facing = source.facing().getOpposite();
        return Vec3.atCenterOf(source.pos()).add(
                facing.getStepX() * CAMERA_FACE_OFFSET,
                facing.getStepY() * CAMERA_FACE_OFFSET,
                facing.getStepZ() * CAMERA_FACE_OFFSET
        );
    }

    private static void runOnRenderThread(Runnable operation) {
        if (RenderSystem.isOnRenderThread()) {
            operation.run();
        } else {
            RenderSystem.recordRenderCall(operation::run);
        }
    }

    private record FeedKey(
            ProjectionSource.Key source,
            ResourceKey<Level> projectorDimension,
            BlockPos projectorPos
    ) {
        private FeedKey {
            projectorPos = projectorPos.immutable();
        }
    }

    private record ProjectionOwner(ResourceKey<Level> dimension, BlockPos projectorPos) {
        private ProjectionOwner {
            projectorPos = projectorPos.immutable();
        }
    }

    private record ProjectionView(
            ResourceKey<Level> dimension,
            BlockPos projectorPos,
            BlockPos origin,
            Direction facing,
            Direction uDirection,
            Direction vDirection,
            List<ProjectionSurface.Face> faces,
            AABB bounds,
            long topologyHash,
            long version
    ) {
        private ProjectionView {
            projectorPos = projectorPos.immutable();
            origin = origin.immutable();
        }

        private static ProjectionView create(
                ResourceKey<Level> dimension,
                BlockPos projectorPos,
                ProjectionSurface surface
        ) {
            return new ProjectionView(
                    dimension,
                    projectorPos,
                    surface.origin(),
                    surface.facing(),
                    surface.uDirection(),
                    surface.vDirection(),
                    surface.faces(),
                    surface.renderBounds(),
                    surface.topologyHash(),
                    surface.version()
            );
        }
    }

    private record TargetSize(int width, int height) {
    }

    private record PortalView(
            Vec3 cameraPosition,
            Quaternionf cameraRotation,
            Matrix4f projection,
            int targetWidth,
            int targetHeight
    ) {
        private PortalView {
            cameraRotation = new Quaternionf(cameraRotation);
            projection = new Matrix4f(projection);
        }
    }

    private static final class PortalSideState {
        private ProjectionView view;
        private Vec3 lastViewerPosition;
        private double portalSide;
        private long lastUpdateFrame = Long.MIN_VALUE;
    }

    public static final class ProjectionFeed {
        private final FeedKey key;
        private final ProjectionSource source;
        private final ResourceLocation textureLocation;
        private final ProjectionCamera camera = new ProjectionCamera();
        private final PortalSideState sideState;
        private ProjectionView view;
        private Vec3 cameraPosition;
        private long lastRequestFrame = -1L;
        private long lastRequestNanos;
        private long lastVisibleFrame = -1L;
        private long lastSlowStageLog;
        private long nextRetryFrame;
        private String diagnosticStage = "requested";
        private boolean ready;
        private int terrainReadyFrames;
        private boolean available;
        private boolean failed;
        private RemoteSceneHandle remoteScene;
        private ClientLevel level;
        private LightTexture lightTexture;
        private int rendererRadius;
        private RenderBuffers renderBuffers;
        private TerrainResources terrain;
        private int compileBufferCount;
        private ProjectionLevelRenderer renderer;
        private TextureTarget target;
        private TextureManager textureManager;
        private ProjectionTargetTexture textureProxy;

        private ProjectionFeed(
                FeedKey key,
                ProjectionSource source,
                ProjectionView view,
                PortalSideState sideState,
                ResourceLocation textureLocation
        ) {
            this.key = key;
            this.source = source;
            this.view = view;
            this.sideState = sideState;
            this.textureLocation = textureLocation;
            this.cameraPosition = ProjectionRenderManager.cameraPosition(source);
        }

        public ProjectionSource source() {
            return source;
        }

        public ResourceLocation textureLocation() {
            return textureLocation;
        }

        public int colorTextureId() {
            TextureTarget currentTarget = target;
            return currentTarget == null ? 0 : currentTarget.getColorTextureId();
        }

        public boolean isReady() {
            return ProjectionRenderManager.isReady(this);
        }

        public boolean isFailed() {
            return failed;
        }

        public String diagnosticStage() {
            return diagnosticStage;
        }

        public String diagnostics() {
            int visible = 0;
            int dirty = 0;
            int uncompiled = 0;
            BlockPos firstBlocked = null;
            if (renderer != null) {
                List<SectionRenderDispatcher.RenderSection> sections = ((LevelRendererBufferAccessor) renderer).glass$getVisibleSections();
                visible = sections.size();
                for (SectionRenderDispatcher.RenderSection section : sections) {
                    boolean needsCompile = section.getCompiled() == SectionRenderDispatcher.CompiledSection.UNCOMPILED;
                    if (section.isDirty()) {
                        dirty++;
                    }
                    if (needsCompile) {
                        uncompiled++;
                    }
                    if (firstBlocked == null && needsCompile) {
                        firstBlocked = section.getOrigin().immutable();
                    }
                }
            }
            return "source=" + source + " camera=" + cameraPosition
                    + " terrainSource=" + (level == null ? "none" : level == activeLevel ? "local" : "remote")
                    + " requestAgeFrames=" + (lastRequestFrame < 0L ? -1L : frameSequence - lastRequestFrame)
                    + " visibleAgeFrames=" + (lastVisibleFrame < 0L ? -1L : frameSequence - lastVisibleFrame)
                    + " retryFrames=" + Math.max(0L, nextRetryFrame - frameSequence)
                    + " terrainReadyFrames=" + terrainReadyFrames + " visibleSections=" + visible
                    + " dirtySections=" + dirty + " uncompiledSections=" + uncompiled
                    + " firstBlockedSection=" + firstBlocked
                    + " firstBlockedChunkReady=" + (firstBlocked != null && chunkReady(this, new ChunkPos(firstBlocked)))
                    + " compileQueueEmpty=" + (renderer != null && renderer.hasRenderedAllSections())
                    + " buildBuffers=" + compileBufferCount
                    + " terrainUsers=" + (terrain == null ? 0 : terrain.references)
                    + " terrainAgeMs=" + (terrain == null ? 0L : (System.nanoTime() - terrain.createdNanos) / 1_000_000L)
                    + " buildQueue=" + (renderer == null ? "none" : renderer.getSectionRenderDispatcher().getStats())
                    + " texture=" + colorTextureId() + " rendererRadius=" + rendererRadius
                    + " remote={" + (remoteScene == null ? "none" : remoteScene.diagnostics()) + "}";
        }
    }

    private static final class ProjectionTargetTexture extends AbstractTexture {
        private ProjectionFeed feed;

        private ProjectionTargetTexture(ProjectionFeed feed) {
            this.feed = feed;
        }

        @Override
        public int getId() {
            ProjectionFeed currentFeed = feed;
            return currentFeed == null ? 0 : currentFeed.colorTextureId();
        }

        @Override
        public void load(ResourceManager resourceManager) throws IOException {
        }

        @Override
        public void releaseId() {
        }

        @Override
        public void close() {
            feed = null;
        }
    }
}
