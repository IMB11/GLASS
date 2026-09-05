package dev.imb11.mixins;

import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.Set;

@Mixin(LevelRenderer.class)
public interface LevelRendererBufferAccessor {
    @Accessor("starBuffer")
    VertexBuffer glass$getStarBuffer();

    @Accessor("skyBuffer")
    VertexBuffer glass$getSkyBuffer();

    @Accessor("darkBuffer")
    VertexBuffer glass$getDarkBuffer();

    @Accessor("cloudBuffer")
    VertexBuffer glass$getCloudBuffer();

    @Accessor("viewArea")
    ViewArea glass$getViewArea();

    @Accessor("viewArea")
    void glass$setViewArea(ViewArea viewArea);

    @Accessor("level")
    void glass$setLevel(ClientLevel level);

    @Accessor("visibleSections")
    ObjectArrayList<SectionRenderDispatcher.RenderSection> glass$getVisibleSections();

    @Accessor("globalBlockEntities")
    Set<BlockEntity> glass$getGlobalBlockEntities();
}
