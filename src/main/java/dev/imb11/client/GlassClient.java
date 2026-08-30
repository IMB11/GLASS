package dev.imb11.client;

import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.client.gui.ProjectorBlockGUI;
import dev.imb11.client.gui.ProjectorBlockScreen;
import dev.imb11.client.gui.TerminalBlockGUI;
import dev.imb11.client.gui.TerminalBlockScreen;
import dev.imb11.client.renderer.projection.ProjectionRenderManager;
import dev.imb11.client.renderer.projection.ProjectionSurfaceRenderer;
import dev.imb11.sync.packets.S2CChannelSnapshotPacket;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.multiplayer.ClientLevel;

import java.io.IOException;

public class GlassClient implements ClientModInitializer {
    private static volatile ClientLevel activeLevel;

    @Override
    public void onInitializeClient() {
        GBlocks.initClient();
        CoreShaderRegistrationCallback.EVENT.register(GlassClient::registerProjectionShader);
        InvalidateRenderStateCallback.EVENT.register(ProjectionSurfaceRenderer::invalidate);
        ClientPlayNetworking.registerGlobalReceiver(
                S2CChannelSnapshotPacket.PACKET_ID,
                (packet, context) -> ClientProjectionSourceRegistry.applySnapshot(packet)
        );
        ClientPlayConnectionEvents.INIT.register((handler, client) -> clearConnection());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clearConnection());
        ClientTickEvents.START_CLIENT_TICK.register(GlassClient::trackLevel);
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) ->
                ProjectionRenderManager.onClientChunkUnloaded(level, chunk.getPos())
        );
        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> {
            if (blockEntity instanceof ProjectorBlockEntity projector) {
                ProjectionSurfaceRenderer.release(level, projector.getBlockPos());
            }
        });

        MenuScreens.<TerminalBlockGUI, TerminalBlockScreen>register(TerminalBlockGUI.SCREEN_HANDLER_TYPE, (gui, inventory, title) -> new TerminalBlockScreen(gui, inventory.player, title));
        MenuScreens.<ProjectorBlockGUI, ProjectorBlockScreen>register(ProjectorBlockGUI.SCREEN_HANDLER_TYPE, (gui, inventory, title) -> new ProjectorBlockScreen(gui, inventory.player, title));
    }

    private static void registerProjectionShader(CoreShaderRegistrationCallback.RegistrationContext context) throws IOException {
        ProjectionRenderManager.reset();
        ProjectionSurfaceRenderer.registerShader(context);
    }

    private static void trackLevel(Minecraft minecraft) {
        ClientLevel currentLevel = minecraft.level;
        if (activeLevel == currentLevel) {
            return;
        }

        ClientLevel previousLevel = activeLevel;
        activeLevel = currentLevel;
        if (previousLevel != null) {
            ProjectionSurfaceRenderer.releaseLevel(previousLevel);
        }
        if (currentLevel == null) {
            ProjectionRenderManager.reset();
        } else {
            ProjectionRenderManager.onClientLevelChanged(currentLevel);
        }
    }

    private static void clearConnection() {
        activeLevel = null;
        ClientProjectionSourceRegistry.clear();
        ProjectionRenderManager.reset();
        ProjectionSurfaceRenderer.reset();
    }
}
