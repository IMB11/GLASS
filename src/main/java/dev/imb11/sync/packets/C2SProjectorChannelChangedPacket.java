package dev.imb11.sync.packets;

import dev.imb11.blocks.entity.ProjectorBlockEntity;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public record C2SProjectorChannelChangedPacket(BlockPos pos, String channel) implements CustomPayload, ServerPlayNetworking.PlayPayloadHandler<C2SProjectorChannelChangedPacket> {
    public static final CustomPayload.Id<C2SProjectorChannelChangedPacket> PACKET_ID = new CustomPayload.Id<>(Identifier.of("glass", "projector_channel_changed"));
    public static final PacketCodec<RegistryByteBuf, C2SProjectorChannelChangedPacket> PACKET_CODEC = PacketCodec.of((value, buf) -> {
        buf.writeBlockPos(value.pos());
        buf.writeString(value.channel());
    }, (buf) -> new C2SProjectorChannelChangedPacket(buf.readBlockPos(), buf.readString()));

    @Override
    public Id<? extends CustomPayload> getId() {
        return PACKET_ID;
    }

    @Override
    public void receive(C2SProjectorChannelChangedPacket payload, ServerPlayNetworking.Context context) {
        BlockPos pos = payload.pos();
        String channel = payload.channel();

        var player = context.player();
        var server = context.server();

        server.executeSync(() -> {
            var entity = player.getWorld().getBlockEntity(pos);

            if (entity instanceof ProjectorBlockEntity projector) {
                projector.channel = channel;
                projector.markDirty();
            }
        });
    }
}
