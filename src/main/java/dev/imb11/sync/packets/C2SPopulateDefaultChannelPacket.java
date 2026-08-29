package dev.imb11.sync.packets;

import dev.imb11.sync.Channel;
import dev.imb11.sync.ChannelManagerPersistence;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record C2SPopulateDefaultChannelPacket() implements CustomPacketPayload, ServerPlayNetworking.PlayPayloadHandler<C2SPopulateDefaultChannelPacket> {
    public static final CustomPacketPayload.Type<C2SPopulateDefaultChannelPacket> PACKET_ID = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("glass", "populate_default_channel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SPopulateDefaultChannelPacket> PACKET_CODEC = StreamCodec.unit(new C2SPopulateDefaultChannelPacket());
    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    @Override
    public void receive(C2SPopulateDefaultChannelPacket payload, ServerPlayNetworking.Context context) {
        var player = context.player();

        var channelManager = ChannelManagerPersistence.get(player.level());

        if (channelManager.stream().anyMatch(channel -> channel.name().equals("Default"))) return;

        channelManager.add(new Channel("Default", null));
    }
}
