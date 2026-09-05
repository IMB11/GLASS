package dev.imb11.client.renderer.projection;

import dev.imb11.mixins.LevelRendererBufferAccessor;
import dev.imb11.mixins.ViewAreaInvoker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.Nullable;

public final class ProjectionSections {
    private ProjectionSections() {
    }

    public static @Nullable SectionRenderDispatcher.RenderSection find(LevelRenderer renderer, int x, int y, int z) {
        ViewArea area = ((LevelRendererBufferAccessor) renderer).glass$getViewArea();
        if (area == null) {
            return null;
        }
        BlockPos origin = SectionPos.of(x, y, z).origin();
        SectionRenderDispatcher.RenderSection section = ((ViewAreaInvoker) area).glass$getRenderSectionAt(origin);
        return section != null && section.getOrigin().equals(origin) ? section : null;
    }
}
