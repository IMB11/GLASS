package dev.imb11.sync.remote;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;

import java.util.Objects;

public record S2CRemoteSubscriptionPacket(RemoteSubscriptionId subscription, ChunkPos grantedCenter, int grantedRadius, ResourceKey<DimensionType> dimensionType, Difficulty difficulty, boolean hardcore, boolean debug, boolean flat, long seedHash, RemoteWorldState state) implements CustomPacketPayload {
    public static final Type<S2CRemoteSubscriptionPacket> PACKET_ID = new Type<>(ResourceLocation.fromNamespaceAndPath("glass", "remote_subscription"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRemoteSubscriptionPacket> PACKET_CODEC = StreamCodec.ofMember(S2CRemoteSubscriptionPacket::write, S2CRemoteSubscriptionPacket::read);

    public S2CRemoteSubscriptionPacket {
        subscription = Objects.requireNonNull(subscription);
        grantedCenter = Objects.requireNonNull(grantedCenter);
        dimensionType = Objects.requireNonNull(dimensionType);
        difficulty = Objects.requireNonNull(difficulty);
        state = Objects.requireNonNull(state);
        if (grantedRadius < 0 || grantedRadius > 6) {
            throw new IllegalArgumentException("invalid granted radius");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return PACKET_ID;
    }

    private void write(RegistryFriendlyByteBuf buf) {
        subscription.write(buf);
        buf.writeInt(grantedCenter.x);
        buf.writeInt(grantedCenter.z);
        buf.writeVarInt(grantedRadius);
        buf.writeResourceLocation(dimensionType.location());
        buf.writeEnum(difficulty);
        buf.writeBoolean(hardcore);
        buf.writeBoolean(debug);
        buf.writeBoolean(flat);
        buf.writeLong(seedHash);
        state.write(buf);
    }

    private static S2CRemoteSubscriptionPacket read(RegistryFriendlyByteBuf buf) {
        RemoteSubscriptionId subscription = RemoteSubscriptionId.read(buf);
        ChunkPos grantedCenter = new ChunkPos(buf.readInt(), buf.readInt());
        int grantedRadius = buf.readVarInt();
        ResourceKey<DimensionType> dimensionType = ResourceKey.create(Registries.DIMENSION_TYPE, buf.readResourceLocation());
        Difficulty difficulty = buf.readEnum(Difficulty.class);
        boolean hardcore = buf.readBoolean();
        boolean debug = buf.readBoolean();
        boolean flat = buf.readBoolean();
        long seedHash = buf.readLong();
        return new S2CRemoteSubscriptionPacket(subscription, grantedCenter, grantedRadius, dimensionType, difficulty, hardcore, debug, flat, seedHash, RemoteWorldState.read(buf));
    }
}
