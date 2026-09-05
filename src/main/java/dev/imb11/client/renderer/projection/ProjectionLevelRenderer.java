package dev.imb11.client.renderer.projection;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.imb11.mixins.LevelRendererBufferAccessor;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

public class ProjectionLevelRenderer extends LevelRenderer {
    private final Minecraft minecraft;
    private final RenderBuffers buffers;
    private final int renderDistance;
    private ClientLevel projectionLevel;
    private SectionRenderDispatcher dispatcher;
    private ChunkPos gridCenter;
    private Vec3 lastSortCamera = Vec3.ZERO;

    public ProjectionLevelRenderer(Minecraft minecraft, RenderBuffers buffers, int renderDistance) {
        super(minecraft, minecraft.getEntityRenderDispatcher(), minecraft.getBlockEntityRenderDispatcher(), buffers);
        this.minecraft = minecraft;
        this.buffers = buffers;
        this.renderDistance = renderDistance;
    }

    @Override
    public void setLevel(@Nullable ClientLevel level) {
        LevelRendererBufferAccessor accessor = (LevelRendererBufferAccessor) this;
        ViewArea area = accessor.glass$getViewArea();
        if (area != null) {
            area.releaseAllBuffers();
            accessor.glass$setViewArea(null);
        }
        if (dispatcher != null) {
            dispatcher.dispose();
            dispatcher = null;
        }
        accessor.glass$getVisibleSections().clear();
        synchronized (accessor.glass$getGlobalBlockEntities()) {
            accessor.glass$getGlobalBlockEntities().clear();
        }
        gridCenter = null;
        lastSortCamera = Vec3.ZERO;
        projectionLevel = level;
        accessor.glass$setLevel(level);
        if (level != null) {
            allChanged();
        }
    }

    @Override
    public void allChanged() {
        if (projectionLevel == null) {
            return;
        }
        LevelRendererBufferAccessor accessor = (LevelRendererBufferAccessor) this;
        ViewArea previous = accessor.glass$getViewArea();
        if (previous != null) {
            previous.releaseAllBuffers();
        }
        if (dispatcher == null) {
            dispatcher = new SectionRenderDispatcher(projectionLevel, this, Util.backgroundExecutor(), buffers,
                    minecraft.getBlockRenderer(), minecraft.getBlockEntityRenderDispatcher());
        } else {
            dispatcher.blockUntilClear();
        }
        ItemBlockRenderTypes.setFancy(Minecraft.useFancyGraphics());
        accessor.glass$getVisibleSections().clear();
        synchronized (accessor.glass$getGlobalBlockEntities()) {
            accessor.glass$getGlobalBlockEntities().clear();
        }
        accessor.glass$setViewArea(new ViewArea(dispatcher, projectionLevel, renderDistance, this));
        gridCenter = null;
        lastSortCamera = Vec3.ZERO;
        needsUpdate();
    }

    public void prepareCamera(ProjectionCamera camera) {
        ChunkPos center = camera.gridCenter();
        if (!center.equals(gridCenter)) {
            ((LevelRendererBufferAccessor) this).glass$getViewArea()
                    .repositionCamera(center.getMiddleBlockX(), center.getMiddleBlockZ());
            gridCenter = center;
        }
        dispatcher.setCamera(camera.getPosition());
    }

    public void renderTerrainLayer(RenderType layer, Vec3 camera, Matrix4f modelView, Matrix4f projection) {
        RenderSystem.assertOnRenderThread();
        if (layer == RenderType.translucent() && !ProjectionRenderManager.usesSharedTerrain(this)) {
            sortTransparency(camera);
        }
        layer.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) {
            layer.clearRenderState();
            return;
        }
        Uniform offset = shader.CHUNK_OFFSET;
        try {
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, minecraft.getWindow());
            shader.apply();
            var sections = ((LevelRendererBufferAccessor) this).glass$getVisibleSections();
            boolean translucent = layer == RenderType.translucent();
            for (int i = 0; i < sections.size(); i++) {
                SectionRenderDispatcher.RenderSection section = sections.get(translucent ? sections.size() - 1 - i : i);
                if (section.getCompiled().isEmpty(layer)) {
                    continue;
                }
                BlockPos origin = section.getOrigin();
                if (offset != null) {
                    offset.set((float) (origin.getX() - camera.x), (float) (origin.getY() - camera.y),
                            (float) (origin.getZ() - camera.z));
                    offset.upload();
                }
                VertexBuffer buffer = section.getBuffer(layer);
                buffer.bind();
                buffer.draw();
            }
        } finally {
            if (offset != null) {
                offset.set(0.0F, 0.0F, 0.0F);
            }
            shader.clear();
            VertexBuffer.unbind();
            layer.clearRenderState();
        }
    }

    private void sortTransparency(Vec3 camera) {
        if (camera.distanceToSqr(lastSortCamera) <= 1.0D) {
            return;
        }
        SectionPos sectionPos = SectionPos.of(BlockPos.containing(camera));
        boolean changedSection = !sectionPos.equals(SectionPos.of(BlockPos.containing(lastSortCamera)));
        lastSortCamera = camera;
        int sorted = 0;
        for (SectionRenderDispatcher.RenderSection section : ((LevelRendererBufferAccessor) this).glass$getVisibleSections()) {
            if ((changedSection || section.isAxisAlignedWith(sectionPos.x(), sectionPos.y(), sectionPos.z()))
                    && section.resortTransparency(RenderType.translucent(), dispatcher) && ++sorted >= 15) {
                break;
            }
        }
    }

    @Override
    public SectionRenderDispatcher getSectionRenderDispatcher() {
        return dispatcher;
    }

    @Override
    public boolean hasRenderedAllSections() {
        return dispatcher == null || dispatcher.isQueueEmpty();
    }

    @Override
    public int countRenderedSections() {
        return (int) ((LevelRendererBufferAccessor) this).glass$getVisibleSections().stream()
                .filter(section -> !section.getCompiled().hasNoRenderableLayers()).count();
    }

    @Override
    public String getSectionStatistics() {
        return dispatcher == null ? "none" : dispatcher.getStats();
    }

    @Override
    public boolean isSectionCompiled(BlockPos pos) {
        SectionRenderDispatcher.RenderSection section = ProjectionSections.find(this,
                SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()));
        return section != null && section.getCompiled() != SectionRenderDispatcher.CompiledSection.UNCOMPILED;
    }

    @Override
    public void setSectionDirty(int x, int y, int z) {
        SectionRenderDispatcher.RenderSection section = ProjectionSections.find(this, x, y, z);
        if (section != null) {
            section.setDirty(false);
        }
    }

    @Override
    public void setSectionDirtyWithNeighbors(int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    setSectionDirty(x + dx, y + dy, z + dz);
                }
            }
        }
    }

    @Override
    public void onChunkLoaded(ChunkPos pos) {
    }

    @Override
    public void addRecentlyCompiledSection(SectionRenderDispatcher.RenderSection section) {
    }
}
