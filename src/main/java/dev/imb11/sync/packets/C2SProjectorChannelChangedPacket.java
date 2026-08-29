package dev.imb11.sync.packets;

import dev.imb11.blocks.entity.ProjectorBlockEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record C2SProjectorChannelChangedPacket(BlockPos pos, String channel) implements CustomPacketPayload, ServerPlayNetworking.PlayPayloadHandler<C2SProjectorChannelChangedPacket> {
    public static final CustomPacketPayload.Type<C2SProjectorChannelChangedPacket> PACKET_ID = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("glass", "projector_channel_changed"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SProjectorChannelChangedPacket> PACKET_CODEC = StreamCodec.ofMember((value, buf) -> {
        buf.writeBlockPos(value.pos());
        buf.writeUtf(value.channel());
    }, (buf) -> new C2SProjectorChannelChangedPacket(buf.readBlockPos(), buf.readUtf()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    @Override
    public void receive(C2SProjectorChannelChangedPacket payload, ServerPlayNetworking.Context context) {
        BlockPos pos = payload.pos();
        String channel = payload.channel();

        var player = context.player();
        var server = context.server();

        server.executeIfPossible(() -> {
            var entity = player.level().getBlockEntity(pos);

            if (entity instanceof ProjectorBlockEntity projector) {
                projector.channel = channel;
                projector.setChanged();
            }
        });
    }
}
