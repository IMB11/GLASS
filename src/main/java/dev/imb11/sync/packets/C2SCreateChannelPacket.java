package dev.imb11.sync.packets;

import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.sync.GNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record C2SCreateChannelPacket(BlockPos pos, String channel) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SCreateChannelPacket> PACKET_ID = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("glass", "create_channel"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SCreateChannelPacket> PACKET_CODEC = StreamCodec.ofMember((value, buf) -> {
        buf.writeBlockPos(value.pos());
        buf.writeUtf(value.channel(), ChannelManagerPersistence.MAX_WIRE_CHANNEL_NAME_LENGTH);
    }, buf -> new C2SCreateChannelPacket(buf.readBlockPos(), buf.readUtf(ChannelManagerPersistence.MAX_WIRE_CHANNEL_NAME_LENGTH)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    public static void receive(C2SCreateChannelPacket payload, ServerPlayNetworking.Context context) {
        if (GNetworking.canUseTerminal(context.player(), payload.pos())) {
            ChannelManagerPersistence.get(context.player().serverLevel()).createChannel(payload.channel());
        }
    }
}
