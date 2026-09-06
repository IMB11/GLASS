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
import dev.imb11.client.ShaderRegistrar;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class ProjectionSurfaceRenderer {
    private static final float SURFACE_OFFSET = 0.002F;
    private static final int MAX_PROJECTIONS = 4;
    private static final int DISTANCE_RADIX = 4096;
    private static final float CROSSFADE_WIDTH = 4.0F;
    private static final String[] PROJECTION_SAMPLERS = {"Projection0", "Projection1", "Projection2", "Projection3"};
    private static final ResourceLocation SHADER_LOCATION =
            ResourceLocation.fromNamespaceAndPath("glass", "projection_surface");
    private static final Map<ClientLevel, Map<BlockPos, CachedMesh>> CACHED_MESHES = new IdentityHashMap<>();
    private static final Map<ResourceLocation, RenderType> SURFACE_TYPES = new HashMap<>();
    private static ClientLevel activeLevel;
    private static List<Projection> projections = List.of();
    private static final Map<FaceKey, SharedFace> SHARED_FACES = new HashMap<>();
    private static final Map<EdgeKey, EnumSet<Direction>> SHARED_EDGE_NORMALS = new HashMap<>();
    private static long layoutVersion;
    private static volatile ShaderInstance shader;
    private static volatile Uniform revealProgressUniform;
    private static volatile Uniform loadingOpacityUniform;
    private static volatile Uniform crossfadeWidthUniform;
    private static final Uniform[] PROJECTOR_POSITION_UNIFORMS = new Uniform[MAX_PROJECTIONS];
    private static final RenderStateShard.ShaderStateShard PROJECTION_SURFACE_SHADER =
            new RenderStateShard.ShaderStateShard(() -> shader);
    private static final RenderType LOADING_SURFACE_TYPE = RenderType.create(
            "glass_projection_loading",
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            1536,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(PROJECTION_SURFACE_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                    .setOverlayState(RenderStateShard.NO_OVERLAY)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false)
    );

    private ProjectionSurfaceRenderer() {
    }

    public static void registerShader(ShaderRegistrar context) throws IOException {
        context.register(
                SHADER_LOCATION,
                DefaultVertexFormat.POSITION_TEX_COLOR,
                ProjectionSurfaceRenderer::onShaderLoaded
        );
    }

    public static void prepare(ClientLevel level, List<Projection> visibleProjections) {
        RenderSystem.assertOnRenderThread();
        if (activeLevel != level) {
            resetNow();
            activeLevel = level;
        }
        List<Projection> next = visibleProjections.stream()
                .sorted(Comparator.comparingLong(projection -> projection.projectorPos().asLong()))
                .limit(MAX_PROJECTIONS)
                .toList();
        boolean changed = projections.size() != next.size();
        for (int index = 0; !changed && index < next.size(); index++) {
            Projection previous = projections.get(index);
            Projection replacement = next.get(index);
            changed = !previous.projectorPos().equals(replacement.projectorPos())
                    || !sameSurface(previous.surface(), replacement.surface());
        }
        projections = next;
        if (!changed) {
            return;
        }

        layoutVersion++;
        SHARED_FACES.clear();
        SHARED_EDGE_NORMALS.clear();
        for (int index = 0; index < projections.size(); index++) {
            for (ProjectionSurface.Face face : projections.get(index).surface().faces()) {
                FaceKey key = new FaceKey(face.position(), face.normal());
                SharedFace shared = SHARED_FACES.get(key);
                if (shared == null) {
                    shared = new SharedFace(index);
                    SHARED_FACES.put(key, shared);
                    addEdgeNormals(geometry(face), SHARED_EDGE_NORMALS);
                }
                if (face.revealDistance() < 0 || face.revealDistance() >= DISTANCE_RADIX) {
                    throw new IllegalArgumentException("Projection reveal distance is outside the 12-bit mesh range");
                }
                shared.distances[index] = face.revealDistance();
            }
        }
    }

    public static void render(
            ClientLevel level,
            BlockPos projectorPos,
            ProjectionSurface surface,
            @Nullable ProjectionRenderManager.ProjectionFeed feed,
            PoseStack matrices,
            float loadingOpacity
    ) {
        RenderSystem.assertOnRenderThread();
        ShaderInstance currentShader = shader;
        Uniform currentRevealProgressUniform = revealProgressUniform;
        Uniform currentLoadingOpacityUniform = loadingOpacityUniform;
        boolean loading = loadingOpacity >= 0.0F;
        if (!loading && !ProjectionRenderManager.isReady(feed)) {
            return;
        }
        int textureId = loading ? 0 : feed.colorTextureId();
        if (currentShader == null || currentRevealProgressUniform == null || currentLoadingOpacityUniform == null
                || crossfadeWidthUniform == null || Arrays.stream(PROJECTOR_POSITION_UNIFORMS).anyMatch(uniform -> uniform == null)
                || !loading && textureId <= 0) {
            return;
        }

        CachedMesh cachedMesh = getOrBuild(level, projectorPos, surface, loading);
        if (cachedMesh == null) {
            return;
        }

        RenderType surfaceType = loading ? LOADING_SURFACE_TYPE : getSurfaceType(feed.textureLocation());

        matrices.pushPose();
        try {
            BlockPos originOffset = cachedMesh.origin.subtract(projectorPos);
            matrices.translate(originOffset.getX(), originOffset.getY(), originOffset.getZ());
            boolean renderStateStarted = false;
            try {
                renderStateStarted = true;
                surfaceType.setupRenderState();
                float[] revealDistances = {-1.0F, -1.0F, -1.0F, -1.0F};
                for (int index = 0; index < MAX_PROJECTIONS; index++) {
                    Projection projection = index < projections.size() ? projections.get(index) : null;
                    currentShader.setSampler(PROJECTION_SAMPLERS[index], projection == null ? textureId : projection.feed().colorTextureId());
                    Uniform positionUniform = PROJECTOR_POSITION_UNIFORMS[index];
                    if (projection == null) {
                        positionUniform.set(0.0F, 0.0F, 0.0F);
                    } else {
                        if (!loading && (cachedMesh.projectionMask & 1 << index) != 0) {
                            ProjectionRenderManager.markVisible(projection.feed());
                        }
                        revealDistances[index] = projection.revealProgress();
                        BlockPos offset = projection.surface().origin().subtract(cachedMesh.origin);
                        Direction facing = projection.surface().facing();
                        positionUniform.set(
                                offset.getX() + 0.5F + facing.getStepX() * 0.5F,
                                offset.getY() + 0.5F + facing.getStepY() * 0.5F,
                                offset.getZ() + 0.5F + facing.getStepZ() * 0.5F
                        );
                    }
                }
                currentRevealProgressUniform.set(revealDistances[0], revealDistances[1], revealDistances[2], revealDistances[3]);
                currentLoadingOpacityUniform.set(loadingOpacity);
                crossfadeWidthUniform.set(CROSSFADE_WIDTH);
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
        loadingOpacityUniform = loadedShader.getUniform("LoadingOpacity");
        crossfadeWidthUniform = loadedShader.getUniform("CrossfadeWidth");
        for (int index = 0; index < MAX_PROJECTIONS; index++) {
            PROJECTOR_POSITION_UNIFORMS[index] = loadedShader.getUniform("ProjectorPosition" + index);
        }
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

    private static CachedMesh getOrBuild(ClientLevel level, BlockPos projectorPos, ProjectionSurface surface, boolean loading) {
        if (activeLevel != level) {
            resetNow();
            activeLevel = level;
        }
        Map<BlockPos, CachedMesh> levelMeshes = CACHED_MESHES.computeIfAbsent(level, ignored -> new HashMap<>());
        BlockPos key = projectorPos.immutable();
        CachedMesh cachedMesh = levelMeshes.get(key);
        if (cachedMesh != null && cachedMesh.matches(surface, loading)) {
            return cachedMesh.vertexBuffer == null ? null : cachedMesh;
        }

        CachedMesh replacement = build(projectorPos, surface, loading);
        levelMeshes.put(key, replacement);
        if (cachedMesh != null) {
            cachedMesh.close();
        }
        return replacement.vertexBuffer == null ? null : replacement;
    }

    private static CachedMesh build(BlockPos projectorPos, ProjectionSurface surface, boolean loading) {
        List<FaceGeometry> faceGeometries = new ArrayList<>(surface.faces().size());
        Map<EdgeKey, EnumSet<Direction>> edgeNormals = loading ? new HashMap<>() : SHARED_EDGE_NORMALS;
        int projectionMask = 0;
        for (ProjectionSurface.Face face : surface.faces()) {
            FaceGeometry geometry = geometry(face);
            SharedFace shared = SHARED_FACES.get(new FaceKey(face.position(), face.normal()));
            if (loading) {
                addEdgeNormals(geometry, edgeNormals);
            }
            if (loading || shared != null && projections.get(shared.owner).projectorPos().equals(projectorPos)) {
                faceGeometries.add(geometry);
                if (shared != null) {
                    for (int index = 0; index < MAX_PROJECTIONS; index++) {
                        if (shared.distances[index] >= 0) {
                            projectionMask |= 1 << index;
                        }
                    }
                }
            }
        }

        if (faceGeometries.isEmpty()) {
            return new CachedMesh(surface, loading, projectionMask, null);
        }
        BufferBuilder builder = Tesselator.getInstance().begin(
                VertexFormat.Mode.QUADS,
                DefaultVertexFormat.POSITION_TEX_COLOR
        );
        for (FaceGeometry geometry : faceGeometries) {
            emitFace(builder, surface.origin(), geometry, edgeNormals);
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
        return new CachedMesh(surface, loading, projectionMask, vertexBuffer);
    }

    private static void addEdgeNormals(FaceGeometry geometry, Map<EdgeKey, EnumSet<Direction>> edgeNormals) {
        for (EdgeKey edge : geometry.edges()) {
            edgeNormals.computeIfAbsent(edge, ignored -> EnumSet.noneOf(Direction.class))
                    .add(geometry.face().normal());
        }
    }

    private static FaceGeometry geometry(ProjectionSurface.Face face) {
        BlockPos position = face.position();
        IntegerCorner[] corners = {
                corner(position, face.normal(), face.uDirection(), face.vDirection(), false, false),
                corner(position, face.normal(), face.uDirection(), face.vDirection(), true, false),
                corner(position, face.normal(), face.uDirection(), face.vDirection(), true, true),
                corner(position, face.normal(), face.uDirection(), face.vDirection(), false, true)
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
            BlockPos position,
            Direction normal,
            Direction uDirection,
            Direction vDirection,
            boolean highU,
            boolean highV
    ) {
        int x = position.getX() + Math.max(0, normal.getStepX());
        int y = position.getY() + Math.max(0, normal.getStepY());
        int z = position.getZ() + Math.max(0, normal.getStepZ());
        int uOffset = cornerOffset(uDirection, highU);
        int vOffset = cornerOffset(vDirection, highV);
        x += Math.abs(uDirection.getStepX()) * uOffset + Math.abs(vDirection.getStepX()) * vOffset;
        y += Math.abs(uDirection.getStepY()) * uOffset + Math.abs(vDirection.getStepY()) * vOffset;
        z += Math.abs(uDirection.getStepZ()) * uOffset + Math.abs(vDirection.getStepZ()) * vOffset;
        return new IntegerCorner(x, y, z);
    }

    private static void emitFace(
            VertexConsumer vertices,
            BlockPos origin,
            FaceGeometry geometry,
            Map<EdgeKey, EnumSet<Direction>> edgeNormals
    ) {
        ProjectionSurface.Face face = geometry.face();
        SharedFace shared = SHARED_FACES.get(new FaceKey(face.position(), face.normal()));
        int[] distances = shared == null ? new int[]{-1, -1, -1, -1} : shared.distances;
        float u = Math.max(0, distances[0]) + Math.max(0, distances[1]) * DISTANCE_RADIX;
        float v = Math.max(0, distances[2]) + Math.max(0, distances[3]) * DISTANCE_RADIX;
        for (int index = 0; index < geometry.corners().length; index++) {
            EdgeKey previousEdge = geometry.edges()[(index + geometry.edges().length - 1) % geometry.edges().length];
            EdgeKey nextEdge = geometry.edges()[index];
            emitVertex(
                    vertices,
                    origin,
                    geometry.corners()[index],
                    face.normal(),
                    edgeNormals.get(previousEdge),
                    edgeNormals.get(nextEdge),
                    u,
                    v,
                    distances[0] >= 0 ? 255 : 0,
                    distances[1] >= 0 ? 255 : 0,
                    distances[2] >= 0 ? 255 : 0,
                    distances[3] >= 0 ? 255 : 0
            );
        }
    }

    private static void emitVertex(
            VertexConsumer vertices,
            BlockPos origin,
            IntegerCorner corner,
            Direction normal,
            EnumSet<Direction> previousEdgeNormals,
            EnumSet<Direction> nextEdgeNormals,
            float u,
            float v,
            int red,
            int green,
            int blue,
            int alpha
    ) {
        EnumSet<Direction> miterNormals = EnumSet.of(normal);
        if (previousEdgeNormals != null) {
            miterNormals.addAll(previousEdgeNormals);
        }
        if (nextEdgeNormals != null) {
            miterNormals.addAll(nextEdgeNormals);
        }
        float x = corner.x() - origin.getX();
        float y = corner.y() - origin.getY();
        float z = corner.z() - origin.getZ();
        for (Direction miterNormal : miterNormals) {
            x += miterNormal.getStepX() * SURFACE_OFFSET;
            y += miterNormal.getStepY() * SURFACE_OFFSET;
            z += miterNormal.getStepZ() * SURFACE_OFFSET;
        }
        vertices.addVertex(x, y, z)
                .setUv(u, v)
                .setColor(red, green, blue, alpha);
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
        projections = List.of();
        SHARED_FACES.clear();
        SHARED_EDGE_NORMALS.clear();
        layoutVersion++;
        activeLevel = null;
    }

    private static boolean sameSurface(ProjectionSurface first, ProjectionSurface second) {
        return first.version() == second.version()
                && first.topologyHash() == second.topologyHash()
                && first.facing() == second.facing()
                && first.origin().equals(second.origin());
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
        private final long layoutVersion;
        private final boolean loading;
        private final int projectionMask;
        private final VertexBuffer vertexBuffer;

        private CachedMesh(
                ProjectionSurface surface,
                boolean loading,
                int projectionMask,
                VertexBuffer vertexBuffer
        ) {
            this.origin = surface.origin().immutable();
            this.facing = surface.facing();
            this.topologyHash = surface.topologyHash();
            this.version = surface.version();
            this.layoutVersion = ProjectionSurfaceRenderer.layoutVersion;
            this.loading = loading;
            this.projectionMask = projectionMask;
            this.vertexBuffer = vertexBuffer;
        }

        private boolean matches(ProjectionSurface surface, boolean loading) {
            return layoutVersion == ProjectionSurfaceRenderer.layoutVersion
                    && this.loading == loading
                    && version == surface.version()
                    && topologyHash == surface.topologyHash()
                    && facing == surface.facing()
                    && origin.equals(surface.origin());
        }

        @Override
        public void close() {
            if (vertexBuffer != null) {
                vertexBuffer.close();
            }
        }
    }

    public record Projection(
            BlockPos projectorPos,
            ProjectionSurface surface,
            ProjectionRenderManager.ProjectionFeed feed,
            float revealProgress
    ) {
    }

    private record FaceKey(BlockPos position, Direction normal) {
    }

    private static final class SharedFace {
        private final int owner;
        private final int[] distances = new int[MAX_PROJECTIONS];

        private SharedFace(int owner) {
            this.owner = owner;
            Arrays.fill(distances, -1);
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
