package dev.imb11.client.renderer.projection;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.imb11.projection.ProjectionSurface;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

public final class ProjectionSurfaceRenderer {
    private static final float SURFACE_OFFSET = 0.002F;
    private static final int MAX_ENCODED_DISTANCE = 0xFFFFFF;
    private static final ResourceLocation SHADER_LOCATION =
            ResourceLocation.fromNamespaceAndPath("glass", "projection_surface");
    private static final Map<ClientLevel, Map<BlockPos, CachedMesh>> CACHED_MESHES = new IdentityHashMap<>();
    private static final Map<ResourceLocation, RenderType> SURFACE_TYPES = new HashMap<>();
    private static ClientLevel activeLevel;
    private static volatile ShaderInstance shader;
    private static volatile Uniform revealProgressUniform;
    private static final RenderStateShard.ShaderStateShard PROJECTION_SURFACE_SHADER =
            new RenderStateShard.ShaderStateShard(() -> shader);

    private ProjectionSurfaceRenderer() {
    }

    public static void registerShader(CoreShaderRegistrationCallback.RegistrationContext context) throws IOException {
        context.register(
                SHADER_LOCATION,
                DefaultVertexFormat.POSITION_TEX_COLOR,
                ProjectionSurfaceRenderer::onShaderLoaded
        );
    }

    public static void render(
            ClientLevel level,
            BlockPos projectorPos,
            ProjectionSurface surface,
            ProjectionRenderManager.ProjectionFeed feed,
            PoseStack matrices,
            float revealProgress
    ) {
        RenderSystem.assertOnRenderThread();
        ShaderInstance currentShader = shader;
        Uniform currentRevealProgressUniform = revealProgressUniform;
        if (!ProjectionRenderManager.isReady(feed)) {
            return;
        }
        int textureId = feed.colorTextureId();
        if (currentShader == null || currentRevealProgressUniform == null || textureId <= 0) {
            return;
        }

        CachedMesh cachedMesh = getOrBuild(level, projectorPos, surface);
        if (cachedMesh == null) {
            return;
        }

        RenderType surfaceType = getSurfaceType(feed.textureLocation());

        matrices.pushPose();
        try {
            BlockPos originOffset = cachedMesh.origin.subtract(projectorPos);
            matrices.translate(originOffset.getX(), originOffset.getY(), originOffset.getZ());
            boolean renderStateStarted = false;
            try {
                renderStateStarted = true;
                surfaceType.setupRenderState();
                currentShader.setSampler("Sampler0", textureId);
                currentRevealProgressUniform.set(revealProgress);
                cachedMesh.vertexBuffer.bind();
                Matrix4f modelView = new Matrix4f(RenderSystem.getModelViewMatrix())
                        .mul(matrices.last().pose());
                cachedMesh.vertexBuffer.drawWithShader(
                        modelView,
                        RenderSystem.getProjectionMatrix(),
                        currentShader
                );
            } finally {
                VertexBuffer.unbind();
                if (renderStateStarted) {
                    surfaceType.clearRenderState();
                }
            }
        } finally {
            matrices.popPose();
        }
    }

    public static void release(ClientLevel level, BlockPos projectorPos) {
        runOnRenderThread(() -> releaseNow(level, projectorPos));
    }

    public static void releaseLevel(ClientLevel level) {
        runOnRenderThread(() -> releaseLevelNow(level));
    }

    public static void invalidate() {
        reset();
    }

    public static void reset() {
        runOnRenderThread(ProjectionSurfaceRenderer::resetNow);
    }

    static void releaseTexture(ResourceLocation textureLocation) {
        SURFACE_TYPES.remove(textureLocation);
    }

    private static void onShaderLoaded(ShaderInstance loadedShader) {
        resetNow();
        shader = loadedShader;
        revealProgressUniform = loadedShader.getUniform("RevealProgress");
    }

    private static RenderType getSurfaceType(ResourceLocation textureLocation) {
        return SURFACE_TYPES.computeIfAbsent(textureLocation, location -> RenderType.create(
                "glass_projection_surface_" + location.getPath().replace('/', '_'),
                DefaultVertexFormat.POSITION_TEX_COLOR,
                VertexFormat.Mode.QUADS,
                1536,
                false,
                false,
                RenderType.CompositeState.builder()
                        .setShaderState(PROJECTION_SURFACE_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(location, true, false))
                        .setTransparencyState(RenderStateShard.NO_TRANSPARENCY)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setCullState(RenderStateShard.NO_CULL)
                        .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                        .setOverlayState(RenderStateShard.NO_OVERLAY)
                        .setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                        .createCompositeState(false)
        ));
    }

    private static CachedMesh getOrBuild(ClientLevel level, BlockPos projectorPos, ProjectionSurface surface) {
        if (activeLevel != level) {
            resetNow();
            activeLevel = level;
        }
        Map<BlockPos, CachedMesh> levelMeshes = CACHED_MESHES.computeIfAbsent(level, ignored -> new HashMap<>());
        BlockPos key = projectorPos.immutable();
        CachedMesh cachedMesh = levelMeshes.get(key);
        if (cachedMesh != null && cachedMesh.matches(surface)) {
            return cachedMesh;
        }

        CachedMesh replacement = build(surface);
        if (replacement == null) {
            levelMeshes.remove(key);
        } else {
            levelMeshes.put(key, replacement);
        }
        if (cachedMesh != null) {
            cachedMesh.close();
        }
        if (levelMeshes.isEmpty()) {
            CACHED_MESHES.remove(level);
        }
        return replacement;
    }

    private static CachedMesh build(ProjectionSurface surface) {
        if (surface.cells().isEmpty()) {
            return null;
        }
        for (ProjectionSurface.Cell cell : surface.cells()) {
            int revealDistance = cell.revealDistance();
            if (revealDistance < 0 || revealDistance > MAX_ENCODED_DISTANCE) {
                throw new IllegalArgumentException("Projection reveal distance is outside the 24-bit mesh range");
            }
        }

        BufferBuilder builder = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX_COLOR
        );
        for (ProjectionSurface.Cell cell : surface.cells()) {
            emitCell(builder, surface, cell);
        }

        VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        boolean uploaded = false;
        try {
            vertexBuffer.bind();
            vertexBuffer.upload(builder.buildOrThrow());
            uploaded = true;
        } finally {
            VertexBuffer.unbind();
            if (!uploaded) {
                vertexBuffer.close();
            }
        }
        return new CachedMesh(
                surface.origin().immutable(),
                surface.facing(),
                surface.topologyHash(),
                surface.version(),
                vertexBuffer
        );
    }

    private static void emitCell(
            VertexConsumer vertices,
            ProjectionSurface surface,
            ProjectionSurface.Cell cell
    ) {
        BlockPos relativePos = cell.position().subtract(surface.origin());
        int revealDistance = cell.revealDistance();
        int red = revealDistance & 0xFF;
        int green = revealDistance >>> 8 & 0xFF;
        int blue = revealDistance >>> 16 & 0xFF;
        emitVertex(
                vertices, relativePos, surface.facing(), surface.uDirection(), surface.vDirection(),
                false, false, (float) cell.u0(), (float) cell.v0(), red, green, blue
        );
        emitVertex(
                vertices, relativePos, surface.facing(), surface.uDirection(), surface.vDirection(),
                true, false, (float) cell.u1(), (float) cell.v0(), red, green, blue
        );
        emitVertex(
                vertices, relativePos, surface.facing(), surface.uDirection(), surface.vDirection(),
                true, true, (float) cell.u1(), (float) cell.v1(), red, green, blue
        );
        emitVertex(
                vertices, relativePos, surface.facing(), surface.uDirection(), surface.vDirection(),
                false, true, (float) cell.u0(), (float) cell.v1(), red, green, blue
        );
    }

    private static void emitVertex(
            VertexConsumer vertices,
            BlockPos relativePos,
            Direction facing,
            Direction uDirection,
            Direction vDirection,
            boolean highU,
            boolean highV,
            float u,
            float v,
            int red,
            int green,
            int blue
    ) {
        float[] position = {relativePos.getX(), relativePos.getY(), relativePos.getZ()};
        position[axisIndex(facing.getAxis())] += facing.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? 1.0F + SURFACE_OFFSET
                : -SURFACE_OFFSET;
        position[axisIndex(uDirection.getAxis())] += cornerOffset(uDirection, highU);
        position[axisIndex(vDirection.getAxis())] += cornerOffset(vDirection, highV);
        vertices.addVertex(position[0], position[1], position[2])
                .setUv(u, v)
                .setColor(red, green, blue, 255);
    }

    private static float cornerOffset(Direction direction, boolean high) {
        boolean positive = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        return positive == high ? 1.0F : 0.0F;
    }

    private static int axisIndex(Direction.Axis axis) {
        return switch (axis) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        };
    }

    private static void releaseNow(ClientLevel level, BlockPos projectorPos) {
        Map<BlockPos, CachedMesh> levelMeshes = CACHED_MESHES.get(level);
        if (levelMeshes == null) {
            return;
        }
        CachedMesh cachedMesh = levelMeshes.remove(projectorPos);
        if (cachedMesh != null) {
            cachedMesh.close();
        }
        if (levelMeshes.isEmpty()) {
            CACHED_MESHES.remove(level);
        }
    }

    private static void releaseLevelNow(ClientLevel level) {
        Map<BlockPos, CachedMesh> levelMeshes = CACHED_MESHES.remove(level);
        if (levelMeshes != null) {
            levelMeshes.values().forEach(CachedMesh::close);
        }
        if (activeLevel == level) {
            activeLevel = null;
        }
    }

    private static void resetNow() {
        CACHED_MESHES.values().forEach(levelMeshes -> levelMeshes.values().forEach(CachedMesh::close));
        CACHED_MESHES.clear();
        SURFACE_TYPES.clear();
        activeLevel = null;
    }

    private static void runOnRenderThread(Runnable operation) {
        if (RenderSystem.isOnRenderThread()) {
            operation.run();
        } else {
            RenderSystem.recordRenderCall(operation::run);
        }
    }

    private static final class CachedMesh implements AutoCloseable {
        private final BlockPos origin;
        private final Direction facing;
        private final long topologyHash;
        private final long version;
        private final VertexBuffer vertexBuffer;

        private CachedMesh(
                BlockPos origin,
                Direction facing,
                long topologyHash,
                long version,
                VertexBuffer vertexBuffer
        ) {
            this.origin = origin;
            this.facing = facing;
            this.topologyHash = topologyHash;
            this.version = version;
            this.vertexBuffer = vertexBuffer;
        }

        private boolean matches(ProjectionSurface surface) {
            return version == surface.version()
                    && topologyHash == surface.topologyHash()
                    && facing == surface.facing()
                    && origin.equals(surface.origin());
        }

        @Override
        public void close() {
            vertexBuffer.close();
        }
    }
}
