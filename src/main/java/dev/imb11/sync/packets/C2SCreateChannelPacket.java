package dev.imb11.sync.packets;

import dev.imb11.sync.Channel;
import dev.imb11.sync.ChannelManagerPersistence;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record C2SCreateChannelPacket(String channel) implements CustomPacketPayload, ServerPlayNetworking.PlayPayloadHandler<C2SCreateChannelPacket> {
    public static final CustomPacketPayload.Type<C2SCreateChannelPacket> PACKET_ID = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("glass", "create_channel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SCreateChannelPacket> PACKET_CODEC = StreamCodec.ofMember((value, buf) -> {
        buf.writeUtf(value.channel());
    }, (buf) -> new C2SCreateChannelPacket(buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    @Override
    public void receive(C2SCreateChannelPacket payload, ServerPlayNetworking.Context context) {
        String channel = payload.channel();

        var player = context.player();

        var channelManager = ChannelManagerPersistence.get(player.level());

        channelManager.add(new Channel(channel, null));
    }
}
