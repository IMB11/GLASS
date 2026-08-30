package dev.imb11.mixins;

import com.mojang.blaze3d.vertex.VertexBuffer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

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
}
