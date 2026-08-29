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
import java.util.concurrent.atomic.AtomicReference;

public record C2STerminalChannelChangedPacket(BlockPos pos, String channel) implements CustomPacketPayload, ServerPlayNetworking.PlayPayloadHandler<C2STerminalChannelChangedPacket> {
    public static final CustomPacketPayload.Type<C2STerminalChannelChangedPacket> PACKET_ID = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("glass", "terminal_channel_changed"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2STerminalChannelChangedPacket> PACKET_CODEC = StreamCodec.ofMember((value, buf) -> {
        buf.writeBlockPos(value.pos());
        buf.writeUtf(value.channel());
    }, (buf) -> new C2STerminalChannelChangedPacket(buf.readBlockPos(), buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    @Override
    public void receive(C2STerminalChannelChangedPacket payload, ServerPlayNetworking.Context context) {
        BlockPos pos = payload.pos();
        String channel = payload.channel();

        var server = context.server();
        var player = context.player();

        server.executeIfPossible(() -> {
            var entity = player.level().getBlockEntity(pos);

            if (entity instanceof TerminalBlockEntity terminal) {
                terminal.channel = channel;
                terminal.setChanged();

                var channelManager = ChannelManagerPersistence.get(player.level());

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
