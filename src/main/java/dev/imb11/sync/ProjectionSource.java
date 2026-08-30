package dev.imb11.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Objects;

public record ProjectionSource(String channel, ResourceKey<Level> dimension, BlockPos pos, Direction facing, long revision) {
    public ProjectionSource {
        channel = Objects.requireNonNull(channel);
        dimension = Objects.requireNonNull(dimension);
        pos = Objects.requireNonNull(pos).immutable();
        facing = Objects.requireNonNull(facing);
        if (revision < 0L) {
            throw new IllegalArgumentException("revision must be non-negative");
        }
    }

    public Key key() {
        return new Key(channel, dimension, pos);
    }

    public record Key(String channel, ResourceKey<Level> dimension, BlockPos pos) {
        public Key {
            channel = Objects.requireNonNull(channel);
            dimension = Objects.requireNonNull(dimension);
            pos = Objects.requireNonNull(pos).immutable();
        }
    }
}
