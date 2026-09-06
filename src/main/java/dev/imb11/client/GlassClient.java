package dev.imb11.client;

import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.client.renderer.block.ProjectorBlockEntityRenderer;
import dev.imb11.client.renderer.projection.ProjectionRenderManager;
import dev.imb11.client.renderer.projection.ProjectionSurfaceRenderer;
import dev.imb11.client.remote.RemoteSceneClientManager;
import dev.imb11.platform.ClientNetworking;
import dev.imb11.sync.packets.S2CChannelSnapshotPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.IOException;

public final class GlassClient {
    private static volatile ClientLevel activeLevel;

    private GlassClient() {
    }

    public static void initialize() {
        ClientNetworking.registerReceiver(S2CChannelSnapshotPacket.PACKET_ID, ClientProjectionSourceRegistry::applySnapshot);
        RemoteSceneClientManager.registerReceivers();
    }

    public static void registerProjectionShader(ShaderRegistrar registrar) throws IOException {
        ProjectionRenderManager.reset();
        RemoteSceneClientManager.onResourceReload();
        ProjectorBlockEntityRenderer.reset();
        ProjectionSurfaceRenderer.registerShader(registrar);
    }

    public static void invalidateRenderState() {
        ProjectionSurfaceRenderer.invalidate();
        ProjectorBlockEntityRenderer.reset();
    }

    public static void onChunkUnload(ClientLevel level, LevelChunk chunk) {
        ProjectionRenderManager.onClientChunkUnloaded(level, chunk.getPos());
    }

    public static void onBlockEntityLoad(BlockEntity blockEntity, ClientLevel level) {
        if (level == Minecraft.getInstance().level && blockEntity instanceof ProjectorBlockEntity projector) {
            ProjectorBlockEntityRenderer.registerLoaded(level, projector);
        }
    }

    public static void onBlockEntityUnload(BlockEntity blockEntity, ClientLevel level) {
        if (level == Minecraft.getInstance().level && blockEntity instanceof ProjectorBlockEntity projector) {
            ProjectorBlockEntityRenderer.unregisterLoaded(level, projector);
            ProjectionSurfaceRenderer.release(level, projector.getBlockPos());
            ProjectorBlockEntityRenderer.release(level, projector.getBlockPos());
            ProjectionRenderManager.releaseProjector(level, projector.getBlockPos());
        }
    }

    public static void tick(Minecraft minecraft) {
        trackLevel(minecraft);
        RemoteSceneClientManager.tick(minecraft);
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
            ProjectorBlockEntityRenderer.releaseLevel(previousLevel);
        }
        if (currentLevel == null) {
            ProjectionRenderManager.reset();
        } else {
            ProjectorBlockEntityRenderer.onMainRendererRebuilt(minecraft.levelRenderer);
            ProjectionRenderManager.onClientLevelChanged(currentLevel);
        }
        RemoteSceneClientManager.onMainLevelChanged(currentLevel);
    }

    public static void clearConnection() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(GlassClient::clearConnection);
            return;
        }
        activeLevel = null;
        ClientProjectionSourceRegistry.clear();
        ProjectionRenderManager.reset();
        RemoteSceneClientManager.clearConnection();
        ProjectionSurfaceRenderer.reset();
        ProjectorBlockEntityRenderer.reset();
        ProjectorBlockEntityRenderer.clearLoaded();
    }
}
