package dev.imb11.sync;

import dev.imb11.blocks.GBlocks;
import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.blocks.entity.TerminalBlockEntity;
import dev.imb11.client.gui.ProjectorBlockGUI;
import dev.imb11.client.gui.TerminalBlockGUI;
import dev.imb11.sync.packets.C2SCreateChannelPacket;
import dev.imb11.sync.packets.C2SDeleteChannelPacket;
import dev.imb11.sync.packets.C2SProjectorChannelChangedPacket;
import dev.imb11.sync.packets.C2SRemoveLinkedChannelPacket;
import dev.imb11.sync.packets.C2STerminalChannelChangedPacket;
import dev.imb11.sync.packets.S2CChannelSnapshotPacket;
import dev.imb11.sync.remote.C2SRemoteSubscribePacket;
import dev.imb11.sync.remote.C2SRemoteChunkAckPacket;
import dev.imb11.sync.remote.C2SRemoteUnsubscribePacket;
import dev.imb11.sync.remote.C2SRemoteUpdatePacket;
import dev.imb11.sync.remote.RemoteSceneServerManager;
import dev.imb11.sync.remote.S2CRemoteBlockUpdatesPacket;
import dev.imb11.sync.remote.S2CRemoteChunkPacket;
import dev.imb11.sync.remote.S2CRemoteEntitiesPacket;
import dev.imb11.sync.remote.S2CRemoteBlockEntitiesPacket;
import dev.imb11.sync.remote.S2CRemoteLightPacket;
import dev.imb11.sync.remote.S2CRemoteSubscriptionPacket;
import dev.imb11.sync.remote.S2CRemoteUnavailablePacket;
import dev.imb11.sync.remote.S2CRemoteUnloadPacket;
import dev.imb11.sync.remote.S2CRemoteWorldStatePacket;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class GNetworking {
    private static final double MAX_INTERACTION_DISTANCE_SQUARED = 64.0D;

    public static void initialize() {
        PayloadTypeRegistry.playC2S().register(C2SCreateChannelPacket.PACKET_ID, C2SCreateChannelPacket.PACKET_CODEC);
        PayloadTypeRegistry.playC2S().register(C2SDeleteChannelPacket.PACKET_ID, C2SDeleteChannelPacket.PACKET_CODEC);
        PayloadTypeRegistry.playC2S().register(C2STerminalChannelChangedPacket.PACKET_ID, C2STerminalChannelChangedPacket.PACKET_CODEC);
        PayloadTypeRegistry.playC2S().register(C2SRemoveLinkedChannelPacket.PACKET_ID, C2SRemoveLinkedChannelPacket.PACKET_CODEC);
        PayloadTypeRegistry.playC2S().register(C2SProjectorChannelChangedPacket.PACKET_ID, C2SProjectorChannelChangedPacket.PACKET_CODEC);
        PayloadTypeRegistry.playC2S().register(C2SRemoteSubscribePacket.PACKET_ID, C2SRemoteSubscribePacket.PACKET_CODEC);
        PayloadTypeRegistry.playC2S().register(C2SRemoteChunkAckPacket.PACKET_ID, C2SRemoteChunkAckPacket.PACKET_CODEC);
        PayloadTypeRegistry.playC2S().register(C2SRemoteUpdatePacket.PACKET_ID, C2SRemoteUpdatePacket.PACKET_CODEC);
        PayloadTypeRegistry.playC2S().register(C2SRemoteUnsubscribePacket.PACKET_ID, C2SRemoteUnsubscribePacket.PACKET_CODEC);
        PayloadTypeRegistry.playS2C().register(S2CChannelSnapshotPacket.PACKET_ID, S2CChannelSnapshotPacket.PACKET_CODEC);
        PayloadTypeRegistry.playS2C().register(S2CRemoteSubscriptionPacket.PACKET_ID, S2CRemoteSubscriptionPacket.PACKET_CODEC);
        PayloadTypeRegistry.playS2C().register(S2CRemoteUnavailablePacket.PACKET_ID, S2CRemoteUnavailablePacket.PACKET_CODEC);
        PayloadTypeRegistry.playS2C().register(S2CRemoteChunkPacket.PACKET_ID, S2CRemoteChunkPacket.PACKET_CODEC);
        PayloadTypeRegistry.playS2C().register(S2CRemoteEntitiesPacket.PACKET_ID, S2CRemoteEntitiesPacket.PACKET_CODEC);
        PayloadTypeRegistry.playS2C().register(S2CRemoteBlockEntitiesPacket.PACKET_ID, S2CRemoteBlockEntitiesPacket.PACKET_CODEC);
        PayloadTypeRegistry.playS2C().register(S2CRemoteUnloadPacket.PACKET_ID, S2CRemoteUnloadPacket.PACKET_CODEC);
        PayloadTypeRegistry.playS2C().register(S2CRemoteBlockUpdatesPacket.PACKET_ID, S2CRemoteBlockUpdatesPacket.PACKET_CODEC);
        PayloadTypeRegistry.playS2C().register(S2CRemoteLightPacket.PACKET_ID, S2CRemoteLightPacket.PACKET_CODEC);
        PayloadTypeRegistry.playS2C().register(S2CRemoteWorldStatePacket.PACKET_ID, S2CRemoteWorldStatePacket.PACKET_CODEC);

        ServerPlayNetworking.registerGlobalReceiver(C2SCreateChannelPacket.PACKET_ID, C2SCreateChannelPacket::receive);
        ServerPlayNetworking.registerGlobalReceiver(C2SDeleteChannelPacket.PACKET_ID, C2SDeleteChannelPacket::receive);
        ServerPlayNetworking.registerGlobalReceiver(C2STerminalChannelChangedPacket.PACKET_ID, C2STerminalChannelChangedPacket::receive);
        ServerPlayNetworking.registerGlobalReceiver(C2SRemoveLinkedChannelPacket.PACKET_ID, C2SRemoveLinkedChannelPacket::receive);
        ServerPlayNetworking.registerGlobalReceiver(C2SProjectorChannelChangedPacket.PACKET_ID, C2SProjectorChannelChangedPacket::receive);
        ServerPlayNetworking.registerGlobalReceiver(C2SRemoteSubscribePacket.PACKET_ID, (payload, context) -> RemoteSceneServerManager.subscribe(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(C2SRemoteChunkAckPacket.PACKET_ID, (payload, context) -> RemoteSceneServerManager.acknowledge(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(C2SRemoteUpdatePacket.PACKET_ID, (payload, context) -> RemoteSceneServerManager.update(context.player(), payload));
        ServerPlayNetworking.registerGlobalReceiver(C2SRemoteUnsubscribePacket.PACKET_ID, (payload, context) -> RemoteSceneServerManager.unsubscribe(context.player(), payload));

        RemoteSceneServerManager.init();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendSnapshot(handler.getPlayer()));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> sendSnapshot(player));
    }

    public static void sendSnapshot(ServerPlayer player) {
        if (ServerPlayNetworking.canSend(player, S2CChannelSnapshotPacket.PACKET_ID)) {
            ServerPlayNetworking.send(player, ChannelManagerPersistence.get(player.serverLevel().getServer()).snapshotPacket());
        }
    }

    public static void broadcastSnapshot(MinecraftServer server, ChannelManagerPersistence persistence) {
        S2CChannelSnapshotPacket snapshot = persistence.snapshotPacket();
        for (ServerPlayer player : PlayerLookup.all(server)) {
            if (ServerPlayNetworking.canSend(player, S2CChannelSnapshotPacket.PACKET_ID)) {
                ServerPlayNetworking.send(player, snapshot);
            }
        }
    }

    public static boolean canUseTerminal(ServerPlayer player, BlockPos pos) {
        if (!(player.containerMenu instanceof TerminalBlockGUI menu) || !menu.isFor(pos)) {
            return false;
        }
        return validTarget(player, pos, true);
    }

    public static boolean canUseProjector(ServerPlayer player, BlockPos pos) {
        if (!(player.containerMenu instanceof ProjectorBlockGUI menu) || !menu.isFor(pos)) {
            return false;
        }
        return validTarget(player, pos, false);
    }

    private static boolean validTarget(ServerPlayer player, BlockPos pos, boolean terminal) {
        var level = player.serverLevel();
        if (!level.hasChunkAt(pos)
                || player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > MAX_INTERACTION_DISTANCE_SQUARED) {
            return false;
        }
        if (terminal) {
            return level.getBlockState(pos).is(GBlocks.TERMINAL) && level.getBlockEntity(pos) instanceof TerminalBlockEntity;
        }
        return level.getBlockState(pos).is(GBlocks.PROJECTOR) && level.getBlockEntity(pos) instanceof ProjectorBlockEntity;
    }

    private GNetworking() {
    }
}
