package dev.imb11.sync.remote;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;

import java.util.Objects;

public record C2SRemoteUpdatePacket(RemoteSubscriptionId subscription, ChunkPos cameraCenter, int requestedRadius) implements CustomPacketPayload {
    public static final Type<C2SRemoteUpdatePacket> PACKET_ID = new Type<>(ResourceLocation.fromNamespaceAndPath("glass", "remote_update"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRemoteUpdatePacket> PACKET_CODEC = StreamCodec.ofMember(C2SRemoteUpdatePacket::write, C2SRemoteUpdatePacket::read);

    public C2SRemoteUpdatePacket {
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

    private static C2SRemoteUpdatePacket read(RegistryFriendlyByteBuf buf) {
        return new C2SRemoteUpdatePacket(RemoteSubscriptionId.read(buf), new ChunkPos(buf.readInt(), buf.readInt()), buf.readVarInt());
    }
}
