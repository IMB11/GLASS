package dev.imb11.sync.remote;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

public record S2CRemoteChunkPacket(RemoteSubscriptionId subscription, long sequence, ClientboundLevelChunkWithLightPacket data, int encodedBytes) implements CustomPacketPayload {
    public static final int MAX_BYTES = 983040;
    public static final Type<S2CRemoteChunkPacket> PACKET_ID = new Type<>(ResourceLocation.fromNamespaceAndPath("glass", "remote_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRemoteChunkPacket> PACKET_CODEC = StreamCodec.ofMember(S2CRemoteChunkPacket::write, S2CRemoteChunkPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    public long terrainAndLightBytes() {
        return encodedBytes;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        subscription.write(buf);
        buf.writeVarLong(sequence);
        ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buf, data);
    }

    private static S2CRemoteChunkPacket read(RegistryFriendlyByteBuf buf) {
        RemoteSubscriptionId subscription = RemoteSubscriptionId.read(buf);
        long sequence = buf.readVarLong();
        int start = buf.readerIndex();
        if (buf.readableBytes() > MAX_BYTES) {
            throw new IllegalArgumentException("Projection chunk exceeds packet limit");
        }
        ClientboundLevelChunkWithLightPacket data = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buf);
        return new S2CRemoteChunkPacket(subscription, sequence, data, buf.readerIndex() - start);
    }
}
