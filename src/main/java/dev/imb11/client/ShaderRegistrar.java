package dev.imb11.client;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.util.function.Consumer;

@FunctionalInterface
public interface ShaderRegistrar {
    void register(ResourceLocation id, VertexFormat format, Consumer<ShaderInstance> onLoaded) throws IOException;
}
