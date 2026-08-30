package dev.imb11.sync.packets;

import dev.imb11.blocks.entity.ProjectorBlockEntity;
import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.sync.GNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record C2SProjectorChannelChangedPacket(BlockPos pos, String channel) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<C2SProjectorChannelChangedPacket> PACKET_ID = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("glass", "projector_channel_changed"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SProjectorChannelChangedPacket> PACKET_CODEC = StreamCodec.ofMember((value, buf) -> {
        buf.writeBlockPos(value.pos());
        buf.writeUtf(value.channel(), ChannelManagerPersistence.MAX_WIRE_CHANNEL_NAME_LENGTH);
    }, buf -> new C2SProjectorChannelChangedPacket(buf.readBlockPos(), buf.readUtf(ChannelManagerPersistence.MAX_WIRE_CHANNEL_NAME_LENGTH)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    public static void receive(C2SProjectorChannelChangedPacket payload, ServerPlayNetworking.Context context) {
        if (!GNetworking.canUseProjector(context.player(), payload.pos())) {
            return;
        }
        String channel = ChannelManagerPersistence.canonicalChannelName(payload.channel());
        var level = context.player().serverLevel();
        ChannelManagerPersistence manager = ChannelManagerPersistence.get(level);
        if (channel != null && manager.containsName(channel) && level.getBlockEntity(payload.pos()) instanceof ProjectorBlockEntity projector) {
            projector.setChannelFromServer(channel);
        }
    }
}
