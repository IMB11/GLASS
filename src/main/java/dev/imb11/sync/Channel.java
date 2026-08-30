package dev.imb11.sync;

import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public record Channel(String name, @Nullable ProjectionSource source) {
    public Channel {
        name = Objects.requireNonNull(name);
        if (source != null && !name.equals(source.channel())) {
            throw new IllegalArgumentException("source channel does not match channel name");
        }
    }

    @Nullable
    public BlockPos linkedBlock() {
        return source == null ? null : source.pos();
    }

    @Override
    public String toString() {
        return "{Name:" + name + ",Source:" + source + "}";
    }

    public Channel removeLinkedBlock() {
        return new Channel(name, null);
    }
}
