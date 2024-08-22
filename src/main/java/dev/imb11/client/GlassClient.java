package dev.imb11.client;

import dev.imb11.client.renderer.TestProjectorBlockEntityRenderer;
import dev.imb11.registry.GBlockEntities;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;

public class GlassClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        BlockEntityRendererFactories.register(GBlockEntities.TEST_PROJECTOR_BLOCK_ENTITY_TYPE, new TestProjectorBlockEntityRenderer());
    }
}
