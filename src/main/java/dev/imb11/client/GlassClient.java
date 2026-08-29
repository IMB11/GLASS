package dev.imb11.client;

import dev.imb11.client.gui.ProjectorBlockGUI;
import dev.imb11.client.gui.ProjectorBlockScreen;
import dev.imb11.client.gui.TerminalBlockGUI;
import dev.imb11.client.gui.TerminalBlockScreen;
import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.client.renderer.projection.ProjectionSurfaceRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.minecraft.client.gui.screens.MenuScreens;

public class GlassClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        GBlocks.initClient();
        CoreShaderRegistrationCallback.EVENT.register(ProjectionSurfaceRenderer::registerShader);
        InvalidateRenderStateCallback.EVENT.register(ProjectionSurfaceRenderer::invalidate);
        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> {
            if (blockEntity instanceof ProjectorBlockEntity projector) {
                ProjectionSurfaceRenderer.release(level, projector.getBlockPos());
            }
        });

        MenuScreens.<TerminalBlockGUI, TerminalBlockScreen>register(TerminalBlockGUI.SCREEN_HANDLER_TYPE, (gui, inventory, title) -> new TerminalBlockScreen(gui, inventory.player, title));
        MenuScreens.<ProjectorBlockGUI, ProjectorBlockScreen>register(ProjectorBlockGUI.SCREEN_HANDLER_TYPE, (gui, inventory, title) -> new ProjectorBlockScreen(gui, inventory.player, title));
    }
}
