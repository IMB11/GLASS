package dev.imb11.sync.remote;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

public record C2SRemoteChunkAckPacket(RemoteSubscriptionId subscription, long sequence, ChunkPos chunk) implements CustomPacketPayload {
    public static final Type<C2SRemoteChunkAckPacket> PACKET_ID = new Type<>(ResourceLocation.fromNamespaceAndPath("glass", "remote_chunk_ack"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRemoteChunkAckPacket> PACKET_CODEC = StreamCodec.ofMember(C2SRemoteChunkAckPacket::write, C2SRemoteChunkAckPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        subscription.write(buf);
        buf.writeVarLong(sequence);
        buf.writeChunkPos(chunk);
    }

    private static C2SRemoteChunkAckPacket read(RegistryFriendlyByteBuf buf) {
        return new C2SRemoteChunkAckPacket(RemoteSubscriptionId.read(buf), buf.readVarLong(), buf.readChunkPos());
    }
}
