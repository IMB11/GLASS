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

import java.util.concurrent.atomic.AtomicReference;

public record C2STerminalChannelChangedPacket(BlockPos pos, String channel) implements CustomPayload, ServerPlayNetworking.PlayPayloadHandler<C2STerminalChannelChangedPacket> {
    public static final CustomPayload.Id<C2STerminalChannelChangedPacket> PACKET_ID = new CustomPayload.Id<>(Identifier.of("glass", "terminal_channel_changed"));
    public static final PacketCodec<RegistryByteBuf, C2STerminalChannelChangedPacket> PACKET_CODEC = PacketCodec.of((value, buf) -> {
        buf.writeBlockPos(value.pos());
        buf.writeString(value.channel());
    }, (buf) -> new C2STerminalChannelChangedPacket(buf.readBlockPos(), buf.readString()));

    @Override
    public Id<? extends CustomPayload> getId() {
        return PACKET_ID;
    }

    @Override
    public void receive(C2STerminalChannelChangedPacket payload, ServerPlayNetworking.Context context) {
        BlockPos pos = payload.pos();
        String channel = payload.channel();

        var server = context.server();
        var player = context.player();

        server.executeSync(() -> {
            var entity = player.getWorld().getBlockEntity(pos);

            if (entity instanceof TerminalBlockEntity terminal) {
                terminal.channel = channel;
                terminal.markDirty();

                var channelManager = ChannelManagerPersistence.get(player.getWorld());

                AtomicReference<Channel> old = new AtomicReference<>();

                channelManager.forEach(channel1 -> {
                    if (channel1.linkedBlock() != null) {
                        if (channel1.linkedBlock().asLong() == pos.asLong()) {
                            old.set(channel1);
                        }
                    }
                });

                if (old.get() != null) {
                    Channel channel1 = old.get();
                    channelManager.remove(channel1);
                    channelManager.add(channel1.removeLinkedBlock());
                }

                channelManager.removeIf(channels -> channels.name().equals(channel));

                channelManager.add(new Channel(channel, pos));
            }
        });
    }
}
