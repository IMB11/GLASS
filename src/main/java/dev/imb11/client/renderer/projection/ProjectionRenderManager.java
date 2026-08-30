package dev.imb11.client.renderer.projection;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.imb11.blocks.TerminalBlock;
import dev.imb11.blocks.entity.TerminalBlockEntity;
import dev.imb11.mixins.BufferSourceAccessor;
import dev.imb11.mixins.LevelRendererBufferAccessor;
import dev.imb11.mixins.LevelRendererInvoker;
import dev.imb11.mixins.ViewAreaInvoker;
import dev.imb11.projection.ProjectionSurface;
import dev.imb11.sync.ProjectionSource;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.SectionBufferBuilderPool;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
    private static final int FEED_RETENTION_GRACE_FRAMES = 120;
    private static final int FAILURE_RETRY_FRAMES = 60;
    private static final float CAMERA_FACE_OFFSET = 0.5625F;
    private static final double PROJECTOR_PLANE_OFFSET = 0.502D;
    private static final double DESTINATION_CLIP_OFFSET = 0.002D;
    private static final float CLIP_EPSILON = 1.0E-5F;
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
    private static final Map<ProjectionOwner, PortalSideState> PORTAL_SIDES = new HashMap<>();
    private static final List<SectionBufferBuilderPool> RETIRED_BUFFER_POOLS = new ArrayList<>();
    private static Map<String, ProjectionSource> registrySources = Map.of();
    private static ClientLevel activeLevel;
    private static long frameSequence;
    private static long textureSequence;
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
        Iterator<Map.Entry<FeedKey, ProjectionFeed>> iterator = FEEDS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<FeedKey, ProjectionFeed> entry = iterator.next();
            if (!entry.getKey().equals(key)
                    && entry.getKey().projectorDimension().equals(key.projectorDimension())
                    && entry.getKey().projectorPos().equals(key.projectorPos())) {
                iterator.remove();
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
        } else if (feed.lastRequestFrame >= 0L
                && frameSequence - feed.lastRequestFrame > REQUEST_RENDER_GRACE_FRAMES) {
            feed.ready = false;
        }
        feed.lastRequestFrame = frameSequence;
        return feed;
    }

    public static boolean isReady(@Nullable ProjectionFeed feed) {
        return feed != null
                && FEEDS.get(feed.key) == feed
                && feed.ready
                && feed.target != null
                && feed.target.getColorTextureId() > 0;
    }

    public static long currentFrameSequence() {
        return frameSequence;
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
            for (ProjectionFeed feed : FEEDS.values()) {
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
            if (feed.renderer != null && sectionInFeedView(feed, sectionX, sectionY, sectionZ)) {
                feed.renderer.setSectionDirty(sectionX, sectionY, sectionZ);
            }
        }
    }

    public static void onMainChunkLoaded(LevelRenderer candidate, ChunkPos chunkPos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (candidate != minecraft.levelRenderer) {
            return;
        }
        for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
            if (feed.renderer != null) {
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
        for (ProjectionFeed feed : List.copyOf(FEEDS.values())) {
            if (feed.renderer != null) {
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

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        Camera viewerCamera = gameRenderer.getMainCamera();
        Vec3 viewerPosition = viewerCamera.getPosition();
        Matrix4f mainProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        Matrix4f mainModelView = new Matrix4f(RenderSystem.getModelViewMatrix());
        boolean initializedFeedThisFrame = false;
        Iterator<Map.Entry<FeedKey, ProjectionFeed>> iterator = FEEDS.entrySet().iterator();
        while (iterator.hasNext()) {
            ProjectionFeed feed = iterator.next().getValue();
            updatePortalSide(feed.sideState, viewerPosition);
            long requestAge = frameSequence - feed.lastRequestFrame;
            if (feed.lastRequestFrame < 0L || requestAge > FEED_RETENTION_GRACE_FRAMES) {
                iterator.remove();
                closeFeed(feed);
                continue;
            }
            if (requestAge > REQUEST_RENDER_GRACE_FRAMES) {
                continue;
            }
            if (frameSequence < feed.nextRetryFrame) {
                continue;
            }

            try {
                PortalView portalView = configurePortalCamera(
                        feed,
                        currentLevel,
                        minecraft,
                        viewerCamera,
                        partialTick,
                        mainProjection,
                        mainTarget
                );
                if (!isAvailable(currentLevel, feed.source, portalView.cameraPosition())) {
                    markUnavailable(feed, mainTarget);
                    continue;
                }
                boolean requiresInitialization = requiresResourceInitialization(feed, currentLevel);
                if (requiresInitialization && initializedFeedThisFrame) {
                    continue;
                }
                if (requiresInitialization) {
                    initializedFeedThisFrame = true;
                }
                if (!ensureResources(minecraft, currentLevel, feed, portalView, partialTick)) {
                    continue;
                }
                renderFeed(minecraft, gameRenderer, partialTick, feed, portalView, mainTarget);
            } catch (RuntimeException exception) {
                LOGGER.error(
                        "Projection feed {} failed; retrying in {} frames",
                        feed.source.key(),
                        FAILURE_RETRY_FRAMES,
                        exception
                );
                disposeFeedResources(feed);
                feed.failed = true;
                feed.nextRetryFrame = frameSequence + FAILURE_RETRY_FRAMES;
            } finally {
                restoreMainRenderState(mainTarget, gameRenderer, mainProjection, mainModelView);
            }
        }
        drainRetiredBufferPools();
        restoreMainRenderState(mainTarget, gameRenderer, mainProjection, mainModelView);
    }

    public static void reset() {
        runOnRenderThread(ProjectionRenderManager::resetNow);
    }

    private static boolean requiresResourceInitialization(ProjectionFeed feed, ClientLevel level) {
        return feed.renderer == null
                || feed.level != level
                || feed.target == null
                || feed.renderBuffers == null;
    }

    private static void replaceRegistryNow(Map<String, ProjectionSource> replacement) {
        registrySources = replacement;
        Iterator<Map.Entry<FeedKey, ProjectionFeed>> iterator = FEEDS.entrySet().iterator();
        while (iterator.hasNext()) {
            ProjectionFeed feed = iterator.next().getValue();
            if (!feed.source.equals(replacement.get(feed.source.channel()))) {
                iterator.remove();
                closeFeed(feed);
            }
        }
        drainRetiredBufferPools();
    }

    private static void chunkUnloadedNow(ClientLevel level, ChunkPos chunkPos) {
        if (level != activeLevel) {
            return;
        }
        for (ProjectionFeed feed : FEEDS.values()) {
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
            renderer.setSectionDirty(chunkPos.x, sectionY, chunkPos.z);
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
        return Math.abs(sectionX - center.x) <= renderDistance
                && Math.abs(sectionZ - center.z) <= renderDistance;
    }

    private static int feedViewDistance(ProjectionFeed feed) {
        LevelRenderer renderer = feed.renderer;
        if (renderer != null) {
            ViewArea viewArea = ((LevelRendererBufferAccessor) renderer).glass$getViewArea();
            if (viewArea != null) {
                return viewArea.getViewDistance();
            }
        }
        return ProjectionRenderContext.feedRenderDistance(
                Minecraft.getInstance().options.getEffectiveRenderDistance()
        );
    }

    private static ChunkPos viewCenterChunk(ProjectionFeed feed) {
        int sectionX = SectionPos.blockToSectionCoord(Mth.ceil(feed.cameraPosition.x) - 8);
        int sectionZ = SectionPos.blockToSectionCoord(Mth.ceil(feed.cameraPosition.z) - 8);
        return new ChunkPos(sectionX, sectionZ);
    }

    private static void releaseProjectorNow(ResourceKey<Level> dimension, BlockPos projectorPos) {
        Iterator<Map.Entry<FeedKey, ProjectionFeed>> iterator = FEEDS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<FeedKey, ProjectionFeed> entry = iterator.next();
            FeedKey key = entry.getKey();
            if (key.projectorDimension().equals(dimension) && key.projectorPos().equals(projectorPos)) {
                iterator.remove();
                closeFeed(entry.getValue());
            }
        }
        PORTAL_SIDES.remove(new ProjectionOwner(dimension, projectorPos));
        drainRetiredBufferPools();
    }

    private static void changeLevelNow(@Nullable ClientLevel level) {
        for (ProjectionFeed feed : FEEDS.values()) {
            closeFeed(feed);
        }
        FEEDS.clear();
        PORTAL_SIDES.clear();
        activeLevel = level;
        drainRetiredBufferPools();
    }

    private static void resetNow() {
        for (ProjectionFeed feed : FEEDS.values()) {
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
            ProjectionFeed feed,
            PortalView portalView,
            float partialTick
    ) {
        if (feed.renderer != null && feed.level == level && feed.target != null && feed.renderBuffers != null) {
            resizeTarget(feed, portalView.targetWidth(), portalView.targetHeight());
            return true;
        }

        disposeFeedResources(feed);
        applyCamera(
                feed.camera,
                level,
                minecraft,
                portalView,
                partialTick
        );
        feed.target = new TextureTarget(
                portalView.targetWidth(),
                portalView.targetHeight(),
                true,
                Minecraft.ON_OSX
        );
        feed.target.setFilterMode(GL11.GL_LINEAR);
        feed.renderBuffers = new RenderBuffers(1);
        feed.renderer = new LevelRenderer(
                minecraft,
                minecraft.getEntityRenderDispatcher(),
                minecraft.getBlockEntityRenderDispatcher(),
                feed.renderBuffers
        );
        feed.level = level;
        try (ProjectionRenderContext.Scope ignored = ProjectionRenderContext.enter(
                feed.renderer,
                feed.camera,
                feed.target,
                feed.source.pos()
        )) {
            feed.renderer.setLevel(level);
        }
        feed.textureProxy = new ProjectionTargetTexture(feed);
        feed.textureManager = minecraft.getTextureManager();
        feed.textureManager.register(feed.textureLocation, feed.textureProxy);
        return false;
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
        if (level == null || renderer == null || target == null || minecraft.player == null) {
            return;
        }

        Vec3 cameraPosition = feed.camera.getPosition();
        int renderDistanceChunks = ProjectionRenderContext.feedRenderDistance(
                minecraft.options.getEffectiveRenderDistance()
        );
        float renderDistanceBlocks = renderDistanceChunks * 16.0F;
        Matrix4f projection = new Matrix4f(portalView.clippedProjection());
        Matrix4f skyProjection = new Matrix4f(portalView.projection());
        Quaternionf inverseRotation = feed.camera.rotation().conjugate(new Quaternionf());
        Matrix4f modelView = new Matrix4f().rotation(inverseRotation);
        Frustum frustum = new Frustum(modelView, skyProjection);
        frustum.prepare(cameraPosition.x, cameraPosition.y, cameraPosition.z);

        boolean worldFog = level.effects().isFoggyAt(Mth.floor(cameraPosition.x), Mth.floor(cameraPosition.y))
                || minecraft.gui.getBossOverlay().shouldCreateWorldFog();
        FogRenderer.setupColor(
                feed.camera,
                partialTick,
                level,
                renderDistanceChunks,
                gameRenderer.getDarkenWorldAmount(partialTick)
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

        LevelRendererInvoker invoker = (LevelRendererInvoker) renderer;
        try (ProjectionRenderContext.Scope ignored = ProjectionRenderContext.enter(
                renderer,
                feed.camera,
                target,
                feed.source.pos()
        )) {
            RenderSystem.setShader(GameRenderer::getPositionShader);
            renderer.renderSky(
                    modelView,
                    skyProjection,
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

            invoker.glass$setupRender(feed.camera, frustum, false, true);
            invoker.glass$compileSections(feed.camera);
            for (RenderType layer : TERRAIN_LAYERS) {
                renderLayer(invoker, layer, cameraPosition, modelView, projection, target);
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
        }
    }

    private static PortalView configurePortalCamera(
            ProjectionFeed feed,
            ClientLevel level,
            Minecraft minecraft,
            Camera viewerCamera,
            float partialTick,
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
        Vec3 destinationPlaneCenter = cameraPosition(feed.source);
        Vec3 dynamicPosition = destinationPlaneCenter
                .add(destinationRight.scale(horizontalOffset))
                .add(destinationUp.scale(verticalOffset))
                .add(destinationBack.scale(normalOffset));
        Vec3 clipPlanePoint = destinationPlaneCenter.add(destinationLook.scale(DESTINATION_CLIP_OFFSET));
        Matrix4f clippedProjection = clippedProjection(
                mainProjection,
                cameraRotation,
                dynamicPosition,
                clipPlanePoint,
                destinationLook
        );
        TargetSize targetSize = targetSize(mainTarget);
        PortalView portalView = new PortalView(
                dynamicPosition,
                cameraRotation,
                mainProjection,
                clippedProjection,
                targetSize.width(),
                targetSize.height()
        );
        applyCamera(feed.camera, level, minecraft, portalView, partialTick);
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

    private static Matrix4f clippedProjection(
            Matrix4f projection,
            Quaternionf cameraRotation,
            Vec3 cameraPosition,
            Vec3 planePoint,
            Vec3 planeNormal
    ) {
        Quaternionf inverseCameraRotation = new Quaternionf(cameraRotation).conjugate();
        Vector3f cameraPlaneNormal = new Vector3f(
                (float) planeNormal.x,
                (float) planeNormal.y,
                (float) planeNormal.z
        ).rotate(inverseCameraRotation).normalize();
        Vec3 relativePlanePoint = planePoint.subtract(cameraPosition);
        Vector3f cameraPlanePoint = new Vector3f(
                (float) relativePlanePoint.x,
                (float) relativePlanePoint.y,
                (float) relativePlanePoint.z
        ).rotate(inverseCameraRotation);
        Vector4f cameraPlane = new Vector4f(
                cameraPlaneNormal.x,
                cameraPlaneNormal.y,
                cameraPlaneNormal.z,
                -cameraPlaneNormal.dot(cameraPlanePoint)
        );
        Matrix4f result = new Matrix4f(projection);
        if (cameraPlane.w >= -CLIP_EPSILON) {
            return result;
        }
        Matrix4f inverseProjection = new Matrix4f(projection).invert();
        for (int x = -1; x <= 1; x += 2) {
            for (int y = -1; y <= 1; y += 2) {
                Vector4f corner = new Vector4f(x, y, 1.0F, 1.0F);
                inverseProjection.transform(corner);
                if (!isFinite(corner) || Math.abs(corner.w) <= CLIP_EPSILON) {
                    return result;
                }
                corner.div(corner.w);
                float planeValue = cameraPlane.x * corner.x
                        + cameraPlane.y * corner.y
                        + cameraPlane.z * corner.z
                        + cameraPlane.w;
                if (!Float.isFinite(planeValue) || planeValue <= CLIP_EPSILON) {
                    return result;
                }
            }
        }
        float denominator = Float.NEGATIVE_INFINITY;
        for (int x = -1; x <= 1; x += 2) {
            for (int y = -1; y <= 1; y += 2) {
                Vector4f corner = new Vector4f(x, y, 1.0F, 1.0F);
                inverseProjection.transform(corner);
                float candidate = cameraPlane.dot(corner);
                if (isFinite(corner) && Float.isFinite(candidate)) {
                    denominator = Math.max(denominator, candidate);
                }
            }
        }
        if (!Float.isFinite(denominator) || denominator < 1.0E-5F) {
            return result;
        }
        cameraPlane.mul(2.0F / denominator);
        result.m02(cameraPlane.x - result.m03());
        result.m12(cameraPlane.y - result.m13());
        result.m22(cameraPlane.z - result.m23());
        result.m32(cameraPlane.w - result.m33());
        return result;
    }

    private static boolean isFinite(Vector4f vector) {
        return Float.isFinite(vector.x)
                && Float.isFinite(vector.y)
                && Float.isFinite(vector.z)
                && Float.isFinite(vector.w);
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

    private static boolean isAvailable(ClientLevel level, ProjectionSource source, Vec3 cameraPosition) {
        if (!isDescriptorValid(source) || !source.dimension().equals(level.dimension())) {
            return false;
        }

        ClientChunkCache chunkCache = level.getChunkSource();
        BlockPos sourcePos = source.pos();
        BlockPos cameraPos = BlockPos.containing(cameraPosition);
        if (!hasFullChunk(chunkCache, sourcePos) || !hasFullChunk(chunkCache, cameraPos)) {
            return false;
        }

        BlockState state = level.getBlockState(sourcePos);
        if (!(state.getBlock() instanceof TerminalBlock)
                || state.getValue(TerminalBlock.FACING) != source.facing()) {
            return false;
        }
        if (!(level.getBlockEntity(sourcePos) instanceof TerminalBlockEntity terminal)) {
            return false;
        }
        return source.channel().equals(terminal.getChannel());
    }

    private static boolean hasFullChunk(ClientChunkCache chunkCache, BlockPos pos) {
        return chunkCache.getChunk(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()),
                ChunkStatus.FULL,
                false
        ) != null;
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
            LevelRendererInvoker invoker,
            RenderType layer,
            Vec3 cameraPosition,
            Matrix4f modelView,
            Matrix4f projection,
            TextureTarget target
    ) {
        boolean completed = false;
        try {
            invoker.glass$renderSectionLayer(
                    layer,
                    cameraPosition.x,
                    cameraPosition.y,
                    cameraPosition.z,
                    modelView,
                    projection
            );
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

    private static void closeFeed(ProjectionFeed feed) {
        disposeFeedResources(feed);
        ProjectionSurfaceRenderer.releaseTexture(feed.textureLocation);
    }

    private static void disposeFeedResources(ProjectionFeed feed) {
        feed.ready = false;
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

        LevelRenderer renderer = feed.renderer;
        feed.renderer = null;
        feed.level = null;
        if (renderer != null) {
            cleanup(feed, "renderer level", () -> renderer.setLevel(null));
            cleanup(feed, "renderer global buffers", () -> closeGlobalBuffers(renderer));
            cleanup(feed, "level renderer", renderer::close);
            ClientLevel currentLevel = Minecraft.getInstance().level;
            if (currentLevel != null) {
                Minecraft.getInstance().getEntityRenderDispatcher().setLevel(currentLevel);
            }
        }

        RenderBuffers renderBuffers = feed.renderBuffers;
        feed.renderBuffers = null;
        if (renderBuffers != null) {
            cleanup(feed, "render buffers", () -> retireRenderBuffers(renderBuffers));
        }

        TextureTarget target = feed.target;
        feed.target = null;
        if (target != null) {
            cleanup(feed, "render target", target::destroyBuffers);
        }
        feed.camera.reset();
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

    private static void retireRenderBuffers(RenderBuffers renderBuffers) {
        Set<ByteBufferBuilder> builders = Collections.newSetFromMap(new IdentityHashMap<>());
        SectionBufferBuilderPack fixedBufferPack = renderBuffers.fixedBufferPack();
        for (RenderType renderType : RenderType.chunkBufferLayers()) {
            builders.add(fixedBufferPack.buffer(renderType));
        }
        addBufferSourceBuilders(builders, renderBuffers.bufferSource());
        addBufferSourceBuilders(builders, renderBuffers.crumblingBufferSource());
        builders.forEach(ByteBufferBuilder::close);
        SectionBufferBuilderPool pool = renderBuffers.sectionBufferPool();
        if (pool.getFreeBufferCount() > 0) {
            closeAvailablePoolBuffers(pool);
        } else {
            RETIRED_BUFFER_POOLS.add(pool);
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
        Iterator<SectionBufferBuilderPool> iterator = RETIRED_BUFFER_POOLS.iterator();
        while (iterator.hasNext()) {
            SectionBufferBuilderPool pool = iterator.next();
            if (pool.getFreeBufferCount() > 0) {
                closeAvailablePoolBuffers(pool);
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
            Matrix4f clippedProjection,
            int targetWidth,
            int targetHeight
    ) {
        private PortalView {
            cameraRotation = new Quaternionf(cameraRotation);
            projection = new Matrix4f(projection);
            clippedProjection = new Matrix4f(clippedProjection);
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
        private long nextRetryFrame;
        private boolean ready;
        private boolean available;
        private boolean failed;
        private ClientLevel level;
        private RenderBuffers renderBuffers;
        private LevelRenderer renderer;
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
