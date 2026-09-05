package dev.imb11.sync.remote;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record S2CRemoteUnloadPacket(RemoteSubscriptionId subscription, long sequence, int chunkX, int chunkZ) implements CustomPacketPayload {
    public static final Type<S2CRemoteUnloadPacket> PACKET_ID = new Type<>(ResourceLocation.fromNamespaceAndPath("glass", "remote_unload"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRemoteUnloadPacket> PACKET_CODEC = StreamCodec.ofMember(S2CRemoteUnloadPacket::write, S2CRemoteUnloadPacket::read);

    public S2CRemoteUnloadPacket {
        subscription = Objects.requireNonNull(subscription);
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must be non-negative");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        subscription.write(buf);
        buf.writeVarLong(sequence);
        buf.writeInt(chunkX);
        buf.writeInt(chunkZ);
    }

    private static S2CRemoteUnloadPacket read(RegistryFriendlyByteBuf buf) {
        return new S2CRemoteUnloadPacket(RemoteSubscriptionId.read(buf), buf.readVarLong(), buf.readInt(), buf.readInt());
    }
}
