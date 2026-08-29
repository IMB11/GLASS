package dev.imb11.sync.packets;

import dev.imb11.sync.ChannelManagerPersistence;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.Objects;

public record C2SDeleteChannelPacket(String channel) implements CustomPacketPayload, ServerPlayNetworking.PlayPayloadHandler<C2SDeleteChannelPacket> {
    public static final CustomPacketPayload.Type<C2SDeleteChannelPacket> PACKET_ID = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("glass", "delete_channel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SDeleteChannelPacket> PACKET_CODEC = StreamCodec.ofMember((value, buf) -> {
        buf.writeUtf(value.channel());
    }, (buf) -> new C2SDeleteChannelPacket(buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    @Override
    public void receive(C2SDeleteChannelPacket payload, ServerPlayNetworking.Context context) {
        String channel = payload.channel();

        var player = context.player();

        var channelManager = ChannelManagerPersistence.get(player.level());

        channelManager.removeIf(channel1 -> Objects.equals(channel1.name(), channel));
    }
}
