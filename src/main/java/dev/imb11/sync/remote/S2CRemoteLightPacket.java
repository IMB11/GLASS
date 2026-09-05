package dev.imb11.sync.remote;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacket;
import net.minecraft.resources.ResourceLocation;

public record S2CRemoteLightPacket(RemoteSubscriptionId subscription, long sequence,
                                   ClientboundLightUpdatePacket data) implements CustomPacketPayload {
    public static final Type<S2CRemoteLightPacket> PACKET_ID = new Type<>(ResourceLocation.fromNamespaceAndPath("glass", "remote_light"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRemoteLightPacket> PACKET_CODEC = StreamCodec.ofMember(S2CRemoteLightPacket::write, S2CRemoteLightPacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        subscription.write(buf);
        buf.writeVarLong(sequence);
        ClientboundLightUpdatePacket.STREAM_CODEC.encode(buf, data);
    }

    private static S2CRemoteLightPacket read(RegistryFriendlyByteBuf buf) {
        RemoteSubscriptionId subscription = RemoteSubscriptionId.read(buf);
        long sequence = buf.readVarLong();
        return new S2CRemoteLightPacket(subscription, sequence, ClientboundLightUpdatePacket.STREAM_CODEC.decode(buf));
    }
}
