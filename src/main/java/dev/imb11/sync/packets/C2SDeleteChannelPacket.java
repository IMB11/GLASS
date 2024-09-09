package dev.imb11.sync.packets;

import dev.imb11.sync.ChannelManagerPersistence;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.Objects;

public record C2SDeleteChannelPacket(String channel) implements CustomPayload, ServerPlayNetworking.PlayPayloadHandler<C2SDeleteChannelPacket> {
    public static final CustomPayload.Id<C2SDeleteChannelPacket> PACKET_ID = new CustomPayload.Id<>(Identifier.of("glass", "delete_channel"));
    public static final PacketCodec<RegistryByteBuf, C2SDeleteChannelPacket> PACKET_CODEC = PacketCodec.of((value, buf) -> {
        buf.writeString(value.channel());
    }, (buf) -> new C2SDeleteChannelPacket(buf.readString()));

    @Override
    public Id<? extends CustomPayload> getId() {
        return PACKET_ID;
    }

    @Override
    public void receive(C2SDeleteChannelPacket payload, ServerPlayNetworking.Context context) {
        String channel = payload.channel();

        var player = context.player();

        var channelManager = ChannelManagerPersistence.get(player.getWorld());

        channelManager.removeIf(channel1 -> Objects.equals(channel1.name(), channel));
    }
}
