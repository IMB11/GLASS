package dev.imb11.sync.packets;

import dev.imb11.blocks.TerminalBlock;
import dev.imb11.blocks.entity.TerminalBlockEntity;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.sync.GNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record C2STerminalChannelChangedPacket(BlockPos pos, String channel) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2STerminalChannelChangedPacket> PACKET_ID = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("glass", "terminal_channel_changed"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2STerminalChannelChangedPacket> PACKET_CODEC = StreamCodec.ofMember((value, buf) -> {
        buf.writeBlockPos(value.pos());
        buf.writeUtf(value.channel(), ChannelManagerPersistence.MAX_WIRE_CHANNEL_NAME_LENGTH);
    }, buf -> new C2STerminalChannelChangedPacket(buf.readBlockPos(), buf.readUtf(ChannelManagerPersistence.MAX_WIRE_CHANNEL_NAME_LENGTH)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    public static void receive(C2STerminalChannelChangedPacket payload, ServerPlayer player) {
        if (!GNetworking.canUseTerminal(player, payload.pos())) {
            return;
        }
        String channel = ChannelManagerPersistence.canonicalChannelName(payload.channel());
        if (channel == null) {
            return;
        }
        var level = player.serverLevel();
        if (!(level.getBlockEntity(payload.pos()) instanceof TerminalBlockEntity terminal)) {
            return;
        }
        ChannelManagerPersistence manager = ChannelManagerPersistence.get(level);
        if (manager.claimTerminal(level, payload.pos(), level.getBlockState(payload.pos()).getValue(TerminalBlock.FACING), channel)) {
            terminal.setChannelFromServer(channel);
        }
    }
}
