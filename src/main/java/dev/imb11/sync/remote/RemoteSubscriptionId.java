package dev.imb11.sync.remote;

import dev.imb11.sync.ChannelManagerPersistence;
import dev.imb11.sync.ProjectionSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;

public record RemoteSubscriptionId(ResourceKey<Level> projectorDimension, BlockPos projectorPos, ProjectionSource source, long epoch) {
    public RemoteSubscriptionId {
        projectorDimension = Objects.requireNonNull(projectorDimension);
        projectorPos = Objects.requireNonNull(projectorPos).immutable();
        source = Objects.requireNonNull(source);
        if (epoch < 0L) {
            throw new IllegalArgumentException("epoch must be non-negative");
        }
    }

    public void write(RegistryFriendlyByteBuf buf) {
        buf.writeResourceLocation(projectorDimension.location());
        buf.writeBlockPos(projectorPos);
        buf.writeUtf(source.channel(), ChannelManagerPersistence.MAX_WIRE_CHANNEL_NAME_LENGTH);
        buf.writeResourceLocation(source.dimension().location());
        buf.writeBlockPos(source.pos());
        buf.writeEnum(source.facing());
        buf.writeVarLong(source.revision());
        buf.writeVarLong(epoch);
    }

    public static RemoteSubscriptionId read(RegistryFriendlyByteBuf buf) {
        ResourceKey<Level> projectorDimension = ResourceKey.create(Registries.DIMENSION, buf.readResourceLocation());
        BlockPos projectorPos = buf.readBlockPos();
        String channel = buf.readUtf(ChannelManagerPersistence.MAX_WIRE_CHANNEL_NAME_LENGTH);
        ResourceKey<Level> sourceDimension = ResourceKey.create(Registries.DIMENSION, buf.readResourceLocation());
        BlockPos sourcePos = buf.readBlockPos();
        Direction facing = buf.readEnum(Direction.class);
        long revision = buf.readVarLong();
        long epoch = buf.readVarLong();
        return new RemoteSubscriptionId(projectorDimension, projectorPos, new ProjectionSource(channel, sourceDimension, sourcePos, facing, revision), epoch);
    }
}
