package dev.imb11.fabric;

import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.client.GlassClient;
import dev.imb11.client.gui.ProjectorBlockGUI;
import dev.imb11.client.gui.ProjectorBlockScreen;
import dev.imb11.client.gui.TerminalBlockGUI;
import dev.imb11.client.gui.TerminalBlockScreen;
import dev.imb11.client.renderer.block.ProjectorBlockEntityRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;

public final class GlassFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        GlassClient.initialize();
        BlockEntityRenderers.register(ProjectorBlockEntity.BLOCK_ENTITY_TYPE, ProjectorBlockEntityRenderer::new);
        BlockRenderLayerMap.INSTANCE.putBlock(GBlocks.PROJECTOR, RenderType.cutout());
        BlockRenderLayerMap.INSTANCE.putBlock(GBlocks.POWERABLE_GLASS, RenderType.cutout());
        CoreShaderRegistrationCallback.EVENT.register(context -> GlassClient.registerProjectionShader(context::register));
        ClientPlayConnectionEvents.INIT.register((handler, client) -> GlassClient.clearConnection());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> GlassClient.clearConnection());
        ClientTickEvents.START_CLIENT_TICK.register(GlassClient::tick);
        ClientChunkEvents.CHUNK_UNLOAD.register(GlassClient::onChunkUnload);
        ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register(GlassClient::onBlockEntityLoad);
        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register(GlassClient::onBlockEntityUnload);
        MenuScreens.<TerminalBlockGUI, TerminalBlockScreen>register(TerminalBlockGUI.SCREEN_HANDLER_TYPE, (gui, inventory, title) -> new TerminalBlockScreen(gui, inventory.player, title));
        MenuScreens.<ProjectorBlockGUI, ProjectorBlockScreen>register(ProjectorBlockGUI.SCREEN_HANDLER_TYPE, (gui, inventory, title) -> new ProjectorBlockScreen(gui, inventory.player, title));
    }
}
