package dev.imb11.sync.packets;

import dev.imb11.sync.Channel;
import dev.imb11.sync.ChannelManagerPersistence;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

public record C2SPopulateDefaultChannelPacket() implements CustomPayload, ServerPlayNetworking.PlayPayloadHandler<C2SPopulateDefaultChannelPacket> {
    public static final CustomPayload.Id<C2SPopulateDefaultChannelPacket> PACKET_ID = new CustomPayload.Id<>(Identifier.of("glass", "populate_default_channel"));
    public static final PacketCodec<RegistryByteBuf, C2SPopulateDefaultChannelPacket> PACKET_CODEC = PacketCodec.unit(new C2SPopulateDefaultChannelPacket());
    @Override
    public Id<? extends CustomPayload> getId() {
        return PACKET_ID;
    }

    @Override
    public void receive(C2SPopulateDefaultChannelPacket payload, ServerPlayNetworking.Context context) {
        var player = context.player();

        var channelManager = ChannelManagerPersistence.get(player.getWorld());

        if (channelManager.stream().anyMatch(channel -> channel.name().equals("Default"))) return;

        channelManager.add(new Channel("Default", null));
    }
}
