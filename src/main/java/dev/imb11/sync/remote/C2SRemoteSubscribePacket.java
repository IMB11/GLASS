package dev.imb11.sync.remote;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.Objects;

public record C2SRemoteSubscribePacket(RemoteSubscriptionId subscription, ChunkPos cameraCenter, int requestedRadius) implements CustomPacketPayload {
    public static final Type<C2SRemoteSubscribePacket> PACKET_ID = new Type<>(ResourceLocation.fromNamespaceAndPath("glass", "remote_subscribe"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRemoteSubscribePacket> PACKET_CODEC = StreamCodec.ofMember(C2SRemoteSubscribePacket::write, C2SRemoteSubscribePacket::read);

    public C2SRemoteSubscribePacket {
        subscription = Objects.requireNonNull(subscription);
        cameraCenter = Objects.requireNonNull(cameraCenter);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        subscription.write(buf);
        buf.writeInt(cameraCenter.x);
        buf.writeInt(cameraCenter.z);
        buf.writeVarInt(requestedRadius);
    }

    private static C2SRemoteSubscribePacket read(RegistryFriendlyByteBuf buf) {
        return new C2SRemoteSubscribePacket(RemoteSubscriptionId.read(buf), new ChunkPos(buf.readInt(), buf.readInt()), buf.readVarInt());
    }
}
