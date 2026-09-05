package dev.imb11.client.renderer.projection;

import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.SectionBufferBuilderPool;

public final class ProjectionRenderBuffers extends RenderBuffers {
    private final SectionBufferBuilderPool projectionPool;

    public ProjectionRenderBuffers(int bufferCount) {
        super(bufferCount);
        SectionBufferBuilderPool pool = super.sectionBufferPool();
        projectionPool = pool.getFreeBufferCount() > 0 ? pool : SectionBufferBuilderPool.allocate(bufferCount);
    }

    @Override
    public SectionBufferBuilderPool sectionBufferPool() {
        return projectionPool;
    }
}
