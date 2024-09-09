package dev.imb11.sync.packets;

import dev.imb11.sync.Channel;
import dev.imb11.sync.ChannelManagerPersistence;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record C2SCreateChannelPacket(String channel) implements CustomPayload, ServerPlayNetworking.PlayPayloadHandler<C2SCreateChannelPacket> {
    public static final CustomPayload.Id<C2SCreateChannelPacket> PACKET_ID = new CustomPayload.Id<>(Identifier.of("glass", "create_channel"));
    public static final PacketCodec<RegistryByteBuf, C2SCreateChannelPacket> PACKET_CODEC = PacketCodec.of((value, buf) -> {
        buf.writeString(value.channel());
    }, (buf) -> new C2SCreateChannelPacket(buf.readString()));

    @Override
    public Id<? extends CustomPayload> getId() {
        return PACKET_ID;
    }

    @Override
    public void receive(C2SCreateChannelPacket payload, ServerPlayNetworking.Context context) {
        String channel = payload.channel();

        var player = context.player();

        var channelManager = ChannelManagerPersistence.get(player.getWorld());

        channelManager.add(new Channel(channel, null));
    }
}
