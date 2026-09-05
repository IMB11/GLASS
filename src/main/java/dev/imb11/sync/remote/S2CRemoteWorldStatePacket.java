package dev.imb11.sync.remote;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record S2CRemoteWorldStatePacket(RemoteSubscriptionId subscription, long sequence, RemoteWorldState state) implements CustomPacketPayload {
    public static final Type<S2CRemoteWorldStatePacket> PACKET_ID = new Type<>(ResourceLocation.fromNamespaceAndPath("glass", "remote_world_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRemoteWorldStatePacket> PACKET_CODEC = StreamCodec.ofMember(S2CRemoteWorldStatePacket::write, S2CRemoteWorldStatePacket::read);

    public S2CRemoteWorldStatePacket {
        subscription = Objects.requireNonNull(subscription);
        state = Objects.requireNonNull(state);
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
        state.write(buf);
    }

    private static S2CRemoteWorldStatePacket read(RegistryFriendlyByteBuf buf) {
        return new S2CRemoteWorldStatePacket(RemoteSubscriptionId.read(buf), buf.readVarLong(), RemoteWorldState.read(buf));
    }
}
