package dev.imb11.mixins;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LevelRenderer.class)
public interface LevelRendererInvoker {
    @Invoker("renderEntity")
    void glass$renderEntity(Entity entity, double x, double y, double z, float partialTick, PoseStack poses, MultiBufferSource buffers);
}
