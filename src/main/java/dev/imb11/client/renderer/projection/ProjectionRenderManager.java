package dev.imb11.client.renderer.projection;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.imb11.mixins.LevelRendererInvoker;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public final class ProjectionRenderManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("glass/projection-renderer");

    public static final ResourceLocation PROJECTION_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("glass", "projection/frame");

    private static final int TARGET_WIDTH = 512;
    private static final int TARGET_HEIGHT = 512;
    private static final int REQUEST_GRACE_FRAMES = 2;
    private static final int FAILURE_RETRY_FRAMES = 60;
    private static final RenderType[] TERRAIN_LAYERS = {
            RenderType.solid(),
            RenderType.cutoutMipped(),
            RenderType.cutout(),
            RenderType.translucent(),
            RenderType.tripwire()
    };

    private static final Camera PROJECTION_CAMERA = new Camera();
    private static final AbstractTexture TEXTURE_PROXY = new ProjectionTargetTexture();

    private static volatile boolean frameRequested;
    private static int graceFrames;
    private static int failureRetryFrames;
    private static boolean renderedFrame;
    private static boolean rebuildRequested;

    private static ClientLevel level;
    private static RenderBuffers renderBuffers;
    private static LevelRenderer renderer;
    private static TextureTarget target;
    private static TextureManager registeredTextureManager;

    private ProjectionRenderManager() {
    }

    public static void requestFrame() {
        frameRequested = true;
    }

    public static boolean isReady() {
        TextureTarget currentTarget = target;
        return renderedFrame && currentTarget != null && currentTarget.getColorTextureId() > 0;
    }

    public static ResourceLocation textureLocation() {
        return PROJECTION_TEXTURE;
    }

    public static TextureTarget target() {
        return target;
    }

    public static int colorTextureId() {
        TextureTarget currentTarget = target;
        return currentTarget == null ? 0 : currentTarget.getColorTextureId();
    }

    public static boolean isProjectionRenderer(LevelRenderer candidate) {
        return candidate != null && candidate == renderer;
    }

    public static void onMainRendererRebuilt(LevelRenderer candidate) {
        Minecraft minecraft = Minecraft.getInstance();
        if (candidate == minecraft.levelRenderer && renderer != null) {
            rebuildRequested = true;
        }
    }

    public static void onMainSectionDirty(LevelRenderer candidate, int sectionX, int sectionY, int sectionZ) {
        Minecraft minecraft = Minecraft.getInstance();
        LevelRenderer current = renderer;
        if (candidate == minecraft.levelRenderer && current != null) {
            current.setSectionDirty(sectionX, sectionY, sectionZ);
        }
    }

    public static void onMainChunkLoaded(LevelRenderer candidate, net.minecraft.world.level.ChunkPos chunkPos) {
        Minecraft minecraft = Minecraft.getInstance();
        LevelRenderer current = renderer;
        if (candidate == minecraft.levelRenderer && current != null) {
            current.onChunkLoaded(chunkPos);
        }
    }

    public static void onMainRendererTick(LevelRenderer candidate) {
        Minecraft minecraft = Minecraft.getInstance();
        LevelRenderer current = renderer;
        if (candidate == minecraft.levelRenderer && current != null) {
            current.tick();
        }
    }

    public static void renderBeforeMain(GameRenderer gameRenderer, DeltaTracker deltaTracker) {
        if (ProjectionRenderContext.isActive()) {
            return;
        }

        boolean requested = frameRequested;
        frameRequested = false;
        if (requested) {
            graceFrames = REQUEST_GRACE_FRAMES;
        } else if (graceFrames > 0) {
            graceFrames--;
        }

        if (!requested && graceFrames == 0) {
            return;
        }

        Minecraft minecraft = gameRenderer.getMinecraft();
        ClientLevel currentLevel = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (currentLevel == null || player == null) {
            reset();
            return;
        }

        if (failureRetryFrames > 0) {
            failureRetryFrames--;
            return;
        }

        RenderTarget mainTarget = minecraft.getMainRenderTarget();
        try {
            ensureResources(minecraft, currentLevel);
            if (rebuildRequested) {
                rebuildRequested = false;
                renderer.allChanged();
            }

            renderTerrain(minecraft, gameRenderer, deltaTracker, mainTarget);
        } catch (RuntimeException exception) {
            LOGGER.error("Projection world pass failed; retrying in {} frames", FAILURE_RETRY_FRAMES, exception);
            disposeOwnedResources();
            failureRetryFrames = FAILURE_RETRY_FRAMES;
        } finally {
            restoreMainRenderState(mainTarget);
        }
    }

    public static void reset() {
        frameRequested = false;
        graceFrames = 0;
        failureRetryFrames = 0;
        rebuildRequested = false;
        disposeOwnedResources();
    }

    private static void ensureResources(Minecraft minecraft, ClientLevel currentLevel) {
        if (renderer != null && level == currentLevel && target != null) {
            return;
        }

        disposeOwnedResources();

        target = new TextureTarget(TARGET_WIDTH, TARGET_HEIGHT, true, Minecraft.ON_OSX);
        target.setFilterMode(GL11.GL_LINEAR);

        renderBuffers = new RenderBuffers(1);
        renderer = new LevelRenderer(
                minecraft,
                minecraft.getEntityRenderDispatcher(),
                minecraft.getBlockEntityRenderDispatcher(),
                renderBuffers
        );
        level = currentLevel;
        renderer.setLevel(currentLevel);

        registerTextureProxy(minecraft.getTextureManager());
    }

    private static void registerTextureProxy(TextureManager textureManager) {
        if (registeredTextureManager == textureManager) {
            return;
        }

        textureManager.register(PROJECTION_TEXTURE, TEXTURE_PROXY);
        registeredTextureManager = textureManager;
    }

    private static void renderTerrain(
            Minecraft minecraft,
            GameRenderer gameRenderer,
            DeltaTracker deltaTracker,
            RenderTarget mainTarget
    ) {
        Entity cameraEntity = minecraft.getCameraEntity();
        if (cameraEntity == null) {
            cameraEntity = minecraft.player;
        }

        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        PROJECTION_CAMERA.setup(
                level,
                cameraEntity,
                !minecraft.options.getCameraType().isFirstPerson(),
                minecraft.options.getCameraType().isMirrored(),
                partialTick
        );
        PROJECTION_CAMERA.setRotation(
                Mth.wrapDegrees(PROJECTION_CAMERA.getYRot() + 180.0F),
                PROJECTION_CAMERA.getXRot()
        );
        Vec3 cameraPosition = PROJECTION_CAMERA.getPosition();

        float fovRadians = (float) Math.toRadians(minecraft.options.fov().get());
        int renderDistanceChunks = minecraft.options.getEffectiveRenderDistance();

        float renderDistanceBlocks = renderDistanceChunks * 16.0F;
        float farPlane = renderDistanceBlocks * 4.0F;
        Matrix4f projection = new Matrix4f().perspective(
                fovRadians,
                (float) TARGET_WIDTH / TARGET_HEIGHT,
                0.05F,
                farPlane
        );
        Quaternionf inverseRotation = PROJECTION_CAMERA.rotation().conjugate(new Quaternionf());
        Matrix4f modelView = new Matrix4f().rotation(inverseRotation);
        Frustum frustum = new Frustum(modelView, projection);
        frustum.prepare(cameraPosition.x, cameraPosition.y, cameraPosition.z);

        boolean worldFog = level.effects().isFoggyAt(Mth.floor(cameraPosition.x), Mth.floor(cameraPosition.y))
                || minecraft.gui.getBossOverlay().shouldCreateWorldFog();
        FogRenderer.setupColor(
                PROJECTION_CAMERA,
                partialTick,
                level,
                renderDistanceChunks,
                gameRenderer.getDarkenWorldAmount(partialTick)
        );
        FogRenderer.levelFogColor();

        TextureTarget currentTarget = target;
        LevelRenderer currentRenderer = renderer;
        if (currentTarget == null || currentRenderer == null) {
            return;
        }

        currentTarget.setClearColor(FogRenderer.fogRed, FogRenderer.fogGreen, FogRenderer.fogBlue, 1.0F);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        currentTarget.clear(Minecraft.ON_OSX);
        currentTarget.bindWrite(true);

        RenderSystem.setShaderGameTime(level.getGameTime(), partialTick);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        LevelRendererInvoker invoker = (LevelRendererInvoker) currentRenderer;
        try (ProjectionRenderContext.Scope ignored =
                     ProjectionRenderContext.enter(currentRenderer, PROJECTION_CAMERA, currentTarget)) {

            RenderSystem.setShader(GameRenderer::getPositionShader);
            currentRenderer.renderSky(
                    modelView,
                    projection,
                    partialTick,
                    PROJECTION_CAMERA,
                    worldFog,
                    () -> FogRenderer.setupFog(
                            PROJECTION_CAMERA,
                            FogRenderer.FogMode.FOG_SKY,
                            renderDistanceBlocks,
                            worldFog,
                            partialTick
                    )
            );
            restoreProjectionTarget(currentTarget);
            FogRenderer.setupFog(
                    PROJECTION_CAMERA,
                    FogRenderer.FogMode.FOG_TERRAIN,
                    Math.max(renderDistanceBlocks, 32.0F),
                    worldFog,
                    partialTick
            );

            invoker.glass$setupRender(PROJECTION_CAMERA, frustum, false, minecraft.player.isSpectator());
            invoker.glass$compileSections(PROJECTION_CAMERA);

            for (RenderType layer : TERRAIN_LAYERS) {
                renderLayer(invoker, layer, cameraPosition, modelView, projection, currentTarget);
            }

            if (minecraft.options.getCloudsType() != CloudStatus.OFF) {
                currentRenderer.renderClouds(
                        new PoseStack(),
                        modelView,
                        projection,
                        partialTick,
                        cameraPosition.x,
                        cameraPosition.y,
                        cameraPosition.z
                );
                restoreProjectionTarget(currentTarget);
            }

            renderedFrame = true;
        } finally {

            mainTarget.bindWrite(true);
        }
    }

    private static void renderLayer(
            LevelRendererInvoker invoker,
            RenderType layer,
            Vec3 cameraPosition,
            Matrix4f modelView,
            Matrix4f projection,
            TextureTarget currentTarget
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
            currentTarget.bindWrite(false);
        }
    }

    private static void restoreProjectionTarget(TextureTarget currentTarget) {
        currentTarget.bindWrite(false);
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

    private static void restoreMainRenderState(RenderTarget mainTarget) {
        if (ProjectionRenderContext.isActive()) {

            LOGGER.error("Projection render context escaped its scope");
        }

        mainTarget.bindWrite(true);
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

    private static void disposeOwnedResources() {
        renderedFrame = false;

        LevelRenderer oldRenderer = renderer;
        renderer = null;
        level = null;
        renderBuffers = null;
        if (oldRenderer != null) {
            oldRenderer.setLevel(null);
            oldRenderer.close();
        }

        TextureTarget oldTarget = target;
        target = null;
        if (oldTarget != null) {
            oldTarget.destroyBuffers();
        }
    }

    private static final class ProjectionTargetTexture extends AbstractTexture {
        @Override
        public int getId() {
            return colorTextureId();
        }

        @Override
        public void load(ResourceManager resourceManager) throws IOException {

        }

        @Override
        public void releaseId() {

        }

        @Override
        public void close() {

        }
    }
}
