package dev.imb11.sync.packets;

import dev.imb11.blocks.entity.TerminalBlockEntity;
import dev.imb11.sync.Channel;
import dev.imb11.sync.ChannelManagerPersistence;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public record C2SRemoveLinkedChannelPacket(BlockPos pos) implements CustomPayload, ServerPlayNetworking.PlayPayloadHandler<C2SRemoveLinkedChannelPacket> {
    public static final CustomPayload.Id<C2SRemoveLinkedChannelPacket> PACKET_ID = new CustomPayload.Id<>(Identifier.of("glass", "remove_linked_channel"));
    public static final PacketCodec<RegistryByteBuf, C2SRemoveLinkedChannelPacket> PACKET_CODEC = PacketCodec.of((value, buf) -> {
        buf.writeBlockPos(value.pos());
    }, (buf) -> new C2SRemoveLinkedChannelPacket(buf.readBlockPos()));

    @Override
    public Id<? extends CustomPayload> getId() {
        return PACKET_ID;
    }

    @Override
    public void receive(C2SRemoveLinkedChannelPacket payload, ServerPlayNetworking.Context context) {
        BlockPos pos = payload.pos();

        var player = context.player();
        var server = context.server();

        server.executeSync(() -> {
            var channelManager = ChannelManagerPersistence.get(player.getWorld());

            var entity = player.getWorld().getBlockEntity(pos);

            String cachedChannel = "";

            if (entity instanceof TerminalBlockEntity terminal) {
                cachedChannel = new String(terminal.channel.toCharArray());
                terminal.channel = "";
                terminal.markDirty();
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
