package dev.imb11.mixins;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Queue;

@Mixin(SectionRenderDispatcher.class)
public interface SectionRenderDispatcherAccessor {
    @Accessor("toUpload")
    Queue<Runnable> glass$getPendingUploads();
}
