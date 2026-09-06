package dev.imb11.neoforge;

import dev.imb11.Glass;
import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.client.GlassClient;
import dev.imb11.client.gui.ProjectorBlockGUI;
import dev.imb11.client.gui.ProjectorBlockScreen;
import dev.imb11.client.gui.TerminalBlockGUI;
import dev.imb11.client.gui.TerminalBlockScreen;
import dev.imb11.client.renderer.block.ProjectorBlockEntityRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.io.IOException;
import java.io.UncheckedIOException;

@Mod(value = Glass.MOD_ID, dist = Dist.CLIENT)
public final class GlassNeoForgeClient {
    public GlassNeoForgeClient(IEventBus modBus) {
        GlassClient.initialize();
        modBus.addListener(GlassNeoForgeClient::setup);
        modBus.addListener(GlassNeoForgeClient::registerRenderers);
        modBus.addListener(GlassNeoForgeClient::registerScreens);
        modBus.addListener(GlassNeoForgeClient::registerShaders);
        NeoForge.EVENT_BUS.addListener(GlassNeoForgeClient::tick);
        NeoForge.EVENT_BUS.addListener(GlassNeoForgeClient::loggingIn);
        NeoForge.EVENT_BUS.addListener(GlassNeoForgeClient::loggingOut);
        NeoForge.EVENT_BUS.addListener(GlassNeoForgeClient::chunkUnloaded);
    }

    private static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ItemBlockRenderTypes.setRenderLayer(GBlocks.PROJECTOR, RenderType.cutout());
            ItemBlockRenderTypes.setRenderLayer(GBlocks.POWERABLE_GLASS, RenderType.cutout());
        });
    }

    private static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ProjectorBlockEntity.BLOCK_ENTITY_TYPE, ProjectorBlockEntityRenderer::new);
    }

    private static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(TerminalBlockGUI.SCREEN_HANDLER_TYPE, TerminalBlockScreen::new);
        event.register(ProjectorBlockGUI.SCREEN_HANDLER_TYPE, ProjectorBlockScreen::new);
    }

    private static void registerShaders(RegisterShadersEvent event) {
        try {
            GlassClient.registerProjectionShader((id, format, onLoaded) ->
                    event.registerShader(new ShaderInstance(event.getResourceProvider(), id, format), onLoaded));
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to load GLASS projection shader", exception);
        }
    }

    private static void tick(ClientTickEvent.Pre event) {
        GlassClient.tick(Minecraft.getInstance());
    }

    private static void loggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        GlassClient.clearConnection();
    }

    private static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        GlassClient.clearConnection();
    }

    private static void chunkUnloaded(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel level && event.getChunk() instanceof LevelChunk chunk) {
            GlassClient.onChunkUnload(level, chunk);
        }
    }
}
