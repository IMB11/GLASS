package dev.imb11.sync.remote;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record S2CRemoteUnavailablePacket(RemoteSubscriptionId subscription, Reason reason) implements CustomPacketPayload {
    public static final Type<S2CRemoteUnavailablePacket> PACKET_ID = new Type<>(ResourceLocation.fromNamespaceAndPath("glass", "remote_unavailable"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRemoteUnavailablePacket> PACKET_CODEC = StreamCodec.ofMember(S2CRemoteUnavailablePacket::write, S2CRemoteUnavailablePacket::read);

    public S2CRemoteUnavailablePacket {
        subscription = Objects.requireNonNull(subscription);
        reason = Objects.requireNonNull(reason);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        subscription.write(buf);
        buf.writeEnum(reason);
    }

    private static S2CRemoteUnavailablePacket read(RegistryFriendlyByteBuf buf) {
        return new S2CRemoteUnavailablePacket(RemoteSubscriptionId.read(buf), buf.readEnum(Reason.class));
    }

    public enum Reason {
        INVALID,
        BUDGET,
        TIMEOUT,
        ENDED,
        UNSUPPORTED
    }
}
