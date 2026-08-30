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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
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
        if (surface.faces().isEmpty()) {
            return null;
        }
        for (ProjectionSurface.Face face : surface.faces()) {
            int revealDistance = face.revealDistance();
            if (revealDistance < 0 || revealDistance > MAX_ENCODED_DISTANCE) {
                throw new IllegalArgumentException("Projection reveal distance is outside the 24-bit mesh range");
            }
        }

        List<FaceGeometry> faceGeometries = new ArrayList<>(surface.faces().size());
        Map<EdgeKey, EnumSet<Direction>> edgeNormals = new HashMap<>();
        for (ProjectionSurface.Face face : surface.faces()) {
            FaceGeometry geometry = geometry(surface, face);
            faceGeometries.add(geometry);
            for (EdgeKey edge : geometry.edges()) {
                edgeNormals.computeIfAbsent(edge, ignored -> EnumSet.noneOf(Direction.class))
                        .add(face.normal());
            }
        }

        BufferBuilder builder = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX_COLOR
        );
        for (FaceGeometry geometry : faceGeometries) {
            emitFace(builder, geometry, edgeNormals);
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

    private static FaceGeometry geometry(
            ProjectionSurface surface,
            ProjectionSurface.Face face
    ) {
        BlockPos relativePos = face.position().subtract(surface.origin());
        IntegerCorner[] corners = {
                corner(relativePos, face.normal(), face.uDirection(), face.vDirection(), false, false),
                corner(relativePos, face.normal(), face.uDirection(), face.vDirection(), true, false),
                corner(relativePos, face.normal(), face.uDirection(), face.vDirection(), true, true),
                corner(relativePos, face.normal(), face.uDirection(), face.vDirection(), false, true)
        };
        EdgeKey[] edges = {
                new EdgeKey(corners[0], corners[1]),
                new EdgeKey(corners[1], corners[2]),
                new EdgeKey(corners[2], corners[3]),
                new EdgeKey(corners[3], corners[0])
        };
        return new FaceGeometry(face, corners, edges);
    }

    private static IntegerCorner corner(
            BlockPos relativePos,
            Direction normal,
            Direction uDirection,
            Direction vDirection,
            boolean highU,
            boolean highV
    ) {
        int x = relativePos.getX() + Math.max(0, normal.getStepX());
        int y = relativePos.getY() + Math.max(0, normal.getStepY());
        int z = relativePos.getZ() + Math.max(0, normal.getStepZ());
        int uOffset = cornerOffset(uDirection, highU);
        int vOffset = cornerOffset(vDirection, highV);
        x += Math.abs(uDirection.getStepX()) * uOffset + Math.abs(vDirection.getStepX()) * vOffset;
        y += Math.abs(uDirection.getStepY()) * uOffset + Math.abs(vDirection.getStepY()) * vOffset;
        z += Math.abs(uDirection.getStepZ()) * uOffset + Math.abs(vDirection.getStepZ()) * vOffset;
        return new IntegerCorner(x, y, z);
    }

    private static void emitFace(
            VertexConsumer vertices,
            FaceGeometry geometry,
            Map<EdgeKey, EnumSet<Direction>> edgeNormals
    ) {
        ProjectionSurface.Face face = geometry.face();
        int revealDistance = face.revealDistance();
        int red = revealDistance & 0xFF;
        int green = revealDistance >>> 8 & 0xFF;
        int blue = revealDistance >>> 16 & 0xFF;
        for (int index = 0; index < geometry.corners().length; index++) {
            EdgeKey previousEdge = geometry.edges()[(index + geometry.edges().length - 1) % geometry.edges().length];
            EdgeKey nextEdge = geometry.edges()[index];
            float u = index == 1 || index == 2 ? 1.0F : 0.0F;
            float v = index >= 2 ? 1.0F : 0.0F;
            emitVertex(
                    vertices,
                    geometry.corners()[index],
                    face.normal(),
                    edgeNormals.get(previousEdge),
                    edgeNormals.get(nextEdge),
                    u,
                    v,
                    red,
                    green,
                    blue
            );
        }
    }

    private static void emitVertex(
            VertexConsumer vertices,
            IntegerCorner corner,
            Direction normal,
            EnumSet<Direction> previousEdgeNormals,
            EnumSet<Direction> nextEdgeNormals,
            float u,
            float v,
            int red,
            int green,
            int blue
    ) {
        EnumSet<Direction> miterNormals = EnumSet.of(normal);
        if (previousEdgeNormals != null) {
            miterNormals.addAll(previousEdgeNormals);
        }
        if (nextEdgeNormals != null) {
            miterNormals.addAll(nextEdgeNormals);
        }
        float x = corner.x();
        float y = corner.y();
        float z = corner.z();
        for (Direction miterNormal : miterNormals) {
            x += miterNormal.getStepX() * SURFACE_OFFSET;
            y += miterNormal.getStepY() * SURFACE_OFFSET;
            z += miterNormal.getStepZ() * SURFACE_OFFSET;
        }
        vertices.addVertex(x, y, z)
                .setUv(u, v)
                .setColor(red, green, blue, 255);
    }

    private static int cornerOffset(Direction direction, boolean high) {
        boolean positive = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        return positive == high ? 1 : 0;
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

    private record FaceGeometry(
            ProjectionSurface.Face face,
            IntegerCorner[] corners,
            EdgeKey[] edges
    ) {
    }

    private record IntegerCorner(int x, int y, int z) implements Comparable<IntegerCorner> {
        @Override
        public int compareTo(IntegerCorner other) {
            int xComparison = Integer.compare(x, other.x);
            if (xComparison != 0) {
                return xComparison;
            }
            int yComparison = Integer.compare(y, other.y);
            return yComparison != 0 ? yComparison : Integer.compare(z, other.z);
        }
    }

    private record EdgeKey(IntegerCorner first, IntegerCorner second) {
        private EdgeKey {
            if (first.compareTo(second) > 0) {
                IntegerCorner swap = first;
                first = second;
                second = swap;
            }
        }
    }
}
