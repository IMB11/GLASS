package dev.imb11.sync.packets;

import dev.imb11.blocks.entity.TerminalBlockEntity;
import dev.imb11.sync.Channel;
import dev.imb11.sync.ChannelManagerPersistence;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record C2SRemoveLinkedChannelPacket(BlockPos pos) implements CustomPacketPayload, ServerPlayNetworking.PlayPayloadHandler<C2SRemoveLinkedChannelPacket> {
    public static final CustomPacketPayload.Type<C2SRemoveLinkedChannelPacket> PACKET_ID = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("glass", "remove_linked_channel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRemoveLinkedChannelPacket> PACKET_CODEC = StreamCodec.ofMember((value, buf) -> {
        buf.writeBlockPos(value.pos());
    }, (buf) -> new C2SRemoveLinkedChannelPacket(buf.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    @Override
    public void receive(C2SRemoveLinkedChannelPacket payload, ServerPlayNetworking.Context context) {
        BlockPos pos = payload.pos();

        var player = context.player();
        var server = context.server();

        server.executeIfPossible(() -> {
            var channelManager = ChannelManagerPersistence.get(player.level());

            var entity = player.level().getBlockEntity(pos);

            String cachedChannel = "";

            if (entity instanceof TerminalBlockEntity terminal) {
                cachedChannel = new String(terminal.channel.toCharArray());
                terminal.channel = "";
                terminal.setChanged();
            }

            channelManager.removeIf(channels -> {
                if (channels.linkedBlock() != null) {
                    return channels.linkedBlock().asLong() == pos.asLong();
                }
                return false;
            });
            channelManager.add(new Channel(cachedChannel, null));

        });
    }
}
