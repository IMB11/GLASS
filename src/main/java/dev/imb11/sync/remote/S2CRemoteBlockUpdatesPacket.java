package dev.imb11.sync.remote;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.resources.ResourceLocation;

public record S2CRemoteBlockUpdatesPacket(RemoteSubscriptionId subscription, long sequence,
                                         int sectionX, int sectionY, int sectionZ,
                                         ClientboundSectionBlocksUpdatePacket updates) implements CustomPacketPayload {
    public static final int MAX_UPDATES = 64;
    public static final Type<S2CRemoteBlockUpdatesPacket> PACKET_ID = new Type<>(ResourceLocation.fromNamespaceAndPath("glass", "remote_block_updates"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRemoteBlockUpdatesPacket> PACKET_CODEC = StreamCodec.ofMember(S2CRemoteBlockUpdatesPacket::write, S2CRemoteBlockUpdatesPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        subscription.write(buf);
        buf.writeVarLong(sequence);
        buf.writeInt(sectionX);
        buf.writeInt(sectionY);
        buf.writeInt(sectionZ);
        ClientboundSectionBlocksUpdatePacket.STREAM_CODEC.encode(buf, updates);
    }

    private static S2CRemoteBlockUpdatesPacket read(RegistryFriendlyByteBuf buf) {
        RemoteSubscriptionId subscription = RemoteSubscriptionId.read(buf);
        long sequence = buf.readVarLong();
        int x = buf.readInt();
        int y = buf.readInt();
        int z = buf.readInt();
        return new S2CRemoteBlockUpdatesPacket(subscription, sequence, x, y, z,
                ClientboundSectionBlocksUpdatePacket.STREAM_CODEC.decode(buf));
    }
}
