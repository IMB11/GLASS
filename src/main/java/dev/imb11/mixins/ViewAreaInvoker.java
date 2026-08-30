package dev.imb11.mixins;

import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ViewArea.class)
public interface ViewAreaInvoker {
    @Invoker("getRenderSectionAt")
    SectionRenderDispatcher.RenderSection glass$getRenderSectionAt(BlockPos pos);
}
