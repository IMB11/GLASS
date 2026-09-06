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
import dev.imb11.platform.PlatformNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class GNetworking {
    private static final double MAX_INTERACTION_DISTANCE_SQUARED = 64.0D;

    public static void initialize() {
        PlatformNetworking.registerServerbound(C2SCreateChannelPacket.PACKET_ID, C2SCreateChannelPacket.PACKET_CODEC, C2SCreateChannelPacket::receive);
        PlatformNetworking.registerServerbound(C2SDeleteChannelPacket.PACKET_ID, C2SDeleteChannelPacket.PACKET_CODEC, C2SDeleteChannelPacket::receive);
        PlatformNetworking.registerServerbound(C2STerminalChannelChangedPacket.PACKET_ID, C2STerminalChannelChangedPacket.PACKET_CODEC, C2STerminalChannelChangedPacket::receive);
        PlatformNetworking.registerServerbound(C2SRemoveLinkedChannelPacket.PACKET_ID, C2SRemoveLinkedChannelPacket.PACKET_CODEC, C2SRemoveLinkedChannelPacket::receive);
        PlatformNetworking.registerServerbound(C2SProjectorChannelChangedPacket.PACKET_ID, C2SProjectorChannelChangedPacket.PACKET_CODEC, C2SProjectorChannelChangedPacket::receive);
        PlatformNetworking.registerServerbound(C2SRemoteSubscribePacket.PACKET_ID, C2SRemoteSubscribePacket.PACKET_CODEC, (payload, player) -> RemoteSceneServerManager.subscribe(player, payload));
        PlatformNetworking.registerServerbound(C2SRemoteChunkAckPacket.PACKET_ID, C2SRemoteChunkAckPacket.PACKET_CODEC, (payload, player) -> RemoteSceneServerManager.acknowledge(player, payload));
        PlatformNetworking.registerServerbound(C2SRemoteUpdatePacket.PACKET_ID, C2SRemoteUpdatePacket.PACKET_CODEC, (payload, player) -> RemoteSceneServerManager.update(player, payload));
        PlatformNetworking.registerServerbound(C2SRemoteUnsubscribePacket.PACKET_ID, C2SRemoteUnsubscribePacket.PACKET_CODEC, (payload, player) -> RemoteSceneServerManager.unsubscribe(player, payload));
        PlatformNetworking.registerClientbound(S2CChannelSnapshotPacket.PACKET_ID, S2CChannelSnapshotPacket.PACKET_CODEC);
        PlatformNetworking.registerClientbound(S2CRemoteSubscriptionPacket.PACKET_ID, S2CRemoteSubscriptionPacket.PACKET_CODEC);
        PlatformNetworking.registerClientbound(S2CRemoteUnavailablePacket.PACKET_ID, S2CRemoteUnavailablePacket.PACKET_CODEC);
        PlatformNetworking.registerClientbound(S2CRemoteChunkPacket.PACKET_ID, S2CRemoteChunkPacket.PACKET_CODEC);
        PlatformNetworking.registerClientbound(S2CRemoteEntitiesPacket.PACKET_ID, S2CRemoteEntitiesPacket.PACKET_CODEC);
        PlatformNetworking.registerClientbound(S2CRemoteBlockEntitiesPacket.PACKET_ID, S2CRemoteBlockEntitiesPacket.PACKET_CODEC);
        PlatformNetworking.registerClientbound(S2CRemoteUnloadPacket.PACKET_ID, S2CRemoteUnloadPacket.PACKET_CODEC);
        PlatformNetworking.registerClientbound(S2CRemoteBlockUpdatesPacket.PACKET_ID, S2CRemoteBlockUpdatesPacket.PACKET_CODEC);
        PlatformNetworking.registerClientbound(S2CRemoteLightPacket.PACKET_ID, S2CRemoteLightPacket.PACKET_CODEC);
        PlatformNetworking.registerClientbound(S2CRemoteWorldStatePacket.PACKET_ID, S2CRemoteWorldStatePacket.PACKET_CODEC);
    }

    public static void sendSnapshot(ServerPlayer player) {
        if (PlatformNetworking.canSend(player, S2CChannelSnapshotPacket.PACKET_ID)) {
            PlatformNetworking.send(player, ChannelManagerPersistence.get(player.serverLevel().getServer()).snapshotPacket());
        }
    }

    public static void broadcastSnapshot(MinecraftServer server, ChannelManagerPersistence persistence) {
        S2CChannelSnapshotPacket snapshot = persistence.snapshotPacket();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (PlatformNetworking.canSend(player, S2CChannelSnapshotPacket.PACKET_ID)) {
                PlatformNetworking.send(player, snapshot);
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
